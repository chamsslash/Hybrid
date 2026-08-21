package com.example.springexample;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Контракт сообщения топика "Images" (beads 6s0, дизайн n5y):
 * { "targetType": "userimage"|"chatimage", "targetId": "&lt;id&gt;", "objectKey": "&lt;key&gt;" }.
 * Совпадает с продюсером HTTPService и консюмером MessegerParody.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageUploadDTO {
    String targetType;
    String targetId;
    String objectKey;

    /**
     * Канонический числовой targetId либо null, если контракт нарушен (beads bwh).
     * targetId приезжает из Kafka, то есть это внешние данные, и после bwh подставляется
     * прямо в STOMP-адрес рассылки (/mutual/chat_image/{chatId}, /mutual/user_image/{userId}),
     * поэтому проверяется ДО подстановки — иначе мусорный targetId породил бы адрес,
     * которого нет ни в одном семействе белого списка, и событие ушло бы в никуда без следа.
     * Ведущие нули срезаются: "007" уехал бы на /mutual/chat_image/007, тогда как участники
     * чата 7 слушают /mutual/chat_image/7 (сценарий 007 из beads g9x).
     */
    public String canonicalTargetId() {
        if (targetId == null || !targetId.matches("\\d+")) {
            return null;
        }
        try {
            return String.valueOf(Long.parseLong(targetId));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
