package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import com.example.springexample.GeminiService;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.List;
import java.util.concurrent.TimeoutException;

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
 */
class AiAssistMembershipTest {

    private final ChatMembershipService membership = Mockito.mock(ChatMembershipService.class);
    private final GeminiService geminiService = Mockito.mock(GeminiService.class);

    private static final Principal SUNNY = new UsernamePasswordAuthenticationToken("4", null, List.of());

    private static DataTransferService.UserDataRequest user(long id, String name) {
        return DataTransferService.UserDataRequest.newBuilder().setId(id).setUsername(name).build();
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

    @Test
    void memberReachesChatContext() {
        Mockito.when(membership.members(3L)).thenReturn(Mono.just(List.of(user(4L, "sunny"), user(7L, "оля"))));
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
        Mockito.when(membership.members(2L)).thenReturn(Mono.just(List.of(user(7L, "оля"), user(9L, "дима"))));
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
        Mockito.verify(membership, Mockito.never()).members(Mockito.anyLong());
        assertRedisUntouched(redis);
    }

    @Test
    void leadingZeroChatIdIsCanonicalized() {
        // "003" проходит "\\d+", но Redis-ключ обязан быть newmessages-3, а не newmessages-003,
        // иначе контекст одного и того же чата расщепляется на два ключа (сценарий 007 из beads g9x).
        Mockito.when(membership.members(3L)).thenReturn(Mono.just(List.of(user(4L, "sunny"))));
        @SuppressWarnings("unchecked")
        ReactiveRedisTemplate<String, String> redis = Mockito.mock(ReactiveRedisTemplate.class);
        ReactiveListOperations<String, String> list = stubEmptyContext(redis);

        assertEquals(HttpStatus.OK, call(service(redis), SUNNY, "003").getStatusCode());

        Mockito.verify(membership).members(3L);
        Mockito.verify(list).range("newmessages-3", 0L, -1L);
    }

    @Test
    void grpcFailureIsRefusedFailClosed() {
        Mockito.when(membership.members(3L)).thenReturn(Mono.error(new IllegalStateException("gRPC недоступен")));
        ReactiveRedisTemplate<String, String> redis = untouchedRedis();

        assertEquals(HttpStatus.FORBIDDEN, call(service(redis), SUNNY, "3").getStatusCode());
        assertRedisUntouched(redis);
    }

    @Test
    void membershipTimeoutIsRefusedFailClosed() {
        // Внешнего .timeout() в этом пути больше нет (beads 8wh) — таймаут и повтор
        // теперь целиком внутри members(). Здесь members() замокан напрямую, поэтому
        // реальный таймаут не воспроизвести; вместо этого симулируем его исход —
        // ошибку, которую members() отдаёт наружу, когда её собственный таймаут истёк
        // и повтор не спас (java.util.concurrent.TimeoutException после исчерпания
        // Retry). Свойство под проверкой то же самое: таймаут — это fail-closed, а не 200.
        Mockito.when(membership.members(3L)).thenReturn(Mono.error(new TimeoutException()));
        ReactiveRedisTemplate<String, String> redis = untouchedRedis();

        assertEquals(HttpStatus.FORBIDDEN, call(service(redis), SUNNY, "3").getStatusCode());
        assertRedisUntouched(redis);
    }

    @Test
    void emptyMemberListIsRefusedFailClosed() {
        Mockito.when(membership.members(3L)).thenReturn(Mono.just(List.of()));
        ReactiveRedisTemplate<String, String> redis = untouchedRedis();

        assertEquals(HttpStatus.FORBIDDEN, call(service(redis), SUNNY, "3").getStatusCode());
        assertRedisUntouched(redis);
    }

    @Test
    void missingPrincipalIsRefused() {
        ReactiveRedisTemplate<String, String> redis = untouchedRedis();

        assertEquals(HttpStatus.FORBIDDEN, call(service(redis), null, "3").getStatusCode());
        Mockito.verify(membership, Mockito.never()).members(Mockito.anyLong());
        assertRedisUntouched(redis);
    }

    /**
     * Сквозная проверка через MVC: отказ обязан дойти до клиента настоящим статусом 403.
     * Хендлер отдаёт Mono, поэтому ответ приходит вторым, async-диспатчем; фронтовой
     * axios-интерцептор перехватывает только 401, так что 403 уходит в .catch как ошибка.
     */
    @Test
    void forbiddenReachesTheWireAsHttp403() throws Exception {
        Mockito.when(membership.members(2L)).thenReturn(Mono.just(List.of(user(7L, "оля"))));
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
