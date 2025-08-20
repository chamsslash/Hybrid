package com.example.springexample;


import net.devh.boot.grpc.client.autoconfigure.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.thymeleaf.ThymeleafAutoConfiguration;
import org.springframework.web.servlet.support.RequestDataValueProcessor;

@EnableScheduling
@ImportAutoConfiguration({
        GrpcClientAutoConfiguration.class,
        GrpcClientMetricAutoConfiguration.class,
        GrpcClientHealthAutoConfiguration.class,
        GrpcClientSecurityAutoConfiguration.class,
        GrpcClientTraceAutoConfiguration.class,
        GrpcDiscoveryClientAutoConfiguration.class,
})

@SpringBootApplication(
)
public class Main {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(Main.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.run(args);
    }

}
