package com.example.springexample;

import jakarta.persistence.EntityManager;
import net.devh.boot.grpc.client.autoconfigure.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

@ImportAutoConfiguration({
        GrpcClientAutoConfiguration.class,
        GrpcClientMetricAutoConfiguration.class,
        GrpcClientHealthAutoConfiguration.class,
        GrpcClientSecurityAutoConfiguration.class,

        GrpcDiscoveryClientAutoConfiguration.class,
})
@SpringBootApplication
@EntityScan(basePackages = "com.example.springexample.JPA_Entities")
@EnableJpaRepositories(basePackages = "com.example.springexample.JPA_Repositories")
@EnableR2dbcRepositories("com.example.springexample.R2DBC_Repositories")

public class Main {
    public static void main(String[] args) {
        SpringApplication.run(Main.class,args);
    }



}