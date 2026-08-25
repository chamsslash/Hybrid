package com.example.springexample.Metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Счётчик сообщений, ДОШЕДШИХ до таблицы message (beads c2k).
 *
 * Лаг консьюмера говорит «сообщения прочитаны из Kafka», но не «сообщения записаны
 * в БД». Между этими двумя событиями лежит вся логика консьюмера, и именно там они
 * терялись в beads 5l4 — ни лаг, ни счётчики продюсера потерю не показывали.
 *
 * Прямой счётчик рядом с kafka_consumer_fetch_manager_records_consumed_total даёт
 * разность, которая и есть искомый сигнал:
 *
 *   rate(kafka_consumer_fetch_manager_records_consumed_total{topic="Messages"}[5m])
 *     - rate(messages_persisted_total[5m])
 *
 * Устойчиво ненулевая разность означает, что сообщения теряются между брокером и
 * таблицей. Приём обобщается: когда система устроена как «принял -> обработал ->
 * записал», счётчик на каждом конце и разность между ними обнаруживают потерю,
 * которой не видно ни на одном из концов по отдельности.
 *
 * Оба счётчика создаются В КОНСТРУКТОРЕ, а не лениво при первом инкременте: пока
 * ряда нет, rate() по нему возвращает пустоту, и разностная панель не рисует
 * ничего — визуально неотличимо от «всё хорошо», то есть отказ мониторинга
 * выглядел бы как исправная работа.
 *
 * Имя метрики в Prometheus получает суффикс _total: реестр micrometer-registry-
 * prometheus добавляет его всем счётчикам сам, поэтому здесь имя без суффикса,
 * а в keep-списке и на дашбордах — с ним.
 */
@Component
public class MessagePersistenceMetric {

    private final Counter success;
    private final Counter failure;

    public MessagePersistenceMetric(MeterRegistry registry) {
        this.success = Counter.builder("messages_persisted")
                .tag("outcome", "success")
                .description("Сообщения, записанные в таблицу message")
                .register(registry);
        this.failure = Counter.builder("messages_persisted")
                .tag("outcome", "failure")
                .description("Сообщения, запись которых в таблицу message провалилась")
                .register(registry);
    }

    public void recordSuccess() {
        success.increment();
    }

    public void recordFailure() {
        failure.increment();
    }
}
