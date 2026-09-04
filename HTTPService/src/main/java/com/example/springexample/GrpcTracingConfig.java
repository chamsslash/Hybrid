package com.example.springexample;

import brave.Tracing;
import brave.grpc.GrpcTracing;
import io.grpc.ClientInterceptor;
import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Перенос trace-контекста через gRPC (beads izv).
 *
 * <p><b>Зачем это вообще нужно.</b> Spring Boot инструментирует HTTP и Kafka сам: входящий
 * запрос открывает span, исходящий подхватывает текущий контекст из заголовков. gRPC в этот
 * набор не входит — ни Boot, ни net.devh-стартер трейсинг по нему не переносят. Без этого
 * класса трейс обрывается ровно на границе HTTPService → AuthService/MessegerParody: у
 * вызываемого сервиса начинается НОВЫЙ trace-id, и связать две половины пути одного запроса
 * становится невозможно. То есть в системе, где вся интересная логика живёт за gRPC,
 * трейсинг без этого класса отвечал бы только на вопросы про сам HTTPService.
 *
 * <p><b>Почему готовый интерсептор, а не свой.</b> Перенос контекста — это не только
 * «положить заголовок в метаданные». Собственная реализация обязана корректно открывать и
 * закрывать scope вокруг асинхронного вызова; ошибка там не падает, а протекает — контекст
 * остаётся привязанным к потоку и достаётся следующему, чужому запросу. Такой баг
 * проявляется как перепутанные trace-id под нагрузкой и ищется мучительно.
 * {@code brave-instrumentation-grpc} эту работу уже делает.
 *
 * <p><b>Только клиентская сторона.</b> HTTPService gRPC-сервера не поднимает — он ходит к
 * AuthService и MessegerParody, но сам вызовов не принимает (в pom только
 * {@code grpc-client-spring-boot-starter}). Серверный интерсептор регистрировать не к чему.
 *
 * <p>{@link GrpcGlobalClientInterceptor} вешает интерсептор на ВСЕ каналы, заведённые
 * стартером, — включая реактивные стабы на reactor-grpc.
 */
@Configuration
public class GrpcTracingConfig {

    /**
     * {@code brave.Tracing} создаёт автоконфигурация Spring Boot (BraveAutoConfiguration),
     * когда на classpath есть micrometer-tracing-bridge-brave. Отдельно настраивать её
     * не нужно: sampling и адрес экспорта берутся из {@code management.tracing.*} и
     * {@code management.zipkin.tracing.*}.
     */
    @Bean
    GrpcTracing grpcTracing(Tracing tracing) {
        return GrpcTracing.create(tracing);
    }

    @GrpcGlobalClientInterceptor
    ClientInterceptor grpcTracingClientInterceptor(GrpcTracing grpcTracing) {
        return grpcTracing.newClientInterceptor();
    }
}
