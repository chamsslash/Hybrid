package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import com.example.springexample.Metrics.GrpcRequestsMetric;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.ReactorReactiveTransferServiceGrpc;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * Единственный источник ответа на вопрос «состоит ли пользователь в чате» (beads g9x).
 *
 * Зависит ТОЛЬКО от gRPC-стаба, а не от ReactiveGrpcClient: тот автовайрит
 * ChatListStompController -> SimpMessagingTemplate -> брокерная конфигурация ->
 * StompConfig -> StompAuthChannelInterceptor. Интерцептор зависит от этого сервиса,
 * поэтому через ReactiveGrpcClient получился бы цикл и контекст Spring не поднялся бы.
 * GrpcRequestsMetric в этот цикл не входит — он знает только про MeterRegistry.
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

    private final ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub reactiveStub;

    private final GrpcRequestsMetric grpcRequestsMetric;

    /** Вынесено в метод, чтобы тест мог укоротить ожидание, не ломая продовое значение. */
    Duration membershipTimeout() {
        return Duration.ofSeconds(5);
    }

    public Mono<List<DataTransferService.UserDataRequest>> members(long chatId) {
        return grpcRequestsMetric.measure(METRIC_METHOD,
                        reactiveStub.getAllUsersByChatId(
                                DataTransferService.ChatData.newBuilder().setChatId(chatId).build()))
                .map(DataTransferService.UserListResponse::getUsersList);
    }

    /**
     * Fail-closed: таймаут, ошибка gRPC, пустой ответ и пустой список участников
     * одинаково означают «нет». Недоступность MessegerParody и так означает, что
     * сообщения не сохраняются, — честнее отказать сразу, чем создать видимость работы.
     *
     * Реактивный вариант нужен вызывающим, которые собирают ответ одной цепочкой и
     * блокируются на ней ровно один раз (beads 7f7, /api/chat): отдельный блокирующий
     * вызов проверки в общем пуле уже приводил к таймаутам (beads 8wh). Правила отказа
     * живут здесь в единственном экземпляре, isMember — блокирующая обёртка над ними,
     * чтобы политика не разъехалась между HTTP- и STOMP-путями.
     */
    public Mono<Boolean> isMemberReactive(long chatId, String userId) {
        return members(chatId)
                .timeout(membershipTimeout())
                .map(members -> {
                    if (members.isEmpty()) {
                        log.warn("Проверка членства: пустой список участников чата {} — отказ", chatId);
                        return false;
                    }
                    return members.stream().anyMatch(u -> String.valueOf(u.getId()).equals(userId));
                })
                .defaultIfEmpty(false)
                .onErrorResume(e -> {
                    log.warn("Проверка членства для чата {} не удалась — отказ (fail-closed)", chatId, e);
                    return Mono.just(false);
                });
    }

    public boolean isMember(long chatId, String userId) {
        return Boolean.TRUE.equals(isMemberReactive(chatId, userId).block());
    }
}
