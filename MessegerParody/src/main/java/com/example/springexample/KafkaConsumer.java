package com.example.springexample;

import com.example.springexample.Services.ImageUrlPersistenceService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class KafkaConsumer {

    @Autowired
    private ImageUrlPersistenceService imageUrlPersistenceService;

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
}
