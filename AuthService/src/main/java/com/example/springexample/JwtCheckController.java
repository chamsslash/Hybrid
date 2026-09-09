package com.example.springexample;

import com.example.springexample.Utils.JwtKeyProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.security.PublicKey;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class JwtCheckController {

    private final JwtKeyProvider jwtKeyProvider;
    private final RedisTemplate<String, String> redisTemplate;

    @GetMapping("/jwtcheck")
    public ResponseEntity<?> jwtCheckProcess(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @CookieValue(value = "access", required = false) String accessCookie) {

        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else if (StringUtils.hasText(accessCookie)) {
            token = accessCookie;
        }
        if (!StringUtils.hasText(token)) {
            log.warn("Токен отсутствует в Authorization и cookie.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            PublicKey publicKey = jwtKeyProvider.getPublicKey();
            Claims claims = Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String userId = claims.getSubject();
            String jti = claims.getId();
            String sid = claims.get("sid", String.class);
            if (!StringUtils.hasText(sid)) {
                log.warn("JWT токен без sid.");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            if (!refreshSessionExists(sid)) {
                log.warn("Refresh session отсутствует для sid: {}", sid);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            List<Map<String, String>> authoritiesMaps = claims.get("authorities", List.class);
            String rolesJson = authoritiesMaps == null ? "[]" : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(authoritiesMaps);

            return ResponseEntity.ok()
                    .header("X-User-ID", userId)
                    .header("X-Authorities", rolesJson)
                    .header("X-Jti", jti)
                    .header("X-Sid", sid)
                    .build();

        } catch (ExpiredJwtException e) {
            String jti = e.getClaims().getId();
            String sid = e.getClaims().get("sid", String.class);
            log.warn("JWT токен истек. JTI: {}", jti);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header("X-Jti", jti)
                    .header("X-Sid", sid == null ? "" : sid)
                    .build();

        } catch (Exception e) {
            log.error("Ошибка валидации JWT: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    private boolean refreshSessionExists(String sid) {
        if (!StringUtils.hasText(sid)) {
            return false;
        }
        Boolean exists = redisTemplate.hasKey("RefreshSession:" + sid);
        return Boolean.TRUE.equals(exists);
    }
}
