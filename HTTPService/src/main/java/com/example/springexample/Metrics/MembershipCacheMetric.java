package com.example.springexample.Metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Попадания и промахи кеша членства в чате (beads 9wi).
 *
 * <p>Метрика заведена вместе с самим кешем не «на всякий случай», а потому что тикет
 * требовал ввести кеш только после замеров, а замеров не было: {@code members} не
 * появлялся в Prometheus вовсе — стенд стоял без трафика. Кеш введён по устройству кода
 * (состав чата неизменяем, см. {@code ChatMembershipService}), а не по измеренной
 * нагрузке, и этот счётчик — то, чем недостающие цифры добираются постфактум.
 *
 * <p>Читается так: {@code result="hit"} — вызов gRPC, которого НЕ случилось;
 * {@code result="miss"} — случившийся. Доля попаданий и есть ответ на вопрос тикета
 * «заметна ли вообще нагрузка на getAllUsersByChatId». Если она окажется низкой, кеш
 * не нужен и его можно снимать — но это будет решение по цифрам, а не по ощущению.
 *
 * <p>Зависит только от {@code MeterRegistry} — по той же причине, что и
 * {@code GrpcRequestsMetric}: {@code ChatMembershipService} обязан оставаться вне
 * цикла бинов вокруг {@code StompConfig}, и любая его зависимость должна быть такой же
 * «плоской».
 */
@Component
public class MembershipCacheMetric {

    /**
     * Имя метрики в Micrometer. Prometheus публикует её как
     * {@code membership_cache_total} — это имя обязано стоять в
     * {@code prometheus.keepMetrics} (Helm/values.yaml), иначе ряд молча отбросится.
     */
    public static final String METRIC = "membership_cache";
    public static final String RESULT_HIT = "hit";
    public static final String RESULT_MISS = "miss";

    private final Counter hit;
    private final Counter miss;

    public MembershipCacheMetric(MeterRegistry registry) {
        hit = Counter.builder(METRIC)
                .description("Состав чата взят из кеша — gRPC-вызова не было")
                .tag("result", RESULT_HIT)
                .register(registry);
        miss = Counter.builder(METRIC)
                .description("Состава чата в кеше нет — понадобился gRPC-вызов")
                .tag("result", RESULT_MISS)
                .register(registry);
    }

    /** Состав взят из кеша. */
    public void hit() {
        hit.increment();
    }

    /** Кеш пуст или протух — идём в gRPC. */
    public void miss() {
        miss.increment();
    }
}
