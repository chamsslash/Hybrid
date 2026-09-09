package com.example.springexample;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StompFrameTimestampInterceptor — единственное место, где снимается время фрейма (beads 525).
 * preSend выполняется в потоке сессии, до раздачи фрейма в пул clientInboundChannel, поэтому
 * только здесь порядок ещё совпадает с порядком прихода фреймов.
 */
class StompFrameTimestampInterceptorTest {

    private final StompFrameTimestampInterceptor interceptor = new StompFrameTimestampInterceptor();

    private Message<byte[]> frame(StompCommand command) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private String stampOf(Message<?> message) {
        Object value = message.getHeaders().get(StompFrameTimestampInterceptor.SERVER_TIMESTAMP_HEADER);
        return value == null ? null : value.toString();
    }

    @Test
    void sendFrameGetsServerTimestamp() {
        Instant before = Instant.now();

        Message<?> result = interceptor.preSend(frame(StompCommand.SEND), null);

        String stamp = stampOf(result);
        assertNotNull(stamp, "SEND обязан унести время фрейма — по нему сортируется история чата");
        assertFalse(Instant.parse(stamp).isBefore(before));
    }

    @Test
    void nonSendFramesAreNotStamped() {
        for (StompCommand command : List.of(StompCommand.CONNECT, StompCommand.SUBSCRIBE,
                StompCommand.UNSUBSCRIBE, StompCommand.DISCONNECT)) {
            assertNull(stampOf(interceptor.preSend(frame(command), null)),
                    command + " не несёт сообщения — метка времени ему не нужна");
        }
    }

    /**
     * Центральный сторож тикета 525: последовательные вызовы preSend (так их и делает поток
     * сессии) дают строго неубывающие метки. Именно это свойство хендлер обеспечить не может —
     * его вызовы раскидываются по пулу потоков.
     */
    @Test
    void sequentialSendFramesGetMonotonicTimestamps() {
        List<Instant> stamps = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            stamps.add(Instant.parse(stampOf(interceptor.preSend(frame(StompCommand.SEND), null))));
        }
        for (int i = 1; i < stamps.size(); i++) {
            assertFalse(stamps.get(i).isBefore(stamps.get(i - 1)),
                    "метка " + i + " (" + stamps.get(i) + ") раньше предыдущей (" + stamps.get(i - 1) + ")");
        }
        assertTrue(stamps.get(stamps.size() - 1).isAfter(stamps.get(0)),
                "за 50 фреймов время обязано сдвинуться — иначе тест ничего не проверяет");
    }
}
