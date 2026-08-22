package com.example.springexample.StompHandlers;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Отбивка отказа отправителю на его персональный адрес /private/{userId} (beads isf).
 *
 * До этого тикета отказ на SEND был одним log.warn: сообщение не доходило никуда, а фронт
 * чистил поле ввода сразу после send — текст пропадал бесследно. По этому DTO фронт
 * показывает тост и возвращает текст в поле.
 *
 * {@code type} — по образцу ChatMessageDTO ("message"): на /private/ может прийти не только
 * отбивка, и клиенту нужно отличать её от прочего трафика. {@code code} — машинная причина
 * (что именно отвалилось), {@code message} — нейтральный текст для пользователя; внутренности
 * (текст исключения, стек) в него не попадают никогда, они остаются в логе.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatErrorDTO {
    String type = "error";
    private String chat_id;
    private String code;
    private String message;
}
