package com.example.springexample.Utils;

import org.springframework.http.ResponseCookie;

import java.time.Duration;

/**
 * Единственное место, где собирается кука сессии (beads ybg).
 *
 * <p>До этого один и тот же билдер стоял тремя копиями — {@code MVC_Service} (ротация
 * токенов и обмен одноразового токена) и {@code WEBFLUX_Service} — и в каждой копии
 * {@code .secure(true)} был закомментирован отдельной строкой. Расхождение таких копий
 * не падает ни одним тестом и не видно в логе: кука просто уходит без нужного атрибута,
 * а обнаруживается это уже в браузере. Поэтому атрибуты живут здесь, а вызывающая
 * сторона передаёт только значение и признак защищённого соединения.
 *
 * <p><b>Почему {@code secure} — параметр, а не константа.</b> Кука с атрибутом
 * {@code Secure} по plain HTTP браузером просто отбрасывается, то есть жёсткое
 * {@code true} сломало бы текущий стенд на {@code http://myapp.localtest.me}, а жёсткое
 * {@code false} отдавало бы refresh-токен в открытом виде при публикации по HTTPS.
 * Значение приходит из {@code COOKIE_SECURE} (см. {@code application.yml}), дефолт
 * {@code false} сохраняет поведение стенда.
 *
 * <p>{@code SameSite=Strict} оставлен константой намеренно: кука ставится нашим же
 * эндпоинтом уже ПОСЛЕ возврата от Google, и ни один сценарий не требует отправлять её
 * при межсайтовой навигации. Ослаблять до {@code Lax}/{@code None} без такого сценария
 * значит расширять поверхность CSRF без причины.
 */
public final class AuthCookies {

    /** Имя куки с refresh-токеном. */
    public static final String REFRESH = "refresh";

    /**
     * Легаси-кука с access-токеном. Access давно живёт в памяти вкладки, а не в куке,
     * но у старых сессий она ещё может лежать в браузере — поэтому её продолжаем гасить.
     */
    public static final String LEGACY_ACCESS = "access";

    /** Срок жизни refresh-куки. Совпадает с {@code securityProps.refresh-expiration-ms}. */
    public static final Duration REFRESH_TTL = Duration.ofDays(7);

    public static final String SAME_SITE = "Strict";

    private AuthCookies() {
    }

    /**
     * Кука с refresh-токеном.
     *
     * @param secure ставить ли атрибут {@code Secure}; {@code true} допустим только
     *               тогда, когда браузер реально пришёл по HTTPS, иначе кука будет
     *               отброшена и пользователь останется без сессии
     */
    public static ResponseCookie refresh(String value, boolean secure) {
        return ResponseCookie.from(REFRESH, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(SAME_SITE)
                .path("/")
                .maxAge(REFRESH_TTL)
                .build();
    }

    /**
     * Гасящая кука для refresh.
     *
     * <p>Атрибут {@code Secure} здесь сознательно НЕ ставится: браузер сопоставляет куку
     * для замены по имени, домену и пути, атрибуты в сопоставлении не участвуют, зато
     * {@code Secure}, выставленный при обращении по plain HTTP, привёл бы к тому, что
     * гасящая кука отбрасывается и старая остаётся жить.
     */
    public static ResponseCookie deleteRefresh() {
        return ResponseCookie.from(REFRESH, "").maxAge(0).path("/").build();
    }

    /** Гасящая кука для легаси-access. Про отсутствие {@code Secure} — см. {@link #deleteRefresh()}. */
    public static ResponseCookie deleteLegacyAccess() {
        return ResponseCookie.from(LEGACY_ACCESS, "").maxAge(0).path("/").build();
    }
}
