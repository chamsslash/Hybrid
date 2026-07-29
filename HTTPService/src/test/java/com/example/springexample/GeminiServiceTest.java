package com.example.springexample;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiServiceTest {

    private final GeminiService service = new GeminiService();

    @Test
    void buildCompletionRequestBody_usesModelFromConfig_andWiresSystemInstructionSeparately() {
        ReflectionTestUtils.setField(service, "model", "gemini-2.5-flash-lite");
        GeminiPrompt prompt = service.BuildJsonPrompt("system task", Map.of("alice", List.of("hi")));

        JsonObject body = service.buildCompletionRequestBody(prompt, null);

        assertEquals("system task",
                body.getAsJsonObject("system_instruction").getAsJsonArray("parts")
                        .get(0).getAsJsonObject().get("text").getAsString());
        assertEquals(prompt.contents(), body.getAsJsonArray("contents"));
        assertEquals(0.5, body.getAsJsonObject("generationConfig").get("temperature").getAsDouble());
        assertTrue(body.getAsJsonObject("generationConfig").get("responseMimeType") == null,
                "без переданной схемы responseMimeType не должен добавляться");
    }

    @Test
    void buildCompletionRequestBody_withResponseSchema_addsStructuredOutputConfig() {
        GeminiPrompt prompt = service.BuildJsonPrompt("sys", Map.of("bob", List.of("hey")));
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "OBJECT");

        JsonObject body = service.buildCompletionRequestBody(prompt, schema);

        JsonObject generationConfig = body.getAsJsonObject("generationConfig");
        assertEquals("application/json", generationConfig.get("responseMimeType").getAsString());
        assertEquals(schema, generationConfig.getAsJsonObject("responseSchema"));
    }

    @Test
    void buildJsonPrompt_buildsOneContentTurnPerMessage_withUserRole() {
        GeminiPrompt prompt = service.BuildJsonPrompt("system task", Map.of("alice", List.of("hi")));

        assertEquals(1, prompt.contents().size());
        JsonObject turn = prompt.contents().get(0).getAsJsonObject();
        assertEquals("user", turn.get("role").getAsString());
        assertEquals("alice: hi", turn.getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void buildJsonPrompt_multipleMessagesFromSameUser_areNotCollapsedIntoLastOne() {
        // Регрессия: старый Yandex-код мутировал один и тот же JsonObject по ссылке —
        // все сообщения одного юзера схлопывались в последнее при сериализации.
        GeminiPrompt prompt = service.BuildJsonPrompt("sys", Map.of("alice", List.of("first", "second", "third")));

        assertEquals(3, prompt.contents().size());
        assertEquals("alice: first",
                prompt.contents().get(0).getAsJsonObject().getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("alice: second",
                prompt.contents().get(1).getAsJsonObject().getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("alice: third",
                prompt.contents().get(2).getAsJsonObject().getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void buildJsonPrompt_throwsOnEmptyInput() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.BuildJsonPrompt("system task", Map.of()));
        assertEquals("Empty prompt", ex.getMessage());
    }

    @Test
    void requireApiKey_blankKey_throwsClearErrorViaGetAssistantAnswer() {
        ReflectionTestUtils.setField(service, "apiKey", "   ");
        GeminiPrompt prompt = service.BuildJsonPrompt("sys", Map.of("alice", List.of("hi")));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.GetAssistantAnswer(prompt).block());
        assertTrue(ex.getMessage().contains("GEMINI_API_KEY"),
                "message should name the missing secret, was: " + ex.getMessage());
    }

    @Test
    void requireApiKey_nullKey_throwsClearErrorViaGetAssistantAnswer() {
        ReflectionTestUtils.setField(service, "apiKey", null);
        GeminiPrompt prompt = service.BuildJsonPrompt("sys", Map.of("alice", List.of("hi")));

        assertThrows(IllegalStateException.class, () -> service.GetAssistantAnswer(prompt).block());
    }

    @Test
    void buildSecurityCheckPrompt_wrapsBothFingerprintsInSingleUserTurn() {
        // ClientMeta — record с 9 обязательными String-полями (ip,country,city,asn,org,
        // visitorId,components,secureUUID,ptr), нет no-arg конструктора.
        var one = new com.example.springexample.Utils.FpSimilarityScore.ClientMeta(
                "1.2.3.4", "US", "NYC", "asn1", "org1", "visitor1", "components1", "secure1", "ptr1");
        var two = new com.example.springexample.Utils.FpSimilarityScore.ClientMeta(
                "5.6.7.8", "US", "NYC", "asn1", "org1", "visitor2", "components2", "secure1", "ptr2");

        GeminiPrompt prompt = service.BuildSecurityCheckPrompt(one, two);

        assertEquals(1, prompt.contents().size());
        assertTrue(prompt.systemInstruction().contains("secureUUID"),
                "системная инструкция должна содержать методику анализа");
        assertEquals("user", prompt.contents().get(0).getAsJsonObject().get("role").getAsString());
    }
}
