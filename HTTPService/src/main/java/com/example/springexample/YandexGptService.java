package com.example.springexample;

import com.example.springexample.Metrics.AiRequestMetric;
import com.example.springexample.Utils.FpSimilarityScore;
import com.google.gson.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jcajce.provider.asymmetric.RSA;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.checkerframework.checker.units.qual.A;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.*;

import org.springframework.http.*;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@Slf4j
public class YandexGptService {
    @Autowired
    AiRequestMetric aiRequestMetric;
    private volatile String  IamToken = null;
    private Gson gson = new Gson();
    private WebClient wC = WebClient.builder().build();
    private final RestTemplate   restTemplate = new RestTemplate();
    public Mono<String> GetAssistantAnswer(JsonArray JsonArrayPrompt)  {
        String url = "https://llm.api.cloud.yandex.net/foundationModels/v1/completion";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + this.IamToken);
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("modelUri", "gpt://b1gbhg7ve7cr68cuodj5/yandexgpt-lite/rc@tamruci1ntqpfudkgabjo");
        JsonObject completionOptions = new JsonObject();
        completionOptions.addProperty("temperature", 0.5);
        requestBody.add("completionOptions", completionOptions);

        requestBody.add("messages", JsonArrayPrompt);


            aiRequestMetric.increment();
            return  wC.post().uri(url).body(BodyInserters.fromValue(requestBody)).headers(heads->heads.addAll(headers)).retrieve().bodyToMono(String.class)
                    .<String>handle((body, sink) -> {
                        if (!body.isEmpty()){
                            log.info(body);
                            JsonObject responseObj = gson.fromJson(body, JsonObject.class);
                            JsonObject resultObj = responseObj.getAsJsonObject("result");
                            JsonArray altsObj = resultObj.getAsJsonArray("alternatives");
                            if (!altsObj.isEmpty()) {
                                JsonObject firstalt = altsObj.get(0).getAsJsonObject();
                                JsonObject message = firstalt.getAsJsonObject("message");
                                String aianswer = message.get("text").getAsString();
                                sink.next(aianswer);
                                return;
                            }
                        }
                        sink.error(new RuntimeException("Ai response has uncorrect struckture"));

                    }).doOnError(e->log.error(e.getMessage()));
    }
    public Mono<String> aiSecurePredict(JsonArray Prompt){

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + this.IamToken);
        Map<String, Object> requestBody = new HashMap<>();
        String modelUri = System.getenv("YANDEX_GPT_MODEL_URI");
        if (modelUri == null) {
            modelUri = "gpt://b1gbhg7ve7cr68cuodj5/yandexgpt-lite/rc@tamruci1ntqpfudkgabjo";
        }
        requestBody.put("modelUri", modelUri);

        Map<String, Object> completionOptions = new HashMap<>();
        completionOptions.put("temperature", 0.5);
        requestBody.put("completionOptions", completionOptions);
        List<Map<String, String>> messages = new ArrayList<>();
        for (JsonElement element : Prompt) {
            JsonObject obj = element.getAsJsonObject();
            Map<String, String> msg = new HashMap<>();
            msg.put("role", obj.get("role").getAsString());
            msg.put("text", obj.get("text").getAsString());
            messages.add(msg);
        }
        requestBody.put("messages", messages);

        log.warn(Prompt.toString());
        aiRequestMetric.increment();
        return wC.post()
                .uri( "https://llm.api.cloud.yandex.net/foundationModels/v1/completion")
                .bodyValue(requestBody)
                .headers(heads -> heads.addAll(headers))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response -> {
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("Unknown 4xx error")
                            .flatMap(errorBody -> {
                                log.error("Client error (4xx): {}", errorBody);
                                return Mono.error(new RuntimeException("Client error: " + errorBody));
                            });
                })
                .onStatus(HttpStatusCode::is5xxServerError, response -> {
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("Unknown 5xx error")
                            .flatMap(errorBody -> {
                                log.error("Server error (5xx): {}", errorBody);
                                return Mono.error(new RuntimeException("Server error: " + errorBody));
                            });
                })
                .bodyToMono(String.class)
                // Заменяем сложный .handle() на простой .map()
                .map(body -> { if (body == null || body.isEmpty()) {
                    // Если тело пустое, выбрасываем исключение.
                    throw new RuntimeException("AI response body is empty");

                }
                    JsonObject responseObj = gson.fromJson(body, JsonObject.class);
                JsonObject resultObj = responseObj.getAsJsonObject("result");
                    JsonArray altsObj = resultObj.getAsJsonArray("alternatives");
                    if (!altsObj.isEmpty()) {
                        JsonObject firstalt = altsObj.get(0).getAsJsonObject();
                        JsonObject message = firstalt.getAsJsonObject("message");
                        String aianswer = message.get("text").getAsString();
                        String jsonText = aianswer.replaceAll("(?s)^```\\s*|\\s*```$", "").trim();
                        JsonObject parsed = JsonParser.parseString(jsonText).getAsJsonObject();
                        log.info("Security Predict Explanation:"+parsed.get("reasoning"));
                        return parsed.get("probability").getAsString();

    }
                    throw new RuntimeException("AI response body is uncorrect");
                });
    }
   public JsonArray BuildJsonPrompt(String SystemTask,Map<String,List<String>> Username_Mesage){
        if(Username_Mesage.keySet().isEmpty()){
            throw new RuntimeException("Empty prompt");
        }
        JsonArray prompt = new JsonArray();
        JsonObject part1 = new JsonObject();
        part1.addProperty("role","system");
        part1.addProperty("text",SystemTask);
        prompt.add(part1);
        for(Map.Entry<String,List<String>> entry : Username_Mesage.entrySet()){

            JsonObject part_n = new JsonObject();
            part_n.addProperty("role","user");
            for (String mess : entry.getValue()) {
                StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append(entry.getKey());
                stringBuilder.append(": ");
                stringBuilder.append(mess);
                part_n.addProperty("text",stringBuilder.toString());
                prompt.add(part_n);
            }


        }
        if (prompt.size()>1 ){return prompt;}else throw new RuntimeException("Empty prompt");


    }


    public JsonArray BuildSecurityCheckPrompt(FpSimilarityScore.ClientMeta one, FpSimilarityScore.ClientMeta two) {
        // Этот экземпляр Gson должен быть доступен в классе
        Gson gson = new Gson();

        JsonArray messagesArray = new JsonArray();

        // 1. Создаем системное сообщение (остается без изменений, оно у вас правильное)
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        String systemInstruction ="Твоя задача — провести экспертный анализ двух браузерных отпечатков и определить вероятность того, что они принадлежат одному и тому же пользователю. При анализе ты должен строго следовать иерархии доказательств и учитывать особенности каждого параметра.\n\n" +
                "**Иерархия доказательств (от самого важного к менее важному):**\n\n" +
                "1.  **`secureUUID` — это самый главный и стабильный идентификатор.** Его полное совпадение является самым весомым аргументом в пользу того, что это один и тот же пользователь. Рассматривай его как \"золотой стандарт\".\n\n" +
                "2.  **`visitorId` и `components`:** Помни, что `visitorId` является хешем (результатом) от множества `components`. Это значит, что если `components` незначительно меняются (например, обновился браузер, установился новый шрифт), то `visitorId` **обязан измениться**. Такое изменение является **ожидаемым поведением** для одного и того же пользователя, а не признаком другого. Не снижай вероятность, если `visitorId` изменился, а `components` изменились лишь незначительно.\n\n" +
                "3.  **Сетевые данные (`ASN`, `Org`, `Country`, `City`):** Эти данные важнее, чем `IP` и `PTR`. IP-адрес у большинства пользователей динамический и меняется постоянно. Совпадение интернет-провайдера (`ASN`, `Org`) и геолокации (`Country`, `City`) является сильным подтверждающим сигналом, даже если конкретный `IP` и `PTR` изменились.\n\n" +
                "**Формат твоего ответа:**\n" +
                "Твой ответ **обязательно** должен быть в формате JSON со следующей структурой:\n" +
                "{\n" +
                "  \"reasoning\": \"Твое пошаговое логическое рассуждение здесь...\",\n" +
                "  \"probability\": <число от 0 до 100>\n" +
                "}";        systemMessage.addProperty("text", systemInstruction);


        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");

        StringBuilder userTextBuilder = new StringBuilder();

        // Добавляем краткую инструкцию
        userTextBuilder.append("Ты анализируешь два браузерных отпечатка и определяешь, насколько они похожи и может ли это быть один и тот же пользователь. Дай логическое рассуждение и итоговую вероятность от 0 до 100%.");
        userTextBuilder.append("\n\n"); // Добавляем отступы для читаемости промпта

        // Добавляем первый отпечаток
        userTextBuilder.append("Первое посещение пользователя:\n");
        userTextBuilder.append(gson.toJson(one));
        userTextBuilder.append("\n\n");


        userTextBuilder.append("Второе посещение пользователя:\n");
        userTextBuilder.append(gson.toJson(two));

        userMessage.addProperty("text", userTextBuilder.toString());

        // 3. Добавляем оба сообщения в итоговый массив
        messagesArray.add(systemMessage);
        messagesArray.add(userMessage);

        return messagesArray;
    }
    @Scheduled(fixedDelay = 43200000)
    public  void GetIAMToken() throws Exception {
        String url = "https://iam.api.cloud.yandex.net/iam/v1/tokens";
        JsonObject req = new JsonObject();
        String jwt = this.JWTPrepare();
            req.addProperty("jwt",jwt);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<?> entity = new HttpEntity<>(req.toString(), headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonObject responseJson = JsonParser.parseString(response.getBody()).getAsJsonObject();
                this.IamToken = responseJson.get("iamToken").getAsString();
                log.info("IAM token updated: {}", this.IamToken);
        }  else throw new Exception("Ошибка при получении IAM токена: " + response.getStatusCode());



    }

    public String JWTPrepare(){
        try {
            Map<String,String> fullData = this.ParsePublic_PrivateKey();
            String key_id = fullData.get("key_id");
            String private_key = fullData.get("private_key");
            String public_key = fullData.get("public_key");
            String service_acc_id = fullData.get("service_account_id");
            byte[] Dec_private_key = Base64.getDecoder().decode(private_key);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PKCS8EncodedKeySpec keySpec_private = new PKCS8EncodedKeySpec(Dec_private_key);
            RSAPrivateKey RSAprivateKey = (RSAPrivateKey) kf.generatePrivate(keySpec_private);
            Map<String, Object> header = new HashMap<>();
            header.put("kid", key_id);
            header.put("alg", "RS256");
            header.put("typ", "JWT");
           String jwt = Jwts.builder().setHeaderParam("kid",key_id)
                   .setIssuer(service_acc_id)
                   .setAudience("https://iam.api.cloud.yandex.net/iam/v1/tokens")
                   .setIssuedAt(Date.from(Instant.now()))
                   .setExpiration(Date.from(Instant.now().plusSeconds(3600))).signWith(RSAprivateKey, SignatureAlgorithm.PS256).compact();
            return jwt;
        }catch (Exception e){
            log.error(e.getMessage());
            e.printStackTrace();
            return null;
        }



    }
    public Map<String,String> ParsePublic_PrivateKey() throws IOException {
        String public_key = normalizePemFromEnv(System.getenv("YANDEX_PUBLIC_KEY_PEM"), "public");
        String private_key = normalizePemFromEnv(System.getenv("YANDEX_PRIVATE_KEY_PEM"), "private");
        String service_account_id = normalizeRequiredEnv(System.getenv("YANDEX_SERVICE_ACCOUNT_ID"), "service_account_id");
        String key_id = normalizeRequiredEnv(System.getenv("YANDEX_KEY_ID"), "key_id");

        PemObject privateKeyPem = readPemObject(private_key, "private");
        PemObject publicKeyPem = readPemObject(public_key, "public");
        return new HashMap<>() {{
            put("public_key", Base64.getEncoder().encodeToString(publicKeyPem.getContent()));
            put("private_key", Base64.getEncoder().encodeToString(privateKeyPem.getContent()));
            put("service_account_id", service_account_id);
            put("key_id", key_id);
        }};
    }
    private String normalizePemFromEnv(String raw, String label) {
        if (raw == null) {
            throw new IllegalStateException("Yandex GPT " + label + " key is not set in environment variables");
        }
        String cleaned = raw.trim();
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\"")) ||
                (cleaned.startsWith("'") && cleaned.endsWith("'")) ||
                (cleaned.startsWith("`") && cleaned.endsWith("`"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        // Handle \n escaped PEM from .env
        cleaned = cleaned.replace("\\n", "\n").trim();
        if (cleaned.isEmpty()) {
            throw new IllegalStateException("Yandex GPT " + label + " key is empty after normalization");
        }
        return cleaned;
    }

    private String normalizeRequiredEnv(String raw, String label) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalStateException("Yandex GPT " + label + " is not set in environment variables");
        }
        return raw.trim();
    }

    private PemObject readPemObject(String pem, String label) throws IOException {
        try (PemReader reader = new PemReader(new StringReader(pem))) {
            PemObject pemObject = reader.readPemObject();
            if (pemObject == null) {
                throw new IllegalStateException("Unable to parse " + label + " PEM: data is empty or malformed");
            }
            return pemObject;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to parse " + label + " PEM: check escaping and quotes", e);
        }
    }
//    public String Clean(String pem) {
//
//        String[] lines = pem.split("\\r?\\n");
//        StringBuilder sb = new StringBuilder();
//        for (String line : lines) {
//            line = line.trim();
//            // Пропускаем пустые строки и заголовки/футеры PEM
//            if (line.isEmpty() ||
//                    line.startsWith("-----BEGIN") ||
//                    line.startsWith("-----END") ||
//                    line.startsWith("PLEASE DO NOT REMOVE")) {
//                continue;
//            }
//            sb.append(line);
//        }
//        return sb.toString();
//    }
    }
