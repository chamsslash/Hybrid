package com.example.springexample;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт формы регистрации на пустой file input (beads krr).
 *
 * Форма (static/views/register.view.js) отдаёт userimage БЕЗ required, то есть обещает
 * пользователю, что аватар опционален. Ключевой вопрос, от которого зависит фикс: что при
 * этом реально долетает до WEBFLUX_Service.registerHandle. Браузер в таком случае кладёт в
 * multipart часть с Content-Disposition: filename="" и нулевым телом (проверено в DOM живого
 * стенда), но трактует ли ридер WebFlux такую часть как FilePart != null — вопрос реализации,
 * а не догадки, поэтому он зафиксирован тестом.
 *
 * От ответа зависит, ЧТО именно чинить:
 *   часть == null            -> пользователь без аватара получает 400 «изображение обязательны»;
 *   часть != null, но пустая -> валидация проходит и в MinIO уезжает 0-байтовый объект.
 */
class RegisterMultipartAvatarContractTest {

    private static final String BOUNDARY = "----krrBoundary";

    /** Тело ровно в том виде, в каком его шлёт браузер для формы регистрации. */
    private static String body(String fileHeaders, String fileContent) {
        return "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"username\"\r\n\r\n"
                + "probe\r\n"
                + "--" + BOUNDARY + "\r\n"
                + fileHeaders
                + "\r\n"
                + fileContent + "\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"password\"\r\n\r\n"
                + "probe-password\r\n"
                + "--" + BOUNDARY + "--\r\n";
    }

    private static MultiValueMap<String, Part> parse(String rawBody) {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/register")
                .contentType(MediaType.parseMediaType(
                        MediaType.MULTIPART_FORM_DATA_VALUE + ";boundary=" + BOUNDARY))
                .body(rawBody);
        ServerRequest serverRequest = ServerRequest.create(
                MockServerWebExchange.from(request),
                HandlerStrategies.withDefaults().messageReaders());
        return serverRequest.multipartData().block();
    }

    /**
     * Пустой file input: браузер шлёт часть с filename="" и нулевым телом.
     *
     * Ответ на вопрос тикета: до хендлера такая часть НЕ доходит — ридер WebFlux
     * выбрасывает её целиком, getFirst("userimage") возвращает null. Значит пользователь,
     * оставивший аватар пустым, попадал ровно в ветку `imagePart == null` и получал 400
     * «изображение обязательны», хотя форма помечает поле необязательным. Вариант с
     * 0-байтовым объектом в MinIO, который тоже рассматривался, отпадает.
     *
     * Тест стережёт именно это поведение: если ридер когда-нибудь начнёт пропускать пустые
     * части, у registerHandle появится вход, которого он не ждёт, и упадёт этот тест, а не прод.
     */
    @Test
    void emptyFileInputIsDroppedByReaderEntirely() {
        MultiValueMap<String, Part> parts = parse(body(
                "Content-Disposition: form-data; name=\"userimage\"; filename=\"\"\r\n"
                        + "Content-Type: application/octet-stream\r\n",
                ""));

        assertThat(parts.getFirst("userimage")).isNull();
    }

    /**
     * Пользователь выбрал файл — часть приходит с непустым именем и телом.
     * Контрольная точка: отличие от предыдущего случая держится именно на filename/размере,
     * а не на самом факте наличия части.
     */
    @Test
    void chosenFileArrivesWithFilenameAndContent() {
        MultiValueMap<String, Part> parts = parse(body(
                "Content-Disposition: form-data; name=\"userimage\"; filename=\"avatar.png\"\r\n"
                        + "Content-Type: image/png\r\n",
                "not-really-png-bytes"));

        Part userimage = parts.getFirst("userimage");

        assertThat(userimage).isInstanceOf(FilePart.class);
        assertThat(((FilePart) userimage).filename()).isEqualTo("avatar.png");
        assertThat(userimage.content()
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
                    return new String(bytes, StandardCharsets.UTF_8);
                })
                .reduce("", String::concat)
                .block()).isEqualTo("not-really-png-bytes");
    }

    /**
     * Форма без file input вовсе (не наш браузерный случай, но именно эту ветку описывает
     * текущее сообщение об ошибке «изображение обязательны»): части действительно нет.
     */
    @Test
    void missingFilePartIsAbsentEntirely() {
        String rawBody = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"username\"\r\n\r\n"
                + "probe\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"password\"\r\n\r\n"
                + "probe-password\r\n"
                + "--" + BOUNDARY + "--\r\n";

        assertThat(parse(rawBody).getFirst("userimage")).isNull();
    }
}
