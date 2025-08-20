package com.example.springexample.Utils;

import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.support.RequestDataValueProcessor;
@AutoConfigureAfter(ReactiveSecurityAutoConfiguration.class)
@Configuration(proxyBeanMethods = false)
public class OverrideCsrfProcessor {

    @Bean("requestDataValueProcessor") // то же самое имя, что и у автоконфигурации
    public RequestDataValueProcessor servletDvProc() {
        return new org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor();
    }
}