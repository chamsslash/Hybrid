package com.example.springexample;

import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import reactor.ReactorReactiveTransferServiceGrpc;

@Component
public class ReactiveStubGen {

    /**
     * Стаб внедряется через {@code @GrpcClient}, а не собирается вручную (beads 16t):
     * так net.devh grpc-client-spring-boot-starter читает ВЕСЬ блок
     * {@code grpc.client.ReactiveTransferService.*} из application.yml — адрес,
     * keepalive, default-load-balancing-policy — тем же путём, что и для
     * ChatService/AuthTransferService (см. {@code AuthGrpc}). Строка в аннотации
     * обязана буквально совпадать с ключом в application.yml: иначе стартер
     * создаст канал по умолчанию, тихо игнорируя блок конфига.
     *
     * Ручной {@code ManagedChannelBuilder.forTarget(...).usePlaintext().build()},
     * который был здесь раньше, не читал ни keepalive, ни LB-политику — эти ключи
     * применяет только стартер net.devh, и только для полей с {@code @GrpcClient}.
     * Заодно ушёл мёртвый {@code grpcAddress.replace("static://", ...)}: схема
     * {@code static://} в конфиге нигде не используется, адрес всегда {@code dns:///...}.
     *
     * Реактивный стаб поддержан стартером через {@code FallbackStubFactory}: он находит
     * фабричный метод рефлексией по сигнатуре {@code public static newXxxStub(Channel)},
     * а у {@code ReactorReactiveTransferServiceGrpc} есть ровно такой —
     * {@code newReactorStub(Channel)} — это не только про Blocking/Async/FutureStub.
     */
    @GrpcClient("ReactiveTransferService")
    private ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub reactiveClientStub;

    @Bean
    public ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub reactiveStub() {
        return reactiveClientStub;
    }
}
