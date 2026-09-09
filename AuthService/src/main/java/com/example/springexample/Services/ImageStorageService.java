package com.example.springexample.Services;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Обёртка над MinIO для аватарок Google-юзеров (beads lyo).
 * Минимальная копия нужного подмножества из HTTPService.ImageStorageService.
 * AuthService — блокирующий (Spring MVC), поэтому вызовы MinIO синхронные,
 * без реактивной обёртки (в отличие от WebFlux-версии в HTTPService).
 */
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
    public void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to ensure MinIO bucket " + bucket, e);
        }
    }

    /** Кладёт объект в бакет по ключу. Перед записью гарантирует наличие бакета. */
    public void putObject(String key, byte[] data, String contentType) {
        ensureBucket();
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
    }
}
