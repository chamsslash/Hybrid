package com.example.springexample;

import com.example.springexample.R2DBC_Repositories.ReactiveRepository;
import com.example.springexample.Services.ImageUrlPersistenceService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
public class KafkaConsumer {

    @Autowired
    private ImageUrlPersistenceService imageUrlPersistenceService;

    @Autowired
    private ReactiveRepository reactiveRepository;

    private final Gson gson = new Gson();

    @RetryableTopic(attempts = "3")
    @KafkaListener(topics = "Images")
    public void listenOauthImage(String event) {
        JsonObject json = gson.fromJson(event, JsonObject.class);

        // Contract (topic "Images"):
        // { "targetType": "userimage"|"chatimage", "targetId": "<id>", "objectKey": "<key>" }
        String targetType = json.get("targetType").getAsString();
        String targetId = json.get("targetId").getAsString();
        String objectKey = json.get("objectKey").getAsString();

        // Persist the short MinIO object key for BOTH image types.
        imageUrlPersistenceService.persistImageUrl(targetType, targetId, objectKey);
    }

    // attempts="7" = 1 исходная попытка + 6 ретраев (initial 1s, x2, max 10s) — после исчерпания
    // запись уходит в топик "Messages-dlt" (дефолтный суффикс), не теряется молча (beads 5l4).
    // exclude: ошибки парсинга мусорного payload (Long.parseLong/Instant.parse/JSON) детерминированы
    // и никогда не станут успешными на повторной попытке — гонять их через все 7 ретраев (~35 c)
    // бессмысленно, такие сообщения должны уходить в DLT сразу.
    @RetryableTopic(attempts = "7", backoff = @Backoff(delay = 1000, multiplier = 2, maxDelay = 10000),
            exclude = {NumberFormatException.class, java.time.format.DateTimeParseException.class,
                    NullPointerException.class, com.google.gson.JsonSyntaxException.class})
    @KafkaListener(topics = "Messages")
    public void listenChatMessages(String message) {
        ChatMessageDTO dto = gson.fromJson(message, ChatMessageDTO.class);
        // Таймаут обязателен: дефолтный r2dbc-пул на acquire — без таймаута. Если пул
        // исчерпан или Postgres завис, .block() без таймаута никогда не вернётся,
        // единственный поток контейнера перестанет вызывать poll(), и через
        // max.poll.interval.ms консьюмер выпадет из группы (бесконечный ребаланс,
        // топик "Messages" перестанет потребляться, при этом под останется Ready).
        reactiveRepository.insertMessage(
                Long.parseLong(dto.getChat_id()),
                Long.parseLong(dto.getUser_id()),
                dto.getText(),
                Instant.parse(dto.getTimestamp())
        ).block(java.time.Duration.ofSeconds(15));
    }
}
