package com.example.springexample;

import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;


/**
 * Геометрию топиков задаёт Helm, а не этот класс (beads lo2).
 *
 * Здесь жил бин {@code KafkaAdmin.NewTopics} с Messages=5 и Images=3 партиций —
 * единственный реальный декларатор геометрии во всём репозитории, при том что чарт
 * делал вид, что управляет ей ключами kafka.createTopics/partitionsPerTopic (они были
 * мёртвыми). Kafka разрешает увеличивать число партиций, поэтому AuthService на каждом
 * своём старте молча переформатировал уже существующий топик: в логе это видно как
 * "Topic 'Messages' exists but has a different partition count: 1 not 5, increasing if
 * the broker supports it". Топик при этом успевал побывать однопартиционным, и консьюмер
 * MessegerParody кешировал ровно одну партицию до истечения metadata.max.age.ms.
 *
 * Теперь топики создаёт джоба kafka-topics (Helm/templates/kafka-topics-job.yaml).
 * @EnableKafka остаётся: KafkaProducer этого сервиса продолжает писать в топики.
 */
@Configuration
@EnableKafka
public class KafkaConfig {

}
