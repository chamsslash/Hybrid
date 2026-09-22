package com.example.springexample.Services;

import com.example.springexample.KafkaProducer;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Загрузка стикера, POST /api/sticker (beads a22). Снаружи это
 * POST /reactive/api/sticker — путь закрыт auth_request на ingress.
 *
 * Эндпоинт скопирован с пары /api/avatar, но отличается тремя решениями, и все три
 * проверяются здесь, потому что ни одно не видно по коду вызывающего:
 *
 *   1) ответ синхронный и НЕСЁТ ключ — клиент сразу шлёт этим ключом STOMP-фрейм;
 *   2) события в Kafka-топик "Images" НЕТ — топик существует, чтобы
 *      ImageUrlPersistenceService записал ключ в users.image_url / chat.image_url,
 *      а у стикера такой строки нет вовсе: он попадёт в БД обычным путём сообщения;
 *   3) тип файла проверяется и отвергается внятным 400, а не молчанием.
 *
 * Принципал подкладывается через контекст подписчика: handleStickerUpload берёт его из
 * ReactiveSecurityContextHolder, а не из формы, — id уходит прямо в ключ объекта MinIO,
 * и приём его снаружи означал бы запись в чужой префикс.
 */
@ExtendWith(MockitoExtension.class)
class WEBFLUX_ServiceStickerUploadTest {

    private static final Pattern UUID_REGEX = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @Mock
    private ImageStorageService imageStorageService;

    @Mock
    private KafkaProducer kafkaProducer;

    @InjectMocks
    private WEBFLUX_Service service;

    /** Валидное multipart-тело с одной файловой частью и явным boundary. */
    private ServerRequest uploadRequest(String filename, String contentType, String body) {
        String multipart = "--b\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n"
                + body + "\r\n--b--\r\n";
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.POST, "/api/sticker")
                        .header("Content-Type", "multipart/form-data; boundary=b")
                        .body(multipart));
        return ServerRequest.create(exchange, HandlerStrategies.withDefaults().messageReaders());
    }

    /** Тот же приём, что в WEBFLUX_ServiceAvatarUploadTest: часть есть, но не файловая. */
    private ServerRequest requestWithoutFilePart() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.POST, "/api/sticker")
                        .header("Content-Type", "multipart/form-data; boundary=b")
                        .body("--b\r\nContent-Disposition: form-data; name=\"other\"\r\n\r\nx\r\n--b--\r\n"));
        return ServerRequest.create(exchange, HandlerStrategies.withDefaults().messageReaders());
    }

    private Mono<ServerResponse> upload(ServerRequest request, String userId) {
        return service.handleStickerUpload(request)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                        new UsernamePasswordAuthenticationToken(userId, null, List.of())));
    }

    private static String bodyText(ServerResponse response) {
        // EntityResponse<Map> собран в хендлере; читаем его через публичный контракт
        // ServerResponse нельзя, поэтому проверяем сериализованное тело.
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.POST, "/api/sticker"));
        response.writeTo(exchange, new ServerResponse.Context() {
            @Override
            public List<org.springframework.http.codec.HttpMessageWriter<?>> messageWriters() {
                return HandlerStrategies.withDefaults().messageWriters();
            }

            @Override
            public List<org.springframework.web.reactive.result.view.ViewResolver> viewResolvers() {
                return List.of();
            }
        }).block();
        DataBuffer buffer = exchange.getResponse().getBody().blockFirst();
        return buffer == null ? "" : buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void storesUnderOwnStickerPrefixAndReturnsKeySynchronously() {
        when(imageStorageService.putObject(anyString(), any(byte[].class), anyString()))
                .thenReturn(Mono.empty());

        ServerResponse response = upload(uploadRequest("cat.png", "image/png", "sticker-bytes"), "42").block();

        assertThat(response).isNotNull();
        assertThat(response.statusCode().value()).isEqualTo(200);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(imageStorageService).putObject(keyCaptor.capture(), any(byte[].class), anyString());
        String key = keyCaptor.getValue();
        assertThat(key).startsWith("sticker/42/").endsWith(".png");
        String uuidPart = key.substring("sticker/42/".length(), key.length() - ".png".length());
        assertThat(UUID_REGEX.matcher(uuidPart).matches()).isTrue();

        // Ключ обязан приехать клиенту в ответе: без него вкладка «Загрузить» не знает,
        // чем отправлять STOMP-фрейм, а ждать события, как это делает аватарка, нечего —
        // события для стикера нет вовсе.
        String json = bodyText(response);
        assertThat(JsonParser.parseString(json).getAsJsonObject().get("objectKey").getAsString())
                .isEqualTo(key);
    }

    @Test
    void doesNotPublishToImagesTopic() {
        when(imageStorageService.putObject(anyString(), any(byte[].class), anyString()))
                .thenReturn(Mono.empty());

        upload(uploadRequest("cat.png", "image/png", "bytes"), "42").block();

        // Топик "Images" ведёт к ImageUrlPersistenceService, который пишет ключ в
        // users.image_url / chat.image_url. У стикера такой строки нет: событие там
        // ушло бы в ветку неизвестного targetType и роняло бы консьюмер в ретраи и DLT.
        verify(kafkaProducer, never()).sendImage(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/pdf", "text/html", "application/octet-stream", "image/svg+xml"})
    void rejectsNonImageContentTypeWithExplicitBadRequest(String contentType) {
        StepVerifier.create(upload(uploadRequest("payload.bin", contentType, "bytes"), "42"))
                .assertNext(r -> assertThat(r.statusCode().value()).isEqualTo(400))
                .verifyComplete();

        // Отказ ДО записи в MinIO: иначе бакет копил бы чужеродные объекты, которые потом
        // отдаются любому аутентифицированному (ACL префикса sticker/ — «всем залогиненным»).
        verify(imageStorageService, never()).putObject(anyString(), any(byte[].class), anyString());
    }

    @Test
    void missingFilePartYieldsBadRequest() {
        StepVerifier.create(upload(requestWithoutFilePart(), "42"))
                .assertNext(r -> assertThat(r.statusCode().value()).isEqualTo(400))
                .verifyComplete();

        verify(imageStorageService, never()).putObject(anyString(), any(byte[].class), anyString());
    }
}
