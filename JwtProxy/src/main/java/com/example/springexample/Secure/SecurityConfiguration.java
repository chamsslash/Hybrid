package com.example.springexample.Secure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
public class SecurityConfiguration {
    @Bean
    public SecurityFilterChain mvcFilterChain(HttpSecurity http) throws Exception {
        http.csrf().disable().authorizeHttpRequests(auth->
                auth.requestMatchers(new AntPathRequestMatcher("/jwtcheck")).permitAll().anyRequest().authenticated());

        return http.build();
    }
}
