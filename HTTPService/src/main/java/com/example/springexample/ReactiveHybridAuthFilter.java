package com.example.springexample;

import com.example.springexample.Utils.TokensResolver;
import com.google.gson.Gson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
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

        String headerUserId = request.getHeaders().getFirst("X-User-ID");
        String headerAuths = request.getHeaders().getFirst("X-Authorities");
        if (StringUtils.hasText(headerUserId) && StringUtils.hasText(headerAuths)) {
            List<SimpleGrantedAuthority> authorities = parseAuthorities(headerAuths);
            Authentication auth = createAuth(headerUserId, authorities);
            return chain.filter(exchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
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

    /**
     * Создает объект Authentication.
     */
    private Authentication createAuth(String sub, List<SimpleGrantedAuthority> authorities) {
        return new UsernamePasswordAuthenticationToken(sub, null, authorities);
    }

    private List<SimpleGrantedAuthority> parseAuthorities(String headerAuths) {
        try {
            List<Object> raw = gson.fromJson(headerAuths, List.class);
            if (raw == null) {
                return List.of();
            }
            List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
            for (Object entry : raw) {
                if (entry instanceof Map) {
                    Object authority = ((Map<?, ?>) entry).get("authority");
                    if (authority != null) {
                        authorities.add(new SimpleGrantedAuthority(authority.toString()));
                        continue;
                    }
                }
                authorities.add(new SimpleGrantedAuthority(entry.toString()));
            }
            return authorities;
        } catch (Exception e) {
            log.warn("Не удалось распарсить X-Authorities: {}", e.getMessage());
            return List.of();
        }
    }

}
