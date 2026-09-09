package com.example.springexample;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Перенос trace-контекста через Kafka (beads izv).
 *
 * <p><b>Почему это не строчка в application.yml.</b> В Spring Boot свойства
 * {@code spring.kafka.template.observation-enabled} и
 * {@code spring.kafka.listener.observation-enabled} появились только в 3.2, а проект
 * стоит на 3.1.3 — проверено по {@code spring-configuration-metadata.json} в
 * {@code spring-boot-autoconfigure-3.1.3.jar}, этих ключей там нет. Написать их в yml
 * можно, но Boot их просто проигнорирует, и трейсинг Kafka молча не заработает.
 * Сам Spring Kafka (3.0.10) нужные методы уже имеет — не хватает только автоконфигурации,
 * которая бы их вызвала.
 *
 * <p><b>Почему BeanPostProcessor, а не свой бин KafkaTemplate/фабрики.</b> Объявить
 * собственный {@code KafkaTemplate} или {@code ConcurrentKafkaListenerContainerFactory}
 * означало бы ОТКЛЮЧИТЬ автоконфигурацию Boot ({@code @ConditionalOnMissingBean}) и взять
 * на себя воспроизведение всех её настроек: сериализаторов, свойств продюсера
 * ({@code max.request.size} здесь поднят до 10 МБ), обработчиков ошибок, обвязки
 * {@code @RetryableTopic}. Любая забытая деталь ломала бы работающий пайплайн ради
 * трейсинга — цена, несопоставимая с выгодой.
 *
 * <p>Post-processor вместо этого переключает ОДИН флаг на уже собранных Boot'ом бинах.
 * Всё остальное остаётся ровно таким, каким его настроила автоконфигурация.
 *
 * <p><b>Что это даёт.</b> Сообщение, ушедшее в Kafka, несёт заголовки с trace-id, а
 * консьюмер на другой стороне продолжает тот же трейс вместо того, чтобы начать новый.
 * Без этого путь запроса разрывается ровно на Kafka — то есть на самом интересном месте:
 * тикет 5l4 был именно про то, что сообщение уходит в топик и не доезжает до базы.
 */
@Configuration
public class KafkaTracingConfig {

    /**
     * {@code static} обязателен: BeanPostProcessor должен быть создан раньше обычных бинов,
     * иначе он не успеет обработать часть из них. Нестатический метод заставил бы контейнер
     * инстанцировать конфигурацию слишком рано, о чём Spring предупреждает в логе.
     */
    @Bean
    static BeanPostProcessor kafkaObservationEnabler() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof KafkaTemplate<?, ?> template) {
                    template.setObservationEnabled(true);
                }
                if (bean instanceof AbstractKafkaListenerContainerFactory<?, ?, ?> factory) {
                    factory.getContainerProperties().setObservationEnabled(true);
                }
                return bean;
            }
        };
    }
}
