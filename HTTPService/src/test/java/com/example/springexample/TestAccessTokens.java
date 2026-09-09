package com.example.springexample;

import io.jsonwebtoken.Jwts;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Хелпер для тестов, которым нужен НАСТОЯЩИЙ подписанный access-JWT (beads 1fs).
 *
 * Подпись здесь не мокается принципиально: фильтры больше не берут личность из
 * X-User-ID, а проверяют RSA-подпись через AccessTokenVerifier, и тест обязан
 * стеречь именно это. С Mockito-моком верификатора «подделанный заголовок против
 * валидного токена» перестал бы что-либо доказывать — мок вернул бы что угодно.
 *
 * FOREIGN_KEYS — вторая пара ключей: ей подписываются токены, которые верификатор
 * обязан отвергнуть (подпись не сходится с JWT_PUBLIC_KEY_PEM).
 */
final class TestAccessTokens {

    private static final KeyPair KEYS = generate();
    private static final KeyPair FOREIGN_KEYS = generate();

    private TestAccessTokens() {
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сгенерировать тестовую пару ключей", e);
        }
    }

    /** PEM публичного ключа в том же виде, в каком приходит JWT_PUBLIC_KEY_PEM. */
    static String publicKeyPem() {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(KEYS.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }

    /** Токен, подписанный «нашим» ключом: authorities в формате [{"authority":"USER"}], как у AuthService. */
    static String bearerFor(String userId, String... authorities) {
        return "Bearer " + sign(KEYS, userId, authorities);
    }

    /** Токен с валидной структурой, но подписанный чужим ключом — верификатор обязан его отвергнуть. */
    static String bearerSignedByForeignKey(String userId, String... authorities) {
        return "Bearer " + sign(FOREIGN_KEYS, userId, authorities);
    }

    private static String sign(KeyPair keys, String userId, String... authorities) {
        List<Map<String, String>> claim = List.of(authorities).stream()
                .map(a -> Map.of("authority", a))
                .toList();
        return Jwts.builder()
                .subject(userId)
                .claim("authorities", claim)
                .claim("sid", "test-sid")
                .signWith(keys.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }
}
