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
import reactor.core.publisher.Sinks;

import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Членство теперь проверяется здесь, а не в StompAuthChannelInterceptor (beads g9x):
     * отправитель отсутствует в списке участников чата → рассылка не происходит,
     * никакого из побочных эффектов (broadcast/Kafka/preview/Redis) не случается.
     */
    @Test
    void messageIsNotBroadcastWhenSenderIsNotAChatMember() {
        Mockito.when(membership.members(5L))
                .thenReturn(Mono.just(List.of(user(7L, "Оля"))));

        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setText("привет");
        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());

        controller().HandleChatMessage("5", principal, dto);

        Mockito.verify(template, Mockito.never()).convertAndSend(Mockito.anyString(), Mockito.any(Object.class));
        Mockito.verify(kafkaProducer, Mockito.never()).send(Mockito.anyString());
        Mockito.verify(chatListController, Mockito.never()).ChangeChatPreview(Mockito.any(), Mockito.any());
        Mockito.verify(list, Mockito.never()).leftPush(Mockito.anyString(), Mockito.anyString());
    }

    /** Тот же контракт членства, что и для сообщений чата, но для статуса набора текста (beads g9x). */
    @Test
    void typingStatusIsNotBroadcastWhenSenderIsNotAChatMember() {
        Mockito.when(membership.members(5L))
                .thenReturn(Mono.just(List.of(user(7L, "Оля"))));

        StatusUserDTO dto = new StatusUserDTO();
        dto.setStatus("START");
        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());

        controller().HandleChangeOfUserStatus("5", principal, dto);

        Mockito.verify(template, Mockito.never()).convertAndSend(Mockito.anyString(), Mockito.any(Object.class));
        Mockito.verify(kafkaProducer, Mockito.never()).send(Mockito.anyString());
        Mockito.verify(chatListController, Mockito.never()).ChangeChatPreview(Mockito.any(), Mockito.any());
        Mockito.verify(list, Mockito.never()).leftPush(Mockito.anyString(), Mockito.anyString());
    }

    /**
     * Строгая валидация формата chatId (beads g9x): на SEND интерцептор больше не парсит
     * chatId, а голый Long.parseLong пропускает "+7". Контроллер обязан отказать сам,
     * ДО любого gRPC-вызова и побочного эффекта.
     */
    @Test
    void nonNumericChatIdCausesNoSideEffects() {
        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setText("привет");
        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());

        controller().HandleChatMessage("+7", principal, dto);

        Mockito.verify(membership, Mockito.never()).members(Mockito.anyLong());
        Mockito.verify(template, Mockito.never()).convertAndSend(Mockito.anyString(), Mockito.any(Object.class));
        Mockito.verify(kafkaProducer, Mockito.never()).send(Mockito.anyString());
        Mockito.verify(chatListController, Mockito.never()).ChangeChatPreview(Mockito.any(), Mockito.any());
        Mockito.verify(list, Mockito.never()).leftPush(Mockito.anyString(), Mockito.anyString());
    }

    /**
     * "007" проходит "\\d+", но Long.parseLong("007") == 7 — рассылка обязана уйти на
     * канонический адрес "/mutual/chat/7", а не на буквальный "/mutual/chat/007", иначе
     * живые подписчики чата 7 её не увидят (сценарий 007 из beads g9x).
     */
    @Test
    void leadingZeroesChatIdBroadcastsToCanonicalAddress() {
        Mockito.when(membership.members(7L))
                .thenReturn(Mono.just(List.of(user(9L, "Дима"))));

        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setText("привет");
        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());

        controller().HandleChatMessage("007", principal, dto);

        ArgumentCaptor<ChatMessageDTO> sent = ArgumentCaptor.forClass(ChatMessageDTO.class);
        Mockito.verify(template).convertAndSend(Mockito.eq("/mutual/chat/7"), sent.capture());
        assertEquals("7", sent.getValue().getChat_id());
    }

    /** Та же канонизация адреса, что и для сообщений чата, но для статуса набора текста (beads g9x). */
    @Test
    void leadingZeroesChatIdCanonicalizesTypingAddress() {
        Mockito.when(membership.members(7L))
                .thenReturn(Mono.just(List.of(user(9L, "Дима"))));

        StatusUserDTO dto = new StatusUserDTO();
        dto.setStatus("START");
        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());

        controller().HandleChangeOfUserStatus("007", principal, dto);

        ArgumentCaptor<StatusUserDTO> sent = ArgumentCaptor.forClass(StatusUserDTO.class);
        Mockito.verify(template).convertAndSend(Mockito.eq("/mutual/typing/7"), sent.capture());
        assertEquals("7", sent.getValue().getChat_id());
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

    /**
     * Время сообщения обязано фиксироваться в момент прихода фрейма, а не в момент возврата
     * gRPC-вызова members(chatId) (beads 525). Порядок ответов gRPC ничем не гарантирован:
     * на живом стенде 10 сообщений подряд от одного клиента разъехались на 90 мс и осели в БД
     * в порядке 03,01,06,05,08,10,07,09,04,02 — история сортируется по time_stamp, поэтому
     * каша переживала перезагрузку страницы. Здесь ответы членства выдаются искусственно
     * в ОБРАТНОМ порядке вызовов хендлера: со временем внутри колбэка timestamp'ы получились
     * бы строго убывающими, с временем на входе в хендлер — строго возрастающими.
     * Паузы нужны, чтобы соседние Instant.now() гарантированно различались и порядок был
     * наблюдаем, а не схлопывался в одно значение.
     */
    @Test
    void timestampFollowsHandlerCallOrderNotMembershipCompletionOrder() throws InterruptedException {
        final int total = 10;
        List<Sinks.One<List<DataTransferService.UserDataRequest>>> gates = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            gates.add(Sinks.one());
        }
        var stubbing = Mockito.when(membership.members(5L)).thenReturn(gates.get(0).asMono());
        for (int i = 1; i < total; i++) {
            stubbing = stubbing.thenReturn(gates.get(i).asMono());
        }

        ChatBoxStompController controller = controller();
        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());
        for (int i = 0; i < total; i++) {
            ChatMessageDTO dto = new ChatMessageDTO();
            dto.setText(String.format("ord-%02d", i + 1));
            controller.HandleChatMessage("5", principal, dto);
            Thread.sleep(2);
        }

        for (int i = total - 1; i >= 0; i--) {
            gates.get(i).tryEmitValue(List.of(user(9L, "Дима")));
            Thread.sleep(2);
        }

        ArgumentCaptor<ChatMessageDTO> sent = ArgumentCaptor.forClass(ChatMessageDTO.class);
        Mockito.verify(template, Mockito.times(total))
                .convertAndSend(Mockito.eq("/mutual/chat/5"), sent.capture());

        Map<String, Instant> stampByText = new HashMap<>();
        for (ChatMessageDTO dto : sent.getAllValues()) {
            stampByText.put(dto.getText(), Instant.parse(dto.getTimestamp()));
        }
        assertEquals(total, stampByText.size(), "каждое сообщение должно быть разослано ровно один раз");

        for (int i = 1; i < total; i++) {
            String prevText = String.format("ord-%02d", i);
            String curText = String.format("ord-%02d", i + 1);
            assertTrue(stampByText.get(prevText).isBefore(stampByText.get(curText)),
                    "timestamp " + curText + " (" + stampByText.get(curText) + ") обязан быть позже "
                            + prevText + " (" + stampByText.get(prevText) + "): порядок времени задаёт "
                            + "порядок прихода фреймов, а не порядок ответов gRPC");
        }
    }

    /**
     * Клиентский timestamp из тела фрейма игнорируется так же, как user_id/chat_id/username
     * (beads g9x), и перенос присвоения времени в начало хендлера (beads 525) это не ослабляет:
     * иначе клиент мог бы задать своему сообщению любое место в истории — она сортируется
     * по time_stamp.
     */
    @Test
    void clientSuppliedTimestampFromBodyIsIgnored() {
        Mockito.when(membership.members(5L))
                .thenReturn(Mono.just(List.of(user(9L, "Дима"))));

        ChatMessageDTO forged = new ChatMessageDTO();
        forged.setText("привет");
        forged.setTimestamp("1999-01-01T00:00:00Z");

        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());
        Instant beforeCall = Instant.now();

        controller().HandleChatMessage("5", principal, forged);

        ArgumentCaptor<ChatMessageDTO> sent = ArgumentCaptor.forClass(ChatMessageDTO.class);
        Mockito.verify(template).convertAndSend(Mockito.eq("/mutual/chat/5"), sent.capture());

        String actual = sent.getValue().getTimestamp();
        assertNotEquals("1999-01-01T00:00:00Z", actual, "время из тела фрейма обязано быть затёрто сервером");
        assertFalse(Instant.parse(actual).isBefore(beforeCall),
                "сервер проставляет время не раньше момента обработки фрейма");
    }
}
