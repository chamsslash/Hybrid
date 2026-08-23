package com.example.springexample;

import com.example.springexample.Services.ChatMembershipService;
import com.example.springexample.Services.MembershipDecision;
import com.example.springexample.StompHandlers.StompErrorNotifier;
import com.example.springexample.Utils.AccessTokenVerifier;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StompAuthChannelInterceptorTest {

    private final AccessTokenVerifier verifier = Mockito.mock(AccessTokenVerifier.class);
    private final ChatMembershipService membership = Mockito.mock(ChatMembershipService.class);
    private final StompDenialCounter denialCounter = new StompDenialCounter();
    private final StompErrorNotifier errorNotifier = Mockito.mock(StompErrorNotifier.class);
    private final StompAuthChannelInterceptor interceptor =
            new StompAuthChannelInterceptor(verifier, membership, denialCounter, errorNotifier);

    /** Фрейм от аутентифицированного пользователя с userId="9". */
    private static Message<byte[]> frame(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        accessor.setUser(new UsernamePasswordAuthenticationToken("9", null, List.of()));
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /** Тот же фрейм, но с идентификатором STOMP-сессии — ключом счётчика отказов. */
    private static Message<byte[]> frame(StompCommand command, String destination, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        accessor.setSessionId(sessionId);
        accessor.setUser(new UsernamePasswordAuthenticationToken("9", null, List.of()));
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /**
     * Членство на SEND в чужой/свой чат больше не проверяет интерцептор (beads g9x) —
     * перенесено в ChatBoxStompController, где список участников и так нужен для рассылки.
     * Здесь стережём только то, что осталось за интерцептором: аутентификация и аллоулист
     * /app/. Оба случая проходят преSend одинаково, membership не опрашивается вовсе.
     */
    @Test
    void sendToOwnChatPassesWithoutMembershipCheck() {
        assertNotNull(interceptor.preSend(frame(StompCommand.SEND, "/app/chat/send/5"), null));

        Mockito.verify(membership, Mockito.never()).decideBlocking(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    void sendToForeignChatPassesInterceptorWithoutMembershipCheck() {
        assertNotNull(interceptor.preSend(frame(StompCommand.SEND, "/app/chat/send/77"), null));

        Mockito.verify(membership, Mockito.never()).decideBlocking(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    void sendTypingStatusPassesInterceptorWithoutMembershipCheck() {
        assertNotNull(interceptor.preSend(frame(StompCommand.SEND, "/app/chat/user_statuses/77"), null));

        Mockito.verify(membership, Mockito.never()).decideBlocking(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    void subscribeToOwnChatPasses() {
        Mockito.when(membership.decideBlocking(5L, "9")).thenReturn(MembershipDecision.MEMBER);

        assertNotNull(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/mutual/chat/5"), null));
    }

    @Test
    void subscribeToForeignChatIsDenied() {
        Mockito.when(membership.decideBlocking(77L, "9")).thenReturn(MembershipDecision.NOT_MEMBER);

        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/mutual/chat/77"), null));
    }

    /**
     * Ветка catch (NumberFormatException) в parseChatIdOrDeny жива и достижима через SUBSCRIBE,
     * хотя на SEND chatId больше не парсится интерцептором (beads g9x). Двадцать девяток не
     * влезают в long — Long.parseLong бросает NumberFormatException, интерцептор обязан
     * превратить это в отказ, а не в необработанное исключение.
     */
    @Test
    void subscribeWithOverflowingChatIdIsDenied() {
        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(
                        frame(StompCommand.SUBSCRIBE, "/mutual/chat/99999999999999999999"), null));

        Mockito.verify(membership, Mockito.never()).decideBlocking(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    void subscribeToForeignTypingChannelIsDenied() {
        Mockito.when(membership.decideBlocking(77L, "9")).thenReturn(MembershipDecision.NOT_MEMBER);

        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/mutual/typing/77"), null));
    }

    /**
     * beads bwh: прежде этот тест утверждал ОБРАТНОЕ — что три глобальных картиночных
     * канала проходят без проверки членства. Именно то поведение и было уязвимостью:
     * по ним летел ImageUploadDTO{targetType,targetId,objectKey} по ВСЕМ чатам системы,
     * так что любой аутентифицированный пользователь собирал id чужих чатов и ключи
     * объектов MinIO. Каналы переехали на per-chat/per-user адреса, а сами эти три
     * адреса обязаны остаться закрытыми навсегда — тест развёрнут, а не удалён,
     * чтобы старый контракт не вернулся вместе с откатом фронта.
     */
    @Test
    void subscribeToLegacyGlobalImageChannelsIsDenied() {
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "/mutual/chat/image_chat_channel"), null));
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "/mutual/chat/image_message_channel"), null));
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "/mutual/chat_list/image_chat_channel"), null));

        Mockito.verify(membership, Mockito.never()).decideBlocking(Mockito.anyLong(), Mockito.anyString());
    }

    /** Неизвестный нечисловой хвост под /mutual/chat/ — отказ, а не тихий проход. */
    @Test
    void subscribeToUnknownNonNumericChatDestinationIsDenied() {
        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(
                        frame(StompCommand.SUBSCRIBE, "/mutual/chat/new_global_channel"), null));
    }

    /**
     * Центральный сторож beads bwh. Адрес выдуман на месте, обработчика за ним нет —
     * живьём на стенде (аккаунт sunny, id=4) он был ALLOWED, потому что не подходил ни
     * под один известный префикс и проваливался в allow-by-default. Теперь незнакомое
     * семейство адресов — отказ, и membership по нему даже не опрашивается.
     */
    @Test
    void subscribeToInventedDestinationIsDenied() {
        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(
                        frame(StompCommand.SUBSCRIBE, "/mutual/chat_list/anything"), null));
        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/mutual/whatever"), null));
        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/news"), null));

        Mockito.verify(membership, Mockito.never()).decideBlocking(Mockito.anyLong(), Mockito.anyString());
    }

    /** Канал аватарки чата, в котором пользователь состоит, — обычная подписка участника. */
    @Test
    void subscribeToOwnChatImageChannelPasses() {
        Mockito.when(membership.decideBlocking(5L, "9")).thenReturn(MembershipDecision.MEMBER);

        assertNotNull(interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "/mutual/chat_image/5"), null));
    }

    /** beads bwh, acceptance 1: канал картинок ЧУЖОГО чата закрыт той же проверкой членства. */
    @Test
    void subscribeToForeignChatImageChannelIsDenied() {
        Mockito.when(membership.decideBlocking(77L, "9")).thenReturn(MembershipDecision.NOT_MEMBER);

        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(
                        frame(StompCommand.SUBSCRIBE, "/mutual/chat_image/77"), null));
    }

    @Test
    void subscribeToForeignChatlistImageChannelIsDenied() {
        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(
                        frame(StompCommand.SUBSCRIBE, "/mutual/chatlist/image/42"), null));
    }

    @Test
    void subscribeToForeignUserImageChannelIsDenied() {
        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(
                        frame(StompCommand.SUBSCRIBE, "/mutual/user_image/42"), null));
    }

    /**
     * Пер-юзерный адрес обязан ЗАКАНЧИВАТЬСЯ на свой userId, а не просто содержать его
     * в конце: прежняя проверка была endsWith("/" + userId), и "/mutual/chatlist/typing/42/9"
     * её проходил — очередной выдуманный адрес, проскочивший бы и мимо deny-by-default,
     * потому что префикс-то знакомый. Хвост сверяется целиком (beads bwh).
     */
    @Test
    void subscribeToPerUserDestinationWithExtraSegmentsIsDenied() {
        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(
                        frame(StompCommand.SUBSCRIBE, "/mutual/chatlist/typing/42/9"), null));
    }

    /**
     * Регрессия аудита фронта (beads bwh, acceptance 3/4). Deny-by-default опасен ровно
     * одним — молча отрезать живую подписку. Здесь перечислены ВСЕ адреса, на которые
     * подписывается приложение после переезда картиночных каналов: 4 из chatlist.view.js
     * и 3 из chat.view.js. Список синхронизирован с белым списком в интерцепторе; если
     * фронт заведёт новую подписку, она обязана появиться и здесь, и там.
     */
    @Test
    void everyFrontendSubscriptionFromAuditPasses() {
        Mockito.when(membership.decideBlocking(5L, "9")).thenReturn(MembershipDecision.MEMBER);

        // chatlist.view.js — пер-юзерные каналы списка чатов
        assertNotNull(interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "/mutual/chatlist/change_chatpreview/9"), null));
        assertNotNull(interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "/mutual/chatlist/list_update/9"), null));
        assertNotNull(interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "/mutual/chatlist/image/9"), null));
        assertNotNull(interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "/mutual/chatlist/typing/9"), null));

        // chat.view.js — каналы конкретного чата, пользователь в нём состоит
        assertNotNull(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/mutual/chat/5"), null));
        assertNotNull(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/mutual/typing/5"), null));
        assertNotNull(interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "/mutual/chat_image/5"), null));

        // chat.view.js — персональная отбивка отказа на SEND (beads isf)
        assertNotNull(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/private/9"), null));
    }

    @Test
    void perUserTypingDestinationOfAnotherUserIsDenied() {
        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(
                        frame(StompCommand.SUBSCRIBE, "/mutual/chatlist/typing/42"), null));
    }

    @Test
    void ownPerUserTypingDestinationPasses() {
        assertNotNull(interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "/mutual/chatlist/typing/9"), null));
    }

    /** C1: подписка по Ant-шаблону обходила бы префиксные проверки — брокер матчит /mutual/** против всего. */
    @Test
    void subscribeToMutualWildcardIsDenied() {
        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/mutual/**"), null));

        Mockito.verify(membership, Mockito.never()).decideBlocking(Mockito.anyLong(), Mockito.anyString());
    }

    /** C1: глобальный "поймать всё" шаблон добирает и /private/**, отклоняется той же проверкой. */
    @Test
    void subscribeToGlobalWildcardIsDenied() {
        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/**"), null));
    }

    /** C2: SEND напрямую на брокерный адрес /mutual/chat/{id} минует @MessageMapping — доставился бы подписчикам чата напрямую. */
    @Test
    void sendDirectlyToMutualChatBrokerAddressIsDenied() {
        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(frame(StompCommand.SEND, "/mutual/chat/77"), null));

        Mockito.verify(membership, Mockito.never()).decideBlocking(Mockito.anyLong(), Mockito.anyString());
    }

    /** C2: SEND напрямую на /private/{userId} — тот же обход, подделка личного сообщения. */
    @Test
    void sendDirectlyToPrivateBrokerAddressIsDenied() {
        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(frame(StompCommand.SEND, "/private/42"), null));
    }

    /** Аллоулист не должен ломать другие /app/-адреса, не входящие в два проверяемых префикса. */
    @Test
    void sendToUnlistedAppDestinationPasses() {
        assertNotNull(interceptor.preSend(
                frame(StompCommand.SEND, "/app/some/other/handler"), null));

        Mockito.verify(membership, Mockito.never()).decideBlocking(Mockito.anyLong(), Mockito.anyString());
    }

    /** CONNECT с валидным токеном — он же заводит запись сессии в счётчике отказов (beads isf). */
    private Message<byte[]> connectFrame(String sessionId) {
        Mockito.when(verifier.verify(Mockito.anyString()))
                .thenReturn(new UsernamePasswordAuthenticationToken("9", null, List.of()));
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(sessionId);
        accessor.setNativeHeader("Authorization", "Bearer valid");
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /**
     * Стоп-кран beads isf. Отказ по членству живёт в контроллере и наружу отдаёт только
     * отбивку — сессию он не рвёт, поэтому серия отказов ничем не ограничена: каждый фрейм
     * стоит одного getAllUsersByChatId в MessegerParody. После порога интерцептор обязан
     * отклонить SEND сам, ДО пропуска фрейма, то есть без единого обращения к членству.
     */
    @Test
    void sendIsDeniedAfterDenialThresholdWithoutTouchingMembership() {
        interceptor.preSend(connectFrame("sess-1"), null);

        for (int i = 1; i < StompDenialCounter.MAX_DENIALS_PER_SESSION; i++) {
            denialCounter.recordDenial("sess-1");
            assertNotNull(interceptor.preSend(frame(StompCommand.SEND, "/app/chat/send/77", "sess-1"), null),
                    "до порога фрейм обязан проходить: легитимный рассинхрон даёт один-два отказа");
        }
        denialCounter.recordDenial("sess-1");

        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(frame(StompCommand.SEND, "/app/chat/send/77", "sess-1"), null));

        Mockito.verifyNoInteractions(membership);
    }

    /** Счётчик чужой сессии не рвёт нашу: ключ — именно sessionId, а не пользователь. */
    @Test
    void denialsOfAnotherSessionDoNotDenySend() {
        interceptor.preSend(connectFrame("sess-1"), null);
        interceptor.preSend(connectFrame("sess-2"), null);
        for (int i = 0; i < StompDenialCounter.MAX_DENIALS_PER_SESSION; i++) {
            denialCounter.recordDenial("sess-1");
        }

        assertNotNull(interceptor.preSend(frame(StompCommand.SEND, "/app/chat/send/5", "sess-2"), null));
    }

    /**
     * Ядро beads 8wh. Транзиентная заминка ChatMembershipService (таймаут, сбой gRPC)
     * не должна стоить пользователю всей STOMP-сессии — раньше UNKNOWN трактовался как
     * отказ, preSend бросал исключение, и Spring рвал SockJS-соединение целиком.
     * Теперь UNKNOWN роняет только этот SUBSCRIBE-фрейм возвратом null; сессия жива,
     * клиент может повторить подписку.
     */
    @Test
    void subscribeWithUnknownMembershipDropsFrameWithoutKillingSession() {
        Mockito.when(membership.decideBlocking(5L, "9"))
                .thenReturn(MembershipDecision.UNKNOWN);

        Message<?> result = interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "/mutual/chat/5"), null);

        assertNull(result, "UNKNOWN обязан ронять фрейм возвратом null, а не исключением — "
                + "исключение из preSend рвёт сессию");
    }

    /** UNKNOWN обязан сопровождаться отбивкой на /private/{userId}, иначе клиент не узнает, что повторить подписку. */
    @Test
    void subscribeWithUnknownMembershipNotifiesUser() {
        Mockito.when(membership.decideBlocking(5L, "9"))
                .thenReturn(MembershipDecision.UNKNOWN);

        interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/mutual/chat/5"), null);

        Mockito.verify(errorNotifier).sendToUser("9", "5", "SUBSCRIPTION_UNAVAILABLE",
                "Не удалось проверить доступ к чату — пробуем ещё раз", "/mutual/chat/5");
        Mockito.verifyNoMoreInteractions(errorNotifier);
    }

    /**
     * Стережёт границу изменения beads 8wh: достоверный отказ по членству (NOT_MEMBER)
     * по-прежнему рвёт сессию исключением, как и до этого тикета — подписка на чужой
     * чат остаётся осознанным зондом, и разрыв сессии здесь работает тормозом. Если кто-то
     * распространит мягкое поведение UNKNOWN и на NOT_MEMBER, этот тест это поймает.
     */
    @Test
    void subscribeWithNotMemberStillThrowsAndKillsSession() {
        Mockito.when(membership.decideBlocking(5L, "9"))
                .thenReturn(MembershipDecision.NOT_MEMBER);

        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/mutual/chat/5"), null));
        Mockito.verifyNoInteractions(errorNotifier);
    }

    /** Обычный успешный путь не задет разводкой NOT_MEMBER/UNKNOWN: MEMBER по-прежнему пропускает фрейм без уведомлений. */
    @Test
    void subscribeWithMemberPassesFrameThrough() {
        Mockito.when(membership.decideBlocking(5L, "9"))
                .thenReturn(MembershipDecision.MEMBER);

        Message<byte[]> subscribe = frame(StompCommand.SUBSCRIBE, "/mutual/chat/5");

        assertSame(subscribe, interceptor.preSend(subscribe, null));
        Mockito.verifyNoInteractions(errorNotifier);
    }

    /**
     * Регрессия fail-open (beads 8wh, ревью раунд 1, F2). Незастабленный мок
     * {@code decideBlocking} отдаёт {@code null} — ни одна из трёх известных констант.
     * Ветка per-chat обязана трактовать «что угодно, кроме MEMBER» как отказ (deny-by-default,
     * доктрина всего класса), а не молча пропускать: до фикса код сравнивал только с
     * NOT_MEMBER/UNKNOWN и в остатке возвращал true, поэтому будущая четвёртая константа
     * enum (или любое иное не-MEMBER значение) провалилась бы в проход без единого красного
     * теста — ни один из существующих тестов на отказ не стабит decideBlocking именно так.
     */
    @Test
    void subscribeWithUnstubbedMembershipDecisionIsDenied() {
        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/mutual/chat/5"), null));
        Mockito.verifyNoInteractions(errorNotifier);
    }
}
