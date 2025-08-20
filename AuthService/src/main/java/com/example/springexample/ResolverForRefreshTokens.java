package com.example.springexample;


import com.example.springexample.Utils.StateResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
@Component
public class ResolverForRefreshTokens implements OAuth2AuthorizationRequestResolver {

    private  final StateResolver stateResolver;
    private final ClientRegistrationRepository clientRegistrationRepository;
    private final DefaultOAuth2AuthorizationRequestResolver defaultResolver;
    public ResolverForRefreshTokens(ClientRegistrationRepository clientRegistrationRepository,StateResolver stateresolve) {
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.defaultResolver =new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository,
                "/oauth2/authorization"
        );
        this.stateResolver = stateresolve;

    }

    public OAuth2AuthorizationRequest createAuthorizationRequest(HttpServletRequest httpRequest,OAuth2AuthorizationRequest request,String state) {
        if (request == null) return null;
        Map<String, Object> additionalParams = new HashMap<>(request.getAdditionalParameters());
        return OAuth2AuthorizationRequest.from(request).state(state)
                .additionalParameters(additionalParams)
                .build();
    }



    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        OAuth2AuthorizationRequest original = defaultResolver.resolve(request);
        return original;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        OAuth2AuthorizationRequest original = defaultResolver.resolve(request);
        return  original;
    }




}
