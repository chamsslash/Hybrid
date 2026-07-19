package com.example.springexample;

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

class MvcJwtAuthFilterTest {

    private final MvcJwtAuthFilter filter = new MvcJwtAuthFilter();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void parsesAuthoritiesFromObjectFormat() {
        List<SimpleGrantedAuthority> auths = filter.parseAuthorities("[{\"authority\":\"USER\"}]");
        assertEquals(List.of(new SimpleGrantedAuthority("USER")), auths);
    }

    @Test
    void parsesAuthoritiesFromPlainStringFormat() {
        List<SimpleGrantedAuthority> auths = filter.parseAuthorities("[\"ADMIN\",\"USER\"]");
        assertEquals(
                List.of(new SimpleGrantedAuthority("ADMIN"), new SimpleGrantedAuthority("USER")),
                auths);
    }

    @Test
    void returnsEmptyListOnGarbage() {
        assertTrue(filter.parseAuthorities("not-json").isEmpty());
        assertTrue(filter.parseAuthorities("null").isEmpty());
    }

    @Test
    void setsAuthenticationFromHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chats");
        request.addHeader("X-User-ID", "42");
        request.addHeader("X-Authorities", "[{\"authority\":\"USER\"}]");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth, "Authentication должен быть установлен из X-* заголовков");
        assertEquals("42", auth.getPrincipal());
        assertEquals(List.of(new SimpleGrantedAuthority("USER")), List.copyOf(auth.getAuthorities()));
    }

    @Test
    void skipsAuthenticationWithoutHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chats");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
