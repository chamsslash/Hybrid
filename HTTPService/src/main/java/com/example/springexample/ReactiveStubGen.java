package com.example.springexample;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import reactor.ReactorReactiveTransferServiceGrpc;

@Component
public class ReactiveStubGen {
    @Bean
    public ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub reactiveStub() {
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 9091).usePlaintext().build();
        return  ReactorReactiveTransferServiceGrpc.newReactorStub(channel);
    }
}
