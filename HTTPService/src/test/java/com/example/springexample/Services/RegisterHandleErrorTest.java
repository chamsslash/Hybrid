package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import com.example.springexample.Utils.AuthResponseException;
import com.example.springexample.Utils.FpSimilarityScore;
import com.example.springexample.Utils.ParsingDataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Реактивный тест WEBFLUX_Service.registerHandle (beads 93e): не-200 ответ
 * AuthService (дубликат логина, статус 666) должен возвращаться клиенту как
 * 409 CONFLICT с РЕАЛЬНЫМ текстом ошибки, а НЕ generic «Отсутствуют данные формы.».
 */
@ExtendWith(MockitoExtension.class)
class RegisterHandleErrorTest {

    @Mock
    private ParsingDataService dataParser;

    @Mock
    private AuthGrpc authGrpc;

    @InjectMocks
    private WEBFLUX_Service webfluxService;

    private FormFieldPart formField(String value) {
        FormFieldPart part = org.mockito.Mockito.mock(FormFieldPart.class);
        when(part.value()).thenReturn(value);
        return part;
    }

    private MockServerRequest requestWithParts() {
        MultiValueMap<String, Part> parts = new LinkedMultiValueMap<>();
        parts.add("username", formField("bob"));
        parts.add("password", formField("secret"));
        parts.add("FpComponents", formField("{}"));
        parts.add("userimage", org.mockito.Mockito.mock(org.springframework.http.codec.multipart.FilePart.class));

        return MockServerRequest.builder()
                .header("X-Client-Meta", "{\"ip\":\"127.0.0.1\"}")
                .header("X-SecureUUID", "uuid-1")
                .header("X-Fingerprint", "fp-1")
                .body(reactor.core.publisher.Mono.just(parts));
    }

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

    @Test
    void duplicateUserReturns409WithRealMessageNotGenericFormError() throws Exception {
        when(dataParser.JsonStreamingParsing(any(), any(FpSimilarityScore.class))).thenReturn("{}");
        when(authGrpc.authRegister(any(DataTransferService.UserDataRequest.class)))
                .thenThrow(new AuthResponseException("666", "User with such name already exists"));

        ServerResponse response = webfluxService.registerHandle(requestWithParts()).block();
        assertThat(response).isNotNull();

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/reactive/register"));
        response.writeTo(exchange, context()).block();

        MockServerHttpResponse httpResponse = exchange.getResponse();
        assertThat(httpResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        String body = httpResponse.getBodyAsString().block();
        assertThat(body).isEqualTo("User with such name already exists");
        assertThat(body).doesNotContain("Отсутствуют данные формы");
    }
}
