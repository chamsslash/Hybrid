package com.example.springexample.Metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * Метрики исходящих gRPC-вызовов HTTPService.
 *
 * Кроме исторического счётчика {@code grpc_calls_counter} (остаётся без тегов метода —
 * на него могли смотреть снаружи) публикует таймер {@link #CALL_TIMER} с тегами
 * {@code method} и {@code outcome}. Латентность нужна распределением, а не средним:
 * тикет 8wh про редкие отказы проверки членства по таймауту 5 с разбирается только
 * по хвосту гистограммы.
 *
 * Значение тега {@code method} — имя gRPC-метода, кроме проверки членства: она ходит
 * в тот же {@code getAllUsersByChatId}, что и сборка списка чатов, но после g9x это
 * самый горячий вызов в системе (по одному на каждое сообщение и на каждое
 * typing-событие), и смешивать её латентность с холодным путём нельзя — у неё
 * собственное значение {@code members}.
 */
@Component
public class GrpcRequestsMetric {

    /** Таймер длительности gRPC-вызовов; теги — {@code method} и {@code outcome}. */
    public static final String CALL_TIMER = "grpc_call_duration";

    private final MeterRegistry meterRegistry;
    private final  Counter GrpCReqCounter;
    public GrpcRequestsMetric(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        GrpCReqCounter =  Counter.builder("grpc_calls_counter")
                .description("Количество GRPC вызовов ")
                .tags("type", "GrpcMetric")
                .register(meterRegistry);
    }

    public void increment() {
       GrpCReqCounter.increment();
    }

    /**
     * Оборачивает вызов замером. Всё делается на подписке, а не в теле вызывающего
     * метода: собранная, но неподписанная цепочка — это не вызов, а считать её за вызов
     * значит врать в обе метрики сразу.
     *
     * Исход различается по терминальному сигналу: {@code success} — ответ пришёл,
     * {@code error} — gRPC вернул ошибку, {@code cancel} — подписчик отвалился раньше
     * ответа. Последнее и есть таймаут: {@code .timeout()} стоит НАД этим замером и
     * гасит вызов отменой, поэтому «медленно, но дошло» и «не дождались» лежат в разных
     * сериях, а не в одной.
     */
    public <T> Mono<T> measure(String method, Mono<T> call) {
        return Mono.defer(() -> {
            increment();
            Timer.Sample sample = Timer.start(meterRegistry);
            return call.doFinally(signal -> sample.stop(callTimer(method, outcome(signal))));
        });
    }

    private Timer callTimer(String method, String outcome) {
        return Timer.builder(CALL_TIMER)
                .description("Длительность GRPC вызовов")
                .tags("method", method, "outcome", outcome)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    private static String outcome(SignalType signal) {
        return switch (signal) {
            case ON_COMPLETE -> "success";
            case ON_ERROR -> "error";
            default -> "cancel";
        };
    }

    }
