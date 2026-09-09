package com.example.springexample;

import com.example.springexample.Utils.AccessTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.WebFilterChain;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Реактивная половина аутентификации (внешний префикс /reactive/*) — beads 1fs.
 *
 * Дыра, которую стережёт этот класс: фильтр строил Authentication из X-User-ID /
 * X-Authorities без единой проверки, и защитой служила только топология ingress —
 * auth_request на http-protected перезаписывал клиентские значения ответом
 * /jwtcheck. На путях http-public те же заголовки шли от клиента насквозь, и одна
 * строка в Helm/templates/http-ingress.yaml превращала это в тихую дыру. Теперь
 * личность берётся из подписи access-JWT (AccessTokenVerifier), а ingress остаётся
 * ответственным только за ревокацию по sid.
 *
 * Пути здесь без префикса /reactive: реактивное приложение смонтировано на сервлет,
 * и ServletHttpHandlerAdapter отдаёт фильтру уже срезанный путь (снаружи
 * /reactive/api/createchat — внутри /api/createchat).
 */
class ReactiveHybridAuthFilterTest {

    private final AccessTokenVerifier verifier =
            new AccessTokenVerifier(TestAccessTokens.publicKeyPem());
    private final ReactiveHybridAuthFilter filter = new ReactiveHybridAuthFilter(verifier);

    private final AtomicReference<Authentication> captured = new AtomicReference<>();

    /** Цепочка, снимающая Authentication из реактивного контекста ниже фильтра. */
    private final WebFilterChain chain = exchange -> ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .doOnNext(captured::set)
            .then();

    private Authentication runFilter(MockServerHttpRequest.BaseBuilder<?> request) {
        filter.filter(MockServerWebExchange.from(request.build()), chain).block();
        return captured.get();
    }

    private MockServerHttpRequest.BaseBuilder<?> protectedRequest() {
        return MockServerHttpRequest.method(HttpMethod.POST, "/api/createchat");
    }

    @Test
    void setsAuthenticationFromSignedToken() {
        Authentication auth = runFilter(protectedRequest()
                .header("Authorization", TestAccessTokens.bearerFor("42", "USER")));

        assertNotNull(auth, "Authentication должен быть установлен из подписанного токена");
        assertEquals("42", auth.getPrincipal());
        assertEquals(List.of(new SimpleGrantedAuthority("USER")), List.copyOf(auth.getAuthorities()));
    }

    @Test
    void skipsAuthenticationWithoutAnyCredentials() {
        assertNull(runFilter(protectedRequest()));
    }

    /**
     * Центральный сторож 1fs: ровно тот запрос, который раньше проходил как
     * аутентифицированный на любом пути мимо auth_request.
     */
    @Test
    void ignoresSpoofedHeadersWithoutBearer() {
        Authentication auth = runFilter(protectedRequest()
                .header("X-User-ID", "42")
                .header("X-Authorities", "[{\"authority\":\"USER\"}]"));

        assertNull(auth, "X-User-ID без подписанного токена не должен давать аутентифицированный контекст");
    }

    /**
     * Подмена личности при наличии собственного валидного токена: подпись перебивает заголовок.
     */
    @Test
    void tokenSubjectWinsOverSpoofedHeader() {
        Authentication auth = runFilter(protectedRequest()
                .header("X-User-ID", "victim-1")
                .header("X-Authorities", "[{\"authority\":\"ADMIN\"}]")
                .header("Authorization", TestAccessTokens.bearerFor("attacker-9", "USER")));

        assertNotNull(auth);
        assertEquals("attacker-9", auth.getPrincipal(), "принципал обязан приходить из sub токена");
        assertEquals(List.of(new SimpleGrantedAuthority("USER")), List.copyOf(auth.getAuthorities()),
                "authorities обязаны приходить из токена, а не из X-Authorities");
    }

    @Test
    void rejectsTokenSignedByForeignKey() {
        assertNull(runFilter(protectedRequest()
                .header("Authorization", TestAccessTokens.bearerSignedByForeignKey("42", "ADMIN"))));
    }

    @Test
    void rejectsGarbageBearer() {
        assertNull(runFilter(protectedRequest().header("Authorization", "Bearer not-a-jwt")));
    }
}
