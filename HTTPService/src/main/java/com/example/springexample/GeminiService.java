package com.example.springexample;

import com.example.springexample.Metrics.AiRequestMetric;
import com.example.springexample.Utils.FpSimilarityScore;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class GeminiService {

    @Autowired
    AiRequestMetric aiRequestMetric;

    @Value("${GEMINI_API_KEY:}")
    private String apiKey;

    @Value("${GEMINI_MODEL:gemini-2.5-flash-lite}")
    private String model;

    private final Gson gson = new Gson();
    private final WebClient wC = WebClient.builder().build();

    public Mono<String> GetAssistantAnswer(GeminiPrompt prompt) {
        requireApiKey();
        JsonObject requestBody = buildCompletionRequestBody(prompt, null);
        aiRequestMetric.increment();
        return callGenerateContent(requestBody).map(GeminiService::extractPlainText);
    }

    public Mono<String> aiSecurePredict(GeminiPrompt prompt) {
        requireApiKey();
        JsonObject requestBody = buildCompletionRequestBody(prompt, securePredictResponseSchema());
        log.warn(prompt.contents().toString());
        aiRequestMetric.increment();
        // callGenerateContent() отдаёт полный конверт ответа Gemini
        // (candidates[0].content.parts[0].text), а structured-output JSON
        // {reasoning, probability} лежит ВНУТРИ этого text как строка —
        // extractPlainText() достаёт именно её, до этого parsed.get(...)
        // читал поля из конверта и всегда получал null/NPE.
        return callGenerateContent(requestBody).map(GeminiService::extractPlainText).map(text -> {
            JsonObject parsed = JsonParser.parseString(text).getAsJsonObject();
            log.info("Security Predict Explanation:" + parsed.get("reasoning"));
            return parsed.get("probability").getAsString();
        });
    }

    private Mono<String> callGenerateContent(JsonObject requestBody) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey); // уже провалидирован requireApiKey() в вызывающем публичном методе
        // BodyInserters.fromValue(requestBody) отдаёт com.google.gson.JsonObject напрямую
        // Spring'овому Jackson-энкодеру, который сериализует его как обычный java-бин через
        // рефлексию — натыкается на служебный геттер JsonObject.getAsDouble() (кидает
        // UnsupportedOperationException на не-примитиве) и падает с EncodingException.
        // Gson и Jackson — разные библиотеки, Jackson не умеет в JsonObject. Сериализуем
        // сами через Gson (.toString() уже даёт валидный JSON) и шлём как обычную строку.
        return wC.post().uri(url).body(BodyInserters.fromValue(requestBody.toString())).headers(h -> h.addAll(headers))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("Unknown 4xx error")
                        .flatMap(errorBody -> {
                            log.error("Client error (4xx): {}", errorBody);
                            return Mono.error(new RuntimeException("Client error: " + errorBody));
                        }))
                .onStatus(HttpStatusCode::is5xxServerError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("Unknown 5xx error")
                        .flatMap(errorBody -> {
                            log.error("Server error (5xx): {}", errorBody);
                            return Mono.error(new RuntimeException("Server error: " + errorBody));
                        }))
                .bodyToMono(String.class)
                .doOnError(e -> log.error(e.getMessage()));
    }

    static String extractPlainText(String body) {
        if (body == null || body.isEmpty()) {
            throw new RuntimeException("Ai response has uncorrect struckture");
        }
        JsonObject responseObj = JsonParser.parseString(body).getAsJsonObject();
        JsonArray candidates = responseObj.getAsJsonArray("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new RuntimeException("Ai response has uncorrect struckture");
        }
        JsonObject firstCandidate = candidates.get(0).getAsJsonObject();
        JsonArray parts = firstCandidate.getAsJsonObject("content").getAsJsonArray("parts");
        return parts.get(0).getAsJsonObject().get("text").getAsString();
    }

    JsonObject buildCompletionRequestBody(GeminiPrompt prompt, JsonObject responseSchemaOrNull) {
        JsonObject requestBody = new JsonObject();

        JsonObject systemInstruction = new JsonObject();
        JsonArray systemParts = new JsonArray();
        JsonObject systemPart = new JsonObject();
        systemPart.addProperty("text", prompt.systemInstruction());
        systemParts.add(systemPart);
        systemInstruction.add("parts", systemParts);
        requestBody.add("system_instruction", systemInstruction);

        requestBody.add("contents", prompt.contents());

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("temperature", 0.5);
        if (responseSchemaOrNull != null) {
            generationConfig.addProperty("responseMimeType", "application/json");
            generationConfig.add("responseSchema", responseSchemaOrNull);
        }
        requestBody.add("generationConfig", generationConfig);
        return requestBody;
    }

    private static JsonObject securePredictResponseSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "OBJECT");
        JsonObject properties = new JsonObject();
        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("type", "STRING");
        properties.add("reasoning", reasoning);
        JsonObject probability = new JsonObject();
        probability.addProperty("type", "NUMBER");
        properties.add("probability", probability);
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("reasoning");
        required.add("probability");
        schema.add("required", required);
        return schema;
    }

    public GeminiPrompt BuildJsonPrompt(String SystemTask, Map<String, List<String>> Username_Mesage) {
        if (Username_Mesage.keySet().isEmpty()) {
            throw new RuntimeException("Empty prompt");
        }
        JsonArray contents = new JsonArray();
        for (Map.Entry<String, List<String>> entry : Username_Mesage.entrySet()) {
            for (String mess : entry.getValue()) {
                contents.add(userTurn(entry.getKey() + ": " + mess));
            }
        }
        if (contents.size() == 0) {
            throw new RuntimeException("Empty prompt");
        }
        return new GeminiPrompt(SystemTask, contents);
    }

    private static JsonObject userTurn(String text) {
        JsonObject turn = new JsonObject();
        turn.addProperty("role", "user");
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        parts.add(part);
        turn.add("parts", parts);
        return turn;
    }

    public GeminiPrompt BuildSecurityCheckPrompt(FpSimilarityScore.ClientMeta one, FpSimilarityScore.ClientMeta two) {
        String systemInstruction = "Твоя задача — провести экспертный анализ двух браузерных отпечатков и определить вероятность того, что они принадлежат одному и тому же пользователю. При анализе ты должен строго следовать иерархии доказательств и учитывать особенности каждого параметра.\n\n" +
                "**Иерархия доказательств (от самого важного к менее важному):**\n\n" +
                "1.  **`secureUUID` — это самый главный и стабильный идентификатор.** Его полное совпадение является самым весомым аргументом в пользу того, что это один и тот же пользователь. Рассматривай его как \"золотой стандарт\".\n\n" +
                "2.  **`visitorId` и `components`:** Помни, что `visitorId` является хешем (результатом) от множества `components`. Это значит, что если `components` незначительно меняются (например, обновился браузер, установился новый шрифт), то `visitorId` **обязан измениться**. Такое изменение является **ожидаемым поведением** для одного и того же пользователя, а не признаком другого. Не снижай вероятность, если `visitorId` изменился, а `components` изменились лишь незначительно.\n\n" +
                "3.  **Сетевые данные (`ASN`, `Org`, `Country`, `City`):** Эти данные важнее, чем `IP` и `PTR`. IP-адрес у большинства пользователей динамический и меняется постоянно. Совпадение интернет-провайдера (`ASN`, `Org`) и геолокации (`Country`, `City`) является сильным подтверждающим сигналом, даже если конкретный `IP` и `PTR` изменились.\n\n" +
                "**Формат твоего ответа:**\n" +
                "Твой ответ **обязательно** должен быть в формате JSON со следующей структурой:\n" +
                "{\n" +
                "  \"reasoning\": \"Твое пошаговое логическое рассуждение здесь...\",\n" +
                "  \"probability\": <число от 0 до 100>\n" +
                "}";

        StringBuilder userTextBuilder = new StringBuilder();
        userTextBuilder.append("Ты анализируешь два браузерных отпечатка и определяешь, насколько они похожи и может ли это быть один и тот же пользователь. Дай логическое рассуждение и итоговую вероятность от 0 до 100%.");
        userTextBuilder.append("\n\n");
        userTextBuilder.append("Первое посещение пользователя:\n");
        userTextBuilder.append(gson.toJson(one));
        userTextBuilder.append("\n\n");
        userTextBuilder.append("Второе посещение пользователя:\n");
        userTextBuilder.append(gson.toJson(two));

        JsonArray contents = new JsonArray();
        contents.add(userTurn(userTextBuilder.toString()));
        return new GeminiPrompt(systemInstruction, contents);
    }

    private void requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Gemini API key is not set (empty or missing) in environment variable: GEMINI_API_KEY");
        }
    }
}
