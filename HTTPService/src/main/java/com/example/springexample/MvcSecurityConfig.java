package com.example.springexample; // Убедитесь, что пакет правильный

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.support.RequestDataValueProcessor;

import java.util.List;


@RequiredArgsConstructor
@Configuration
@EnableWebSecurity // Аннотация ТОЛЬКО для MVC
@Order(2) // Низкий приоритет
public class MvcSecurityConfig  {

    private final MvcJwtAuthFilter mvcJwtAuthFilter;

    @Bean(name = "requestDataValueProcessor")
    @Primary
    public RequestDataValueProcessor requestDataValueProcessor() {
        return new CsrfRequestDataValueProcessor();
    }

    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return (web) -> {
//            web.ignoring().requestMatchers(new AntPathRequestMatcher("/reactive/**"));
            web.ignoring().requestMatchers(new AntPathRequestMatcher("/actuator/prometheus"));
        };

    }
    @Bean
    public SecurityFilterChain mvcFilterChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setSecure(false);
        CsrfTokenRequestAttributeHandler attributeHandler = new CsrfTokenRequestAttributeHandler();

        http.
        sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        )
                .requestCache(RequestCacheConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                new AntPathRequestMatcher("/js/**"),
                                new AntPathRequestMatcher("/css/**"),
                                new AntPathRequestMatcher("/images/**"),
                                new AntPathRequestMatcher("/callback.js"),
                                new AntPathRequestMatcher("/*.js"),
                                new AntPathRequestMatcher("/views/**"),
                                new AntPathRequestMatcher("/*.css"),
                                new AntPathRequestMatcher("/error"),
                                new AntPathRequestMatcher("/static/**"),
                                new AntPathRequestMatcher("/actuator/prometheus"),
                                new AntPathRequestMatcher("/verifylogin"),
                                new AntPathRequestMatcher("/welcome"),
                                new AntPathRequestMatcher("/registerpage"),
                                new AntPathRequestMatcher("/createchatpage"),
                                new AntPathRequestMatcher("/"),
                                new AntPathRequestMatcher("/reactive/**"),
                                new AntPathRequestMatcher("/authcallback"),
                                new AntPathRequestMatcher("/collect-fingerprint"),
                                new AntPathRequestMatcher("/exchangeTokens")
                        ).permitAll()

                        .anyRequest().authenticated()
                )
                .addFilterBefore(mvcJwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        // SPA-API отдаёт голый 401 — клиент сам делает refresh+retry (beads 59)
                        .defaultAuthenticationEntryPointFor(
                                new org.springframework.security.web.authentication.HttpStatusEntryPoint(
                                        org.springframework.http.HttpStatus.UNAUTHORIZED),
                                new AntPathRequestMatcher("/api/**"))
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/welcome"))
                )

                .csrf(csrf -> csrf
                        .csrfTokenRepository(repo)
                        .csrfTokenRequestHandler(attributeHandler)
                        // Bearer-API не подвержен CSRF (нет cookie-аутентификации)
                        .ignoringRequestMatchers(new AntPathRequestMatcher("/api/**"))
                );

        return http.build();
    }



}
