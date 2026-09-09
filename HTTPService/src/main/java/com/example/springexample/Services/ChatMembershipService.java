package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import com.example.springexample.Metrics.GrpcRequestsMetric;
import com.example.springexample.Metrics.MembershipCacheMetric;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import java.util.Optional;
import java.util.concurrent.TimeUnit;
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
     * удваивается, причём на самом горячем gRPC-вызове системы. Потолок — 2 + 0.1 + 2 = 4.1 с,
     * по-прежнему меньше внешней границы {@link #blockingGuard()} в 5 с.
     */
    static final long MEMBERSHIP_RETRIES = 1;

    private final ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub reactiveStub;

    private final GrpcRequestsMetric grpcRequestsMetric;

    private final MembershipCacheMetric cacheMetric;

    /**
     * Кеш состава чата (beads 9wi). Создаётся лениво в {@link #cache()}, чтобы TTL можно
     * было укоротить в тесте, не трогая продовое значение — тем же приёмом, что и у
     * {@link #membershipTimeout()}.
     */
    private volatile Cache<Long, List<DataTransferService.UserDataRequest>> cache;

    /**
     * Срок жизни записи. Здесь он НЕ средство корректности: состав чата неизменяем
     * (см. {@link #members(long)}), поэтому запись не может протухнуть по смыслу.
     * Шестьдесят секунд стоят по двум другим причинам — ограничить память и оставить
     * страховку на случай, если удаление участника из чата когда-нибудь появится, а про
     * этот кеш забудут: тогда экспозиция будет минутой, а не бесконечностью.
     */
    Duration cacheTtl() {
        return Duration.ofSeconds(60);
    }

    /**
     * Потолок числа чатов в кеше. Запись — это список участников одного чата, поэтому
     * тысяча записей стоит десятки килобайт; ограничение здесь ради того, чтобы память
     * не росла с числом когда-либо открытых чатов, а не ради экономии.
     */
    long cacheMaxSize() {
        return 1000;
    }

    private Cache<Long, List<DataTransferService.UserDataRequest>> cache() {
        Cache<Long, List<DataTransferService.UserDataRequest>> local = cache;
        if (local == null) {
            synchronized (this) {
                local = cache;
                if (local == null) {
                    local = Caffeine.newBuilder()
                            .expireAfterWrite(cacheTtl())
                            .maximumSize(cacheMaxSize())
                            .build();
                    cache = local;
                }
            }
        }
        return local;
    }

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
     * Список участников чата — с кешем поверх gRPC-вызова (beads 9wi).
     *
     * <p>Через этот метод ходят ВСЕ потребители членства: фрейм SEND и событие набора
     * текста в {@code ChatBoxStompController}, SUBSCRIBE в {@code StompAuthChannelInterceptor},
     * веерная рассылка аватарки чата, {@code /api/chat}, картинки чата и реактивный путь
     * {@code WEBFLUX_Service}. Поэтому кеш живёт здесь и только здесь: одна точка входа —
     * одно место, где решается, идти ли в сеть.
     *
     * <p><b>НЕСУЩИЙ ИНВАРИАНТ: состав чата неизменяем.</b> Кеш корректен ровно потому,
     * что участник чата не может перестать быть участником. Проверено по коду:
     * в {@code user_chat} есть только INSERT ({@code ReactiveUserChatRepository}), DELETE
     * нет нигде; среди RPC нет ни добавления, ни удаления участника существующего чата;
     * {@code ReactiveImpl.transferchat} либо находит чат с ТОЧНО таким же составом и
     * ничего не пишет, либо создаёт новый чат и вставляет все связки разом.
     *
     * <p><b>Если этот инвариант когда-нибудь сломается — здесь появится дыра в
     * авторизации.</b> Спека 2026-08-14 (beads g9x) отвергла кеш именно из-за окна, в
     * котором исключённый из чата ещё может писать; окна нет только потому, что нет
     * исключения. Тот, кто добавит удаление участника или дозапись в существующий чат,
     * обязан прийти сюда и сбрасывать запись по chatId — иначе удалённый пользователь
     * останется полноправным на весь {@link #cacheTtl()}.
     *
     * <p>Кешируется только непустой успешный список; про пустые и ошибочные исходы —
     * в комментариях по месту.
     *
     * <p>Сам вызов gRPC вынесен в {@link #fetchMembers(long)}; тайминги, повтор и
     * порядок операторов описаны там.
     */
    public Mono<List<DataTransferService.UserDataRequest>> members(long chatId) {
        // Mono.defer обязателен: без него поиск в кеше выполнился бы при СБОРКЕ цепочки,
        // а не при подписке, и повторная подписка на один и тот же Mono отдавала бы
        // результат, снятый в прошлом. Здесь же он даёт и второе: промах, случившийся
        // при сборке, не превратился бы в попадание при подписке.
        return Mono.defer(() -> {
            List<DataTransferService.UserDataRequest> cached = cache().getIfPresent(chatId);
            if (cached != null) {
                cacheMetric.hit();
                return Mono.just(cached);
            }
            cacheMetric.miss();
            return fetchMembers(chatId)
                    // Кладём в кеш ТОЛЬКО непустой успешный список.
                    //
                    // Пустой не кешируем намеренно: decide() трактует пустоту как
                    // NOT_MEMBER, и закешированная пустота приколотила бы ложный отказ на
                    // весь TTL. А пустота тут достижима — например, гонкой с созданием
                    // чата, когда строка чата уже есть, а связки в user_chat ещё пишутся.
                    //
                    // Ошибки и таймауты не кешируются просто в силу устройства doOnNext:
                    // он не срабатывает на onError. Это существенно — исход UNKNOWN обязан
                    // оставаться живым, иначе одна сетевая заминка замерзала бы на минуту.
                    .doOnNext(members -> {
                        if (!members.isEmpty()) {
                            cache().put(chatId, members);
                        }
                    });
        });
    }

    /**
     * Собственно gRPC-вызов за составом чата, в обход кеша: таймаут и одна повторная
     * попытка.
     *
     * ПОРЯДОК ОПЕРАТОРОВ КРИТИЧЕН: .timeout() и .retryWhen() стоят СНАРУЖИ measure().
     * measure реализован через Mono.defer, поэтому при таком порядке он перезапускается
     * на каждой попытке и в гистограмму попадает КАЖДАЯ попытка отдельно, а отменённая
     * по таймауту ложится как outcome=cancel. При обратном порядке ретраи слились бы
     * в один замер — ровно та слепота, из-за которой 8wh требовал сначала закрыть cwo.
     */
    private Mono<List<DataTransferService.UserDataRequest>> fetchMembers(long chatId) {
        // Вызов стаба обёрнут в Mono.defer нарочно: без этого reactiveStub.getAllUsersByChatId(...)
        // выполняется один раз при сборке цепочки (Java вычисляет аргумент до вызова measure()),
        // и retryWhen просто пересматривает уже готовый (и уже упавший) Mono вместо повторного
        // вызова. Обёртка обязательна и в проде, и в тестах: сгенерированный reactor-grpc
        // (ClientCalls.oneToOne) оборачивает Mono в SubscribeOnlyOnceLifter — тот хранит
        // AtomicBoolean на экземпляр и на ВТОРОЙ subscribe() того же Mono бросает
        // NullPointerException("You cannot directly subscribe to a gRPC service multiple times
        // concurrently. Use Flux.share() instead."), что проверено дизассемблированием
        // reactor-grpc-stub-1.2.4 из ~/.m2 этого проекта. Без defer повторная подписка
        // retryWhen попадала бы на тот же экземпляр Mono и падала бы этим NPE — то есть
        // ретрай не работал бы не только в тесте с Mockito, но и в проде тоже.
        return grpcRequestsMetric.measure(METRIC_METHOD,
                        Mono.defer(() -> reactiveStub
                                // Дедлайн на вызов (beads 8wh, F3): reactor .timeout() снаружи
                                // отменяет только ПОДПИСКУ на Mono, а не сам gRPC-вызов —
                                // дизассемблирование ClientCalls.oneToOne (reactor-grpc-stub
                                // 1.2.4) показывает, что MonoSink там подключён к
                                // StreamObserver без sink.onCancel/sink.onDispose, то есть
                                // отмена подписчика до ClientCall физически не долетает.
                                // Без withDeadlineAfter брошенный по .timeout() RPC остаётся
                                // в полёте неограниченно: держит поток на MessegerParody и
                                // HTTP/2-стрим на клиенте, а повтор удваивает скорость
                                // накопления таких сирот на уже задыхающемся бэкенде — ровно
                                // тот сценарий, ради которого заведён тикет. Дедлайн намеренно
                                // на 250 мс больше membershipTimeout(): дедлайн должен сработать
                                // ПОСЛЕ reactor-таймаута, иначе исход в гистограмме стал бы
                                // error вместо честного cancel. DEADLINE_EXCEEDED уже входит
                                // в isTransient, так что поведение повтора не меняется.
                                .withDeadlineAfter(membershipTimeout().toMillis() + 250, TimeUnit.MILLISECONDS)
                                .getAllUsersByChatId(
                                        DataTransferService.ChatData.newBuilder().setChatId(chatId).build())))
                .timeout(membershipTimeout())
                .map(DataTransferService.UserListResponse::getUsersList)
                .retryWhen(Retry.fixedDelay(MEMBERSHIP_RETRIES, Duration.ofMillis(100))
                        .filter(ChatMembershipService::isTransient)
                        // Без этого Retry.fixedDelay после исчерпания попыток заворачивает
                        // исходную ошибку в reactor.core.Exceptions$RetryExhaustedException
                        // (проверено прогоном на reactor-core 3.5.9 из ~/.m2: thrown
                        // class=RetryExhaustedException, msg="Retries exhausted: 1/1",
                        // cause=TimeoutException) — сам ретрай при этом работает, но log.error
                        // в горячей ветке SEND пишет обёртку вместо настоящей причины
                        // (UNAVAILABLE/DEADLINE_EXCEEDED/TimeoutException), а тикет 8wh целиком
                        // про то, чтобы по логу можно было отличить сбой от отказа.
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    /**
     * Повторяем только то, что может пройти со второй попытки. Детерминированная ошибка
     * (INVALID_ARGUMENT и подобные) со второго раза не станет успехом, а вторые 2 секунды
     * сожжёт — и на SUBSCRIBE это секунды удержания потока отправителя (WebSocket-контейнер,
     * http-nio-*), а не пула clientInboundChannel (beads 8wh, R7 — та же неточность,
     * что и в StompAuthChannelInterceptor, поправленная там находкой F8).
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
     * Правило «участник ли этот пользователь» поверх УЖЕ полученного списка участников
     * (beads 40i). Существует затем же, зачем и весь класс: чтобы ответ на этот вопрос
     * был записан ровно в одном месте.
     *
     * В gRPC не ходит и ходить не должен. Вызывается с горячего пути — с каждого фрейма
     * SEND и каждого события набора текста в ChatBoxStompController, — где вызывающий уже
     * держит в руках список из ОДНОГО вызова {@link #members(long)} и берёт из него сразу
     * три вещи: вердикт, username отправителя и адресатов веерной рассылки. Позвать оттуда
     * {@link #isMemberReactive} значило бы добавить второй gRPC-вызов на самый горячий путь
     * системы ради ответа, который уже лежит в руках; обмен невыгодный, поэтому политика
     * приходит к списку, а не список к политике.
     *
     * Возвращается найденный участник, а не boolean, тоже нарочно: вызывающему нужен ещё и
     * его username, и доставать его отдельным stream'ом значило бы оставить на месте ровно
     * ту копию сравнения, ради устранения которой метод и заведён.
     *
     * Пустой список даёт пустой результат, то есть «не участник», — та же трактовка, что и
     * в {@link #decide}, и разъехаться они не могут: decide пользуется этим же методом.
     *
     * static нарочно: сигнатура сама сообщает, что вызов чистый — не ходит в сеть, не
     * зависит от состояния сервиса и потому дёшев в любом количестве.
     */
    public static Optional<DataTransferService.UserDataRequest> findMember(
            List<DataTransferService.UserDataRequest> members, String userId) {
        return members.stream()
                .filter(u -> String.valueOf(u.getId()).equals(userId))
                .findFirst();
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
                    return findMember(members, userId).isPresent()
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
