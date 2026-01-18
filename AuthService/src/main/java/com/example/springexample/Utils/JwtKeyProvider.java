package com.example.springexample.Utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Загружает публичный ключ для валидации JWT из переменной окружения.
 */
@Component
public class JwtKeyProvider {

    private final PublicKey publicKey;

    public JwtKeyProvider(@Value("${JWT_PUBLIC_KEY_PEM:}") String publicKeyPem) {
        String normalized = normalizePem(publicKeyPem);
        this.publicKey = buildPublicKey(normalized);
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }

    private String normalizePem(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("JWT_PUBLIC_KEY_PEM is not set");
        }
        String cleaned = raw.trim();
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\"")) ||
                (cleaned.startsWith("'") && cleaned.endsWith("'")) ||
                (cleaned.startsWith("`") && cleaned.endsWith("`"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        cleaned = cleaned.replace("\\n", "\n");
        cleaned = cleaned.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");

        if (cleaned.contains("REPLACE_ME") || cleaned.isEmpty()) {
            throw new IllegalStateException("JWT_PUBLIC_KEY_PEM is not a valid PEM (looks like placeholder)");
        }
        return cleaned;
    }

    private PublicKey buildPublicKey(String base64Body) {
        try {
            byte[] decoded = decodeBase64(base64Body);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePublic(spec);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse JWT public key", ex);
        }
    }

    private byte[] decodeBase64(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException ignored) {
            // Fallback for URL-safe base64 (allows '-' and '_')
            return Base64.getUrlDecoder().decode(value);
        }
    }
}
