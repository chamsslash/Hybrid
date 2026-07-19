package com.example.springexample;

import com.example.springexample.Utils.JwtKeyProvider;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtCheckControllerTest {

    private KeyPair keyPair;
    private JwtCheckController controller;
    private RedisTemplate<String, String> redisTemplate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();

        String publicPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
        JwtKeyProvider keyProvider = new JwtKeyProvider(publicPem);

        redisTemplate = mock(RedisTemplate.class);
        controller = new JwtCheckController(keyProvider, redisTemplate);
    }

    private String buildToken(String sid, Date expiration) {
        return Jwts.builder()
                .subject("42")
                .id("access-jti")
                .claim("sid", sid)
                .claim("authorities", List.of(Map.of("authority", "USER")))
                .expiration(expiration)
                .signWith(keyPair.getPrivate())
                .compact();
    }

    private Date inOneHour() {
        return new Date(System.currentTimeMillis() + 3_600_000);
    }

    @Test
    void validTokenWithSessionReturns200AndHeaders() {
        when(redisTemplate.hasKey("RefreshSession:sid-1")).thenReturn(true);

        ResponseEntity<?> resp = controller.jwtCheckProcess("Bearer " + buildToken("sid-1", inOneHour()), null);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("42", resp.getHeaders().getFirst("X-User-ID"));
        assertEquals("access-jti", resp.getHeaders().getFirst("X-Jti"));
        assertEquals("sid-1", resp.getHeaders().getFirst("X-Sid"));
        assertEquals("[{\"authority\":\"USER\"}]", resp.getHeaders().getFirst("X-Authorities"));
    }

    @Test
    void missingRefreshSessionReturns401() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        ResponseEntity<?> resp = controller.jwtCheckProcess("Bearer " + buildToken("sid-1", inOneHour()), null);

        assertEquals(401, resp.getStatusCode().value());
    }

    @Test
    void expiredTokenReturns401WithJti() {
        ResponseEntity<?> resp = controller.jwtCheckProcess(
                "Bearer " + buildToken("sid-1", new Date(System.currentTimeMillis() - 10_000)), null);

        assertEquals(401, resp.getStatusCode().value());
        assertEquals("access-jti", resp.getHeaders().getFirst("X-Jti"));
    }

    @Test
    void garbageTokenReturns401() {
        assertEquals(401, controller.jwtCheckProcess("Bearer not-a-jwt", null).getStatusCode().value());
    }

    @Test
    void missingTokenReturns401() {
        assertEquals(401, controller.jwtCheckProcess(null, null).getStatusCode().value());
    }

    @Test
    void tokenWithoutSidReturns401() {
        String token = Jwts.builder()
                .subject("42")
                .id("access-jti")
                .expiration(inOneHour())
                .signWith(keyPair.getPrivate())
                .compact();

        assertEquals(401, controller.jwtCheckProcess("Bearer " + token, null).getStatusCode().value());
    }
}
