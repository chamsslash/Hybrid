package com.example.springexample;

import com.example.springexample.Utils.AccessTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Сервлетная половина аутентификации (/api/*, /AiAssist) — beads 1fs.
 *
 * Личность берётся из подписи access-JWT, а не из X-User-ID/X-Authorities.
 * Раньше фильтр строил Authentication прямо из заголовков, и доверенными их делала
 * только аннотация auth_request на ingress http-protected: перенос одного пути в
 * http-public тихо превращал заголовки в клиентский ввод. Теперь ingress отвечает
 * только за ревокацию (жива ли refresh-сессия по sid в Redis), а личность приложение
 * проверяет само.
 */
class MvcJwtAuthFilterTest {

    private final AccessTokenVerifier verifier =
            new AccessTokenVerifier(TestAccessTokens.publicKeyPem());
    private final MvcJwtAuthFilter filter = new MvcJwtAuthFilter(verifier);

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest apiRequest() {
        return new MockHttpServletRequest("GET", "/api/chats");
    }

    private Authentication runFilter(MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void setsAuthenticationFromSignedToken() throws Exception {
        MockHttpServletRequest request = apiRequest();
        request.addHeader("Authorization", TestAccessTokens.bearerFor("42", "USER"));

        Authentication auth = runFilter(request);

        assertNotNull(auth, "Authentication должен быть установлен из подписанного токена");
        assertEquals("42", auth.getPrincipal());
        assertEquals(List.of(new SimpleGrantedAuthority("USER")), List.copyOf(auth.getAuthorities()));
    }

    @Test
    void skipsAuthenticationWithoutAnyCredentials() throws Exception {
        assertNull(runFilter(apiRequest()));
    }

    /**
     * Центральный сторож 1fs: заголовки без токена — просто клиентский ввод.
     */
    @Test
    void ignoresSpoofedHeadersWithoutBearer() throws Exception {
        MockHttpServletRequest request = apiRequest();
        request.addHeader("X-User-ID", "42");
        request.addHeader("X-Authorities", "[{\"authority\":\"USER\"}]");

        assertNull(runFilter(request),
                "X-User-ID без подписанного токена не должен давать аутентифицированный контекст");
    }

    /**
     * Второй сторож 1fs: если заголовок и токен расходятся, побеждает подпись.
     * Иначе владелец любого валидного токена мог бы выдать себя за чужого userId
     * на пути, не покрытом auth_request, и обойти проверки членства в чате (7f7, dz5).
     */
    @Test
    void tokenSubjectWinsOverSpoofedHeader() throws Exception {
        MockHttpServletRequest request = apiRequest();
        request.addHeader("X-User-ID", "victim-1");
        request.addHeader("X-Authorities", "[{\"authority\":\"ADMIN\"}]");
        request.addHeader("Authorization", TestAccessTokens.bearerFor("attacker-9", "USER"));

        Authentication auth = runFilter(request);

        assertNotNull(auth);
        assertEquals("attacker-9", auth.getPrincipal(), "принципал обязан приходить из sub токена");
        assertEquals(List.of(new SimpleGrantedAuthority("USER")), List.copyOf(auth.getAuthorities()),
                "authorities обязаны приходить из токена, а не из X-Authorities");
    }

    @Test
    void rejectsTokenSignedByForeignKey() throws Exception {
        MockHttpServletRequest request = apiRequest();
        request.addHeader("Authorization", TestAccessTokens.bearerSignedByForeignKey("42", "ADMIN"));

        assertNull(runFilter(request), "токен с чужой подписью не должен аутентифицировать");
    }

    @Test
    void rejectsGarbageBearer() throws Exception {
        MockHttpServletRequest request = apiRequest();
        request.addHeader("Authorization", "Bearer not-a-jwt");

        assertNull(runFilter(request));
    }

    /**
     * SockJS/STOMP-хендшейк остаётся публичным (beads 58/59): браузер не шлёт
     * Authorization на хендшейке, аутентификация живёт на STOMP CONNECT.
     * Фильтр обязан пропускать такие пути, не пытаясь их аутентифицировать.
     */
    @Test
    void stompHandshakePathsStayPublic() {
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/ChatMessagesConn/info")));
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/StatusUserConn")));
        assertFalse(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/chats")));
    }
}
