package com.example.springexample;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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

    // Фактический хост, на котором обслуживается приложение (тот же, что в
    // OAuth redirect-uri в application.yml). Дефолт совпадает с Helm ingress.host.
    @Value("${INGRESS_HOST:myapp.localtest.me}")
    private String ingressHost;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http    .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                new AntPathRequestMatcher("/startauth"),
                                new AntPathRequestMatcher("/jwtcheck")
                        ).permitAll()
                        .anyRequest().authenticated())
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

        // Разрешённые Origin строятся из фактического ingress-хоста (INGRESS_HOST),
        // а не хардкодятся. Раньше список содержал только myapp.local/localhost, тогда
        // как приложение реально обслуживается на myapp.localtest.me — из-за рассинхрона
        // любой same-origin POST (createchat, /AiAssist) слал Origin, который nginx
        // проксировал в auth_request-сабреквест к /jwtcheck, а Spring CorsFilter отбивал
        // его 403 ДО контроллера (beads 2q5). GET'ы не страдали: браузер не шлёт Origin
        // на same-origin GET.
        configuration.setAllowedOrigins(List.of(
                "http://localhost",
                "http://" + ingressHost,
                "https://" + ingressHost
        ));
        configuration.setAllowedMethods(List.of("GET","POST"));

        // Разрешаем все стандартные и ваши кастомные заголовки
        configuration.setAllowedHeaders(List.of( "Content-Type","Accept","X-Fingerprint","X-SecureUUID","X-Client-Meta"));




        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}
