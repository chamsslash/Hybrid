package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import com.example.springexample.GeminiPrompt;
import com.example.springexample.GeminiService;
import com.example.springexample.Metrics.GrpcRequestsMetric;
import com.example.springexample.Metrics.MembershipCacheMetric;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ReactiveListOperations;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.ReactorReactiveTransferServiceGrpc;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Промпт, который `/AiAssist` собирает из Redis-контекста (beads j97).
 *
 * Симптом тикета: ассистент здоровался и спрашивал «как дела» вместо ответа на последнее
 * сообщение собеседника. Контекст при этом писался исправно — ломалась сборка промпта:
 * реплики группировались по авторам в `HashMap` (порядок разговора терялся полностью)
 * и нигде не было сказано, на какое именно сообщение отвечать.
 *
 * Тест идёт через настоящий `aiAssistHandler` и настоящий `GeminiService.BuildJsonPrompt`,
 * потому что стережёт шов между слоями: порядок из Redis обязан дойти до `contents`
 * неизменным, а якорь — попасть в system-инструкцию. Замокан только сетевой вызов
 * `GetAssistantAnswer`. Членство проверяется настоящим `ChatMembershipService` поверх
 * замоканного gRPC-стаба, как в `AiAssistMembershipTest`: иначе до сборки промпта
 * управление не дойдёт.
 */
class AiAssistPromptContextTest {

    // RETURNS_SELF: members() зовёт stub.withDeadlineAfter(...) — см. AiAssistMembershipTest.
    private final ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub stub =
            Mockito.mock(ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub.class,
                    Mockito.RETURNS_SELF);

    private final ChatMembershipService membership = new ChatMembershipService(
            stub, new GrpcRequestsMetric(new SimpleMeterRegistry()),
            new MembershipCacheMetric(new SimpleMeterRegistry()));

    // Спай, а не мок: BuildJsonPrompt должен исполниться настоящий (он и есть предмет
    // проверки), наружу в сеть не уходит только GetAssistantAnswer.
    private final GeminiService gemini = Mockito.spy(new GeminiService());

    @SuppressWarnings("unchecked")
    private final ReactiveRedisTemplate<String, String> redis = Mockito.mock(ReactiveRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ReactiveListOperations<String, String> list = Mockito.mock(ReactiveListOperations.class);

    private static final Principal SUNNY = new UsernamePasswordAuthenticationToken("4", null, List.of());

    private static String json(String user, String message) {
        return "{\"user\":\"" + user + "\",\"message\":\"" + message + "\"}";
    }

    /** Контекст чата 3: хвост, вытесненный из окна, и само окно — оба хронологически. */
    private void contextOfChat3(List<String> tail, List<String> window) {
        Mockito.when(redis.opsForList()).thenReturn(list);
        Mockito.when(list.range("oldmessages-3", 0, -1)).thenReturn(Flux.fromIterable(tail));
        Mockito.when(list.range("newmessages-3", 0, -1)).thenReturn(Flux.fromIterable(window));
    }

    private WEBFLUX_Service service() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.just(DataTransferService.UserListResponse.newBuilder()
                        .addUsers(DataTransferService.UserDataRequest.newBuilder()
                                .setId(4L).setUsername("gyattalert").build())
                        .addUsers(DataTransferService.UserDataRequest.newBuilder()
                                .setId(7L).setUsername("yamam").build())
                        .build()));
        Mockito.doReturn(Mono.just("готовый ответ")).when(gemini).GetAssistantAnswer(Mockito.any());

        WEBFLUX_Service service = new WEBFLUX_Service();
        ReflectionTestUtils.setField(service, "rredisTemplate", redis);
        ReflectionTestUtils.setField(service, "chatMembershipService", membership);
        ReflectionTestUtils.setField(service, "geminiService", gemini);
        return service;
    }

    private GeminiPrompt promptFor(String targetUsername) {
        ResponseEntity<String> response = service().aiAssistHandler(SUNNY, targetUsername, "3").block();
        assertNotNull(response, "хендлер обязан вернуть ответ");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<GeminiPrompt> captor = ArgumentCaptor.forClass(GeminiPrompt.class);
        Mockito.verify(gemini).GetAssistantAnswer(captor.capture());
        return captor.getValue();
    }

    private static List<String> turnTexts(GeminiPrompt prompt) {
        return prompt.contents().asList().stream()
                .map(turn -> turn.getAsJsonObject().getAsJsonArray("parts")
                        .get(0).getAsJsonObject().get("text").getAsString())
                .toList();
    }

    @Test
    void dialogReachesGeminiInConversationOrderWithAuthors() {
        // Живой дамп из прода (newmessages-2), выпрямленный в хронологию: два сообщения
        // уже вытеснены в хвост, два лежат в окне.
        contextOfChat3(
                List.of(json("gyattalert", "привет"), json("yamam", "🙂")),
                List.of(json("gyattalert", "рад тебя видеть"), json("yamam", "как настроение?")));

        GeminiPrompt prompt = promptFor("yamam");

        assertEquals(List.of(
                        "gyattalert: привет",
                        "yamam: 🙂",
                        "gyattalert: рад тебя видеть",
                        "yamam: как настроение?"),
                turnTexts(prompt),
                "contents обязан повторять хронологию чата: хвост, затем окно, каждая реплика с автором");
        // Зачем именно так: прежняя сборка сворачивала поток в Map<ник, реплики> — в промпт
        // уходило «все реплики gyattalert, потом все реплики yamam» в произвольном порядке
        // ключей HashMap, и модель отвечала на что угодно, обычно на самое первое «привет».
    }

    @Test
    void systemInstructionAnchorsOnTheLastMessageOfTheTargetUser() {
        contextOfChat3(
                List.of(json("yamam", "первое сообщение yamam")),
                List.of(json("gyattalert", "ответ"), json("yamam", "последнее сообщение yamam")));

        String instruction = promptFor("yamam").systemInstruction();

        assertTrue(instruction.contains("последнее сообщение yamam"),
                "в инструкции обязан быть якорь — текст последнего сообщения целевого пользователя, было: "
                        + instruction);
        assertFalse(instruction.contains("первое сообщение yamam"),
                "якорем должно быть именно ПОСЛЕДНЕЕ сообщение, а не любое из его реплик");
        assertTrue(instruction.contains("@yamam"),
                "требование упоминать собеседника из старого промпта сохраняется");
        // Зачем: раньше инструкция просила «составить ответ пользователю X на его сообщение»,
        // не говоря, какое именно — а оно лежало в перемешанном и вывернутом контексте.
    }

    @Test
    void missingRepliesOfTargetUserStillProduceAnswerWithoutFakeAnchor() {
        // Целевой участник в окне контекста не писал ничего (например, вся история — реплики
        // других). Якорить не на что, но отказывать незачем: подсказка всё равно строится
        // по переписке, а прежний путь на этом входе просто молчал о проблеме.
        contextOfChat3(List.of(), List.of(json("gyattalert", "кто тут?")));

        GeminiPrompt prompt = promptFor("yamam");

        assertEquals(List.of("gyattalert: кто тут?"), turnTexts(prompt));
        assertTrue(prompt.systemInstruction().contains("yamam"),
                "ник целевого пользователя в инструкции остаётся даже без якоря");
        assertFalse(prompt.systemInstruction().contains("«»"),
                "пустой якорь в кавычках в инструкцию попасть не должен");
    }

    // --- неудача генерации не должна выглядеть как подсказка (beads n2f) ---

    @Test
    void emptyContextIsRefusedWithUnprocessableAndNotAsASuggestion() {
        // В чате ещё нет сообщений: собирать промпт не из чего.
        contextOfChat3(List.of(), List.of());

        ResponseEntity<String> response = service().aiAssistHandler(SUNNY, "yamam", "3").block();

        assertNotNull(response, "хендлер обязан вернуть ответ, а не пустой Mono");
        // Именно НЕ 200: фронт отличает подсказку от неудачи по статусу, и на 2xx он
        // показывает панель с кнопкой «Вставить». Прежний 200 с текстом в теле означал,
        // что пользователю предлагают отправить сообщение об ошибке в чат.
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode(),
                "пустой контекст — это «генерировать нечего», а не ответ ассистента");
        Mockito.verify(gemini, Mockito.never()).GetAssistantAnswer(Mockito.any());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("нет сообщений"),
                "тело объясняет человеку, почему не вышло, было: " + response.getBody());
    }

    @Test
    void generationFailureIsRefusedWithServiceUnavailableAndLeaksNoInternals() {
        contextOfChat3(List.of(), List.of(json("gyattalert", "привет")));
        // service() сам стабит GetAssistantAnswer на успешный ответ, поэтому отказ ставим
        // ПОСЛЕ его вызова — иначе он затрёт этот стаб и тест проверит сытый путь.
        WEBFLUX_Service service = service();
        // Сообщение настоящего отказа: ровно так падает requireApiKey() в GeminiService,
        // и до этой правки его текст уезжал пользователю как готовая подсказка.
        Mockito.doReturn(Mono.error(new IllegalStateException(
                        "Gemini API key is not set (empty or missing) in environment variable: GEMINI_API_KEY")))
                .when(gemini).GetAssistantAnswer(Mockito.any());

        ResponseEntity<String> response = service.aiAssistHandler(SUNNY, "yamam", "3").block();

        assertNotNull(response);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode(),
                "сорвавшаяся генерация — это отказ, а не ответ ассистента");
        assertNotNull(response.getBody());
        // Текст исключения наружу не отдаётся: имя переменной окружения пользователю
        // бесполезно, а в чат такую строку раньше можно было вставить одной кнопкой.
        assertFalse(response.getBody().contains("GEMINI_API_KEY"),
                "внутренности наружу не уезжают, было: " + response.getBody());
        assertFalse(response.getBody().contains("Извините, сервис временно недоступен"),
                "прежняя формулировка приехала бы с кодом 200 — её быть не должно");
    }
}
