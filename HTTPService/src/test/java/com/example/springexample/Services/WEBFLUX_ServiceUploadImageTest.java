package com.example.springexample.Services;

import com.example.springexample.KafkaProducer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты WEBFLUX_Service.Upload_image (beads se2): роутинг targetType
 * (userimage/chatimage) и формирование ключа &lt;targetType&gt;/&lt;targetId&gt;/&lt;uuid&gt;.&lt;ext&gt;,
 * без обращения к реальному MinIO/Kafka — оба коллаборатора замокань.
 */
@ExtendWith(MockitoExtension.class)
class WEBFLUX_ServiceUploadImageTest {

    private static final Pattern UUID_REGEX = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @Mock
    private ImageStorageService imageStorageService;

    @Mock
    private KafkaProducer kafkaProducer;

    @InjectMocks
    private WEBFLUX_Service webfluxService;

    private FilePart mockFilePart(String filename, MediaType contentType, byte[] bytes) {
        FilePart part = org.mockito.Mockito.mock(FilePart.class);
        lenient().when(part.filename()).thenReturn(filename);
        DataBuffer buffer = new DefaultDataBufferFactory().wrap(bytes);
        lenient().when(part.content()).thenReturn(Flux.just(buffer));
        HttpHeaders headers = new HttpHeaders();
        if (contentType != null) {
            headers.setContentType(contentType);
        }
        lenient().when(part.headers()).thenReturn(headers);
        return part;
    }

    @Test
    void routesRegistrationAvatarToUserimageWithGeneratedKey() {
        when(imageStorageService.putObject(anyString(), any(byte[].class), anyString()))
                .thenReturn(Mono.empty());
        FilePart file = mockFilePart("avatar.png", MediaType.IMAGE_PNG, "avatar-bytes".getBytes(StandardCharsets.UTF_8));

        StepVerifier.create(webfluxService.Upload_image(file, "42", "userimage")).verifyComplete();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> contentTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(imageStorageService).putObject(keyCaptor.capture(), dataCaptor.capture(), contentTypeCaptor.capture());

        String key = keyCaptor.getValue();
        assertThat(key).startsWith("userimage/42/");
        assertThat(key).endsWith(".png");
        String uuidPart = key.substring("userimage/42/".length(), key.length() - ".png".length());
        assertThat(UUID_REGEX.matcher(uuidPart).matches()).isTrue();
        assertThat(new String(dataCaptor.getValue(), StandardCharsets.UTF_8)).isEqualTo("avatar-bytes");
        assertThat(contentTypeCaptor.getValue()).isEqualTo("image/png");

        ArgumentCaptor<String> kafkaCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaProducer).sendImage(kafkaCaptor.capture());
        JsonObject event = JsonParser.parseString(kafkaCaptor.getValue()).getAsJsonObject();
        assertThat(event.get("targetType").getAsString()).isEqualTo("userimage");
        assertThat(event.get("targetId").getAsString()).isEqualTo("42");
        assertThat(event.get("objectKey").getAsString()).isEqualTo(key);
    }

    @Test
    void routesChatCreationImageToChatimage() {
        when(imageStorageService.putObject(anyString(), any(byte[].class), anyString()))
                .thenReturn(Mono.empty());
        FilePart file = mockFilePart("banner.jpg", MediaType.IMAGE_JPEG, "chat-bytes".getBytes(StandardCharsets.UTF_8));

        StepVerifier.create(webfluxService.Upload_image(file, "7", "chatimage")).verifyComplete();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(imageStorageService).putObject(keyCaptor.capture(), any(byte[].class), eq("image/jpeg"));
        assertThat(keyCaptor.getValue()).startsWith("chatimage/7/").endsWith(".jpg");

        ArgumentCaptor<String> kafkaCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaProducer).sendImage(kafkaCaptor.capture());
        JsonObject event = JsonParser.parseString(kafkaCaptor.getValue()).getAsJsonObject();
        assertThat(event.get("targetType").getAsString()).isEqualTo("chatimage");
        assertThat(event.get("targetId").getAsString()).isEqualTo("7");
    }

    @Test
    void defaultsExtensionToJpgWhenFilenameHasNoExtension() {
        when(imageStorageService.putObject(anyString(), any(byte[].class), anyString()))
                .thenReturn(Mono.empty());
        FilePart file = mockFilePart("no-extension-name", MediaType.IMAGE_JPEG, "bytes".getBytes(StandardCharsets.UTF_8));

        StepVerifier.create(webfluxService.Upload_image(file, "1", "userimage")).verifyComplete();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(imageStorageService).putObject(keyCaptor.capture(), any(byte[].class), anyString());
        assertThat(keyCaptor.getValue()).endsWith(".jpg");
    }

    @Test
    void propagatesStorageFailureWithoutPublishingToKafka() {
        when(imageStorageService.putObject(anyString(), any(byte[].class), anyString()))
                .thenReturn(Mono.error(new RuntimeException("minio down")));
        FilePart file = mockFilePart("avatar.png", MediaType.IMAGE_PNG, "bytes".getBytes(StandardCharsets.UTF_8));

        StepVerifier.create(webfluxService.Upload_image(file, "42", "userimage"))
                .expectError(RuntimeException.class)
                .verify();

        verify(kafkaProducer, org.mockito.Mockito.never()).sendImage(anyString());
    }
}
