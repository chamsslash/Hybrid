package com.example.springexample;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class RedisOauth2AuthorizedClientService implements OAuth2AuthorizedClientService {
    @Autowired
    ClientRegistrationRepository clientRegistrationRepository;
    @Autowired
    RedisTemplate<String, String> redisTemplate;

    private  Gson gson= new GsonBuilder().registerTypeAdapter(Instant.class, new InstTypeAdapter()).create();
    @Override
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId, String sub) {
        String key = generateKey(sub);
        String Jsoned_oAuth2AuthorizedClientDTO=  redisTemplate.opsForValue().get(key);

        OAuth2AuthorizedClient client = gson.fromJson(Jsoned_oAuth2AuthorizedClientDTO,OAuth2AuthorizedClientDTO.class).fromDTO(clientRegistrationRepository);
        return (T) client;
    }

    @Override
    public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal) {
        String sub = getSubFromPrincipal(principal);
        String key= generateKey(sub);

        OAuth2AuthorizedClientDTO oAuth2AuthorizedClientDTO=  new OAuth2AuthorizedClientDTO();
        OAuth2AuthorizedClientDTO dto =  oAuth2AuthorizedClientDTO.toDTO(authorizedClient);
        redisTemplate.opsForValue().set(key, gson.toJson(dto), Duration.ofHours(1));

    }

    @Override
    public void removeAuthorizedClient(String clientRegistrationId, String sub) {
        String key = generateKey(sub);
        redisTemplate.delete(key);
    }
    private String generateKey( String sub) {
        return "oauth2:authorized_client:"  + ":" + sub;
    }
    private String getSubFromPrincipal(Authentication principal) {
            String subName = principal.getName();
            if (subName != null) {
                return subName;
            }else throw new IllegalArgumentException("sub is not provided");
        }



}
