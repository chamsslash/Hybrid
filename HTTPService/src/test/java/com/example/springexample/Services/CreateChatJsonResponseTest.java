package com.example.springexample.Services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPA-миграция: success-ветка создания чата (WEBFLUX_Service.createChatSuccess)
 * должна возвращать 200 JSON {chatId, title} вместо прежнего 303-редиректа на
 * /reactive/chatlist, чтобы клиентская вью навигировала без перезагрузки.
 * Тестируем success-ответ точечно, без multipart/ReactiveSecurityContextHolder.
 */
class CreateChatJsonResponseTest {

    private ServerResponse.Context context() {
        return new ServerResponse.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return HandlerStrategies.withDefaults().messageWriters();
            }

            @Override
            public List<ViewResolver> viewResolvers() {
                return Collections.emptyList();
            }
        };
    }

    private MockServerHttpResponse writeResponse(ServerResponse response) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/reactive/api/createchat"));
        response.writeTo(exchange, context()).block();
        return exchange.getResponse();
    }

    @Test
    void successReturns200JsonWithChatIdAndTitle() {
        JsonObject jsonObj = JsonParser.parseString(
                "{\"status\":\"0\",\"id\":\"42\",\"title\":\"My Chat\",\"image_id\":\"pending\"}")
                .getAsJsonObject();

        WEBFLUX_Service service = new WEBFLUX_Service();
        ServerResponse response = service.createChatSuccess(jsonObj).block();
        assertThat(response).isNotNull();

        MockServerHttpResponse httpResponse = writeResponse(response);

        assertThat(httpResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(httpResponse.getHeaders().getContentType())
                .isNotNull()
                .satisfies(ct -> assertThat(ct.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue());

        String body = httpResponse.getBodyAsString().block();
        assertThat(body).contains("\"chatId\":42");
        assertThat(body).contains("\"title\":\"My Chat\"");
    }

    @Test
    void successWithoutTitleReturnsOnlyChatId() {
        JsonObject jsonObj = JsonParser.parseString(
                "{\"status\":\"0\",\"id\":\"7\",\"image_id\":\"done\"}")
                .getAsJsonObject();

        WEBFLUX_Service service = new WEBFLUX_Service();
        ServerResponse response = service.createChatSuccess(jsonObj).block();
        assertThat(response).isNotNull();

        MockServerHttpResponse httpResponse = writeResponse(response);

        assertThat(httpResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = httpResponse.getBodyAsString().block();
        assertThat(body).contains("\"chatId\":7");
        assertThat(body).doesNotContain("title");
    }
}
