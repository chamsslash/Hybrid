package com.example.springexample;

import com.example.springexample.Utils.TokensResolver;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class ReactiveHybridAuthFilter implements WebFilter {

    private final TokensResolver tokensResolver;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    // Список публичных путей (аналогично сервлетной версии)
    private static final List<String> PUBLIC_PATHS = List.of(
            "/reactive/login", "/reactive/register", "/welcome", "/authcallback",
            "/collect-fingerprint", "/exchangeTokens", "/actuator/**",
            "/public/**", "/static/**", "/**/*.js", "/verifylogin"
    );

    private boolean shouldNotFilter(ServerHttpRequest request) {
        String uri = request.getURI().getPath();
        log.warn("Проверка URI для фильтра: '{}'", uri);

        return PUBLIC_PATHS.stream().anyMatch(pattern -> pathMatcher.match(pattern, uri));
    }

    @Override
    @NonNull
    public Mono<Void> filter(ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (shouldNotFilter(request)) {
            log.info("запрос не прошел по фильтру из за того что он публичен");
            return chain.filter(exchange);
        }
        log.info("Запрос по фильтру прошел");
        // Получаем JWT из cookie "access"
        String jwt = getJwtFromRequest(request);

        // Валидируем токен
        return isTokenValid(jwt)
                .flatMap(result -> {
                    // --- Сценарий 1: JWT валиден ---
                    // Если результат - это объект Authentication, устанавливаем его в контекст
                    if (result instanceof Authentication) {
                        log.warn("JWT валиден, пропускаем запрос на {}", request.getURI().getPath());
                        Authentication auth = (Authentication) result;
                        return chain.filter(exchange)
                                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
                    }
                    // --- Сценарий 2: JWT истек ---
                    // Если результат - это строка (JTI), токен истек, и нужно проверить Refresh токен
                    else if (result instanceof String) {

                        String jti = (String) result;
                        return Mono.fromCallable(() -> tokensResolver.getRefreshByJti(jti))
                                .subscribeOn(Schedulers.boundedElastic()) // Выполняем блокирующий вызов в отдельном потоке
                                .flatMap(refreshToken -> {
                                    if (Objects.nonNull(refreshToken)) {
                                        // Refresh токен найден, редиректим на сбор фингерпринта
                                        return redirectToFingerprint(exchange);
                                    } else {
                                        // Refresh токен не найден, пропускаем запрос без аутентификации
                                        return chain.filter(exchange);
                                    }
                                });
                    }
                    // Если результат другого типа (неожиданный случай), пропускаем
                    return chain.filter(exchange);
                })
                // --- Сценарий 3: JWT невалиден (ошибка парсинга) или отсутствует ---
                .switchIfEmpty(Mono.defer(() -> {
                    log.debug("JWT отсутствует или невалиден (ошибка парсинга). Пропускаем запрос.");
                    // Пропускаем запрос без аутентификации. Его может перехватить `AuthenticationEntryPoint`.
                    return chain.filter(exchange);
                }));
    }

    /**
     * Валидирует JWT.
     * Возвращает:
     * - Mono<Object> содержащий Authentication, если токен валиден.
     * - Mono<Object> содержащий String (JTI), если токен истек.
     * - Mono.empty(), если токен невалиден по другим причинам (ошибка подписи, и т.д.).
     */
    public Mono<Object> isTokenValid(String jwt) {
        if (!StringUtils.hasText(jwt)) {
            return Mono.empty();
        }

        return Mono.fromCallable(() -> {
                    try {
                        Claims claims = Jwts.parser()
                                .verifyWith((PublicKey) tokensResolver.loadKeys().get("public_key"))
                                .build()
                                .parseSignedClaims(jwt)
                                .getPayload();

                        // Если все проверки пройдены, создаем объект Authentication
                        log.debug("JWT токен успешно валидирован.");
                        List<Map<String, String>> authoritiesMaps = claims.get("authorities", List.class);
                        List<SimpleGrantedAuthority> authoritiesList = authoritiesMaps.stream()
                                .map(map -> map.get("authority"))
                                .map(SimpleGrantedAuthority::new)
                                .collect(Collectors.toList());
                        return (Object) createAuth(claims.getSubject(), authoritiesList);

                    } catch (ExpiredJwtException e) {
                        log.warn("JWT токен истек.");
                        // Если токен истек, возвращаем его JTI для поиска Refresh токена
                        return e.getClaims().getId();
                    }
                }).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.error("Ошибка валидации JWT: {}", e.getMessage());
                    return Mono.empty(); // Любая другая ошибка при парсинге = невалидный токен
                });
    }

    /**
     * Извлекает JWT из cookie "access".
     */
    private String getJwtFromRequest(ServerHttpRequest request) {
        HttpCookie accessTokenCookie = request.getCookies().getFirst("access");
        if (accessTokenCookie != null) {
            return accessTokenCookie.getValue();
        }
        return null;
    }

    /**
     * Создает объект Authentication.
     */
    private Authentication createAuth(String sub, List<SimpleGrantedAuthority> authorities) {
        return new UsernamePasswordAuthenticationToken(sub, null, authorities);
    }

    /**
     * Выполняет редирект на страницу сбора фингерпринта.
     */
    private Mono<Void> redirectToFingerprint(ServerWebExchange exchange) {
        String originalUrl = exchange.getRequest().getURI().toString();
        String collectorUrl;
        try {
            collectorUrl = "/collect-fingerprint?return_url=" + URLEncoder.encode(originalUrl, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // В случае ошибки кодирования, редиректим без return_url
            collectorUrl = "/collect-fingerprint";
        }
        log.debug("JWT невалиден, но есть Refresh. Редирект на {}", collectorUrl);
        return sendRedirect(exchange, collectorUrl);
    }

    /**
     * Устанавливает редирект в ответе.
     */
    private Mono<Void> sendRedirect(ServerWebExchange exchange, String location) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.SEE_OTHER);
        response.getHeaders().setLocation(URI.create(location));
        return response.setComplete();
    }
}