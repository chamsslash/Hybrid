package com.example.springexample;

import com.example.springexample.Utils.TokensResolver;
import com.google.gson.Gson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class ReactiveHybridAuthFilter implements WebFilter {

    private final TokensResolver tokensResolver;
    private final Gson gson = new Gson();
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

        String auths = request.getHeaders().getFirst("X-Authorities");
        String userId = request.getHeaders().getFirst("X-User-ID");
        if (StringUtils.hasText(auths) && StringUtils.hasText(userId)) {
            List<SimpleGrantedAuthority> authorities = parseAuthorities(auths);
            if (!authorities.isEmpty()) {
                Authentication auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
                return chain.filter(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
            }
        }

        String jti = request.getHeaders().getFirst("X-Jti");
        if (StringUtils.hasText(jti)) {
            return Mono.fromCallable(() -> tokensResolver.getRefreshByJti(jti))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(refreshToken -> {
                        if (Objects.isNull(refreshToken)) {
                            return chain.filter(exchange);
                        }
                        exchange.getResponse().setStatusCode(HttpStatus.valueOf(419));
                        return exchange.getResponse().setComplete();
                    });
        }

        return chain.filter(exchange);
    }

    private List<SimpleGrantedAuthority> parseAuthorities(String raw) {
        try {
            List<?> parsed = gson.fromJson(raw, List.class);
            if (parsed == null) {
                return List.of();
            }
            List<String> roles = new ArrayList<>();
            for (Object entry : parsed) {
                if (entry instanceof String s && StringUtils.hasText(s)) {
                    roles.add(s);
                    continue;
                }
                if (entry instanceof Map<?, ?> map) {
                    Object val = map.get("authority");
                    if (val instanceof String s && StringUtils.hasText(s)) {
                        roles.add(s);
                    }
                }
            }
            return roles.stream()
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Не удалось распарсить X-Authorities: {}", e.getMessage());
            return List.of();
        }
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
