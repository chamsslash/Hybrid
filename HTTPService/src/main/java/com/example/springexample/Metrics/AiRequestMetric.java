package com.example.springexample.Metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class AiRequestMetric {
    private final Counter AiRequestCounter;
    public AiRequestMetric(MeterRegistry registry) {
        AiRequestCounter = Counter.builder("AiRequestCounter").tag("Ai", "true").description("Количество обращений за помощью к AI-ассистенту").register(registry);

    }
    public void increment() {
        AiRequestCounter.increment();
    }
}
