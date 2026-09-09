package com.example.springexample;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * Официальный Spring-паттерн CSRF для SPA (Spring Security reference,
 * "Configure CSRF for SPA"). Резолвит токен из заголовка X-XSRF-TOKEN через
 * plain-handler (сырое значение, как его берёт фронт из cookie), а рендер в
 * тело ответа защищает XOR-обёрткой (BREACH). Работает в паре с
 * {@link CsrfCookieFilter}, который материализует deferred-токен в cookie.
 */
final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        // XOR-обёртка даёт BREACH-защиту при рендере токена в тело ответа.
        this.xor.handle(request, response, csrfToken);
        // Форсируем загрузку deferred-токена, чтобы значение попало в cookie.
        csrfToken.get();
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String headerValue = request.getHeader(csrfToken.getHeaderName());
        // Если токен пришёл в заголовке (SPA берёт сырое значение из cookie) —
        // резолвим plain-handler'ом; иначе (параметр формы) — через XOR.
        return (StringUtils.hasText(headerValue) ? this.plain : this.xor)
                .resolveCsrfTokenValue(request, csrfToken);
    }
}
