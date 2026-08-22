package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import com.example.springexample.Metrics.GrpcRequestsMetric;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.ReactorReactiveTransferServiceGrpc;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatMembershipServiceTest {

    private final ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub stub =
            Mockito.mock(ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub.class);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final GrpcRequestsMetric metric = new GrpcRequestsMetric(registry);

    private final ChatMembershipService service = new ChatMembershipService(stub, metric);

    private Timer membersTimer(String outcome) {
        return registry.find(GrpcRequestsMetric.CALL_TIMER)
                .tags("method", "members", "outcome", outcome)
                .timer();
    }

    /**
     * Ждёт до 500 мс появления записи с данным исходом (beads 8wh, после Retry.fixedDelay).
     * Нужно только для success/error: у Reactor doFinally для ON_COMPLETE/ON_ERROR
     * выполняет коллбэк ПОСЛЕ того, как терминальный сигнал уже ушёл вниз по цепочке —
     * то есть уже РАЗБУДИЛ .block() в вызывающем потоке. Пока ретрай был мгновенным
     * (Retry.max, без задержки), вся вторая попытка выполнялась синхронно в том же
     * потоке, что и .block(), и гонки не было. С Retry.fixedDelay вторая попытка уходит
     * на поток Schedulers.parallel(), и запись метрики может физически ещё не случиться
     * в момент, когда .block() уже вернул значение в тестовом потоке. Для cancel это не
     * нужно (см. timedOutAttemptIsRecordedAsCancelledInHistogram) — там doFinally
     * снимается синхронно внутри cancel(), до отправки ошибки вниз по цепочке.
     */
    private Timer awaitMembersTimer(String outcome) {
        long deadlineNanos = System.nanoTime() + Duration.ofMillis(500).toNanos();
        Timer timer;
        while ((timer = membersTimer(outcome)) == null && System.nanoTime() < deadlineNanos) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return timer;
    }

    private static DataTransferService.UserListResponse responseWith(long... ids) {
        DataTransferService.UserListResponse.Builder b =
                DataTransferService.UserListResponse.newBuilder();
        for (long id : ids) {
            b.addUsers(DataTransferService.UserDataRequest.newBuilder()
                    .setId(id)
                    .setUsername("user-" + id)
                    .build());
        }
        return b.build();
    }

    @Test
    void membersReturnsUsersWithIdAndUsername() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.just(responseWith(7L, 9L)));

        List<DataTransferService.UserDataRequest> members = service.members(5L).block();

        assertNotNull(members);
        assertEquals(2, members.size());
        assertEquals(7L, members.get(0).getId());
        assertEquals("user-7", members.get(0).getUsername());
    }

    @Test
    void decideReturnsMemberWhenUserPresent() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.just(responseWith(7L, 9L)));

        assertEquals(MembershipDecision.MEMBER, service.decide(5L, "9").block());
    }

    @Test
    void decideReturnsNotMemberWhenUserAbsent() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.just(responseWith(7L, 9L)));

        assertEquals(MembershipDecision.NOT_MEMBER, service.decide(5L, "42").block());
    }

    @Test
    void decideReturnsNotMemberOnEmptyMemberList() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.just(responseWith()));

        assertEquals(MembershipDecision.NOT_MEMBER, service.decide(5L, "9").block());
    }

    @Test
    void decideReturnsUnknownOnTimeout() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.never());

        // Укороченный таймаут по уже существующему в этом файле образцу: иначе тест ждёт
        // 4 секунды (2 с на попытку x 2 попытки — таймаут транзиентен и повторяется).
        ChatMembershipService fastService = new ChatMembershipService(stub, metric) {
            @Override
            Duration membershipTimeout() {
                return Duration.ofMillis(50);
            }
        };

        assertEquals(MembershipDecision.UNKNOWN, fastService.decide(5L, "9").block());
    }

    @Test
    void decideReturnsUnknownOnGrpcError() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.error(new StatusRuntimeException(Status.INTERNAL)));

        assertEquals(MembershipDecision.UNKNOWN, service.decide(5L, "9").block());
    }

    @Test
    void transientFailureIsRetriedOnceAndSucceeds() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.error(new StatusRuntimeException(Status.UNAVAILABLE)))
                .thenReturn(Mono.just(responseWith(9L)));

        assertEquals(MembershipDecision.MEMBER, service.decide(5L, "9").block());
        Mockito.verify(stub, Mockito.times(2))
                .getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class));
    }

    @Test
    void deadlineExceededIsRetriedOnceAndSucceeds() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.error(new StatusRuntimeException(Status.DEADLINE_EXCEEDED)))
                .thenReturn(Mono.just(responseWith(9L)));

        assertEquals(MembershipDecision.MEMBER, service.decide(5L, "9").block());
        Mockito.verify(stub, Mockito.times(2))
                .getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class));
    }

    @Test
    void deterministicFailureIsNotRetried() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.error(new StatusRuntimeException(Status.INVALID_ARGUMENT)));

        assertEquals(MembershipDecision.UNKNOWN, service.decide(5L, "9").block());
        Mockito.verify(stub, Mockito.times(1))
                .getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class));
    }

    @Test
    void isMemberReactiveMapsUnknownToFalse() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.error(new StatusRuntimeException(Status.INTERNAL)));

        assertEquals(Boolean.FALSE, service.isMemberReactive(5L, "9").block());
    }

    @Test
    void decideBlockingReturnsUnknownInsteadOfThrowing() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.never());

        ChatMembershipService fastService = new ChatMembershipService(stub, metric) {
            @Override
            Duration membershipTimeout() {
                return Duration.ofMillis(50);
            }
        };

        assertEquals(MembershipDecision.UNKNOWN, fastService.decideBlocking(5L, "9"));
    }

    @Test
    void decideBlockingReturnsUnknownWhenOuterGuardFiresFirst() {
        // membershipTimeout() намеренно НЕ укорочен: внутренняя реактивная цепочка должна
        // ещё висеть, когда истечёт именно blockingGuard(). Иначе (как в предыдущем тесте,
        // где укорочен membershipTimeout()) до catch(RuntimeException) в decideBlocking
        // дело не доходит — исключение уже погашено внутри самой цепочки, а не на внешней
        // границе .block(blockingGuard()). Стережёт код, который иначе никогда не исполняется.
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.never());

        ChatMembershipService guarded = new ChatMembershipService(stub, metric) {
            @Override
            Duration blockingGuard() {
                return Duration.ofMillis(50);
            }
        };

        assertEquals(MembershipDecision.UNKNOWN, guarded.decideBlocking(5L, "9"));
    }

    @Test
    void everyAttemptLandsInHistogramSeparately() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.error(new StatusRuntimeException(Status.UNAVAILABLE)))
                .thenReturn(Mono.just(responseWith(9L)));

        service.decide(5L, "9").block();

        assertNotNull(membersTimer("error"));
        assertEquals(1, membersTimer("error").count());
        Timer success = awaitMembersTimer("success");
        assertNotNull(success);
        assertEquals(1, success.count());
    }

    @Test
    void timedOutAttemptIsRecordedAsCancelledInHistogram() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.never());

        ChatMembershipService fastService = new ChatMembershipService(stub, metric) {
            @Override
            Duration membershipTimeout() {
                return Duration.ofMillis(50);
            }
        };

        assertEquals(MembershipDecision.UNKNOWN, fastService.decide(5L, "9").block());

        // Таймаут гасит вызов ОТМЕНОЙ, а не ошибкой, потому что .timeout() стоит снаружи
        // measure(). Обе попытки (исходная и повторная) обязаны лечь в серию cancel
        // отдельными замерами — если операторы когда-нибудь переставят местами, здесь
        // окажется одна отмена вместо двух либо серия исчезнет вовсе.
        assertNotNull(membersTimer("cancel"));
        assertEquals(2, membersTimer("cancel").count());
    }

    @Test
    void membersRecordsLatencyUnderItsOwnMethodTag() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.just(responseWith(7L, 9L)));

        service.members(5L).block();

        assertNotNull(membersTimer("success"));
        assertEquals(1L, membersTimer("success").count());
        assertNull(membersTimer("error"));
    }

    @Test
    void membersLatencyIsCountedOnSubscriptionNotOnAssembly() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.just(responseWith(7L)));

        service.members(5L);

        assertNull(membersTimer("success"));
    }

}
