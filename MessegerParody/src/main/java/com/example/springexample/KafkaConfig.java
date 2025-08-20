//package com.example.springexample;
//
//import org.apache.kafka.clients.admin.NewTopic;
//import org.apache.kafka.clients.consumer.ConsumerConfig;
//import org.apache.kafka.common.serialization.StringDeserializer;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.kafka.annotation.EnableKafka;
//import org.springframework.kafka.core.*;
//import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
//import org.springframework.kafka.listener.ContainerProperties;
//import org.springframework.kafka.listener.MessageListener;
//import org.springframework.kafka.listener.MessageListenerContainer;
//import java.util.Collections;
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//
//
//
//@Configuration
//@EnableKafka
//public class KafkaConfig {
//    private static final String topic1 = "messages";
//    private static final String topic2 = "chats";
//    private static final String[] port =  new String[]{"localhost:9092", "localhost:9093"};
//    @Bean
//    public KafkaAdmin kafkaAdmin() {
//        return new KafkaAdmin(Collections.singletonMap("bootstrap.servers", port));
//    }
//    @Bean
//    public KafkaTemplate<String,String> KafkaTemplate() {
//        Map<String, Object> configProps = new HashMap<>();
//        configProps.put("bootstrap.servers", port);
//        configProps.put("serializer.class", "kafka.serializer.StringEncoder");
//        configProps.put("key.serializer.class", "kafka.serializer.StringEncoder");
//        ProducerFactory<String,String> ProdFac=new DefaultKafkaProducerFactory<>(configProps);
//        return new KafkaTemplate<>(ProdFac);
//    }
//
//    @Bean
//    public KafkaAdmin.NewTopics newTopic() {
//        return new KafkaAdmin.NewTopics(
//                new NewTopic("Messages", 5, (short) 2),
//                new NewTopic("Events", 2, (short) 2)
//        );
//    }
//
//
//}
