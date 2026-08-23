package com.example.springexample;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Сторож контракта отправки в Kafka (beads lo2). Топик "Messages" многопартиционный, а
 * продюсер писал в него без ключа — записи размазывались по всем партициям, и порядок
 * сообщений одного чата между партициями брокером не гарантировался.
 */
class KafkaProducerTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = Mockito.mock(KafkaTemplate.class);

    private KafkaProducer producer() {
        KafkaProducer producer = new KafkaProducer();
        ReflectionTestUtils.setField(producer, "kafkaTemplate", kafkaTemplate);
        return producer;
    }

    /**
     * Ключ — chat_id: все сообщения одного чата обязаны лечь в одну партицию, только тогда
     * их взаимный порядок держит сам брокер. Проверяется и топик, и ключ, и тело.
     */
    @Test
    void chatMessageIsSentToMessagesTopicKeyedByChatId() {
        producer().send("42", "{\"chat_id\":\"42\",\"text\":\"привет\"}");

        Mockito.verify(kafkaTemplate).send("Messages", "42", "{\"chat_id\":\"42\",\"text\":\"привет\"}");
    }

    /**
     * Ключ обязан быть именно тем, что передали, а не производным: два разных чата не
     * должны схлопнуться в одну партицию, а один и тот же — разъехаться на две.
     */
    @Test
    void differentChatsKeepTheirOwnKeys() {
        KafkaProducer producer = producer();

        producer.send("1", "первый");
        producer.send("2", "второй");
        producer.send("1", "третий");

        Mockito.verify(kafkaTemplate).send("Messages", "1", "первый");
        Mockito.verify(kafkaTemplate).send("Messages", "2", "второй");
        Mockito.verify(kafkaTemplate).send("Messages", "1", "третий");
        Mockito.verifyNoMoreInteractions(kafkaTemplate);
    }

    /**
     * sendImage намеренно остаётся без ключа (beads lo2): событие картинки самодостаточно
     * (targetType/targetId/objectKey) и пишется идемпотентно по targetId, порядка между
     * событиями разных целей не требуется. Тест стережёт, что "ключ для Messages" не
     * расползётся на Images заодно.
     */
    @Test
    void imageEventIsSentToImagesTopicWithoutKey() {
        producer().sendImage("{\"targetType\":\"userimage\",\"targetId\":\"9\"}");

        Mockito.verify(kafkaTemplate).send("Images", "{\"targetType\":\"userimage\",\"targetId\":\"9\"}");
        Mockito.verifyNoMoreInteractions(kafkaTemplate);
    }
}
