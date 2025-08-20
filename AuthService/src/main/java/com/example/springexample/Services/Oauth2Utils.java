package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import com.example.springexample.CustomOAuth2User;
import com.nimbusds.jose.shaded.gson.JsonElement;
import com.nimbusds.jose.shaded.gson.JsonObject;
import com.nimbusds.jose.shaded.gson.JsonParser;

import io.grpc.stub.StreamObserver;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.*;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
@Slf4j
@Service
@ConfigurationProperties(prefix = "security.oauth2.client.registration.google")
public class Oauth2Utils {
    RestTemplate restTemplate = new RestTemplate();

    private String clientId;

    private String clientSecret;
    public CustomOAuth2User getUserByAccessToken(OAuth2AccessToken accessToken) throws IOException {
        String userInfoUrl = "https://www.googleapis.com/oauth2/v1/userinfo?access_token=" + accessToken.getTokenValue();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken.getTokenValue());
        HttpEntity<String> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<String> responseEntity = restTemplate.exchange(userInfoUrl, HttpMethod.GET, entity, String.class);
            String json_resp = responseEntity.getBody();
            if (!json_resp.isEmpty()) {
                JsonObject dataObject = JsonParser.parseString(json_resp).getAsJsonObject();
                CustomOAuth2User user = new CustomOAuth2User();
                user.setName(dataObject.get("name").getAsString());
                return user;
            }
        }catch (Exception e) {
            log.error("Error in trading access token", e.getMessage());

            return null;
        }
        log.error("error in trading access token idk why");

        return null;
    }
    public OAuth2AccessToken exchangeTokens(String refresh, StreamObserver<DataTransferService.AccessResponse> responseObserver) throws IOException {
        try{

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("client_id", clientId);
            params.add("client_secret", clientSecret);
            params.add("grant_type", "refresh_token");
            params.add("refresh_token", refresh);
            String tokenUrl = "https://oauth2.googleapis.com/token";
            String readybody = UriComponentsBuilder.newInstance()
                    .queryParams(params)
                    .build()
                    .encode()
                    .getQuery();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            HttpEntity<String> request = new HttpEntity<>(readybody, headers);

            ResponseEntity<String> response1 = restTemplate.exchange(
                    tokenUrl,
                    HttpMethod.POST,
                    request,
                    String.class
            );
            String body = response1.getBody();
            JsonObject dataObject =  JsonParser.parseString(body).getAsJsonObject();

            if ( body!= null &&dataObject.get("access_token") != null ) {
                OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                        dataObject.get("access_token").getAsString(),
                        Instant.now(),Instant.now().plusSeconds(dataObject.get("expires_in").getAsInt()));
                return accessToken;
            }else return null;
        } catch (Exception e) {
            responseObserver.onNext(DataTransferService.AccessResponse.newBuilder().build());//empty message
            return null;
        }
    }


}
