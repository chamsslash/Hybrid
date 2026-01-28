package com.example.springexample.Utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Загружает публичный ключ JWT из переменной окружения JWT_PUBLIC_KEY_PEM.
 * Поддерживает PEM с экранированными \\n.
 */
@Component
public class JwtKeyProvider {

    private final PublicKey publicKey;

    public JwtKeyProvider(@Value("${JWT_PUBLIC_KEY_PEM:}") String publicKeyPem) {
        if (publicKeyPem == null || publicKeyPem.isBlank()) {
            throw new IllegalStateException("JWT_PUBLIC_KEY_PEM is not set");
        }
        try {
            String normalized = publicKeyPem
                    .replace("\\n", "\n")
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] decoded = Base64.getDecoder().decode(normalized);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            this.publicKey = keyFactory.generatePublic(spec);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse JWT public key", ex);
        }
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }
}
