package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import com.example.springexample.Metrics.GrpcRequestsMetric;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.ReactorReactiveTransferServiceGrpc;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Единственный источник ответа на вопрос «состоит ли пользователь в чате» (beads g9x).
 *
 * Зависит ТОЛЬКО от gRPC-стаба, а не от ReactiveGrpcClient: тот автовайрит
 * ChatListStompController -> SimpMessagingTemplate -> брокерная конфигурация ->
 * StompConfig -> StompAuthChannelInterceptor. Интерцептор зависит от этого сервиса,
 * поэтому через ReactiveGrpcClient получился бы цикл и контекст Spring не поднялся бы.
 * GrpcRequestsMetric в этот цикл не входит — он знает только про MeterRegistry.
 *
 * Исходов проверки три, а не два (beads 8wh): см. {@link MembershipDecision}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMembershipService {

    /**
     * Тег метрики для этого вызова. Отдельный от {@code getAllUsersByChatId} нарочно:
     * RPC тот же, но горячий путь проверки членства меряется сам по себе (beads 8wh).
     */
    static final String METRIC_METHOD = "members";

    /**
     * Одна повторная попытка с задержкой 100 мс (beads 8wh): {@code Retry.max} без задержки
     * повторяет мгновенно, а для {@code UNAVAILABLE} от лежащего канала мгновенный повтор
     * попадает в то же самое состояние — пользы ноль, а нагрузка на умирающий бэкенд
     * удваивается, причём на самом горячем gRPC-вызове системы. Потолок — 2х2.1 с = 4.1 с,
     * по-прежнему меньше внешней границы {@link #blockingGuard()} в 5 с.
     */
    static final long MEMBERSHIP_RETRIES = 1;

    private final ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub reactiveStub;

    private final GrpcRequestsMetric grpcRequestsMetric;

    /**
     * Таймаут ОДНОЙ попытки. Вынесено в метод, чтобы тест мог укоротить ожидание,
     * не ломая продовое значение.
     *
     * 2 с обоснованы замерами (beads cwo): типичный вызов members — 0.05 с, самый долгий
     * наблюдавшийся первый вызов любого RPC — 0.53 с. Это четырёхкратный запас над худшим
     * измеренным и сорокакратный над типичным.
     */
    Duration membershipTimeout() {
        return Duration.ofSeconds(2);
    }

    /**
     * Внешняя граница блокирующего вызова, с запасом над внутренним потолком в 4 с.
     * Голый {@code .block()} без аргумента здесь не используется намеренно: он корректен
     * ровно до тех пор, пока внутренняя цепочка гарантированно завершается, то есть молча
     * зависит от того, что будущая правка не снимет .timeout().
     */
    Duration blockingGuard() {
        return Duration.ofSeconds(5);
    }

    /**
     * Список участников чата с таймаутом и одной повторной попыткой.
     *
     * ПОРЯДОК ОПЕРАТОРОВ КРИТИЧЕН: .timeout() и .retryWhen() стоят СНАРУЖИ measure().
     * measure реализован через Mono.defer, поэтому при таком порядке он перезапускается
     * на каждой попытке и в гистограмму попадает КАЖДАЯ попытка отдельно, а отменённая
     * по таймауту ложится как outcome=cancel. При обратном порядке ретраи слились бы
     * в один замер — ровно та слепота, из-за которой 8wh требовал сначала закрыть cwo.
     */
    public Mono<List<DataTransferService.UserDataRequest>> members(long chatId) {
        // Вызов стаба обёрнут в Mono.defer нарочно: без этого reactiveStub.getAllUsersByChatId(...)
        // выполняется один раз при сборке цепочки (Java вычисляет аргумент до вызова measure()),
        // и retryWhen просто пересматривает уже готовый (и уже упавший) Mono вместо повторного
        // вызова. В сгенерированном reactor-grpc сам getAllUsersByChatId ленивый и
        // переподписываемый (ClientCalls.oneToOne), так что в проде повторная подписка,
        // скорее всего, и без этой обёртки выпустила бы новый RPC — но полагаться на эту
        // деталь реализации стаба нельзя, а с моком Mockito в тестах (который отдаёт один
        // заранее собранный Mono) без defer ретрай не работает вовсе. Обёртка делает
        // повторный вызов явным и не зависящим от того, кто именно стоит за стабом.
        return grpcRequestsMetric.measure(METRIC_METHOD,
                        Mono.defer(() -> reactiveStub.getAllUsersByChatId(
                                DataTransferService.ChatData.newBuilder().setChatId(chatId).build())))
                .timeout(membershipTimeout())
                .map(DataTransferService.UserListResponse::getUsersList)
                .retryWhen(Retry.fixedDelay(MEMBERSHIP_RETRIES, Duration.ofMillis(100))
                        .filter(ChatMembershipService::isTransient));
    }

    /**
     * Повторяем только то, что может пройти со второй попытки. Детерминированная ошибка
     * (INVALID_ARGUMENT и подобные) со второго раза не станет успехом, а вторые 2 секунды
     * сожжёт — и на SUBSCRIBE это секунды удержания потока пула clientInboundChannel.
     */
    static boolean isTransient(Throwable e) {
        if (e instanceof TimeoutException) {
            return true;
        }
        if (e instanceof StatusRuntimeException statusError) {
            Status.Code code = statusError.getStatus().getCode();
            return code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED;
        }
        return false;
    }

    /**
     * Основной метод: три состояния вместо булева (beads 8wh).
     *
     * Fail-closed сохраняется — UNKNOWN не даёт доступа. Меняется другое: вызывающий
     * теперь ЗНАЕТ, что перед ним отсутствие ответа, а не ответ «нет», и может
     * отреагировать иначе — не врать пользователю и не рвать ему сессию.
     */
    public Mono<MembershipDecision> decide(long chatId, String userId) {
        return members(chatId)
                .map(members -> {
                    if (members.isEmpty()) {
                        log.warn("Проверка членства: пустой список участников чата {} — отказ", chatId);
                        return MembershipDecision.NOT_MEMBER;
                    }
                    return members.stream().anyMatch(u -> String.valueOf(u.getId()).equals(userId))
                            ? MembershipDecision.MEMBER
                            : MembershipDecision.NOT_MEMBER;
                })
                .defaultIfEmpty(MembershipDecision.NOT_MEMBER)
                .onErrorResume(e -> {
                    log.warn("Проверка членства для чата {} не удалась — состояние неизвестно", chatId, e);
                    return Mono.just(MembershipDecision.UNKNOWN);
                });
    }

    /**
     * Блокирующая обёртка для StompAuthChannelInterceptor: preSend синхронен по контракту
     * Spring, реактивную цепочку туда не отдать. Срабатывание внешней границы тоже даёт
     * UNKNOWN, а не исключение наружу, — иначе таймаут снова стал бы разрывом сессии.
     */
    public MembershipDecision decideBlocking(long chatId, String userId) {
        try {
            MembershipDecision decision = decide(chatId, userId).block(blockingGuard());
            return decision == null ? MembershipDecision.UNKNOWN : decision;
        } catch (RuntimeException e) {
            log.warn("Проверка членства для чата {} не уложилась во внешнюю границу — состояние неизвестно",
                    chatId, e);
            return MembershipDecision.UNKNOWN;
        }
    }

    /**
     * Переходник для HTTP-пути /api/chat: наблюдаемое поведение прежнее — fail-closed,
     * UNKNOWN отображается в false. Тайминг наследуется общий (2 с x 2 попытки вместо
     * одной по 5 с), что укладывается в прежний потолок и является улучшением.
     */
    public Mono<Boolean> isMemberReactive(long chatId, String userId) {
        return decide(chatId, userId).map(decision -> decision == MembershipDecision.MEMBER);
    }
}
