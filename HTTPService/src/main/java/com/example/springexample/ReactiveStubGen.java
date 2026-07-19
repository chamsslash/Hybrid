package com.example.springexample;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import reactor.ReactorReactiveTransferServiceGrpc;

@Component
public class ReactiveStubGen {

    // Адрес берём из конфига (dns:///messegerparody:9091), а не хардкодим localhost —
    // иначе в кластере реактивные gRPC-вызовы уходят в никуда.
    @Value("${grpc.client.ReactiveTransferService.address}")
    private String grpcAddress;

    @Bean
    public ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub reactiveStub() {
        ManagedChannel channel = ManagedChannelBuilder
                .forTarget(grpcAddress.replace("static://", ""))
                .usePlaintext()
                .build();
        return ReactorReactiveTransferServiceGrpc.newReactorStub(channel);
    }
}
