package com.example.springexample.Metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Счётчик сообщений, ДОШЕДШИХ до таблицы message (beads c2k).
 *
 * Зачем он нужен рядом с лагом консьюмера: лаг говорит «сообщения прочитаны из
 * Kafka», но не «сообщения записаны в БД». Между этими двумя событиями лежит вся
 * логика консьюмера, и именно там они терялись в beads 5l4 — ни один из концов
 * по отдельности потерю не показывал.
 *
 * Дашборд вычитает одно из другого, поэтому счётчик обязан вести себя
 * предсказуемо в обоих режимах, включая режим «ещё ничего не произошло».
 */
class MessagePersistenceMetricTest {

    private MeterRegistry registry;
    private MessagePersistenceMetric metric;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metric = new MessagePersistenceMetric(registry);
    }

    private double count(String outcome) {
        return registry.get("messages_persisted").tag("outcome", outcome).counter().count();
    }

    @Test
    void bothOutcomesAreRegisteredBeforeAnyMessageArrives() {
        // Самый содержательный тест группы. Если счётчики создавать лениво, при первом
        // инкременте, то до первого сбоя ряда messages_persisted{outcome="failure"}
        // в Prometheus не существует вовсе — а rate() по несуществующему ряду
        // возвращает пустоту, и разностная панель дашборда молча не рисует НИЧЕГО.
        // Пустая панель при этом визуально неотличима от панели «всё хорошо»,
        // то есть отказ мониторинга выглядит как исправная работа.
        //
        // Обращение через registry.get(...) бросит MeterNotFoundException, если
        // счётчик не зарегистрирован — именно это тест и стережёт.
        assertThat(count("success")).isZero();
        assertThat(count("failure")).isZero();
    }

    @Test
    void successAndFailureAreCountedSeparately() {
        // Стережёт перепутанные теги: при обмене местами success/failure оба
        // предыдущих ассерта остались бы зелёными, а дашборд показывал бы
        // ровно обратную картину происходящего.
        metric.recordSuccess();
        metric.recordSuccess();
        metric.recordFailure();

        assertThat(count("success")).isEqualTo(2.0);
        assertThat(count("failure")).isEqualTo(1.0);
    }
}
