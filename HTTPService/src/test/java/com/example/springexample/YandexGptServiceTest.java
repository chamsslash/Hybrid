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

class YandexGptServiceTest {

    private final YandexGptService service = new YandexGptService();

    @Test
    void buildCompletionRequestBody_usesModelUriFromConfig() {
        ReflectionTestUtils.setField(service, "modelUri", "gpt://test-folder/test-model/rc");
        JsonArray prompt = service.BuildJsonPrompt("system task", Map.of("alice", List.of("hi")));

        JsonObject body = service.buildCompletionRequestBody(prompt);

        assertEquals("gpt://test-folder/test-model/rc", body.get("modelUri").getAsString());
        assertEquals(0.5, body.getAsJsonObject("completionOptions").get("temperature").getAsDouble());
        assertEquals(prompt, body.getAsJsonArray("messages"));
    }

    @Test
    void buildJsonPrompt_buildsSystemAndUserMessages() {
        JsonArray prompt = service.BuildJsonPrompt("system task", Map.of("alice", List.of("hi")));

        assertEquals(2, prompt.size());

        JsonObject system = prompt.get(0).getAsJsonObject();
        assertEquals("system", system.get("role").getAsString());
        assertEquals("system task", system.get("text").getAsString());

        JsonObject user = prompt.get(1).getAsJsonObject();
        assertEquals("user", user.get("role").getAsString());
        assertEquals("alice: hi", user.get("text").getAsString());
    }

    @Test
    void buildJsonPrompt_throwsOnEmptyInput() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.BuildJsonPrompt("system task", Map.of()));
        assertEquals("Empty prompt", ex.getMessage());
    }

    @Test
    void parseKeys_blankPrivateKey_throwsClearError() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.parseKeys("public", "   ", "sa-id", "key-id"));
        assertTrue(ex.getMessage().contains("YANDEX_PRIVATE_KEY_PEM"),
                "message should name the missing secret, was: " + ex.getMessage());
    }

    @Test
    void parseKeys_nullPrivateKey_throwsClearError() {
        assertThrows(IllegalStateException.class,
                () -> service.parseKeys("public", null, "sa-id", "key-id"));
    }
}
