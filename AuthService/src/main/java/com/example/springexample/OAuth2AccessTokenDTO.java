package com.example.springexample;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.AnyKeyJavaClass;

import java.time.Instant;
@Data
@AllArgsConstructor
@NoArgsConstructor
public class OAuth2AccessTokenDTO {
    private String tokenValue;
    private Instant issuedAt;
    private Instant expiresAt;
    private Set<String> scopes;
}
