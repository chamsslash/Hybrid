package com.example.springexample;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaProducer {
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    /**
     * Запись чата уходит в "Messages" с ключом chat_id (beads lo2). Без ключа Kafka
     * раскидывала записи по всем партициям топика, и порядок сообщений ОДНОГО чата между
     * партициями брокером не гарантировался — держалось всё на том, что история чата
     * сортируется по time_stamp, а не по порядку в логе. С ключом все записи чата ложатся
     * в одну партицию, и их взаимный порядок обеспечивает сам брокер.
     * Ключ обязан быть каноническим ("7", а не "007"), иначе один чат разъедется на две
     * партиции — канонизацию делает вызывающий (ChatBoxStompController).
     */
    public void send(String chatId, String message) {
        this.kafkaTemplate.send("Messages", chatId, message);
    }

    /**
     * Публикует событие картинки в топик "Images" (beads 6s0).
     * Тело — контракт { targetType, targetId, objectKey } без Base64.
     *
     * Ключа здесь намеренно нет (beads lo2): событие самодостаточно, консьюмер пишет его
     * идемпотентно по targetId, и порядка между событиями разных целей не требуется.
     */
    public void sendImage(String message) {
        this.kafkaTemplate.send("Images", message);
    }

}
