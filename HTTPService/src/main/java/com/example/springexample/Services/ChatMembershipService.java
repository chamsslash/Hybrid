package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMembershipService {

    private final ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub reactiveStub;

    /** Вынесено в метод, чтобы тест мог укоротить ожидание, не ломая продовое значение. */
    Duration membershipTimeout() {
        return Duration.ofSeconds(5);
    }

    public Mono<List<DataTransferService.UserDataRequest>> members(long chatId) {
        return reactiveStub.getAllUsersByChatId(
                        DataTransferService.ChatData.newBuilder().setChatId(chatId).build())
                .map(DataTransferService.UserListResponse::getUsersList);
    }

    /**
     * Fail-closed: таймаут, ошибка gRPC и пустой список участников одинаково означают «нет».
     * Недоступность MessegerParody и так означает, что сообщения не сохраняются, —
     * честнее отказать сразу, чем создать видимость работы.
     */
    public boolean isMember(long chatId, String userId) {
        try {
            List<DataTransferService.UserDataRequest> members = members(chatId).block(membershipTimeout());
            if (members == null || members.isEmpty()) {
                log.warn("Проверка членства: пустой список участников чата {} — отказ", chatId);
                return false;
            }
            return members.stream().anyMatch(u -> String.valueOf(u.getId()).equals(userId));
        } catch (Exception e) {
            log.warn("Проверка членства для чата {} не удалась — отказ (fail-closed)", chatId, e);
            return false;
        }
    }
}
