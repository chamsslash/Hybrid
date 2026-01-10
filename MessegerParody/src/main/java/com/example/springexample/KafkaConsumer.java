package com.example.springexample;

import com.example.grpc.DataTransferService.DriveUrl;
import com.example.springexample.Services.GoogleDriveService;
import com.example.springexample.Services.MTS_impl;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class KafkaConsumer {
    @Autowired
    MTS_impl mts_impl;
    @Autowired
    GoogleDriveService driveService;
    @Autowired
    KafkaProducer kafkaProducer;
    private final Gson gson = new Gson();
    @RetryableTopic(attempts = "3",backoff = @Backoff(delay = 2000,multiplier = 2),dltTopicSuffix = ".DLT")
    @KafkaListener(topics = "Images")
    public void listenOauthImage(String event) {
        JsonObject json = gson.fromJson(event, JsonObject.class);

        if (!"image".equals(json.get("type").getAsString())) return;
        String targetType = json.get("TargetType").getAsString();
        if (!"userimage".equals(targetType) && !"chatimage".equals(targetType)) return;
        String url;
        try {
            url = driveService.SaveImage(json.get("Base64Image").getAsString());
        } catch (Exception e) {
            log.error("An error in googleDrive saving process", e);
            throw new RuntimeException(e);
        }
        DriveUrl dUrl = DriveUrl.newBuilder()
                .setUrl(url)
                .setChatId(json.get("Target").getAsString())
                .setType(targetType)
                .build();

        DriveUrl res = mts_impl.transferimageUrltoDB(dUrl);
        if (res.getUrl() == null || res.getUrl().isEmpty()) {
            log.warn("Empty image url after SaveImage, skip publish to Kafka");
            return;
        }
        ImageUploadDTO imageUploadDTO = new ImageUploadDTO(
                res.getType(),
                res.getChatId(),
                res.getUrl()
        );
        kafkaProducer.send(gson.toJson(imageUploadDTO));

    }
    @KafkaListener(topics = "Images.DLT")
    public void listenImagesDLT(String event,@Header(KafkaHeaders.EXCEPTION_MESSAGE) String errorMessage,
                                @Header(KafkaHeaders.EXCEPTION_STACKTRACE) String stacktrace,
                                @Header(KafkaHeaders.RECEIVED_TOPIC) String originalTopic,
                                @Header(KafkaHeaders.OFFSET) long offset,@Header(KafkaHeaders.PARTITION) int partititon
    ) {
        log.error("""
        DLT MESSAGE
        topic: {}
        partition: {}
        offset: {}
        error: {}
        payload: {}
        """,
                originalTopic,partititon , offset, errorMessage, event
        );

    }

}
