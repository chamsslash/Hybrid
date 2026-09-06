package com.example.springexample.Metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Деградация проверки отпечатка браузера (beads kz6).
 *
 * <p>Проверка отпечатка имеет три исхода, а не два. Кроме «похоже» и «не похоже» есть
 * третий: проверка НЕ СМОГЛА отработать. Раньше он выражался исключением, которое
 * улетало мимо фолбэка и становилось 500 на {@code /exchangeTokens}. Теперь он —
 * штатный исход, и оба нештатных исхода обязаны быть видны снаружи.
 *
 * <p>Тег {@code reason} различает их, потому что стоят они принципиально разного:
 * <ul>
 *   <li>{@code ai} — Gemini не дал вердикт (квота, сеть, таймаут, пустой ключ), решение
 *       принято одной эвристикой. Это ЗАДУМАННАЯ деградация; разовая — норма, тревожит
 *       только устойчивая, иначе AI выключен из решения фактически, а не по замыслу.</li>
 *   <li>{@code unavailable} — упала сама эвристика, сравнивать нечем, доступ закрыт
 *       (fail-closed). Любое ненулевое значение — это баг проверки, который прямо
 *       сейчас разлогинивает живых людей.</li>
 * </ul>
 *
 * <p>Одна метрика с тегом, а не две метрики — как у {@code MessagePersistenceMetric}
 * в MessegerParody: одно имя проще провести через allowlist {@code keepMetrics}, а
 * различать исходы в PromQL по тегу дешевле, чем держать два ряда.
 *
 * <p>Оба счётчика регистрируются в конструкторе, а не лениво при первом инкременте:
 * отсутствующий ряд не даёт алерту перейти в {@code inactive}, и правило молчит не
 * потому, что всё хорошо, а потому, что смотреть не на что.
 */
@Component
public class FpCheckMetric {

    /**
     * Имя метрики в Micrometer. Prometheus публикует её как
     * {@code fp_check_degraded_total} — именно это имя обязано стоять в
     * {@code prometheus.keepMetrics} (Helm/values.yaml), иначе ряд молча отбросится.
     */
    public static final String METRIC = "fp_check_degraded";
    public static final String REASON_AI = "ai";
    public static final String REASON_UNAVAILABLE = "unavailable";

    private final Counter aiDegraded;
    private final Counter verdictUnavailable;

    public FpCheckMetric(MeterRegistry registry) {
        aiDegraded = Counter.builder(METRIC)
                .description("Проверка отпечатка приняла решение без AI")
                .tag("reason", REASON_AI)
                .register(registry);
        verdictUnavailable = Counter.builder(METRIC)
                .description("Проверка отпечатка не смогла отработать, доступ закрыт")
                .tag("reason", REASON_UNAVAILABLE)
                .register(registry);
    }

    /** Gemini не дал вердикт — решение принято одной эвристикой. */
    public void aiDegraded() {
        aiDegraded.increment();
    }

    /** Эвристика упала — вердикта нет, доступ закрыт. */
    public void verdictUnavailable() {
        verdictUnavailable.increment();
    }
}
