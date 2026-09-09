package com.example.springexample.StompHandlers;

import com.example.grpc.DataTransferService;
import com.example.springexample.ImageUploadDTO;
import com.example.springexample.Services.ChatMembershipService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Веерная рассылка аватарки чата в списки чатов участников (beads bwh).
 *
 * Раньше метод слал единственное сообщение на /mutual/chat_list/image_chat_channel —
 * глобальный адрес, который вдобавок лежал ВНЕ всех префиксов интерцептора
 * (chat_list через подчёркивание, тогда как PER_USER_PREFIXES знает /mutual/chatlist/
 * без подчёркивания), поэтому проваливался в allow-by-default и раздавал chatId и
 * objectKey всех чатов системы любому аутентифицированному подписчику.
 *
 * Схема рассылки повторяет typing-статусы (beads g9x): список чатов не знает заранее,
 * какие чаты в нём появятся, поэтому подписан на один пер-юзерный адрес, а сервер
 * веером раскладывает событие по участникам чата.
 */
class ChatListStompControllerTest {

    private final SimpMessagingTemplate template = Mockito.mock(SimpMessagingTemplate.class);
    private final ChatMembershipService membership = Mockito.mock(ChatMembershipService.class);

    private ChatListStompController controller() {
        ChatListStompController c = new ChatListStompController();
        ReflectionTestUtils.setField(c, "template", template);
        ReflectionTestUtils.setField(c, "chatMembershipService", membership);
        return c;
    }

    private static DataTransferService.UserDataRequest user(long id) {
        return DataTransferService.UserDataRequest.newBuilder().setId(id).setUsername("u" + id).build();
    }

    @Test
    void chatImageEventFansOutToChatMembersOnly() {
        Mockito.when(membership.members(5L)).thenReturn(Mono.just(List.of(user(9L), user(7L))));

        controller().UploadChatImageFromKafka(new ImageUploadDTO("chatimage", "5", "chatimage/5/uuid.jpg"));

        Mockito.verify(template).convertAndSend(Mockito.eq("/mutual/chatlist/image/9"), Mockito.any(Object.class));
        Mockito.verify(template).convertAndSend(Mockito.eq("/mutual/chatlist/image/7"), Mockito.any(Object.class));
        // Глобальный адрес удалён — утечка чужих chatId/objectKey закрыта.
        Mockito.verify(template, Mockito.never())
                .convertAndSend(Mockito.eq("/mutual/chat_list/image_chat_channel"), Mockito.any(Object.class));
        Mockito.verifyNoMoreInteractions(template);
    }

    /**
     * Fail-closed, как и везде, где список участников недоступен (beads g9x): без ответа
     * MessegerParody неизвестно, кому событие адресовано, — не рассылаем никому.
     */
    @Test
    void nothingIsSentWhenMembershipLookupFails() {
        Mockito.when(membership.members(5L))
                .thenReturn(Mono.error(new IllegalStateException("messegerparody недоступен")));

        controller().UploadChatImageFromKafka(new ImageUploadDTO("chatimage", "5", "chatimage/5/uuid.jpg"));

        Mockito.verify(template, Mockito.never()).convertAndSend(Mockito.anyString(), Mockito.any(Object.class));
    }

    /**
     * targetId приходит из Kafka — это внешние данные. Нечисловой id не должен уходить
     * ни в адрес рассылки, ни в gRPC-запрос членства.
     */
    @Test
    void malformedTargetIdIsNotBroadcast() {
        controller().UploadChatImageFromKafka(new ImageUploadDTO("chatimage", "abc", "chatimage/abc/uuid.jpg"));

        Mockito.verify(template, Mockito.never()).convertAndSend(Mockito.anyString(), Mockito.any(Object.class));
        Mockito.verify(membership, Mockito.never()).members(Mockito.anyLong());
    }
}
