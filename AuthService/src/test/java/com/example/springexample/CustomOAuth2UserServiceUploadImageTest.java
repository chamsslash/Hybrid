package com.example.springexample;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.springexample.Repositories.Auth_rep;
import com.example.springexample.Services.ImageStorageService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit-тест новой версии Upload_image (beads lyo): картинка кладётся в MinIO,
 * а в Kafka уходит контракт из трёх lowercase-полей
 * { targetType, targetId, objectKey } — без Base64.
 */
class CustomOAuth2UserServiceUploadImageTest {

    private ImageStorageService imageStorageService;
    private KafkaProducer kafkaProducer;
    private CustomOAuth2UserService service;

    @BeforeEach
    void setUp() {
        Auth_rep authRep = mock(Auth_rep.class);
        imageStorageService = mock(ImageStorageService.class);
        kafkaProducer = mock(KafkaProducer.class);

        service = new CustomOAuth2UserService(authRep, imageStorageService);
        // kafkaProducer — @Autowired package-private поле, тест в том же пакете
        service.kafkaProducer = kafkaProducer;
    }

    @Test
    void uploadsDecodedBytesAndPublishesLowercaseContract() throws Exception {
        byte[] original = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
        String b64 = Base64.getEncoder().encodeToString(original);
        String userId = "42";

        service.Upload_image(b64, userId);

        // 1) putObject вызван с правильным ключом, декодированными байтами и content-type
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> ctCaptor = ArgumentCaptor.forClass(String.class);
        verify(imageStorageService)
                .putObject(keyCaptor.capture(), bytesCaptor.capture(), ctCaptor.capture());

        String key = keyCaptor.getValue();
        assertTrue(key.startsWith("userimage/42/"),
                "ключ должен начинаться с userimage/<userId>/, а был: " + key);
        assertTrue(key.endsWith(".jpg"), "ключ должен оканчиваться на .jpg, а был: " + key);
        assertArrayEquals(original, bytesCaptor.getValue(),
                "в MinIO должны уйти именно декодированные байты");
        assertEquals("image/jpeg", ctCaptor.getValue());

        // 2) в Kafka уходит контракт из ровно трёх lowercase-полей
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaProducer).send(msgCaptor.capture(), topicCaptor.capture());

        assertEquals("Images", topicCaptor.getValue());

        JsonObject payload = JsonParser.parseString(msgCaptor.getValue()).getAsJsonObject();
        assertEquals(3, payload.size(), "в payload должно быть ровно три поля");
        assertEquals("userimage", payload.get("targetType").getAsString());
        assertEquals(userId, payload.get("targetId").getAsString());
        assertEquals(key, payload.get("objectKey").getAsString(),
                "objectKey в Kafka должен совпадать с ключом, отданным в putObject");

        // старого формата (Base64) быть не должно
        assertTrue(!payload.has("Base64Image"), "Base64 не должен попадать в Kafka");
    }
}
