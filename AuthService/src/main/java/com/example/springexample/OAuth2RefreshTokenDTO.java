package com.example.springexample;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
@Data
@AllArgsConstructor
@NoArgsConstructor
public class OAuth2RefreshTokenDTO {
    private String tokenValue;
    private Instant issuedAt;
}
