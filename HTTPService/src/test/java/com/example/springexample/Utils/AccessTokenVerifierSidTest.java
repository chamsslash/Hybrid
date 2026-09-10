package com.example.springexample.Utils;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * sid в Authentication.details (beads ehe). Проверяется именно перенос клейма, а не
 * валидация подписи — её стережёт JwtCheckControllerTest.
 */
class AccessTokenVerifierSidTest {

    private KeyPair keyPair;
    private AccessTokenVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        String pem = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        verifier = new AccessTokenVerifier(pem);
    }

    private String token(String subject, String sid) {
        var builder = Jwts.builder()
                .subject(subject)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000));
        if (sid != null) {
            builder.claim("sid", sid);
        }
        return builder.signWith(keyPair.getPrivate()).compact();
    }

    @Test
    void sidClaimIsExposedAsAuthenticationDetails() {
        Authentication auth = verifier.verify("Bearer " + token("42", "sid-1"));

        assertNotNull(auth);
        assertEquals("42", auth.getName());
        assertEquals("sid-1", auth.getDetails());
    }

    @Test
    void tokenWithoutSidYieldsNullDetailsButStillAuthenticates() {
        Authentication auth = verifier.verify("Bearer " + token("42", null));

        assertNotNull(auth);
        assertEquals("42", auth.getName());
        assertNull(auth.getDetails());
    }
}
