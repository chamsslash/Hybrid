package com.example.springexample;

import java.time.Instant;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class OAuth2AccessTokenDTO {

    private String tokenValue;
    private Instant issuedAt;
    private Instant expiresAt;
    private Set<String> scopes;
}
