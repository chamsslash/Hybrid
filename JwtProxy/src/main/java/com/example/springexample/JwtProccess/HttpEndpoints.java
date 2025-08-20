package com.example.springexample.JwtProccess;

import com.example.springexample.Utils.DataResolver;
import com.google.gson.Gson;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.security.PublicKey;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
@RequiredArgsConstructor
@Slf4j
@Controller
public class HttpEndpoints {
    private Gson gson = new Gson();
    private final DataResolver dataResolver;
    // HttpEndpoints.java
    @GetMapping("jwtcheck")
    public ResponseEntity<?> jwtCheckProcess(@RequestHeader(value = "Authorization", required = false) String authHeader) {

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Запрос без заголовка Authorization или некорректный формат.");
            // JWT нет, возвращаем 200 OK без заголовков
            return ResponseEntity.ok().build();
        }

        String token = authHeader.substring(7);
        if (!StringUtils.hasText(token)) {
            log.warn("Токен в заголовке пустой.");
            // Пустой JWT, возвращаем 200 OK без заголовков
            return ResponseEntity.ok().build();
        }
        try {
            Map<String, Object> keys = dataResolver.loadKeys();
            if (keys == null || keys.get("public_key") == null) {
                log.error("КРИТИЧЕСКАЯ ОШИБКА: Публичный ключ не загружен.");
                // Ошибка конфигурации сервера, но для NGINX все равно возвращаем 200 OK,
                // чтобы не сломать флоу. Отсутствие заголовков будет сигналом проблемы.
                return ResponseEntity.ok().build();
            }
            Claims claims = Jwts.parser()
                    .verifyWith((PublicKey) keys.get("public_key"))
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            String userId = claims.getSubject();
            String jti = claims.getId();
            List<Map<String, String>> authoritiesMaps = claims.get("authorities", List.class);
            String rolesJson = gson.toJson(authoritiesMaps);
            return ResponseEntity.ok()
                    .header("X-User-ID", userId)
                    .header("X-Authorities", rolesJson)
                    .header("X-JTI",jti)
                    .build();

        } catch (ExpiredJwtException e) {
            // --- ОСОБЫЙ СЛУЧАЙ: JWT ПРОСРОЧЕН ---
            // Нам нужно извлечь JTI, чтобы `main` сервис мог найти Refresh Token
            String jti = e.getClaims().getId();
            log.warn("JWT токен истек. JTI: {}", jti);
            // Возвращаем 200 OK и передаем ТОЛЬКО JTI
            return ResponseEntity.ok()
                    .header("X-Jti", jti)
                    .build();

        } catch (Exception e) {
            // --- ЛЮБАЯ ДРУГАЯ ОШИБКА ВАЛИДАЦИИ ---
            // (неверная подпись, неверный формат и т.д.)
            log.error("Ошибка валидации JWT: {}", e.getMessage());
            // Возвращаем 200 OK без каких-либо заголовков
            return ResponseEntity.ok().build();
        }
    }}
