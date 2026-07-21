package com.example.springexample;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Бин MinioClient для server-side загрузки/отдачи картинок (beads 6s0).
 * Endpoint и креды берутся из окружения (Secret из feat/minio-helm):
 *   MINIO_ENDPOINT, MINIO_ACCESS_KEY, MINIO_SECRET_KEY.
 * Имя бакета — MINIO_BUCKET (по умолчанию "images"), читается в ImageStorageService.
 */
@Configuration
public class MinioConfig {

    @Value("${MINIO_ENDPOINT:http://minio:9000}")
    private String endpoint;

    @Value("${MINIO_ACCESS_KEY:minioadmin}")
    private String accessKey;

    @Value("${MINIO_SECRET_KEY:minioadmin}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
