package com.example.springexample;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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

import java.util.ArrayList;
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

    // Origin'ы, которые из ingressHost не выводятся, через запятую (beads ybg). Раньше
    // http://localhost стоял в списке литералом — на публичном хосте он уже не «локальная
    // разработка», а просто лишний разрешённый источник, который никак не отключить.
    // Пустое значение означает «дополнительных нет».
    @Value("${EXTRA_ALLOWED_ORIGINS:http://localhost}")
    private String extraAllowedOrigins;

    /**
     * Отдельная цепочка для эндпоинтов актуатора (beads c2k).
     *
     * Нужна потому, что management.server.port поднимает ДОЧЕРНИЙ контекст, а бины
     * родительского он наследует — включая filterChain ниже. Та цепочка не имеет
     * securityMatcher и закрывает .anyRequest().authenticated(), поэтому запрос к
     * /actuator/health/readiness на порту 8090 уходил в oauth2Login и отвечал
     * СТРАНИЦЕЙ ВХОДА GOOGLE. Ловилось живьём: wget по podIP:8090 возвращал HTML
     * accounts.google.com вместо {"status":"UP"}.
     *
     * Последствия без этой цепочки: Prometheus получал бы редирект вместо метрик,
     * а readinessProbe никогда не увидела бы 200 и держала под вечно неготовым.
     *
     * permitAll здесь безопасен именно из-за разделения портов: 8090 не публикуется
     * ни в Service, ни в ingress, поэтому эндпоинт достижим только изнутри кластера.
     * Ровно ради этого свойства порт и разделяли — на общем порту пришлось бы
     * выводить актуатор из-под Security точечными исключениями, что и стало
     * дырой в HTTPService.
     *
     * HIGHEST_PRECEDENCE обязателен: цепочки проверяются по порядку, и без него
     * первой сматчилась бы catch-all цепочка ниже.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

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
        List<String> origins = new ArrayList<>(List.of(
                "http://" + ingressHost,
                "https://" + ingressHost
        ));
        for (String extra : extraAllowedOrigins.split(",")) {
            String trimmed = extra.trim();
            if (!trimmed.isEmpty() && !origins.contains(trimmed)) {
                origins.add(trimmed);
            }
        }
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET","POST"));

        // Разрешаем все стандартные и ваши кастомные заголовки
        configuration.setAllowedHeaders(List.of( "Content-Type","Accept","X-Fingerprint","X-SecureUUID","X-Client-Meta"));




        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}
