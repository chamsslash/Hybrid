package com.example.springexample.Utils;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Атрибуты куки сессии (beads ybg).
 *
 * <p>Проверяется не «работает ли билдер», а то, что переключатель {@code COOKIE_SECURE}
 * действительно доходит до заголовка и что гасящие куки этот атрибут НЕ получают.
 * Оба свойства невидимы в рантайме: неверный атрибут не роняет запрос и не пишет
 * в лог — пользователь просто остаётся без сессии, а причину видно только в devtools.
 */
class AuthCookiesTest {

    /**
     * Что делает: собирает refresh-куку с secure=false (текущий стенд на plain HTTP).
     * Что проверяет: атрибута Secure нет, а HttpOnly, SameSite=Strict, Path и срок
     * жизни на месте.
     * Зачем: Secure по plain HTTP заставляет браузер отбросить куку целиком, то есть
     * ошибка здесь означает полную неработоспособность входа на стенде.
     */
    @Test
    void plainHttpCookieHasNoSecureAttribute() {
        ResponseCookie cookie = AuthCookies.refresh("token-value", false);

        assertFalse(cookie.isSecure(), "по HTTP Secure ставить нельзя — браузер отбросит куку");
        assertTrue(cookie.isHttpOnly());
        assertEquals("Strict", cookie.getSameSite());
        assertEquals("/", cookie.getPath());
        assertEquals(Duration.ofDays(7), cookie.getMaxAge());
        assertEquals("refresh", cookie.getName());
        assertFalse(cookie.toString().contains("Secure"),
                "атрибут не должен просачиваться в сериализованный заголовок");
    }

    /**
     * Что делает: собирает refresh-куку с secure=true (публикация по HTTPS).
     * Что проверяет: атрибут Secure присутствует и в объекте, и в сериализованном
     * заголовке Set-Cookie.
     * Зачем: это единственное, что удерживает refresh-токен от передачи в открытом
     * виде при публикации наружу; проверка объекта без проверки заголовка не поймала бы
     * ситуацию, когда флаг выставлен, но в заголовок не попадает.
     */
    @Test
    void httpsCookieCarriesSecureAttribute() {
        ResponseCookie cookie = AuthCookies.refresh("token-value", true);

        assertTrue(cookie.isSecure());
        assertTrue(cookie.toString().contains("Secure"));
        assertEquals("Strict", cookie.getSameSite());
    }

    /**
     * Что делает: строит гасящие куки для refresh и легаси-access.
     * Что проверяет: Max-Age равен нулю, путь "/", и атрибута Secure НЕТ ни у одной.
     * Зачем: стережёт ловушку, описанную в javadoc AuthCookies.deleteRefresh —
     * гасящая кука с Secure, отданная по plain HTTP, отбрасывается браузером, и
     * старая кука переживает выход из системы. Для сопоставления Secure не нужен:
     * браузер ищет замену по имени, домену и пути.
     */
    @Test
    void deletionCookiesExpireImmediatelyAndSkipSecure() {
        ResponseCookie refresh = AuthCookies.deleteRefresh();
        ResponseCookie access = AuthCookies.deleteLegacyAccess();

        assertEquals("refresh", refresh.getName());
        assertEquals("access", access.getName());
        for (ResponseCookie cookie : new ResponseCookie[]{refresh, access}) {
            assertEquals(Duration.ZERO, cookie.getMaxAge());
            assertEquals("/", cookie.getPath());
            assertEquals("", cookie.getValue());
            assertFalse(cookie.isSecure(),
                    "гасящая кука с Secure по HTTP была бы отброшена — старая осталась бы жить");
        }
    }
}
