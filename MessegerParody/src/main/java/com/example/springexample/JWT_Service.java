package com.example.springexample;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.interfaces.DecodedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.auth0.jwt.algorithms.Algorithm;
@Slf4j
@Component
public class JWT_Service {
    private static final String secret = "sunnytyans";
    private static final long expire = 86400000;
    public String  validate(String token) {
        try {
            Algorithm algorithm = Algorithm.HMAC256(secret);
            JWTVerifier verifier = JWT.require(algorithm).build();
            DecodedJWT decodedJWT = verifier.verify(token);
            log.info("JWT verified successfully");
            String uname = decodedJWT.getSubject();
            log.info("JWT subject is obtained");
            return uname;

        } catch (Exception e) {
            log.error(e.getMessage());
            return null;
        }
    }
}
