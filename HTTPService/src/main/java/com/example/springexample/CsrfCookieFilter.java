package com.example.springexample;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Материализует deferred CSRF-токен в cookie на КАЖДОМ ответе (Spring Security
 * reference, SPA CSRF). Без этого CookieCsrfTokenRepository пишет XSRF-TOKEN
 * cookie лениво (только когда токен реально запрашивается при обработке), из-за
 * чего первый POST после загрузки страницы (напр. /exchangeTokens в
 * авто-логине/регистрации) уходил с рассинхроненным/отсутствующим токеном и
 * отбивался 403, а ретраи проходили. Также чинит deep-link на /registerpage,
 * который раньше вообще не ставил cookie (beads 6i5).
 */
final class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute("_csrf");
        if (csrfToken != null) {
            // getToken() форсирует загрузку токена и запись cookie в ответ.
            csrfToken.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
