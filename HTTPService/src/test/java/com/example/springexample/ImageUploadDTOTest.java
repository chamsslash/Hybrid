package com.example.springexample;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip (де)сериализации контракта топика "Images" (beads se2, design n5y):
 * {"targetType":"userimage"|"chatimage","targetId":"<id>","objectKey":"<key>"}.
 */
class ImageUploadDTOTest {

    private final Gson gson = new Gson();

    @Test
    void roundTripsUserImageContract() {
        ImageUploadDTO original = new ImageUploadDTO("userimage", "42", "userimage/42/uuid.png");

        String json = gson.toJson(original);
        ImageUploadDTO parsed = gson.fromJson(json, ImageUploadDTO.class);

        assertThat(parsed).isEqualTo(original);
        assertThat(json).contains("\"targetType\":\"userimage\"");
        assertThat(json).contains("\"targetId\":\"42\"");
        assertThat(json).contains("\"objectKey\":\"userimage/42/uuid.png\"");
    }

    @Test
    void roundTripsChatImageContract() {
        ImageUploadDTO original = new ImageUploadDTO("chatimage", "7", "chatimage/7/uuid.jpg");

        String json = gson.toJson(original);
        ImageUploadDTO parsed = gson.fromJson(json, ImageUploadDTO.class);

        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void deserializesLiteralContractJson() {
        String contractJson = "{\"targetType\":\"userimage\",\"targetId\":\"1\",\"objectKey\":\"userimage/1/x.png\"}";

        ImageUploadDTO parsed = gson.fromJson(contractJson, ImageUploadDTO.class);

        assertThat(parsed.getTargetType()).isEqualTo("userimage");
        assertThat(parsed.getTargetId()).isEqualTo("1");
        assertThat(parsed.getObjectKey()).isEqualTo("userimage/1/x.png");
    }
}
