package com.example.springexample;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaAdmin;


@Configuration
@EnableKafka
public class KafkaConfig {



    @Bean
    public KafkaAdmin.NewTopics newTopic() {

        return new KafkaAdmin.NewTopics(
                new NewTopic("Messages", 5, (short) 2),
                new NewTopic("Events", 2, (short) 2)
        );
    }


}
