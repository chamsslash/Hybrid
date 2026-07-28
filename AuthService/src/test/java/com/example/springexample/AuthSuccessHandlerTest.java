package com.example.springexample;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.Authentication;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты OAuth success-flow (beads ok9): AuthSuccessHandler после успешного
 * Google-логина кладёт одноразовый код в Redis (sub = DB user id, TTL 300s) и
 * редиректит на /authcallback с state и code. Redis замокан.
 */
class AuthSuccessHandlerTest {

    private RedisTemplate<String, String> redisTemplate;
    private ValueOperations<String, String> valueOps;
    private AuthSuccessHandler handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        handler = new AuthSuccessHandler(redisTemplate);
    }

    private Authentication authenticationWithSub(String sub) {
        CustomOAuth2User principal = new CustomOAuth2User();
        principal.setSub(sub);
        principal.setName("alice");
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        return authentication;
    }

    @Test
    void successStoresOneTimeCodeAndRedirectsWithStateAndCode() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getParameter("state")).thenReturn("st-1");

        handler.onAuthenticationSuccess(request, response, authenticationWithSub("42"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<TimeUnit> unitCaptor = ArgumentCaptor.forClass(TimeUnit.class);
        verify(valueOps).set(keyCaptor.capture(), valueCaptor.capture(),
                ttlCaptor.capture(), unitCaptor.capture());

        String key = keyCaptor.getValue();
        assertTrue(key.startsWith("UserOneTimeCodeFastCheck"),
                "ключ должен начинаться с UserOneTimeCodeFastCheck, а был: " + key);
        assertEquals("42", valueCaptor.getValue(), "в Redis должен уйти sub = DB user id");
        assertEquals(300L, ttlCaptor.getValue());
        assertEquals(TimeUnit.SECONDS, unitCaptor.getValue());

        String code = key.substring("UserOneTimeCodeFastCheck".length());
        ArgumentCaptor<String> redirectCaptor = ArgumentCaptor.forClass(String.class);
        verify(response).sendRedirect(redirectCaptor.capture());
        String redirect = redirectCaptor.getValue();
        assertTrue(redirect.startsWith("/authcallback"),
                "редирект должен вести на /authcallback, а был: " + redirect);
        assertTrue(redirect.contains("state=st-1"), "редирект должен нести state: " + redirect);
        assertTrue(redirect.contains("code=" + code),
                "code в редиректе должен совпадать с суффиксом Redis-ключа: " + redirect);
    }

    @Test
    void emptyStateSkipsRedirectAndDoesNotStoreCode() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getParameter("state")).thenReturn("");

        handler.onAuthenticationSuccess(request, response, authenticationWithSub("42"));

        verify(valueOps, never()).set(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(TimeUnit.class));
        verify(response, never()).sendRedirect(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void missingSubThrows() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThrows(IllegalArgumentException.class,
                () -> handler.onAuthenticationSuccess(request, response, authenticationWithSub(null)));
    }
}
