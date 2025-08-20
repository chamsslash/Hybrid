package com.example.springexample;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.ReactiveTransferServiceGrpc;

@Configuration
public class GrpcReactiveStubConfig {

    @Value("${grpc.client.ReactiveTransferService.address}")
    private String grpcAddress;

    @Bean
    public reactor.ReactiveTransferServiceGrpc.ReactiveTransferServiceStub reactiveTransferServiceStub() {
        ManagedChannel channel = ManagedChannelBuilder
                .forTarget(grpcAddress.replace("static://", "")) // если в .yml указан static://
                .usePlaintext()
                .build();

        return ReactiveTransferServiceGrpc.newStub(channel);
    }
}