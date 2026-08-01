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
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.http.HttpStatus;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.support.RequestDataValueProcessor;

import java.util.LinkedHashMap;
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
        // Официальный SPA-паттерн CSRF (Spring Security): SpaCsrfTokenRequestHandler
        // резолвит токен из X-XSRF-TOKEN, а CsrfCookieFilter (ниже) материализует
        // deferred-токен в cookie на каждом ответе — иначе первый POST после
        // загрузки страницы (/exchangeTokens) отбивался 403 из-за рассинхрона (6i5).
        SpaCsrfTokenRequestHandler spaCsrfHandler = new SpaCsrfTokenRequestHandler();

        // SPA-API и AI-assist отдают голый 401 — клиент сам делает refresh+retry (beads 59).
        // Одиночный .authenticationEntryPoint(...) молча перекрывает
        // .defaultAuthenticationEntryPointFor(...), поэтому строим делегирующий
        // entry point вручную: /api/** и /AiAssist -> 401, всё остальное -> /welcome.
        AuthenticationEntryPoint unauthorizedEntryPoint = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
        LinkedHashMap<RequestMatcher, AuthenticationEntryPoint> entryPoints = new LinkedHashMap<>();
        entryPoints.put(new AntPathRequestMatcher("/api/**"), unauthorizedEntryPoint);
        entryPoints.put(new AntPathRequestMatcher("/AiAssist"), unauthorizedEntryPoint);
        DelegatingAuthenticationEntryPoint delegatingEntryPoint = new DelegatingAuthenticationEntryPoint(entryPoints);
        delegatingEntryPoint.setDefaultEntryPoint(new LoginUrlAuthenticationEntryPoint("/welcome"));

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
                                new AntPathRequestMatcher("/exchangeTokens"),
                                // SockJS/STOMP-хендшейк публичен: браузерный хендшейк не несёт
                                // Authorization, аутентификация — на STOMP CONNECT
                                // (StompAuthChannelInterceptor). Без этого GET-транспорты
                                // (/info,/websocket,/iframe.html) ловили 302, а POST-xhr — 403,
                                // и соединение падало до CONNECT (beads 58/59).
                                new AntPathRequestMatcher("/*Conn/**"),
                                new AntPathRequestMatcher("/*Conn")
                        ).permitAll()

                        .anyRequest().authenticated()
                )
                .addFilterBefore(mvcJwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(delegatingEntryPoint))

                .csrf(csrf -> csrf
                        .csrfTokenRepository(repo)
                        .csrfTokenRequestHandler(spaCsrfHandler)
                        // Bearer-API не подвержен CSRF (нет cookie-аутентификации).
                        // SockJS POST-транспорты (/*Conn/.../xhr, xhr_streaming) — тоже:
                        // это часть публичного хендшейка, auth на STOMP CONNECT.
                        .ignoringRequestMatchers(
                                new AntPathRequestMatcher("/api/**"),
                                new AntPathRequestMatcher("/*Conn/**"))
                )
                // Материализует XSRF-TOKEN cookie на каждом ответе (после CsrfFilter,
                // который кладёт deferred-токен в атрибут запроса).
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);

        return http.build();
    }



}
