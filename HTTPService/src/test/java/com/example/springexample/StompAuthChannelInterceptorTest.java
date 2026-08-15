package com.example.springexample;

import com.example.springexample.Services.ChatMembershipService;
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
    private final StompAuthChannelInterceptor interceptor =
            new StompAuthChannelInterceptor(verifier, membership);

    /** Фрейм от аутентифицированного пользователя с userId="9". */
    private static Message<byte[]> frame(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
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

        Mockito.verify(membership, Mockito.never()).isMember(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    void sendToForeignChatPassesInterceptorWithoutMembershipCheck() {
        assertNotNull(interceptor.preSend(frame(StompCommand.SEND, "/app/chat/send/77"), null));

        Mockito.verify(membership, Mockito.never()).isMember(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    void sendTypingStatusPassesInterceptorWithoutMembershipCheck() {
        assertNotNull(interceptor.preSend(frame(StompCommand.SEND, "/app/chat/user_statuses/77"), null));

        Mockito.verify(membership, Mockito.never()).isMember(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    void subscribeToOwnChatPasses() {
        Mockito.when(membership.isMember(5L, "9")).thenReturn(true);

        assertNotNull(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/mutual/chat/5"), null));
    }

    @Test
    void subscribeToForeignChatIsDenied() {
        Mockito.when(membership.isMember(77L, "9")).thenReturn(false);

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

        Mockito.verify(membership, Mockito.never()).isMember(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    void subscribeToForeignTypingChannelIsDenied() {
        Mockito.when(membership.isMember(77L, "9")).thenReturn(false);

        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/mutual/typing/77"), null));
    }

    /** Стережёт ловушку: глобальные картиночные каналы живут под тем же префиксом. */
    @Test
    void subscribeToGlobalImageChannelsPassesWithoutMembershipCheck() {
        assertNotNull(interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "/mutual/chat/image_chat_channel"), null));
        assertNotNull(interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "/mutual/chat/image_message_channel"), null));

        Mockito.verify(membership, Mockito.never()).isMember(Mockito.anyLong(), Mockito.anyString());
    }

    /** Неизвестный нечисловой хвост под /mutual/chat/ — отказ, а не тихий проход. */
    @Test
    void subscribeToUnknownNonNumericChatDestinationIsDenied() {
        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(
                        frame(StompCommand.SUBSCRIBE, "/mutual/chat/new_global_channel"), null));
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

        Mockito.verify(membership, Mockito.never()).isMember(Mockito.anyLong(), Mockito.anyString());
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

        Mockito.verify(membership, Mockito.never()).isMember(Mockito.anyLong(), Mockito.anyString());
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

        Mockito.verify(membership, Mockito.never()).isMember(Mockito.anyLong(), Mockito.anyString());
    }
}
