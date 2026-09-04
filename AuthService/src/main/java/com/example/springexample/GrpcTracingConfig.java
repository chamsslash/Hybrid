package com.example.springexample;

import brave.Tracing;
import brave.grpc.GrpcTracing;
import io.grpc.ServerInterceptor;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Перенос trace-контекста через gRPC (beads izv).
 *
 * <p>Полное объяснение, зачем это нужно и почему взят готовый интерсептор вместо
 * собственного, — в одноимённом классе HTTPService. Коротко: ни Spring Boot, ни
 * net.devh-стартер трейсинг по gRPC не переносят, и без этого класса trace-id
 * обрывается на границе сервисов.
 *
 * <p><b>Только серверная сторона.</b> В pom AuthService есть и
 * {@code grpc-client-spring-boot-starter}, но ни одного {@code @GrpcClient} в коде нет:
 * сервис вызовы принимает ({@code Auth_impl}), а сам по gRPC никуда не ходит.
 * Клиентский интерсептор здесь регистрировать не к чему — он молча висел бы без каналов
 * и создавал ложное впечатление, что исходящие вызовы у сервиса есть.
 */
@Configuration
public class GrpcTracingConfig {

    /**
     * {@code brave.Tracing} создаёт автоконфигурация Spring Boot (BraveAutoConfiguration),
     * когда на classpath есть micrometer-tracing-bridge-brave.
     */
    @Bean
    GrpcTracing grpcTracing(Tracing tracing) {
        return GrpcTracing.create(tracing);
    }

    @GrpcGlobalServerInterceptor
    ServerInterceptor grpcTracingServerInterceptor(GrpcTracing grpcTracing) {
        return grpcTracing.newServerInterceptor();
    }
}
