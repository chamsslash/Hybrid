package com.example.springexample;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;

import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@EnableWebSecurity
@Configuration
@RequiredArgsConstructor
public class SecurityConfiguration {
    private final AuthSuccessHandler  authSuccessHandler;
    private final  CustomOAuth2UserService customOAuth2UserService;
        private  final ResolverForRefreshTokens customresolverForRefreshTokens;
    private final RedisOauth2AuthorizedClientService authorizedClientService;
    private  final  RedisAuthorizationRequestRepository repository;
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http    .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth->auth
                        .requestMatchers(
                                new AntPathRequestMatcher("/startauth"),
                                new AntPathRequestMatcher("/jwtcheck")
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth ->{
                    oauth.successHandler(authSuccessHandler);
                    oauth.authorizedClientService(authorizedClientService);
                    oauth.userInfoEndpoint(oauth2->oauth2.userService(customOAuth2UserService));
                    oauth.authorizationEndpoint(customizer->
                            customizer.authorizationRequestRepository(repository)
                    );
                    oauth.authorizationEndpoint(endpoint->{
                        endpoint.authorizationRequestResolver(customresolverForRefreshTokens);
                    });

                });

               return http.build();
    }
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Разрешаем запросы с ingress-домена и локальной разработки.
        configuration.setAllowedOrigins(List.of(
                "http://localhost",
                "http://myapp.local",
                "https://myapp.local"
        ));

        // Разрешаем методы
        configuration.setAllowedMethods(List.of("POST","GET"));

        // Разрешаем все стандартные и ваши кастомные заголовки
        configuration.setAllowedHeaders(List.of( "Content-Type","Accept","X-Fingerprint","X-SecureUUID","X-Client-Meta"));




        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}
