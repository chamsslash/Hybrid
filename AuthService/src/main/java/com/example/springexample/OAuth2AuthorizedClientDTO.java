package com.example.springexample;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
@AllArgsConstructor
@NoArgsConstructor
@Data
public class OAuth2AuthorizedClientDTO {
    private String clientRegistrationId;
    private String principalName;
    private OAuth2AccessTokenDTO accessToken;
    private OAuth2RefreshTokenDTO refreshToken;
    public OAuth2AuthorizedClient fromDTO(ClientRegistrationRepository repo){
        ClientRegistration registration = repo.findByRegistrationId(this.clientRegistrationId);
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,this.accessToken.getTokenValue(),this.accessToken.getIssuedAt(),this.accessToken.getExpiresAt(),this.accessToken.getScopes());
        OAuth2RefreshToken refreshToken = null;
        if (this.refreshToken != null) {
            refreshToken = new OAuth2RefreshToken(
                    this.refreshToken.getTokenValue(),
                    this.refreshToken.getIssuedAt()
            );
        }
        return new OAuth2AuthorizedClient(registration, this.principalName, accessToken, refreshToken);


    }
    public OAuth2AuthorizedClientDTO toDTO(OAuth2AuthorizedClient client) {
        OAuth2AccessToken accessToken = client.getAccessToken();
        OAuth2RefreshToken refreshToken = client.getRefreshToken();

        OAuth2AccessTokenDTO accessTokenDTO = new OAuth2AccessTokenDTO(
                accessToken.getTokenValue(),
                accessToken.getIssuedAt(),
                accessToken.getExpiresAt(),
                accessToken.getScopes()
        );

        OAuth2RefreshTokenDTO refreshTokenDTO = null;
        if (refreshToken != null) {
            refreshTokenDTO = new OAuth2RefreshTokenDTO(
                    refreshToken.getTokenValue(),
                    refreshToken.getIssuedAt()
            );
        }

        return new OAuth2AuthorizedClientDTO(
                client.getClientRegistration().getRegistrationId(),
                client.getPrincipalName(),
                accessTokenDTO,
                refreshTokenDTO
        );
    }
}
