package com.example.springexample;

import com.example.springexample.Services.ImageUrlPersistenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

/**
 * Unit-тесты парсинга контракта топика "Images" в KafkaConsumer (beads se2):
 * { "targetType": "userimage"|"chatimage", "targetId": "<id>", "objectKey": "<key>" }.
 * ImageUrlPersistenceService замокан, проверяем передачу распарсенных полей.
 */
@ExtendWith(MockitoExtension.class)
class KafkaConsumerTest {

    @Mock
    private ImageUrlPersistenceService imageUrlPersistenceService;

    @InjectMocks
    private KafkaConsumer kafkaConsumer;

    @Test
    void parsesUserimageContractAndDelegatesToPersistenceService() {
        String event = "{\"targetType\":\"userimage\",\"targetId\":\"42\",\"objectKey\":\"userimage/42/uuid.png\"}";

        kafkaConsumer.listenOauthImage(event);

        verify(imageUrlPersistenceService).persistImageUrl("userimage", "42", "userimage/42/uuid.png");
    }

    @Test
    void parsesChatimageContractAndDelegatesToPersistenceService() {
        String event = "{\"targetType\":\"chatimage\",\"targetId\":\"7\",\"objectKey\":\"chatimage/7/uuid.jpg\"}";

        kafkaConsumer.listenOauthImage(event);

        verify(imageUrlPersistenceService).persistImageUrl("chatimage", "7", "chatimage/7/uuid.jpg");
    }

    @Test
    void ignoresExtraFieldsNotInContract() {
        String event = "{\"targetType\":\"userimage\",\"targetId\":\"1\",\"objectKey\":\"userimage/1/x.png\",\"extra\":\"ignored\"}";

        kafkaConsumer.listenOauthImage(event);

        verify(imageUrlPersistenceService).persistImageUrl("userimage", "1", "userimage/1/x.png");
    }
}
