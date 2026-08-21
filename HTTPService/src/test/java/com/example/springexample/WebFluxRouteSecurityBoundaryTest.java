package com.example.springexample;

import com.example.springexample.Services.WEBFLUX_Service;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Граница «публичный шелл / защищённая мутация» для страницы создания чата (beads 52u).
 *
 * Ingress не умеет разводить auth_request по HTTP-методу (configuration-snippet на
 * нашем ingress-nginx отбивается admission-вебхуком по annotations-risk-level), поэтому
 * GET-шелл и POST-мутация разведены по РАЗНЫМ путям: шелл /reactive/createchat публичен,
 * мутация /reactive/api/createchat закрыта auth_request -> AuthService /jwtcheck.
 *
 * Тест стережёт именно это разведение: если POST-роут вернуть на /createchat, он окажется
 * под публичным префиксом шелла и создание чата станет доступно анониму.
 */
class WebFluxRouteSecurityBoundaryTest {

    private final WebFluxConfig config = new WebFluxConfig();

    private ServerRequest request(org.springframework.http.HttpMethod method, String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(method, path));
        return ServerRequest.create(exchange, HandlerStrategies.withDefaults().messageReaders());
    }

    private boolean matches(RouterFunction<ServerResponse> router,
                            org.springframework.http.HttpMethod method,
                            String path) {
        HandlerFunction<ServerResponse> handler = router.route(request(method, path)).block();
        return handler != null;
    }

    @Test
    void createChatMutationIsRoutedUnderProtectedApiPrefix() {
        RouterFunction<ServerResponse> router = config.createchatHandle(new WEBFLUX_Service());

        // снаружи это POST /reactive/api/createchat — путь покрыт ingress http-protected
        assertThat(matches(router, org.springframework.http.HttpMethod.POST, "/api/createchat")).isTrue();
    }

    @Test
    void createChatMutationIsNotRoutedUnderPublicShellPath() {
        RouterFunction<ServerResponse> router = config.createchatHandle(new WEBFLUX_Service());

        // /createchat = публичный шелл; POST здесь означал бы создание чата без auth_request
        assertThat(matches(router, org.springframework.http.HttpMethod.POST, "/createchat")).isFalse();
    }

    @Test
    void createChatShellStaysGetOnlyOnPublicPath() {
        RouterFunction<ServerResponse> shell = config.createChatPageRouter(new WEBFLUX_Service(), null);

        assertThat(matches(shell, org.springframework.http.HttpMethod.GET, "/createchat")).isTrue();
        assertThat(matches(shell, org.springframework.http.HttpMethod.POST, "/createchat")).isFalse();
    }
}
