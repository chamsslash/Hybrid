package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import com.example.springexample.Metrics.GrpcRequestsMetric;
import com.example.springexample.Metrics.MembershipCacheMetric;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ChatMembershipServiceTest {

    // RETURNS_SELF (beads 8wh, F3): members() теперь зовёт reactiveStub.withDeadlineAfter(...)
    // перед getAllUsersByChatId — у настоящего AbstractStub этот метод возвращает новый стаб
    // с тем же каналом, у мока без явного стаба вернул бы null и уронил цепочку NPE ещё
    // до getAllUsersByChatId. RETURNS_SELF отдаёт сам мок на любой вызов, возвращающий тип
    // мока, и не мешает явным when(stub.getAllUsersByChatId(...)) ниже — они по-прежнему
    // приоритетнее дефолтного ответа.
    private final ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub stub =
            Mockito.mock(ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub.class,
                    Mockito.RETURNS_SELF);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final GrpcRequestsMetric metric = new GrpcRequestsMetric(registry);

    private final MembershipCacheMetric cacheMetric = new MembershipCacheMetric(registry);

    private final ChatMembershipService service = new ChatMembershipService(stub, metric, cacheMetric);

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
        while (((timer = membersTimer(outcome)) == null || timer.count() < 1)
                && System.nanoTime() < deadlineNanos) {
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
        ChatMembershipService fastService = new ChatMembershipService(stub, metric, cacheMetric) {
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
        // beads 8wh, R1: RETURNS_SELF маскирует withDeadlineAfter, если его случайно убрать
        // из members() — метод мока просто вернёт сам мок, и тесты выше останутся зелёными.
        // times(2) со значением 2250 (membershipTimeout() 2000 + 250) ловит и пропажу вызова,
        // и вынос withDeadlineAfter наружу Mono.defer (тогда был бы один вызов на обе попытки,
        // и вторая попытка уходила бы с уже просроченным дедлайном).
        Mockito.verify(stub, Mockito.times(2))
                .withDeadlineAfter(2250L, java.util.concurrent.TimeUnit.MILLISECONDS);
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
    void findMemberReturnsParticipantWithUsername() {
        Optional<DataTransferService.UserDataRequest> found =
                ChatMembershipService.findMember(responseWith(7L, 9L).getUsersList(), "9");

        assertTrue(found.isPresent(), "участник обязан находиться в списке участников");
        assertEquals(9L, found.get().getId());
        // Возвращается сам участник, а не boolean: вызывающему на горячем пути нужен ещё и
        // username, и достать его отдельным stream'ом значило бы оставить копию сравнения.
        assertEquals("user-9", found.get().getUsername(),
                "предикат обязан отдавать самого участника, а не только вердикт");
    }

    @Test
    void findMemberReturnsEmptyForNonParticipant() {
        assertTrue(ChatMembershipService.findMember(responseWith(7L, 9L).getUsersList(), "42").isEmpty(),
                "постороннего в списке участников быть не должно");
    }

    @Test
    void findMemberTreatsEmptyListAsNotAMember() {
        assertTrue(ChatMembershipService.findMember(List.of(), "9").isEmpty(),
                "пустой список участников — это NOT_MEMBER, как и в decide()");
    }

    @Test
    void findMemberComparesIdsAsExactStrings() {
        assertTrue(ChatMembershipService.findMember(responseWith(9L).getUsersList(), "09").isEmpty(),
                "сравнение идёт по каноническому виду id, а не по числовому значению");
    }

    @Test
    void decideAgreesWithFindMemberOnTheSameList() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.just(responseWith(7L, 9L)));
        List<DataTransferService.UserDataRequest> members = responseWith(7L, 9L).getUsersList();

        for (String userId : List.of("9", "7", "42", "09")) {
            MembershipDecision expected = ChatMembershipService.findMember(members, userId).isPresent()
                    ? MembershipDecision.MEMBER
                    : MembershipDecision.NOT_MEMBER;
            assertEquals(expected, service.decide(5L, userId).block(),
                    "вердикт decide() обязан совпадать с предикатом для userId=" + userId);
        }
    }

    @Test
    void decideBlockingReturnsUnknownInsteadOfThrowing() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.never());

        ChatMembershipService fastService = new ChatMembershipService(stub, metric, cacheMetric) {
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

        ChatMembershipService guarded = new ChatMembershipService(stub, metric, cacheMetric) {
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

        ChatMembershipService fastService = new ChatMembershipService(stub, metric, cacheMetric) {
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

    // ─── Кеш состава чата (beads 9wi) ────────────────────────────────────────────

    private double cacheCounter(String result) {
        return registry.get(MembershipCacheMetric.METRIC).tag("result", result).counter().count();
    }

    private int grpcCalls() {
        return Mockito.mockingDetails(stub).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("getAllUsersByChatId"))
                .toList()
                .size();
    }

    /**
     * Что делает: дважды спрашивает состав одного и того же чата.
     * Что проверяет: в gRPC ушёл ровно ОДИН вызов, второй ответ пришёл из кеша;
     * счётчики дали miss=1, hit=1.
     * Зачем: собственно цель тикета — снять повторный вызов с горячего пути (события
     * набора текста летят примерно дважды за заход). Счётчики проверяются здесь же,
     * потому что именно ими тикет предлагал добрать недостающие замеры.
     */
    @Test
    void secondLookupForSameChatIsServedFromCache() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.just(responseWith(7L, 9L)));

        assertEquals(2, service.members(5L).block().size());
        assertEquals(2, service.members(5L).block().size());

        assertEquals(1, grpcCalls(), "второй запрос обязан обслуживаться кешем");
        assertEquals(1.0, cacheCounter(MembershipCacheMetric.RESULT_MISS));
        assertEquals(1.0, cacheCounter(MembershipCacheMetric.RESULT_HIT));
    }

    /**
     * Что делает: спрашивает состав двух РАЗНЫХ чатов.
     * Что проверяет: оба запроса ушли в gRPC, попаданий нет.
     * Зачем: сторожит ключ кеша. Кеш без chatId в ключе отдавал бы состав чужого чата —
     * это не промах производительности, а прямая утечка членства.
     */
    @Test
    void differentChatsDoNotShareCacheEntry() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.just(responseWith(7L)));

        service.members(1L).block();
        service.members(2L).block();

        assertEquals(2, grpcCalls(), "состав разных чатов не имеет права смешиваться");
        assertEquals(0.0, cacheCounter(MembershipCacheMetric.RESULT_HIT));
    }

    /**
     * Что делает: gRPC возвращает ПУСТОЙ список участников дважды подряд.
     * Что проверяет: оба раза был реальный вызов — пустота не осела в кеше.
     * Зачем: decide() трактует пустой список как NOT_MEMBER. Закешированная пустота
     * приколотила бы ложный отказ на весь TTL, а достижима она гонкой с созданием чата:
     * строка чата уже есть, а связки в user_chat ещё пишутся (ReactiveImpl.transferchat
     * вставляет их отдельными операциями после save чата).
     */
    @Test
    void emptyMemberListIsNotCached() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.just(DataTransferService.UserListResponse.newBuilder().build()));

        assertTrue(service.members(5L).block().isEmpty());
        assertTrue(service.members(5L).block().isEmpty());

        assertEquals(2, grpcCalls(), "пустой список не имеет права кешироваться");
        assertEquals(0.0, cacheCounter(MembershipCacheMetric.RESULT_HIT));
    }

    /**
     * Что делает: первый запрос падает детерминированной ошибкой (не ретраится), второй
     * получает нормальный ответ.
     * Что проверяет: второй запрос дошёл до gRPC и вернул состав.
     * Зачем: исход UNKNOWN обязан оставаться живым. Закешируйся отказ — одна сетевая
     * заминка замораживала бы «состояние неизвестно» на весь TTL, и на fail-closed путях
     * это выглядело бы как отказ в доступе на минуту.
     */
    @Test
    void failedLookupIsNotCached() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.error(new StatusRuntimeException(Status.INVALID_ARGUMENT)))
                .thenReturn(Mono.just(responseWith(9L)));

        assertEquals(MembershipDecision.UNKNOWN, service.decide(5L, "9").block());
        assertEquals(MembershipDecision.MEMBER, service.decide(5L, "9").block());

        assertEquals(2, grpcCalls(), "неуспешный ответ не имеет права кешироваться");
    }

    /**
     * Что делает: сервис с укороченным до 50 мс TTL; запрос, ожидание сверх срока, ещё запрос.
     * Что проверяет: после истечения TTL снова случился вызов gRPC.
     * Зачем: TTL здесь не средство корректности (состав чата неизменяем), а страховка —
     * ограничение памяти и потолок экспозиции на случай, если удаление участника из чата
     * когда-нибудь появится, а про кеш забудут. Тест сторожит, что срок вообще
     * соблюдается, а не задан декоративно.
     */
    @Test
    void cacheEntryExpiresAfterTtl() throws InterruptedException {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.just(responseWith(7L)));

        ChatMembershipService shortTtl = new ChatMembershipService(stub, metric, cacheMetric) {
            @Override
            Duration cacheTtl() {
                return Duration.ofMillis(50);
            }
        };

        shortTtl.members(5L).block();
        Thread.sleep(120);
        shortTtl.members(5L).block();

        assertEquals(2, grpcCalls(), "после истечения TTL состав обязан перезапрашиваться");
    }

    /**
     * Что делает: собирает цепочку members(), не подписываясь на неё, затем подписывается.
     * Что проверяет: до подписки в кеш ничего не попало и счётчики не двигались.
     * Зачем: без Mono.defer поиск в кеше выполнялся бы при СБОРКЕ цепочки. Тогда повторная
     * подписка на один и тот же Mono отдавала бы снимок, снятый в прошлом, а промах,
     * случившийся при сборке, не стал бы попаданием при подписке — то есть кеш врал бы
     * и в поведении, и в собственных метриках.
     */
    @Test
    void cacheIsConsultedOnSubscriptionNotOnAssembly() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.just(responseWith(7L)));

        Mono<List<DataTransferService.UserDataRequest>> assembled = service.members(5L);

        assertEquals(0.0, cacheCounter(MembershipCacheMetric.RESULT_MISS));
        assertEquals(0, grpcCalls());

        assembled.block();

        assertEquals(1.0, cacheCounter(MembershipCacheMetric.RESULT_MISS));
        assertEquals(1, grpcCalls());
    }
}
