package com.example.springexample.Services;

import com.example.springexample.WebFluxConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.thymeleaf.spring6.SpringWebFluxTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.context.Context;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class AppShellRenderTest {

    private static final Pattern NONCE = Pattern.compile("nonce=\"([^\"]*)\"");

    private final WebFluxConfig config = new WebFluxConfig();

    private SpringWebFluxTemplateEngine engine() {
        return engineWithPrefix("templates/");
    }

    private SpringWebFluxTemplateEngine engineWithPrefix(String prefix) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix(prefix);
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringWebFluxTemplateEngine e = new SpringWebFluxTemplateEngine();
        e.setTemplateResolver(resolver);
        return e;
    }

    private ServerResponse.Context responseContext() {
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

    /** Прогон GET-запроса через реальный роутер из WebFluxConfig до записанного HTTP-ответа. */
    private MockServerHttpResponse getThroughRouter(RouterFunction<ServerResponse> router, String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.GET, path));
        ServerRequest request = ServerRequest.create(
                exchange, HandlerStrategies.withDefaults().messageReaders());

        HandlerFunction<ServerResponse> handler = router.route(request).block();
        assertThat(handler).as("маршрут %s не найден", path).isNotNull();

        ServerResponse response = handler.handle(request).block();
        assertThat(response).isNotNull();
        response.writeTo(exchange, responseContext()).block();
        return exchange.getResponse();
    }

    private void assertIsAppShell(MockServerHttpResponse httpResponse, String path) {
        assertThat(httpResponse.getStatusCode()).as("статус %s", path).isEqualTo(HttpStatus.OK);
        assertThat(httpResponse.getHeaders().getContentType())
                .as("content-type %s", path)
                .isNotNull()
                .satisfies(ct -> assertThat(ct.isCompatibleWith(MediaType.TEXT_HTML)).isTrue());

        String html = httpResponse.getBodyAsString().block();
        assertThat(html).as("тело %s", path).isNotNull();
        assertThat(html).contains("id=\"app\"");
        assertThat(html).contains("src=\"/app.js\"");
        assertThat(nonceOf(html)).as("nonce на %s", path).isNotBlank();
    }

    private String nonceOf(String html) {
        Matcher m = NONCE.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    @Test
    void appShellRendersRootAndEntrypoint() {
        Context ctx = new Context();
        Map<String, Object> model = new HashMap<>();
        model.put("nonce", "testnonce");
        ctx.setVariables(model);

        String html = engine().process("app", ctx);

        assertThat(html).contains("id=\"app\"");
        assertThat(html).contains("src=\"/app.js\"");
        assertThat(html).contains("nonce=\"testnonce\"");
    }

    @Test
    void chatListRouteServesAppShell() {
        MockServerHttpResponse response = getThroughRouter(
                config.chatListRouter(new WEBFLUX_Service(), engine()), "/chatlist");
        assertIsAppShell(response, "/chatlist");
    }

    @Test
    void chatRouteServesAppShell() {
        MockServerHttpResponse response = getThroughRouter(
                config.chatPageRouter(new WEBFLUX_Service(), engine()), "/chat");
        assertIsAppShell(response, "/chat");
    }

    @Test
    void createChatRouteServesAppShell() {
        MockServerHttpResponse response = getThroughRouter(
                config.createChatPageRouter(new WEBFLUX_Service(), engine()), "/createchat");
        assertIsAppShell(response, "/createchat");
    }

    @Test
    void everyShellResponseGetsItsOwnNonce() {
        SpringWebFluxTemplateEngine engine = engine();
        WEBFLUX_Service service = new WEBFLUX_Service();

        String first = nonceOf(renderShellBody(service, engine, "/chatlist"));
        String second = nonceOf(renderShellBody(service, engine, "/chat"));

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank();
        assertThat(first).isNotEqualTo(second);
    }

    private String renderShellBody(WEBFLUX_Service service,
                                   SpringWebFluxTemplateEngine engine,
                                   String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.GET, path));
        ServerRequest request = ServerRequest.create(
                exchange, HandlerStrategies.withDefaults().messageReaders());
        ServerResponse response = service.renderAppShell(request, engine).block();
        assertThat(response).isNotNull();
        response.writeTo(exchange, responseContext()).block();
        return exchange.getResponse().getBodyAsString().block();
    }

    @Test
    void shellRenderFailureReturns500WithoutInternals() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.GET, "/chatlist"));
        ServerRequest request = ServerRequest.create(
                exchange, HandlerStrategies.withDefaults().messageReaders());

        // резолвер смотрит в несуществующий каталог — templateEngine.process бросает
        ServerResponse response = new WEBFLUX_Service()
                .renderAppShell(request, engineWithPrefix("no-such-templates/")).block();
        assertThat(response).isNotNull();
        response.writeTo(exchange, responseContext()).block();

        MockServerHttpResponse httpResponse = exchange.getResponse();
        assertThat(httpResponse.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        String body = httpResponse.getBodyAsString().block();
        assertThat(body).isNotNull();
        assertThat(body).doesNotContain("no-such-templates");
        assertThat(body).doesNotContain("TemplateInputException");
        assertThat(body).doesNotContain("class path resource");
    }
}
