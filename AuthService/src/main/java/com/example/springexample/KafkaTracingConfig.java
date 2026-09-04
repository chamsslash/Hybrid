package com.example.springexample;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Перенос trace-контекста через Kafka (beads izv).
 *
 * <p>Полное объяснение — почему это не свойство в yml (ключи появились в Boot 3.2,
 * проект на 3.1.3) и почему BeanPostProcessor, а не собственный бин фабрики, — в
 * одноимённом классе HTTPService.
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
