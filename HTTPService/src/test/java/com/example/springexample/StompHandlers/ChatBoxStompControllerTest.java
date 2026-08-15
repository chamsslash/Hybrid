package com.example.springexample.StompHandlers;

import com.example.grpc.DataTransferService;
import com.example.springexample.KafkaProducer;
import com.example.springexample.Services.ChatMembershipService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveListOperations;
import org.springframework.data.redis.core.ReactiveSetOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatBoxStompControllerTest {

    private final KafkaProducer kafkaProducer = Mockito.mock(KafkaProducer.class);
    private final SimpMessagingTemplate template = Mockito.mock(SimpMessagingTemplate.class);
    private final ChatMembershipService membership = Mockito.mock(ChatMembershipService.class);
    private final ChatListStompController chatListController = Mockito.mock(ChatListStompController.class);
    @SuppressWarnings("unchecked")
    private final ReactiveListOperations<String, String> list = Mockito.mock(ReactiveListOperations.class);

    @SuppressWarnings("unchecked")
    private ChatBoxStompController controller() {
        ReactiveRedisTemplate<String, String> redis = Mockito.mock(ReactiveRedisTemplate.class);
        ReactiveSetOperations<String, String> set = Mockito.mock(ReactiveSetOperations.class);
        Mockito.when(redis.opsForList()).thenReturn(list);
        Mockito.when(redis.opsForSet()).thenReturn(set);
        Mockito.when(list.leftPush(Mockito.anyString(), Mockito.anyString())).thenReturn(Mono.just(1L));
        Mockito.when(list.size(Mockito.anyString())).thenReturn(Mono.just(1L));
        Mockito.when(redis.expire(Mockito.anyString(), Mockito.any())).thenReturn(Mono.just(true));

        ChatBoxStompController c = new ChatBoxStompController(kafkaProducer);
        ReflectionTestUtils.setField(c, "template", template);
        ReflectionTestUtils.setField(c, "redisTemplate", redis);
        ReflectionTestUtils.setField(c, "chatListController", chatListController);
        ReflectionTestUtils.setField(c, "chatMembershipService", membership);
        return c;
    }

    private static DataTransferService.UserDataRequest user(long id, String name) {
        return DataTransferService.UserDataRequest.newBuilder().setId(id).setUsername(name).build();
    }

    @Test
    void serverOverwritesForgedIdentityFromFrameBody() {
        Mockito.when(membership.members(5L))
                .thenReturn(Mono.just(List.of(user(9L, "Дима"), user(7L, "Оля"))));

        // Клиент прислал чужое авторство и чужой чат в теле фрейма.
        ChatMessageDTO forged = new ChatMessageDTO();
        forged.setChat_id("77");
        forged.setUser_id("7");
        forged.setUsername("Оля");
        forged.setText("привет");

        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());

        controller().HandleChatMessage("5", principal, forged);

        ArgumentCaptor<ChatMessageDTO> sent = ArgumentCaptor.forClass(ChatMessageDTO.class);
        Mockito.verify(template).convertAndSend(Mockito.eq("/mutual/chat/5"), sent.capture());

        ChatMessageDTO actual = sent.getValue();
        assertEquals("5", actual.getChat_id(), "chat_id обязан прийти из адреса, а не из тела");
        assertEquals("9", actual.getUser_id(), "user_id обязан прийти из принципала, а не из тела");
        assertEquals("Дима", actual.getUsername(), "username обязан прийти из списка участников");
        assertEquals("привет", actual.getText(), "текст — единственное, что берётся из тела");
        assertNotNull(actual.getTimestamp(), "время проставляет сервер");
    }

    @Test
    void previewFansOutToAllChatMembers() {
        Mockito.when(membership.members(5L))
                .thenReturn(Mono.just(List.of(user(9L, "Дима"), user(7L, "Оля"))));

        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setText("привет");
        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());

        controller().HandleChatMessage("5", principal, dto);

        ArgumentCaptor<java.util.ArrayList<String>> ids = ArgumentCaptor.forClass(java.util.ArrayList.class);
        Mockito.verify(chatListController).ChangeChatPreview(ids.capture(), Mockito.any());
        assertEquals(List.of("9", "7"), ids.getValue());
    }

    @Test
    void nothingIsBroadcastWhenMembershipLookupFails() {
        Mockito.when(membership.members(5L))
                .thenReturn(Mono.error(new IllegalStateException("messegerparody недоступен")));

        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setText("привет");
        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());

        controller().HandleChatMessage("5", principal, dto);

        Mockito.verify(template, Mockito.never()).convertAndSend(Mockito.anyString(), Mockito.any(Object.class));
        Mockito.verify(kafkaProducer, Mockito.never()).send(Mockito.anyString());
        Mockito.verify(chatListController, Mockito.never()).ChangeChatPreview(Mockito.any(), Mockito.any());
        Mockito.verify(list, Mockito.never()).leftPush(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void typingStatusIdentityComesFromPrincipalNotBody() {
        Mockito.when(membership.members(5L))
                .thenReturn(Mono.just(List.of(user(9L, "Дима"), user(7L, "Оля"))));

        StatusUserDTO forged = new StatusUserDTO();
        forged.setUser_id("7");
        forged.setUser_name("Оля");
        forged.setChat_id("77");
        forged.setStatus("START");

        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());

        controller().HandleChangeOfUserStatus("5", principal, forged);

        ArgumentCaptor<StatusUserDTO> sent = ArgumentCaptor.forClass(StatusUserDTO.class);
        Mockito.verify(template).convertAndSend(Mockito.eq("/mutual/typing/5"), sent.capture());

        StatusUserDTO actual = sent.getValue();
        assertEquals("9", actual.getUser_id());
        assertEquals("Дима", actual.getUser_name());
        assertEquals("5", actual.getChat_id());
    }

    @Test
    void typingStatusFansOutPerMemberAndNotGlobally() {
        Mockito.when(membership.members(5L))
                .thenReturn(Mono.just(List.of(user(9L, "Дима"), user(7L, "Оля"))));

        StatusUserDTO dto = new StatusUserDTO();
        dto.setStatus("START");
        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());

        controller().HandleChangeOfUserStatus("5", principal, dto);

        Mockito.verify(template).convertAndSend(Mockito.eq("/mutual/chatlist/typing/9"), Mockito.any(Object.class));
        Mockito.verify(template).convertAndSend(Mockito.eq("/mutual/chatlist/typing/7"), Mockito.any(Object.class));
        // Глобальный канал удалён — утечка графа общения закрыта.
        Mockito.verify(template, Mockito.never())
                .convertAndSend(Mockito.eq("/mutual/typing_statuses_channel"), Mockito.any(Object.class));
    }
}
