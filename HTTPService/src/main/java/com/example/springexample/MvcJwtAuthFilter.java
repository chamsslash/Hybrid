package com.example.springexample;

import com.example.grpc.DataTransferService;
import com.example.springexample.Services.AuthGrpc;
import com.example.springexample.Utils.ParsingDataService;
import com.example.springexample.Utils.TokenException;
import com.example.springexample.Utils.TokensResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.Http;
import com.google.gson.Gson;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
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
    // Ваши зависимости остаются
    private final TokensResolver tokensResolver;
    private Gson gson= new Gson();

    // Список публичных путей
    private static final List<String> PUBLIC_PATHS = List.of(
            "/reactive/login", "/reactive/register", "/welcome", "/authcallback",
            "/collect-fingerprint", "/exchangeTokens","/actuator/**",
            "/public/**",
            "/static/**",
            "/reactive/**",
            "**.js",
            "/**/*.js","/verifylogin"
    );
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        AntPathMatcher pathMatcher = new AntPathMatcher();
        String uri = request.getRequestURI();
        return PUBLIC_PATHS.stream().anyMatch(pattern -> pathMatcher.match(pattern, uri));    }
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

        String auths = request.getHeader("X-Authorities");
        String userId = request.getHeader("X-User-ID");
        if(  auths!=null &&userId!=null && !userId.isEmpty() && !auths.isEmpty()){
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            List<String>authsList =  gson.fromJson(auths,List.class);
            authsList.stream().map(auth->{
                return authorities.add(new SimpleGrantedAuthority(auth));
            });
            if(!userId.isEmpty() && userId!=null ){
                Authentication auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
                filterChain.doFilter(request, response);
                return;
            }
        }



        String jti =  request.getHeader("X-Jti");
        String Refresh = tokensResolver.getRefreshByJti(jti);
        // --- Шаг 2: JWT невалиден. Проверяем наличие Refresh Token ---
        if (Objects.isNull(Refresh)) {
            filterChain.doFilter(request, response);
            return;
        }
        log.debug("JWT невалиден, но Refresh Token доступен. Возвращаем статус {}.", 419);
        response.setStatus(419); //refresh required status
//        String originalUrl = request.getRequestURI() +
//                (request.getQueryString() != null ? "?" + request.getQueryString() : "");
//        String collectorUrl = "/collect-fingerprint?return_url=" + URLEncoder.encode(originalUrl, StandardCharsets.UTF_8);
//
//        log.debug("JWT невалиден. Запускаем флоу обновления через страницу сбора фингерпринта.");
//        response.sendRedirect(collectorUrl);
    }






    private boolean isPublicPath(String path) {
        // ... (код без изменений)
        return PUBLIC_PATHS.stream().anyMatch(path::equalsIgnoreCase);
    }}