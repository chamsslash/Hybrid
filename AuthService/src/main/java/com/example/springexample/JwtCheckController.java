package com.example.springexample;

import com.example.springexample.Utils.JwtKeyProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @GetMapping("/jwtcheck")
    public ResponseEntity<?> jwtCheckProcess(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @CookieValue(value = "access", required = false) String accessCookie) {

        String resolvedAuthHeader = authHeader;
        if ((resolvedAuthHeader == null || !resolvedAuthHeader.startsWith("Bearer "))
                && StringUtils.hasText(accessCookie)) {
            resolvedAuthHeader = "Bearer " + accessCookie;
        }

        if (resolvedAuthHeader == null || !resolvedAuthHeader.startsWith("Bearer ")) {
            log.warn("Запрос без заголовка Authorization или некорректный формат.");
            return ResponseEntity.ok().build();
        }

        String token = resolvedAuthHeader.substring(7);
        if (!StringUtils.hasText(token)) {
            log.warn("Токен в заголовке пустой.");
            return ResponseEntity.ok().build();
        }
        try {
            PublicKey publicKey = jwtKeyProvider.getPublicKey();
            Claims claims = Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            String userId = claims.getSubject();
            String jti = claims.getId();
            List<Map<String, String>> authoritiesMaps = claims.get("authorities", List.class);
            String rolesJson = authoritiesMaps == null ? "[]" : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(authoritiesMaps);

            return ResponseEntity.ok()
                    .header("X-User-ID", userId)
                    .header("X-Authorities", rolesJson)
                    .header("X-Jti", jti)
                    .build();

        } catch (ExpiredJwtException e) {
            String jti = e.getClaims().getId();
            log.warn("JWT токен истек. JTI: {}", jti);
            return ResponseEntity.ok()
                    .header("X-Jti", jti)
                    .build();

        } catch (Exception e) {
            log.error("Ошибка валидации JWT: {}", e.getMessage());
            return ResponseEntity.ok().build();
        }
    }
}
