package com.example.springexample;

import com.example.grpc.DataTransferService.DriveUrl;
import com.example.springexample.Services.MTS_impl;
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
    private MTS_impl mts_impl;

    private final Gson gson = new Gson();

    @RetryableTopic(attempts = "3")
    @KafkaListener(topics = "Images")
    public void listenOauthImage(String event) {
        JsonObject json = gson.fromJson(event, JsonObject.class);

        if (!"image".equals(json.get("type").getAsString())) return;
        if (!"userimage".equals(json.get("TargetType").getAsString())) return;

        DriveUrl dUrl = DriveUrl.newBuilder()
            .setUrl(json.get("Base64Image").getAsString())
            .setChatId(json.get("Target").getAsString())
            .setType("userimage")
            .build();

        mts_impl.transferimageUrltoDB(dUrl);
    }
}
