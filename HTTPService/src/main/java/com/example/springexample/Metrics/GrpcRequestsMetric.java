package com.example.springexample.Metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.Counter;

@Component
public class GrpcRequestsMetric {
    private final  Counter GrpCReqCounter;
    public GrpcRequestsMetric(MeterRegistry meterRegistry) {
        GrpCReqCounter =  Counter.builder("grpc_calls_counter")
                .description("Количество GRPC вызовов ")
                .tags("type", "GrpcMetric")
                .register(meterRegistry);
    }

    public void increment() {
       GrpCReqCounter.increment();
    }

    }
