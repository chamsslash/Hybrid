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

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class ReactiveHybridAuthFilter implements WebFilter {

    private final TokensResolver tokensResolver;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final Gson gson = new Gson();

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
        String authsHeader = request.getHeaders().getFirst("X-Authorities");
        String userIdHeader = request.getHeaders().getFirst("X-User-ID");
        if (StringUtils.hasText(userIdHeader) && StringUtils.hasText(authsHeader)) {
            List<SimpleGrantedAuthority> authorities = parseAuthorities(authsHeader);
            Authentication auth = createAuth(userIdHeader, authorities);
            return chain.filter(exchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
        }

        String jtiHeader = request.getHeaders().getFirst("X-Jti");
        if (!StringUtils.hasText(jtiHeader)) {
            return chain.filter(exchange);
        }

        return Mono.fromCallable(() -> tokensResolver.getRefreshByJti(jtiHeader))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(refreshToken -> {
                    if (Objects.nonNull(refreshToken)) {
                        return respondRefreshRequired(exchange);
                    }
                    return chain.filter(exchange);
                });
    }

    /**
     * Парсит authorities из заголовка X-Authorities.
     */
    private List<SimpleGrantedAuthority> parseAuthorities(String authsHeader) {
        List<?> rawList = gson.fromJson(authsHeader, List.class);
        if (rawList == null) {
            return List.of();
        }
        return rawList.stream()
                .map(item -> {
                    if (item instanceof java.util.Map<?, ?> map) {
                        Object value = map.get("authority");
                        return value == null ? null : value.toString();
                    }
                    return item == null ? null : item.toString();
                })
                .filter(Objects::nonNull)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
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
    private Mono<Void> respondRefreshRequired(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(419));
        return response.setComplete();
    }
}
