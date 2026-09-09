package com.example.springexample.Services;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers-интеграция ImageStorageService AuthService (beads ok9): аватарка
 * Google-юзера кладётся в реальный MinIO-контейнер и читается байт-в-байт обратно,
 * без моков SDK. Проверяет и идемпотентное создание бакета (ensureBucket).
 *
 * ТРЕБУЕТ Docker. Помечен @Tag("integration") — исключён из обычного `mvn test`
 * (surefire excludedGroups=integration), запускать явно:
 * `mvn test -Dgroups=integration` (нужен Docker).
 */
@Tag("integration")
@Testcontainers
class ImageStorageServiceIT {

    private static final String BUCKET = "it-images";

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2024-06-13T22-53-53Z")
            .withUserName("testadmin")
            .withPassword("testadmin123");

    private MinioClient rawClient() {
        return MinioClient.builder()
                .endpoint(MINIO.getS3URL())
                .credentials(MINIO.getUserName(), MINIO.getPassword())
                .build();
    }

    private ImageStorageService newService(MinioClient client) {
        return new ImageStorageService(client, BUCKET);
    }

    @Test
    void putObjectCreatesBucketAndStoresBytesExactly() throws Exception {
        MinioClient client = rawClient();
        ImageStorageService service = newService(client);

        byte[] original = new byte[64 * 1024];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i % 251);
        }
        String key = "userimage/1/roundtrip.jpg";

        service.putObject(key, original, "image/jpeg");

        // бакет создан ensureBucket-ом
        assertThat(client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())).isTrue();

        // байты сохранены точь-в-точь
        try (GetObjectResponse stored = client.getObject(
                GetObjectArgs.builder().bucket(BUCKET).object(key).build())) {
            byte[] readBack = stored.readAllBytes();
            assertThat(readBack).isEqualTo(original);
            assertThat(sha256Hex(readBack)).isEqualTo(sha256Hex(original));
            assertThat(stored.headers().get("Content-Type")).startsWith("image/jpeg");
        }
    }

    @Test
    void putObjectIsIdempotentAcrossCallsOnSameBucket() throws Exception {
        MinioClient client = rawClient();
        ImageStorageService service = newService(client);

        byte[] data = "second-avatar".getBytes(StandardCharsets.UTF_8);
        String key = "userimage/2/second.jpg";

        // повторный putObject на уже существующий бакет не должен падать
        service.putObject(key, data, "image/jpeg");

        try (GetObjectResponse stored = client.getObject(
                GetObjectArgs.builder().bucket(BUCKET).object(key).build())) {
            assertThat(new String(stored.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("second-avatar");
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
