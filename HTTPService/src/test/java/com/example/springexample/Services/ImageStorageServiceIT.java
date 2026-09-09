package com.example.springexample.Services;

import io.minio.MinioClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers-интеграция ImageStorageService (beads se2, design n5y "Стратегия
 * тестирования" -> Integration): putObject -> getObject byte-for-byte через реальный
 * MinIO-контейнер, без моков SDK.
 *
 * ТРЕБУЕТ Docker. Помечен @Tag("integration") — по умолчанию не входит в общий unit-run,
 * запускать явно: `mvn test -Dtest=ImageStorageServiceIT` или `mvn verify -Dgroups=integration`
 * (если/когда в pom будет настроен failsafe/surefire-groups фильтр).
 */
@Tag("integration")
@Testcontainers
class ImageStorageServiceIT {

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2024-06-13T22-53-53Z")
            .withUserName("testadmin")
            .withPassword("testadmin123");

    private ImageStorageService newService() {
        MinioClient client = MinioClient.builder()
                .endpoint(MINIO.getS3URL())
                .credentials(MINIO.getUserName(), MINIO.getPassword())
                .build();
        return new ImageStorageService(client, "it-images");
    }

    @Test
    void putThenGetRoundTripsBytesExactly() {
        ImageStorageService service = newService();
        byte[] original = new byte[64 * 1024];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i % 251);
        }
        String key = "userimage/1/roundtrip.bin";

        StepVerifier.create(service.putObject(key, original, "application/octet-stream"))
                .verifyComplete();

        StepVerifier.create(service.getObject(key))
                .assertNext(stored -> {
                    assertThat(stored.data()).isEqualTo(original);
                    assertThat(sha256Hex(stored.data())).isEqualTo(sha256Hex(original));
                })
                .verifyComplete();
    }

    @Test
    void putThenGetPreservesContentType() {
        ImageStorageService service = newService();
        byte[] data = "small-text-payload".getBytes(StandardCharsets.UTF_8);
        String key = "chatimage/9/note.txt";

        StepVerifier.create(service.putObject(key, data, "text/plain")).verifyComplete();

        StepVerifier.create(service.getObject(key))
                .assertNext(stored -> {
                    assertThat(new String(stored.data(), StandardCharsets.UTF_8)).isEqualTo("small-text-payload");
                    assertThat(stored.contentType()).startsWith("text/plain");
                })
                .verifyComplete();
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
