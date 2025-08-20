package com.example.springexample;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;

@Slf4j
@Service
public class JWT_Service {
    private static final String secret = "sunnytyans";
    private static final long expire = 86400000;
    public String createToken(String  Id,String access) {
        Algorithm algorithm = Algorithm.HMAC256(secret);
        String jwt = JWT.create().withSubject(Id).withClaim("AccessToken",access).withExpiresAt(Date.from(Instant.now().plusSeconds(expire))).sign(algorithm);
        return jwt;
    }
    public Long  validate(String token) {
        try {
            Algorithm algorithm = Algorithm.HMAC256(secret);
            JWTVerifier verifier = JWT.require(algorithm).build();
            DecodedJWT decodedJWT = verifier.verify(token);
            log.info("JWT verified successfully");
            Long id  = Long.valueOf(decodedJWT.getSubject());
            log.info("JWT subject is obtained");
            return id;

        } catch (Exception e) {
            log.error(e.getMessage());
            return null;
        }
    }
}
