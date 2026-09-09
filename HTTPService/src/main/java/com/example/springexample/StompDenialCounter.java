package com.example.springexample;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Счётчик отказов на SEND в пределах одной STOMP-сессии (beads isf).
 *
 * Зачем он нужен. Пока проверка членства жила в {@code StompAuthChannelInterceptor},
 * отказ бросал {@code AccessDeniedException}, Spring отвечал ERROR-фреймом и закрывал
 * сессию — серия отказов сама себя обрывала. После переноса проверки в контроллер (beads g9x)
 * отказ стал одним {@code log.warn}, и аутентифицированный пользователь может бесконечно
 * долбить {@code /app/chat/send/<любой id>}: каждый фрейм — это один
 * {@code getAllUsersByChatId} в MessegerParody. Счётчик возвращает старое поведение
 * (ERROR-фрейм + разрыв), но уже без единого лишнего gRPC-вызова: контроллер отмечает отказ,
 * интерцептор проверяет порог ДО пропуска следующего фрейма.
 *
 * Разрыв — НЕ бан. Переподключение даёт новую сессию с чистым счётчиком, и это осознанно:
 * цель не запретить доступ, а сделать так, чтобы каждые {@link #MAX_DENIALS_PER_SESSION}
 * отказных фреймов стоили атакующему полного CONNECT-хендшейка. Не «чините» это как дырявый
 * бан — банить пользователя за отказ по членству нельзя, самый вероятный отказ в проде
 * легитимный: человека убрали из чата, а у него открыта старая вкладка.
 *
 * Запись рождается только на CONNECT ({@link #register}) и умирает только на разрыве
 * ({@link #onSessionDisconnect}). Инкремент запись не создаёт намеренно: он прилетает с
 * потока пула обработчиков и может опоздать за разрывом сессии — создав запись, такой
 * опоздавший отказ оставил бы в карте мусор, который уже некому убрать, то есть утечку
 * памяти на каждую когда-либо подключавшуюся сессию.
 *
 * Потокобезопасность обязательна: {@code preSend} исполняется в потоке сессии, а инкремент
 * из контроллера — в потоке пула {@code clientInboundChannel}, это разные потоки.
 */
@Slf4j
@Component
public class StompDenialCounter {

    /**
     * Порог отказов на сессию. Легитимный рассинхрон даёт один-два отказа, атака — сотни;
     * пять заведомо выше нормы и заведомо ниже полезной для атакующего амплификации.
     */
    public static final int MAX_DENIALS_PER_SESSION = 5;

    private final Map<String, AtomicInteger> denialsBySession = new ConcurrentHashMap<>();

    /** CONNECT прошёл аутентификацию — с этого момента отказы сессии считаются. */
    public void register(String sessionId) {
        if (sessionId == null) {
            return;
        }
        denialsBySession.put(sessionId, new AtomicInteger());
    }

    /** Отказ на SEND: фрейм разобран, gRPC-вызов оплачен, наружу не ушло ничего. */
    public void recordDenial(String sessionId) {
        if (sessionId == null) {
            return;
        }
        AtomicInteger denials = denialsBySession.computeIfPresent(
                sessionId, (id, counter) -> {
                    counter.incrementAndGet();
                    return counter;
                });
        if (denials != null && denials.get() >= MAX_DENIALS_PER_SESSION) {
            log.warn("Сессия {} набрала {} отказов на SEND — следующий фрейм будет отклонён с разрывом",
                    sessionId, denials.get());
        }
    }

    /** Проверяется интерцептором ДО пропуска фрейма, поэтому обязана быть дешёвой. */
    public boolean isOverLimit(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        AtomicInteger denials = denialsBySession.get(sessionId);
        return denials != null && denials.get() >= MAX_DENIALS_PER_SESSION;
    }

    /**
     * Spring публикует это событие при закрытии WebSocket-сессии — и на штатный DISCONNECT,
     * и на обрыв транспорта, поэтому запись убирается в любом сценарии выхода.
     */
    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        denialsBySession.remove(event.getSessionId());
    }

    /** Размер карты — единственный способ увидеть утечку записей из теста. */
    int trackedSessions() {
        return denialsBySession.size();
    }
}
