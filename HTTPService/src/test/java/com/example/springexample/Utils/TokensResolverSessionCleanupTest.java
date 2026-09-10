package com.example.springexample.Utils;

import com.example.springexample.Services.AuthGrpc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Гашение чужих refresh-сессий при смене пароля (beads ehe).
 *
 * Ключи здесь буквальные, а не выведенные из констант: приватные generateSessionKey /
 * generateUserSessionsSetKey формируют "RefreshSession:{sid}" и "user:{sub}", и тест
 * стережёт именно этот формат — разъехавшись с ним, метод удалял бы несуществующие ключи
 * и молча оставлял чужие сессии живыми.
 */
class TokensResolverSessionCleanupTest {

    private RedisTemplate<String, String> redisTemplate;
    private SetOperations<String, String> setOps;
    private TokensResolver resolver;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        setOps = mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        resolver = new TokensResolver(mock(AuthGrpc.class), redisTemplate);
    }

    private Set<String> sessions(String... sids) {
        Set<String> keys = new LinkedHashSet<>();
        for (String sid : sids) {
            keys.add("RefreshSession:" + sid);
        }
        return keys;
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletesEveryOtherSessionAndKeepsTheCurrentOne() {
        when(setOps.members("user:42")).thenReturn(sessions("sid-a", "sid-b", "sid-c"));

        resolver.deleteOtherSessionsByUser("42", "sid-b");

        ArgumentCaptor<List<String>> deleted = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).delete(deleted.capture());
        assertEquals(List.of("RefreshSession:sid-a", "RefreshSession:sid-c"), deleted.getValue());

        // Набор сессий пользователя не удаляется целиком — в нём остаётся текущая.
        verify(setOps).remove("user:42", "RefreshSession:sid-a", "RefreshSession:sid-c");
    }

    @Test
    void keepsEverythingWhenTheOnlySessionIsTheCurrentOne() {
        when(setOps.members("user:42")).thenReturn(sessions("sid-b"));

        resolver.deleteOtherSessionsByUser("42", "sid-b");

        verify(redisTemplate, never()).delete(any(List.class));
        verify(setOps, never()).remove(anyString(), any(Object[].class));
    }

    @Test
    void doesNothingWhenUserHasNoSessions() {
        when(setOps.members("user:42")).thenReturn(null);

        resolver.deleteOtherSessionsByUser("42", "sid-b");

        verify(redisTemplate, never()).delete(any(List.class));
    }

    @Test
    void deletesAllSessionsWhenCurrentSidIsUnknown() {
        when(setOps.members("user:42")).thenReturn(sessions("sid-a", "sid-b"));

        // Токен без клейма sid: сохранять нечего, гасим всё — включая свою.
        resolver.deleteOtherSessionsByUser("42", null);

        ArgumentCaptor<List<String>> deleted = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).delete(deleted.capture());
        assertEquals(List.of("RefreshSession:sid-a", "RefreshSession:sid-b"), deleted.getValue());
    }
}
