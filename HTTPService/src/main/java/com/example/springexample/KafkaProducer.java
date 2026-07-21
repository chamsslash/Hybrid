package com.example.springexample;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaProducer {
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    public void send(String message) {
        this.kafkaTemplate.send("Messages",message);

    }

    /**
     * Публикует событие картинки в топик "Images" (beads 6s0).
     * Тело — контракт { targetType, targetId, objectKey } без Base64.
     */
    public void sendImage(String message) {
        this.kafkaTemplate.send("Images", message);
    }

}
