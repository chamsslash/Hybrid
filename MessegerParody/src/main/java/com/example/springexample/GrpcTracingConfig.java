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
 * <p>Полное объяснение — в одноимённом классе HTTPService.
 *
 * <p><b>Только серверная сторона.</b> MessegerParody — конечная точка пути: он принимает
 * gRPC-вызовы и читает Kafka, но сам к соседям по gRPC не ходит (в pom только
 * {@code grpc-spring-boot-starter}, без клиентского).
 *
 * <p>Именно здесь трейс и заканчивается — записью в таблицу {@code message}. Ради этого
 * последнего хопа трейсинг и заводился: тикет 5l4 был ровно про то, что сообщение
 * доходит до Kafka и не доезжает до базы, а по метрикам судьбу одного сообщения
 * восстановить нельзя.
 */
@Configuration
public class GrpcTracingConfig {

    @Bean
    GrpcTracing grpcTracing(Tracing tracing) {
        return GrpcTracing.create(tracing);
    }

    @GrpcGlobalServerInterceptor
    ServerInterceptor grpcTracingServerInterceptor(GrpcTracing grpcTracing) {
        return grpcTracing.newServerInterceptor();
    }
}
