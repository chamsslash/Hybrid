package com.example.springexample.Utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Валидирует access-JWT по публичному ключу из JWT_PUBLIC_KEY_PEM.
 * Используется STOMP-интерцептором (CONNECT с Authorization-заголовком),
 * где нет ingress auth_request.
 */
@Slf4j
@Component
public class AccessTokenVerifier {

    private final PublicKey publicKey;

    public AccessTokenVerifier(
            @org.springframework.beans.factory.annotation.Value("${JWT_PUBLIC_KEY_PEM:}") String pem) {
        if (!StringUtils.hasText(pem)) {
            throw new IllegalStateException("JWT_PUBLIC_KEY_PEM is not set");
        }
        this.publicKey = parsePublicKey(pem);
    }

    /**
     * @param bearerOrToken значение вида "Bearer xxx" либо сам токен
     * @return Authentication с userId-принципалом и authorities, либо null если токен невалиден
     */
    public Authentication verify(String bearerOrToken) {
        if (!StringUtils.hasText(bearerOrToken)) {
            return null;
        }
        String token = bearerOrToken.startsWith("Bearer ")
                ? bearerOrToken.substring(7)
                : bearerOrToken;
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String userId = claims.getSubject();
            if (!StringUtils.hasText(userId)) {
                return null;
            }
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            Object raw = claims.get("authorities");
            if (raw instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof Map<?, ?> map && map.get("authority") != null) {
                        authorities.add(new SimpleGrantedAuthority(map.get("authority").toString()));
                    } else if (entry != null) {
                        authorities.add(new SimpleGrantedAuthority(entry.toString()));
                    }
                }
            }
            return new UsernamePasswordAuthenticationToken(userId, null, authorities);
        } catch (Exception e) {
            log.debug("Access token rejected: {}", e.getMessage());
            return null;
        }
    }

    private static PublicKey parsePublicKey(String rawPem) {
        try {
            String cleaned = rawPem.trim()
                    .replace("\\n", "\n")
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] decoded = Base64.getDecoder().decode(cleaned);
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse JWT public key", e);
        }
    }
}
