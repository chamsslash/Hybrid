package com.example.springexample;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Проставляет серверное время фрейма ДО того, как фрейм уедет в пул обработчиков (beads 525).
 *
 * Зачем отдельный интерцептор, а не Instant.now() в самом хендлере: clientInboundChannel —
 * это ExecutorSubscribableChannel, и вызовы @MessageMapping раскидываются по пулу потоков
 * (StompConfig своего taskExecutor не задаёт, значит действует дефолт Spring —
 * availableProcessors * 2). Порядок ВХОДА в хендлер поэтому не совпадает с порядком прихода
 * фреймов, и время, снятое в любой точке хендлера, порядок не восстанавливает: на живом
 * стенде 10 сообщений подряд от одного клиента получали времена в пределах 7 мс, но
 * вперемешку.
 *
 * preSend же выполняется в потоке ОТПРАВИТЕЛЯ — том самом, который читает фреймы из
 * WebSocket-сессии, — до раздачи задач исполнителю. Внутри одной сессии он строго
 * последовательный, поэтому снятое здесь время монотонно по порядку прихода фреймов.
 *
 * Ограничение осознанное: порядок гарантируется в пределах одной сессии. Между разными
 * отправителями порядок задаётся временем прихода на сервер — так и должно быть.
 */
@Component
public class StompFrameTimestampInterceptor implements ChannelInterceptor {

    /** Имя заголовка, по которому ChatBoxStompController забирает время (@Header). */
    public static final String SERVER_TIMESTAMP_HEADER = "serverTimestamp";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        // Мутируем только SEND и только пока аксессор изменяем: для входящих STOMP-фреймов
        // Spring отдаёт mutable-аксессор именно на время preSend.
        if (accessor != null && StompCommand.SEND.equals(accessor.getCommand()) && accessor.isMutable()) {
            accessor.setHeader(SERVER_TIMESTAMP_HEADER, Instant.now().toString());
        }
        return message;
    }
}
