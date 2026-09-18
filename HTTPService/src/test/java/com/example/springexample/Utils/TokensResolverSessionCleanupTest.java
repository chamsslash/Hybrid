package com.example.springexample.Utils;

import com.example.springexample.Services.AuthGrpc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;

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

    // --- выход из аккаунта (logoutCurrentSession) ---

    @Test
    void logoutDeletesOnlyTheCurrentSession() {
        // Сессия лежит в Redis: deleteSessionBySid читает её, чтобы узнать sub и вычистить
        // ключ из набора пользователя.
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("RefreshSession:sid-b")).thenReturn("{\"sub\":\"42\"}");

        resolver.logoutCurrentSession("sid-b");

        // Ровно свой ключ — и он же снят с набора user:42. Сессии на других устройствах
        // не трогаются: выйти на ноутбуке и остаться в телефоне — штатное ожидание.
        verify(redisTemplate).delete("RefreshSession:sid-b");
        verify(setOps).remove("user:42", "RefreshSession:sid-b");
    }

    @Test
    void logoutWithoutSidTouchesNothingInRedis() {
        // Access-токен без клейма sid (выпущен до появления клейма): опознать сессию нечем.
        // Гасить вслепую нельзя — под руку попали бы чужие устройства; куку снимает
        // вызывающий, поэтому сессия всё равно становится недостижимой.
        resolver.logoutCurrentSession(null);

        // verifyNoInteractions тут не годится: setUp уже трогает redisTemplate при
        // настройке opsForSet, и проверка упала бы на этом, а не на поведении метода.
        verify(redisTemplate, never()).delete(anyString());
        verify(setOps, never()).remove(anyString(), any(Object[].class));
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
