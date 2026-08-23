package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import com.example.springexample.GeminiService;
import com.example.springexample.Metrics.GrpcRequestsMetric;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ReactiveListOperations;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveSetOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.ReactorReactiveTransferServiceGrpc;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Авторизация /AiAssist (beads dz5): хендлер раньше вообще не имел Principal в сигнатуре
 * и читал Redis-контекст любого чата по chat_id из тела запроса — залогиненный
 * посторонний получал пересказ чужой переписки. Тесты стерегут, что членство
 * проверяется ДО чтения Redis и что отказ — настоящий 403, а не 200 с текстом.
 *
 * Членство проверяется НАСТОЯЩИМ ChatMembershipService поверх замоканного gRPC-стаба
 * (beads cgu), по образцу ApiControllerChatAccessTest, а не заглушкой самой проверки.
 * Заглушить членство булевым стабом здесь нельзя: fail-closed-политика (ошибка gRPC,
 * таймаут, пустой список, отсутствие ответа) — ровно то, что эти тесты и стерегут,
 * и стаб превратил бы их в проверку мока. С настоящим сервисом каждый исход по-прежнему
 * задаётся на границе с MessegerParody, а тесты видят его сквозь всю цепочку до HTTP-статуса.
 */
class AiAssistMembershipTest {

    // RETURNS_SELF (beads 8wh, F3): ChatMembershipService.members() зовёт
    // stub.withDeadlineAfter(...) перед getAllUsersByChatId; у настоящего AbstractStub это
    // возвращает новый стаб с тем же каналом, а у мока без явного стаба вернуло бы null
    // и роняло бы цепочку NPE до gRPC-вызова.
    private final ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub stub =
            Mockito.mock(ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub.class,
                    Mockito.RETURNS_SELF);

    private final GrpcRequestsMetric grpcMetric = new GrpcRequestsMetric(new SimpleMeterRegistry());

    // Спай, а не голый экземпляр (beads cgu): все методы выполняются настоящие, но тест
    // может (а) убедиться, что хендлер спрашивает членство единственным законным способом,
    // и (б) укоротить membershipTimeout() там, где таймаут проверяется по-настоящему.
    private final ChatMembershipService membership =
            Mockito.spy(new ChatMembershipService(stub, grpcMetric));

    private final GeminiService geminiService = Mockito.mock(GeminiService.class);

    private static final Principal SUNNY = new UsernamePasswordAuthenticationToken("4", null, List.of());

    private static DataTransferService.UserListResponse membersResponse(long... ids) {
        DataTransferService.UserListResponse.Builder b = DataTransferService.UserListResponse.newBuilder();
        for (long id : ids) {
            b.addUsers(DataTransferService.UserDataRequest.newBuilder()
                    .setId(id).setUsername("user-" + id).build());
        }
        return b.build();
    }

    /** Ответ MessegerParody на запрос участников — единственная точка, где задаётся исход. */
    private void membersOfChatAre(Mono<DataTransferService.UserListResponse> response) {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(response);
    }

    private static DataTransferService.ChatData chatData(long chatId) {
        return DataTransferService.ChatData.newBuilder().setChatId(chatId).build();
    }

    private WEBFLUX_Service service(ReactiveRedisTemplate<String, String> redis) {
        WEBFLUX_Service service = new WEBFLUX_Service();
        ReflectionTestUtils.setField(service, "rredisTemplate", redis);
        ReflectionTestUtils.setField(service, "chatMembershipService", membership);
        ReflectionTestUtils.setField(service, "geminiService", geminiService);
        return service;
    }

    /** Redis, который взорвётся при любом обращении: отказ обязан случиться раньше. */
    @SuppressWarnings("unchecked")
    private ReactiveRedisTemplate<String, String> untouchedRedis() {
        return Mockito.mock(ReactiveRedisTemplate.class);
    }

    @SuppressWarnings("unchecked")
    private ReactiveListOperations<String, String> stubEmptyContext(ReactiveRedisTemplate<String, String> redis) {
        ReactiveListOperations<String, String> list = Mockito.mock(ReactiveListOperations.class);
        ReactiveSetOperations<String, String> set = Mockito.mock(ReactiveSetOperations.class);
        Mockito.when(redis.opsForList()).thenReturn(list);
        Mockito.when(redis.opsForSet()).thenReturn(set);
        Mockito.when(list.range(Mockito.anyString(), Mockito.anyLong(), Mockito.anyLong()))
                .thenReturn(Flux.empty());
        Mockito.when(set.members(Mockito.anyString())).thenReturn(Flux.empty());
        return list;
    }

    private ResponseEntity<String> call(WEBFLUX_Service service, Principal principal, String chatId) {
        ResponseEntity<String> response = service.aiAssistHandler(principal, "fwt_probe_b", chatId).block();
        assertNotNull(response, "хендлер обязан вернуть ответ, а не пустой Mono");
        return response;
    }

    private void assertRedisUntouched(ReactiveRedisTemplate<String, String> redis) {
        Mockito.verify(redis, Mockito.never()).opsForList();
        Mockito.verify(redis, Mockito.never()).opsForSet();
    }

    /** Проверка членства не должна была случиться вовсе — ни на каком уровне. */
    private void assertMembershipNotChecked() {
        Mockito.verifyNoInteractions(membership);
        Mockito.verifyNoInteractions(stub);
    }

    @Test
    void memberReachesChatContext() {
        membersOfChatAre(Mono.just(membersResponse(4L, 7L)));
        @SuppressWarnings("unchecked")
        ReactiveRedisTemplate<String, String> redis = Mockito.mock(ReactiveRedisTemplate.class);
        ReactiveListOperations<String, String> list = stubEmptyContext(redis);

        ResponseEntity<String> response = call(service(redis), SUNNY, "3");

        assertEquals(HttpStatus.OK, response.getStatusCode(), "участник чата должен пройти дальше");
        // Контекст пуст, поэтому до Gemini дело не доходит — важно, что чтение Redis состоялось.
        Mockito.verify(list).range("newmessages-3", 0L, -1L);
    }

    @Test
    void nonMemberGetsForbiddenAndRedisIsNotRead() {
        // Сценарий из beads dz5: sunny (id=4) состоит только в чате 3, просит контекст чата 2.
        membersOfChatAre(Mono.just(membersResponse(7L, 9L)));
        ReactiveRedisTemplate<String, String> redis = untouchedRedis();

        ResponseEntity<String> response = call(service(redis), SUNNY, "2");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), "посторонний обязан получить 403, а не 200");
        assertRedisUntouched(redis);
    }

    @ParameterizedTest
    @ValueSource(strings = {"+3", "-3", "abc", "", " 3", "3 ", "3.0", "0x3", "99999999999999999999"})
    void malformedChatIdIsRefusedBeforeAnySideEffect(String chatId) {
        ReactiveRedisTemplate<String, String> redis = untouchedRedis();

        ResponseEntity<String> response = call(service(redis), SUNNY, chatId);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertMembershipNotChecked();
        assertRedisUntouched(redis);
    }

    @Test
    void leadingZeroChatIdIsCanonicalized() {
        // "003" проходит "\\d+", но Redis-ключ обязан быть newmessages-3, а не newmessages-003,
        // иначе контекст одного и того же чата расщепляется на два ключа (сценарий 007 из beads g9x).
        membersOfChatAre(Mono.just(membersResponse(4L)));
        @SuppressWarnings("unchecked")
        ReactiveRedisTemplate<String, String> redis = Mockito.mock(ReactiveRedisTemplate.class);
        ReactiveListOperations<String, String> list = stubEmptyContext(redis);

        assertEquals(HttpStatus.OK, call(service(redis), SUNNY, "003").getStatusCode());

        // Канонизация проверяется на самой границе с MessegerParody: в gRPC уходит chatId=3,
        // а не "003" и не отдельный чат.
        Mockito.verify(stub).getAllUsersByChatId(chatData(3L));
        Mockito.verify(list).range("newmessages-3", 0L, -1L);
    }

    @Test
    void grpcFailureIsRefusedFailClosed() {
        membersOfChatAre(Mono.error(new IllegalStateException("messegerparody недоступен")));
        ReactiveRedisTemplate<String, String> redis = untouchedRedis();

        assertEquals(HttpStatus.FORBIDDEN, call(service(redis), SUNNY, "3").getStatusCode());
        assertRedisUntouched(redis);
    }

    @Test
    void membershipTimeoutIsRefusedFailClosedWithoutStranglingRetry() {
        // MessegerParody не отвечает вовсе — таймаут внутри members() отрабатывает
        // по-настоящему, а не подсовывается готовой ошибкой. Таймаут попытки укорочен
        // до 120 мс по образцу ChatMembershipServiceTest, иначе тест ждал бы 4 с.
        Mockito.doReturn(Duration.ofMillis(120)).when(membership).membershipTimeout();
        membersOfChatAre(Mono.never());
        ReactiveRedisTemplate<String, String> redis = untouchedRedis();

        assertEquals(HttpStatus.FORBIDDEN, call(service(redis), SUNNY, "3").getStatusCode());

        // Попыток ровно две (beads 8wh, cgu): таймаут на попытку и один повтор живут
        // ВНУТРИ members(). Любой внешний .timeout()/.retryWhen() поверх проверки членства
        // в хендлере срывал бы цепочку на первом же таймауте, и здесь осталась бы одна
        // попытка — ровно та регрессия, которую в прошлый раз поймало только ревью.
        Mockito.verify(stub, Mockito.times(2))
                .getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class));
        assertRedisUntouched(redis);
    }

    @Test
    void emptyMemberListIsRefusedFailClosed() {
        membersOfChatAre(Mono.just(membersResponse()));
        ReactiveRedisTemplate<String, String> redis = untouchedRedis();

        assertEquals(HttpStatus.FORBIDDEN, call(service(redis), SUNNY, "3").getStatusCode());
        assertRedisUntouched(redis);
    }

    @Test
    void unknownChatIsRefusedFailClosed() {
        // Несуществующий чат: ответа от MessegerParody нет вовсе (пустой Mono), а не
        // пустой список. Тот же класс, что unknownChatIdIsForbiddenNotServerError
        // для /api/chat — отсутствие ответа обязано читаться как отказ, а не как «всем можно».
        membersOfChatAre(Mono.empty());
        ReactiveRedisTemplate<String, String> redis = untouchedRedis();

        assertEquals(HttpStatus.FORBIDDEN, call(service(redis), SUNNY, "3").getStatusCode());
        assertRedisUntouched(redis);
    }

    @Test
    void missingPrincipalIsRefused() {
        ReactiveRedisTemplate<String, String> redis = untouchedRedis();

        assertEquals(HttpStatus.FORBIDDEN, call(service(redis), null, "3").getStatusCode());
        assertMembershipNotChecked();
        assertRedisUntouched(redis);
    }

    /**
     * Сквозная проверка через MVC: отказ обязан дойти до клиента настоящим статусом 403.
     * Хендлер отдаёт Mono, поэтому ответ приходит вторым, async-диспатчем; фронтовой
     * axios-интерцептор перехватывает только 401, так что 403 уходит в .catch как ошибка.
     */
    @Test
    void forbiddenReachesTheWireAsHttp403() throws Exception {
        membersOfChatAre(Mono.just(membersResponse(7L)));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(service(untouchedRedis())).build();

        MvcResult started = mvc.perform(multipart("/AiAssist")
                        .file(new MockMultipartFile("TargetUsername", "", MediaType.TEXT_PLAIN_VALUE,
                                "fwt_probe_b".getBytes()))
                        .file(new MockMultipartFile("chat_id", "", MediaType.TEXT_PLAIN_VALUE, "2".getBytes()))
                        .principal(SUNNY))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(started))
                .andExpect(status().isForbidden());
    }
}
