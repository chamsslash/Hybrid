package com.example.springexample.Services;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Загрузка аватарки (beads ehe). Проверяется только та часть, которая не требует живого
 * MinIO/Kafka: отсутствие файла в форме. Успешный путь идёт через Upload_image, у которого
 * есть свой тест (WEBFLUX_ServiceUploadImageTest), и проверяется живьём.
 */
class WEBFLUX_ServiceAvatarUploadTest {

    private final WEBFLUX_Service service = new WEBFLUX_Service();

    private ServerRequest emptyMultipartRequest() {
        // MockServerHttpRequest.contentType(MULTIPART_FORM_DATA) с пустым телом не даёт boundary,
        // и request.multipartData() падает с DecodingException вместо отдачи пустой карты частей
        // (ответ уезжает в onErrorResume как 500). Собираем валидное multipart-тело с одной
        // НЕ-файловой частью и явным boundary, чтобы дойти до реальной ветки "нет part file".
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.POST, "/api/avatar")
                        .header("Content-Type", "multipart/form-data; boundary=b")
                        .body("--b\r\nContent-Disposition: form-data; name=\"other\"\r\n\r\nx\r\n--b--\r\n"));
        return ServerRequest.create(exchange, HandlerStrategies.withDefaults().messageReaders());
    }

    @Test
    void missingFilePartYieldsBadRequest() {
        Mono<ServerResponse> response = service.handleAvatarUpload(emptyMultipartRequest());

        StepVerifier.create(response)
                .assertNext(r -> assertThat(r.statusCode().value()).isEqualTo(400))
                .verifyComplete();
    }
}
