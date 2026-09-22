package com.example.springexample;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Контракт сообщения топика "Messages" — зеркало HTTPService/StompHandlers/ChatMessageDTO.java
 * (сервисы не шарят Java-классы, только JSON-поля). Имена полей обязаны совпадать с той
 * стороной буква в букву: разъехавшееся имя приедет сюда молчаливым null, без ошибки.
 * Для message_id это особенно дорого — вместо защиты от дублей получилась бы её видимость
 * (см. KafkaConsumerTest.serverMessageIdFromPayloadReachesRepository).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDTO {
    String type = "message";
    // Сквозной идентификатор сообщения, сгенерированный HTTPService (beads myl). Ключ
    // идемпотентности вставки: Kafka даёт at-least-once, и переигранная запись несёт
    // тот же id. null — легаси-формат из бэклога топика, защиты от дублей для него нет.
    private String message_id;
    private String chat_id;
    private String user_id;
    private String username;
    private String timestamp;
    private String text;
    private String imageurl;
    // Ключ стикера в MinIO (beads a22): sticker/<ownerUserId>/<uuid>.<ext>. null —
    // обычное текстовое сообщение.
    //
    // Соседнее поле imageurl — это НЕ вложение, а ключ аватарки отправителя; sticker_key
    // стоит рядом и ничего не заменяет.
    private String sticker_key;
}
