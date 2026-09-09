package com.example.springexample;

import com.example.springexample.Utils.AccessTokenVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class ReactiveHybridAuthFilter implements WebFilter {

    private final AccessTokenVerifier accessTokenVerifier;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    // Список публичных путей (аналогично сервлетной версии)
    private static final List<String> PUBLIC_PATHS = List.of(
            "/reactive/login", "/reactive/register", "/welcome", "/authcallback",
            // "/actuator/**" убран (beads c2k) — см. пояснение в MvcJwtAuthFilter.
            "/exchangeTokens",
            "/public/**", "/static/**", "/**/*.js", "/verifylogin"
    );

    private boolean shouldNotFilter(ServerHttpRequest request) {
        String uri = request.getURI().getPath();
        log.warn("Проверка URI для фильтра: '{}'", uri);

        return PUBLIC_PATHS.stream().anyMatch(pattern -> pathMatcher.match(pattern, uri));
    }

    // Личность берётся из подписи access-JWT, а не из X-User-ID/X-Authorities (beads 1fs).
    //
    // Раньше здесь строился Authentication прямо из этих заголовков, и доверенными их
    // делала ровно одна вещь — аннотация auth_request на ingress http-protected: nginx
    // через auth-response-headers перезаписывал клиентские значения ответом AuthService
    // /jwtcheck. На путях http-public те же заголовки шли от клиента насквозь, так что
    // защита держалась на топологии ingress, а не на коде: одна строка в
    // Helm/templates/http-ingress.yaml, переносящая путь в http-public, тихо превращала
    // X-User-ID в клиентский ввод.
    //
    // Разделение ответственности теперь такое:
    //  - ingress auth_request -> /jwtcheck остаётся и делает то, чего приложение локально
    //    сделать не может: проверяет, что refresh-сессия по sid ещё жива в Redis (ревокация);
    //  - личность приложение проверяет само по RSA-подписи (AccessTokenVerifier, тот же
    //    компонент, что и на STOMP CONNECT). Для легитимного трафика принципал не меняется:
    //    /jwtcheck отдаёт X-User-ID = claims.getSubject() и X-Authorities = клейм authorities,
    //    то есть ровно то, что верификатор достаёт из токена локально.
    //
    // nginx пробрасывает исходный Authorization в бэкенд без изменений, а SPA вешает его на
    // все защищённые запросы (static/axios.js). Без валидного Bearer аутентифицированный
    // контекст не создаётся — как и раньше при отсутствии заголовков.
    @Override
    @NonNull
    public Mono<Void> filter(ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (shouldNotFilter(request)) {
            log.info("запрос не прошел по фильтру из за того что он публичен");
            return chain.filter(exchange);
        }
        log.info("Запрос по фильтру прошел");

        Authentication auth = accessTokenVerifier.verify(
                request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (auth != null) {
            return chain.filter(exchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
        }

        return chain.filter(exchange);
    }

}
