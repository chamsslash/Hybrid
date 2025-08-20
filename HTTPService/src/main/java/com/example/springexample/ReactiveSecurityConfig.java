package com.example.springexample;



//import com.example.springexample.FrankensteinSecurityFilter; // Ваш фильтр
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.WebSessionServerCsrfTokenRepository;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.web.server.WebFilter;

import java.net.URI;
@EnableWebFluxSecurity
@Configuration
@RequiredArgsConstructor
public class ReactiveSecurityConfig {

    private final ReactiveHybridAuthFilter frankensteinSecurityFilter;
    private final WebFilter webfluxRequestDataValueProcessorFilter;
    @Bean
    public SecurityWebFilterChain webfluxSecurityFilterChain(ServerHttpSecurity http) {
        CookieServerCsrfTokenRepository repo = CookieServerCsrfTokenRepository.withHttpOnlyFalse();
        repo.setSecure(false);
        http.csrf(ServerHttpSecurity.CsrfSpec::disable)

                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)


                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())

                // Сначала добавляем наш фильтр
                .addFilterAt(frankensteinSecurityFilter, SecurityWebFiltersOrder.HTTP_HEADERS_WRITER)
                .addFilterAt(webfluxRequestDataValueProcessorFilter, SecurityWebFiltersOrder.HTTP_HEADERS_WRITER)
                // А уже ПОТОМ настраиваем правила доступа
                .authorizeExchange(exchanges -> exchanges
                                // Здесь можно оставить только публичные пути, если они есть внутри /reactive
                                .pathMatchers("/register").permitAll()
                                .pathMatchers("/login").permitAll()
                        // Например, .pathMatchers("/public/**").permitAll()
//                            .anyExchange().authenticated() // Все остальные требуют аутентификации
                )

                // Обработка исключений, если после нашего фильтра аутентификации все еще нет
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint((exchange, ex) -> {

                            exchange.getResponse().setStatusCode(HttpStatus.SEE_OTHER);
                            exchange.getResponse().getHeaders().setLocation(URI.create("http://localhost:2009/welcome"));
                            return exchange.getResponse().setComplete();
                        })
                );

        return http.build();
    }
}

