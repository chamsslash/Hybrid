package com.example.springexample.Metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrpcRequestsMetricTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GrpcRequestsMetric metric = new GrpcRequestsMetric(registry);

    private Timer timer(String method, String outcome) {
        return registry.find(GrpcRequestsMetric.CALL_TIMER)
                .tags("method", method, "outcome", outcome)
                .timer();
    }

    private Counter legacyCounter() {
        return registry.find("grpc_calls_counter").tags("type", "GrpcMetric").counter();
    }

    @Test
    void assemblingMonoWithoutSubscriptionCountsNothing() {
        metric.measure("getnewest", Mono.just("ok"));

        assertNull(timer("getnewest", "success"));
        assertNotNull(legacyCounter());
        assertEquals(0.0, legacyCounter().count());
    }

    @Test
    void successfulCallIsTimedWithMethodTag() {
        StepVerifier.create(metric.measure("getnewest", Mono.just("ok")))
                .expectNext("ok")
                .verifyComplete();

        Timer success = timer("getnewest", "success");
        assertNotNull(success);
        assertEquals(1L, success.count());
        assertTrue(success.totalTime(TimeUnit.NANOSECONDS) >= 0);
        assertNull(timer("getnewest", "error"));
        assertEquals(1.0, legacyCounter().count());
    }

    @Test
    void failedCallGoesToErrorOutcome() {
        StepVerifier.create(metric.measure("getnewest", Mono.error(new IllegalStateException("gRPC упал"))))
                .verifyError(IllegalStateException.class);

        assertNotNull(timer("getnewest", "error"));
        assertEquals(1L, timer("getnewest", "error").count());
        assertNull(timer("getnewest", "success"));
        assertEquals(1.0, legacyCounter().count());
    }

    @Test
    void timedOutCallGoesToCancelOutcome() {
        StepVerifier.create(metric.measure("members", Mono.never()).timeout(Duration.ofMillis(50)))
                .verifyError(TimeoutException.class);

        assertNotNull(timer("members", "cancel"));
        assertEquals(1L, timer("members", "cancel").count());
        assertNull(timer("members", "success"));
        assertNull(timer("members", "error"));
    }

    @Test
    void eachSubscriptionIsCountedSeparately() {
        Mono<String> measured = metric.measure("getimageurl", Mono.just("ok"));

        measured.block();
        measured.block();

        assertEquals(2L, timer("getimageurl", "success").count());
        assertEquals(2.0, legacyCounter().count());
    }

    /**
     * Проверяется на PrometheusMeterRegistry, а не на SimpleMeterRegistry: бакеты
     * percentile-гистограммы материализует только реестр, умеющий агрегируемые
     * перцентили, — а в приложении стоит ровно он (actuator /prometheus).
     */
    @Test
    void latencyIsExposedAsPrometheusHistogramNotOnlyAverage() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new GrpcRequestsMetric(prometheus).measure("members", Mono.just("ok")).block();

        String scrape = prometheus.scrape();
        assertTrue(scrape.contains("grpc_call_duration_seconds_bucket"),
                "нужна гистограмма, по среднему тикет 8wh не разобрать: " + scrape);
        assertTrue(scrape.contains("method=\"members\""), scrape);
        assertTrue(scrape.contains("outcome=\"success\""), scrape);
        assertTrue(scrape.contains("grpc_calls_counter_total"),
                "исторический счётчик обязан остаться на месте: " + scrape);
    }

    @Test
    void legacyIncrementStillFeedsOldCounter() {
        metric.increment();

        assertEquals(1.0, legacyCounter().count());
    }
}
