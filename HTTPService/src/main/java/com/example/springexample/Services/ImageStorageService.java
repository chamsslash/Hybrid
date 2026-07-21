package com.example.springexample.Services;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;

/**
 * Обёртка над MinIO для картинок (beads 6s0).
 * Все блокирующие вызовы SDK выполняются на Schedulers.boundedElastic(),
 * чтобы не блокировать реактивные потоки.
 */
@Slf4j
@Service
public class ImageStorageService {

    private final MinioClient minioClient;
    private final String bucket;

    public ImageStorageService(MinioClient minioClient,
                               @Value("${MINIO_BUCKET:images}") String bucket) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    public String getBucket() {
        return bucket;
    }

    /** Создаёт бакет, если его ещё нет. Идемпотентно. */
    public Mono<Void> ensureBucket() {
        return Mono.<Void>fromRunnable(() -> {
            try {
                boolean exists = minioClient.bucketExists(
                        BucketExistsArgs.builder().bucket(bucket).build());
                if (!exists) {
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    log.info("Created MinIO bucket {}", bucket);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to ensure MinIO bucket " + bucket, e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Кладёт объект в бакет по ключу. Перед записью гарантирует наличие бакета. */
    public Mono<Void> putObject(String key, byte[] data, String contentType) {
        return ensureBucket().then(Mono.<Void>fromRunnable(() -> {
            try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(key)
                        .stream(in, data.length, -1)
                        .contentType(contentType == null ? "application/octet-stream" : contentType)
                        .build());
            } catch (Exception e) {
                throw new RuntimeException("Failed to put object " + key, e);
            }
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    /** Читает объект целиком (картинки небольшие, лимит multipart = 5MB). */
    public Mono<StoredObject> getObject(String key) {
        return Mono.fromCallable(() -> {
            try (GetObjectResponse resp = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build())) {
                byte[] bytes = resp.readAllBytes();
                String ct = resp.headers().get("Content-Type");
                return new StoredObject(bytes, ct);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Прочитанный из MinIO объект: байты + Content-Type. */
    public record StoredObject(byte[] data, String contentType) {}
}
