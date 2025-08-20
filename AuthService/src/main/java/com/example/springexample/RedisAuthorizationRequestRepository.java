package com.example.springexample;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;
@Slf4j
@Component("redisAuthorizationRequestRepository")
public class RedisAuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    private Gson gson= new Gson();
    private final RedisTemplate<String, String> redisTemplate;
    private static final String KEY_PREFIX = "oauth2_auth_request:";
    private static final int TIMEOUT_MINUTES = 10;

    public RedisAuthorizationRequestRepository(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        String state = request.getParameter("state");
        if (state == null) {
            return null;
        }

        return gson.fromJson(redisTemplate.opsForValue().get(KEY_PREFIX + state),OAuth2AuthorizationRequest.class);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest, HttpServletRequest request, HttpServletResponse response) {
        if (authorizationRequest == null) {
            // Если запрос null, возможно, нужно удалить старый
            String state =authorizationRequest.getState();
            if (state != null) {
                log.warn("Missing fp header in req on start auth-->cant validate fp,Redirecting to auth(welcome page)");
                throw  new RuntimeException("No fp header");
            }
            return;
        }
        String key = KEY_PREFIX + authorizationRequest.getState();
        redisTemplate.opsForValue().set(key, gson.toJson(authorizationRequest), TIMEOUT_MINUTES, TimeUnit.MINUTES);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        OAuth2AuthorizationRequest savedRequest = loadAuthorizationRequest(request);
        if (savedRequest != null ) {
            redisTemplate.delete(KEY_PREFIX +savedRequest.getState());
        }
        return savedRequest;
    }
}