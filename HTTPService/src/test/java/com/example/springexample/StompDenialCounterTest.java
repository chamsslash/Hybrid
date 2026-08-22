package com.example.springexample;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import static org.junit.jupiter.api.Assertions.*;

class StompDenialCounterTest {

    private final StompDenialCounter counter = new StompDenialCounter();

    /** Событие, которое Spring публикует при закрытии WebSocket-сессии. */
    private static SessionDisconnectEvent disconnect(String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId(sessionId);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionDisconnectEvent(new Object(), message, sessionId, CloseStatus.NORMAL);
    }

    /**
     * Порог срабатывает ровно на MAX_DENIALS_PER_SESSION, не раньше: легитимный рассинхрон
     * (пользователя убрали из чата, вкладка открыта) даёт один-два отказа и не должен рвать
     * сессию, иначе стоп-кран бил бы по обычным пользователям.
     */
    @Test
    void sessionIsOverLimitOnlyAfterThresholdDenials() {
        counter.register("s1");

        for (int i = 1; i < StompDenialCounter.MAX_DENIALS_PER_SESSION; i++) {
            counter.recordDenial("s1");
            assertFalse(counter.isOverLimit("s1"), "после " + i + " отказов сессия ещё живая");
        }

        counter.recordDenial("s1");
        assertTrue(counter.isOverLimit("s1"),
                "после " + StompDenialCounter.MAX_DENIALS_PER_SESSION + " отказов SEND обязан отклоняться");
    }

    /**
     * Самая частая ошибка такой схемы — оставить запись после разрыва: без этого счётчик
     * растёт на каждую когда-либо подключавшуюся сессию и живёт до рестарта пода.
     */
    @Test
    void disconnectEventClearsSessionRecord() {
        counter.register("s1");
        for (int i = 0; i < StompDenialCounter.MAX_DENIALS_PER_SESSION; i++) {
            counter.recordDenial("s1");
        }
        assertTrue(counter.isOverLimit("s1"));

        counter.onSessionDisconnect(disconnect("s1"));

        assertFalse(counter.isOverLimit("s1"), "запись сессии обязана исчезнуть при разрыве");
        assertEquals(0, counter.trackedSessions(), "в карте не должно остаться ни одной записи");
    }

    /**
     * Запись рождается только на CONNECT: инкремент из контроллера прилетает с потока пула
     * обработчиков и может опоздать за разрывом сессии. Если бы он создавал запись сам,
     * каждый такой опоздавший отказ оставлял бы в карте мусор, который уже некому убрать —
     * SessionDisconnectEvent для этой сессии уже прошёл.
     */
    @Test
    void denialsOfUnregisteredSessionAreNotStored() {
        for (int i = 0; i < StompDenialCounter.MAX_DENIALS_PER_SESSION * 2; i++) {
            counter.recordDenial("ghost");
        }

        assertFalse(counter.isOverLimit("ghost"));
        assertEquals(0, counter.trackedSessions(), "незарегистрированная сессия не создаёт записи");
    }

    /**
     * Переподключение — это новая сессия с чистым счётчиком, и это осознанно: стоп-кран не
     * бан, его цель — сделать каждые MAX_DENIALS_PER_SESSION отказных фреймов ценой полного
     * CONNECT-хендшейка, а не запретить пользователю вернуться.
     */
    @Test
    void reconnectedSessionStartsWithCleanCounter() {
        counter.register("s1");
        for (int i = 0; i < StompDenialCounter.MAX_DENIALS_PER_SESSION; i++) {
            counter.recordDenial("s1");
        }
        counter.onSessionDisconnect(disconnect("s1"));

        counter.register("s2");
        counter.recordDenial("s2");

        assertFalse(counter.isOverLimit("s2"), "новая сессия не наследует счётчик старой");
    }

    /**
     * sessionId может отсутствовать: фрейм пришёл иным каналом или хендлер вызван напрямую.
     * Счётчик обязан это пережить и не свалить обработку фрейма NPE.
     */
    @Test
    void nullSessionIdIsIgnored() {
        counter.register(null);
        counter.recordDenial(null);

        assertFalse(counter.isOverLimit(null));
        assertEquals(0, counter.trackedSessions());
    }
}
