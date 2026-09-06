package com.example.springexample.Metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Счётчик деградации проверки отпечатка (beads kz6).
 *
 * <p>Тест сторожит ИМЕНА, а не арифметику инкремента. Имя метрики и значения тега —
 * это контракт с двумя файлами вне Java: allowlist {@code prometheus.keepMetrics} в
 * Helm/values.yaml и правила алертинга. Расхождение здесь тихое: keep-фильтр просто
 * не совпадёт, ряд не сохранится, панель покажет «No data» — неотличимо от «метрики нет».
 */
class FpCheckMetricTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FpCheckMetric metric = new FpCheckMetric(registry);

    /**
     * Что делает: инкрементит ветку деградации AI.
     * Что проверяет: ряд fp_check_degraded{reason="ai"} равен 1, а соседняя ветка — 0.
     * Зачем: ветки обязаны быть раздельными рядами — алерт на них смотрит с разной
     * severity (warning против critical).
     */
    @Test
    void aiDegradedIncrementsOnlyItsOwnSeries() {
        metric.aiDegraded();

        assertThat(count(FpCheckMetric.REASON_AI)).isEqualTo(1.0);
        assertThat(count(FpCheckMetric.REASON_UNAVAILABLE)).isEqualTo(0.0);
    }

    /**
     * Что делает: инкрементит ветку «вердикт не получен».
     * Что проверяет: ряд fp_check_degraded{reason="unavailable"} равен 1, соседний — 0.
     * Зачем: это ветка fail-closed, по ней стоит critical-алерт; смешение с AI-веткой
     * замаскировало бы баг проверки под штатную деградацию.
     */
    @Test
    void verdictUnavailableIncrementsOnlyItsOwnSeries() {
        metric.verdictUnavailable();

        assertThat(count(FpCheckMetric.REASON_UNAVAILABLE)).isEqualTo(1.0);
        assertThat(count(FpCheckMetric.REASON_AI)).isEqualTo(0.0);
    }

    /**
     * Что делает: читает счётчик по имени-константе и тегу.
     * Что проверяет: ряд с нулевым значением СУЩЕСТВУЕТ до первого инкремента.
     * Зачем: счётчик, зарегистрированный лениво (только в момент первого инкремента),
     * до первого сбоя отсутствует в /actuator/prometheus — а значит алерт на rate()
     * по нему не может перейти в inactive и молчит вместо того, чтобы сторожить.
     */
    @Test
    void bothSeriesExistBeforeAnyIncrement() {
        assertThat(count(FpCheckMetric.REASON_AI)).isEqualTo(0.0);
        assertThat(count(FpCheckMetric.REASON_UNAVAILABLE)).isEqualTo(0.0);
    }

    private double count(String reason) {
        return registry.get(FpCheckMetric.METRIC).tag("reason", reason).counter().count();
    }
}
