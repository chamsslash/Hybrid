package com.example.springexample.StompHandlers;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Единственный владелец знания «как отправить пользователю отбивку об ошибке» (beads 8wh).
 *
 * Извлечён из ChatBoxStompController, потому что тот же механизм понадобился
 * StompAuthChannelInterceptor: на SUBSCRIBE со статусом UNKNOWN фрейм роняется, а
 * пользователю нужно сказать, что доступ не проверился. Держать вторую копию отправки
 * значило бы завести второе место, где можно разойтись в адресе или в форме DTO.
 *
 * Отбивка уходит на /private/{userId} — семейство уже закрыто проверкой личности в
 * PER_USER_PREFIXES, так что чужую отбивку никто не прочитает. Персональный адрес,
 * а не адрес чата: в /mutual/chat/{id} её увидели бы все участники.
 */
@Component
@RequiredArgsConstructor
public class StompErrorNotifier {

    private final SimpMessagingTemplate template;

    /**
     * @param destination адрес подписки, которая не удалась (beads 8wh, F1) — по нему клиент
     *                     отличает свой канал сообщений от чужой вкладки/typing/chat_image.
     *                     На пути SEND (ChatBoxStompController) подписки нет, там null.
     */
    public void sendToUser(String userId, String chatId, String code, String message, String destination) {
        ChatErrorDTO error = new ChatErrorDTO();
        error.setChat_id(chatId);
        error.setCode(code);
        error.setMessage(message);
        error.setDestination(destination);
        template.convertAndSend("/private/" + userId, error);
    }
}
