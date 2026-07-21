package com.example.springexample.Services;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты ImageStorageService (beads se2) с замоканным MinioClient:
 * проверяем, что put/get дёргают правильные *Args (bucket/key/contentType),
 * а ensureBucket идемпотентно создаёт бакет только когда его ещё нет.
 */
@ExtendWith(MockitoExtension.class)
class ImageStorageServiceTest {

    private static final String BUCKET = "images";

    @Mock
    private MinioClient minioClient;

    private ImageStorageService service;

    private void initService() {
        service = new ImageStorageService(minioClient, BUCKET);
    }

    @Test
    void ensureBucketCreatesBucketWhenMissing() throws Exception {
        initService();
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        StepVerifier.create(service.ensureBucket()).verifyComplete();

        ArgumentCaptor<BucketExistsArgs> existsCaptor = ArgumentCaptor.forClass(BucketExistsArgs.class);
        verify(minioClient).bucketExists(existsCaptor.capture());
        assertThat(existsCaptor.getValue().bucket()).isEqualTo(BUCKET);

        ArgumentCaptor<MakeBucketArgs> makeCaptor = ArgumentCaptor.forClass(MakeBucketArgs.class);
        verify(minioClient).makeBucket(makeCaptor.capture());
        assertThat(makeCaptor.getValue().bucket()).isEqualTo(BUCKET);
    }

    @Test
    void ensureBucketSkipsCreationWhenBucketExists() throws Exception {
        initService();
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        StepVerifier.create(service.ensureBucket()).verifyComplete();

        verify(minioClient, never()).makeBucket(any(MakeBucketArgs.class));
    }

    @Test
    void putObjectSendsCorrectBucketKeyAndContentType() throws Exception {
        initService();
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        byte[] data = "hello-image-bytes".getBytes(StandardCharsets.UTF_8);
        String key = "userimage/42/some-uuid.png";

        StepVerifier.create(service.putObject(key, data, "image/png")).verifyComplete();

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient, times(1)).putObject(captor.capture());
        PutObjectArgs args = captor.getValue();
        assertThat(args.bucket()).isEqualTo(BUCKET);
        assertThat(args.object()).isEqualTo(key);
        assertThat(args.contentType()).isEqualTo("image/png");
    }

    @Test
    void putObjectDefaultsContentTypeWhenNull() throws Exception {
        initService();
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        StepVerifier.create(service.putObject("chatimage/7/x.jpg", new byte[]{1, 2, 3}, null))
                .verifyComplete();

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(captor.capture());
        assertThat(captor.getValue().contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void putObjectEnsuresBucketBeforeWriting() throws Exception {
        initService();
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        StepVerifier.create(service.putObject("userimage/1/a.jpg", new byte[]{9}, "image/jpeg"))
                .verifyComplete();

        verify(minioClient).makeBucket(any(MakeBucketArgs.class));
        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    void getObjectReturnsBytesAndContentType() throws Exception {
        initService();
        byte[] payload = "round-trip-bytes".getBytes(StandardCharsets.UTF_8);
        GetObjectResponse response = new GetObjectResponse(
                Headers.of("Content-Type", "image/jpeg"),
                BUCKET,
                "us-east-1",
                "userimage/42/uuid.jpg",
                new ByteArrayInputStream(payload));
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(response);

        StepVerifier.create(service.getObject("userimage/42/uuid.jpg"))
                .assertNext(obj -> {
                    assertThat(obj.data()).isEqualTo(payload);
                    assertThat(obj.contentType()).isEqualTo("image/jpeg");
                })
                .verifyComplete();

        ArgumentCaptor<GetObjectArgs> captor = ArgumentCaptor.forClass(GetObjectArgs.class);
        verify(minioClient).getObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().object()).isEqualTo("userimage/42/uuid.jpg");
    }

    @Test
    void getObjectPropagatesErrorAsMonoError() throws Exception {
        initService();
        when(minioClient.getObject(any(GetObjectArgs.class)))
                .thenThrow(new RuntimeException("not found"));

        StepVerifier.create(service.getObject("missing/key.jpg"))
                .expectError(RuntimeException.class)
                .verify();
    }
}
