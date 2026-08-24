package com.example.springexample;

import com.example.grpc.DataTransferService;
import com.example.springexample.Services.AuthGrpc;
import com.example.springexample.Utils.AccessTokenVerifier;
import com.example.springexample.Utils.ParsingDataService;
import com.example.springexample.Utils.TokenException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.Http;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class MvcJwtAuthFilter extends OncePerRequestFilter {
    public record  jwt_refresh_auths(String jwt,String refresh,String auths){}

    private final AccessTokenVerifier accessTokenVerifier;

    // Список публичных путей
    private static final List<String> PUBLIC_PATHS = List.of(
            "/reactive/login", "/reactive/register", "/welcome", "/", "/authcallback",
            "/exchangeTokens", "/actuator/**",
            "/public/**",
            "/static/**",
            "/css/**",
            "/images/**",
            "/reactive/**",
            "**.js",
            "/**/*.js", "/verifylogin",
            // SockJS/STOMP-хендшейк публичен (auth на STOMP CONNECT, beads 58/59)
            "/*Conn/**", "/*Conn"
    );
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return PUBLIC_PATHS.stream().anyMatch(pattern -> pathMatcher.match(pattern, uri));
    }

    // /api/* и /AiAssist возвращают Callable<T> (реактивная цепочка внутри) — Spring
    // обрабатывает их через async-диспетчинг. SecurityContextHolder хранит
    // Authentication в ThreadLocal, а OncePerRequestFilter по умолчанию НЕ
    // перезапускается на async-диспетчинге (shouldNotFilterAsyncDispatch()==true).
    // Из-за этого на втором (async) проходе цепочки фильтров SecurityContext пуст,
    // и AuthorizationFilter (который на async-диспетчинге проверяет заново) отдаёт
    // 401 при валидном токене. Заголовок Authorization на запросе всё ещё есть,
    // поэтому переустанавливаем Authentication и на async-диспетчинге (beads 6i5).
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        // Пропускаем публичные пути
        if (isPublicPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        // Личность — из подписи access-JWT, а не из X-User-ID/X-Authorities (beads 1fs).
        //
        // Раньше Authentication строился прямо из этих заголовков, и доверенными их делала
        // ровно одна вещь — аннотация auth_request на ingress http-protected: nginx через
        // auth-response-headers перезаписывал клиентские значения ответом AuthService
        // /jwtcheck. На путях http-public те же заголовки шли от клиента насквозь, то есть
        // защита держалась на топологии ingress, а не на коде.
        //
        // Теперь ingress отвечает только за то, чего приложение локально сделать не может, —
        // за ревокацию (жива ли refresh-сессия по sid в Redis), а личность приложение
        // проверяет само по RSA-подписи. Принципал для легитимного трафика тот же самый:
        // /jwtcheck отдаёт X-User-ID = claims.getSubject() и X-Authorities = клейм
        // authorities — ровно то, что AccessTokenVerifier достаёт из токена локально.
        // Без валидного Bearer контекст не создаётся, как и раньше без заголовков.
        Authentication auth = accessTokenVerifier.verify(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (auth != null) {
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        filterChain.doFilter(request, response);
    }



    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }}
