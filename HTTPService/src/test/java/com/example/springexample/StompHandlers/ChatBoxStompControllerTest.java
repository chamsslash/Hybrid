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
    private final com.example.springexample.StompDenialCounter denialCounter =
            Mockito.mock(com.example.springexample.StompDenialCounter.class);
    private final StompErrorNotifier errorNotifier = Mockito.mock(StompErrorNotifier.class);
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
        ReflectionTestUtils.setField(c, "denialCounter", denialCounter);
        ReflectionTestUtils.setField(c, "errorNotifier", errorNotifier);
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

        controller().HandleChatMessage("5", principal, null, "sess-1", forged);

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

        controller().HandleChatMessage("5", principal, null, "sess-1", dto);

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

        controller().HandleChatMessage("5", principal, null, "sess-1", dto);

        // Отбивка отправителю на /private/9 (beads isf) — единственное, что теперь уходит наружу.
        Mockito.verify(template, Mockito.never()).convertAndSend(Mockito.eq("/mutual/chat/5"), Mockito.any(Object.class));
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

        controller().HandleChangeOfUserStatus("5", principal, "sess-1", forged);

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

        controller().HandleChatMessage("5", principal, null, "sess-1", dto);

        // Отбивка отправителю на /private/9 (beads isf) — единственное, что теперь уходит наружу.
        Mockito.verify(template, Mockito.never()).convertAndSend(Mockito.eq("/mutual/chat/5"), Mockito.any(Object.class));
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

        controller().HandleChangeOfUserStatus("5", principal, "sess-1", dto);

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

        controller().HandleChatMessage("+7", principal, null, "sess-1", dto);

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

        controller().HandleChatMessage("007", principal, null, "sess-1", dto);

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

        controller().HandleChangeOfUserStatus("007", principal, "sess-1", dto);

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

        controller().HandleChangeOfUserStatus("5", principal, "sess-1", dto);

        Mockito.verify(template).convertAndSend(Mockito.eq("/mutual/chatlist/typing/9"), Mockito.any(Object.class));
        Mockito.verify(template).convertAndSend(Mockito.eq("/mutual/chatlist/typing/7"), Mockito.any(Object.class));
        // Глобальный канал удалён — утечка графа общения закрыта.
        Mockito.verify(template, Mockito.never())
                .convertAndSend(Mockito.eq("/mutual/typing_statuses_channel"), Mockito.any(Object.class));
    }

    /**
     * beads bwh: аватарка чата уезжает на адрес самого чата, а не в глобальный канал.
     * Раньше это был /mutual/chat/image_chat_channel — один адрес на всю систему, откуда
     * любой подписчик вычитывал chatId и objectKey чужих чатов. Теперь адрес несёт chatId,
     * и подписаться на него может только участник (проверку делает StompAuthChannelInterceptor).
     */
    @Test
    void chatImageEventGoesToPerChatAddress() {
        controller().UploadChatImageFromKafka(
                new com.example.springexample.ImageUploadDTO("chatimage", "5", "chatimage/5/uuid.jpg"));

        Mockito.verify(template).convertAndSend(Mockito.eq("/mutual/chat_image/5"), Mockito.any(Object.class));
        Mockito.verify(template, Mockito.never())
                .convertAndSend(Mockito.eq("/mutual/chat/image_chat_channel"), Mockito.any(Object.class));
    }

    /**
     * beads bwh: у события userimage targetId — это userId (аватарка при регистрации,
     * WEBFLUX_Service.Upload_image(..., "userimage")), а НЕ chatId, поэтому per-chat адрес
     * для него невозможен в принципе. Уезжает на пер-юзерный адрес, закрытый той же
     * проверкой личности, что и остальные /mutual/...-каналы конкретного пользователя.
     */
    @Test
    void userImageEventGoesToPerUserAddress() {
        controller().UploadMessageImageFromKafka(
                new com.example.springexample.ImageUploadDTO("userimage", "42", "userimage/42/uuid.png"));

        Mockito.verify(template).convertAndSend(Mockito.eq("/mutual/user_image/42"), Mockito.any(Object.class));
        Mockito.verify(template, Mockito.never())
                .convertAndSend(Mockito.eq("/mutual/chat/image_message_channel"), Mockito.any(Object.class));
    }

    /**
     * targetId из Kafka — внешние данные, в адрес он попадает подстановкой. Нечисловой
     * targetId не должен порождать адрес вида /mutual/chat_image/../.. — рассылки нет вовсе.
     * Ведущие нули канонизируются, иначе "007" уехал бы на /mutual/chat_image/007, а
     * участники чата 7 слушают /mutual/chat_image/7 (сценарий 007 из beads g9x).
     */
    @Test
    void malformedImageTargetIdIsNotBroadcastAndLeadingZeroesAreCanonicalized() {
        ChatBoxStompController c = controller();

        c.UploadChatImageFromKafka(
                new com.example.springexample.ImageUploadDTO("chatimage", "../7", "chatimage/x/uuid.jpg"));
        c.UploadMessageImageFromKafka(
                new com.example.springexample.ImageUploadDTO("userimage", "", "userimage/x/uuid.png"));

        Mockito.verify(template, Mockito.never()).convertAndSend(Mockito.anyString(), Mockito.any(Object.class));

        c.UploadChatImageFromKafka(
                new com.example.springexample.ImageUploadDTO("chatimage", "007", "chatimage/007/uuid.jpg"));

        Mockito.verify(template).convertAndSend(Mockito.eq("/mutual/chat_image/7"), Mockito.any(Object.class));
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
            controller.HandleChatMessage("5", principal, null, "sess-1", dto);
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

        controller().HandleChatMessage("5", principal, null, "sess-1", forged);

        ArgumentCaptor<ChatMessageDTO> sent = ArgumentCaptor.forClass(ChatMessageDTO.class);
        Mockito.verify(template).convertAndSend(Mockito.eq("/mutual/chat/5"), sent.capture());

        String actual = sent.getValue().getTimestamp();
        assertNotEquals("1999-01-01T00:00:00Z", actual, "время из тела фрейма обязано быть затёрто сервером");
        assertFalse(Instant.parse(actual).isBefore(beforeCall),
                "сервер проставляет время не раньше момента обработки фрейма");
    }

    /**
     * Время сообщения берётся из заголовка, который проставил StompFrameTimestampInterceptor
     * в потоке сессии (beads 525). Хендлер снимать время сам не имеет права: вызовы
     * @MessageMapping раскидываются по пулу clientInboundChannel, и порядок входа в хендлер
     * не совпадает с порядком прихода фреймов — живая проверка первой версии фикса показала
     * ровно это (разброс упал с 90 мс до 7 мс, но порядок остался вперемешку).
     */
    @Test
    void timestampComesFromFrameHeaderNotFromHandlerEntry() {
        Mockito.when(membership.members(5L))
                .thenReturn(Mono.just(List.of(user(9L, "Дима"))));

        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setText("привет");
        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());

        controller().HandleChatMessage("5", principal, "2020-05-05T05:05:05Z", "sess-1", dto);

        ArgumentCaptor<ChatMessageDTO> sent = ArgumentCaptor.forClass(ChatMessageDTO.class);
        Mockito.verify(template).convertAndSend(Mockito.eq("/mutual/chat/5"), sent.capture());
        assertEquals("2020-05-05T05:05:05Z", sent.getValue().getTimestamp(),
                "хендлер обязан использовать время фрейма, а не снимать своё");
    }

    /**
     * Fallback: фрейм пришёл без заголовка (иной канал, прямой вызов) — время всё равно
     * серверное и не раньше момента обработки. Пустой timestamp сломал бы сортировку
     * истории (ORDER BY time_stamp), поэтому пустым он остаться не может (beads 525).
     */
    @Test
    void missingFrameTimestampFallsBackToServerTime() {
        Mockito.when(membership.members(5L))
                .thenReturn(Mono.just(List.of(user(9L, "Дима"))));

        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setText("привет");
        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());
        Instant beforeCall = Instant.now();

        controller().HandleChatMessage("5", principal, "   ", "sess-1", dto);

        ArgumentCaptor<ChatMessageDTO> sent = ArgumentCaptor.forClass(ChatMessageDTO.class);
        Mockito.verify(template).convertAndSend(Mockito.eq("/mutual/chat/5"), sent.capture());
        String actual = sent.getValue().getTimestamp();
        assertNotNull(actual, "время не может остаться пустым — по нему сортируется история");
        assertFalse(Instant.parse(actual).isBefore(beforeCall));
    }

    /**
     * beads isf, центральный сторож. Отказ по членству до этого тикета был одним log.warn:
     * пользователь со старой вкладкой (его убрали из чата) писал в пустоту, а фронт чистил
     * поле ввода сразу после send — текст пропадал бесследно. Отбивка уходит персонально
     * отправителю и НЕ должна попасть в адрес чата: туда её увидели бы все участники.
     */
    @Test
    void membershipDenialSendsFeedbackToSenderOnly() {
        Mockito.when(membership.members(5L))
                .thenReturn(Mono.just(List.of(user(7L, "Оля"))));

        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setText("привет");
        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());

        controller().HandleChatMessage("5", principal, null, "sess-1", dto);

        // Форма и адрес отбивки (type/chat_id) проверяются отдельно, в
        // notifierSendsErrorToPersonalDestination — здесь контроллер лишь обязан позвать
        // notifier с правильными аргументами (beads 8wh).
        Mockito.verify(errorNotifier).sendToUser(Mockito.eq("9"), Mockito.eq("5"),
                Mockito.eq("NOT_A_MEMBER"), Mockito.notNull());

        Mockito.verify(template, Mockito.never())
                .convertAndSend(Mockito.eq("/mutual/chat/5"), Mockito.any(Object.class));
    }

    /**
     * Вторая ветка потери сообщения (beads isf): список участников не получен (MessegerParody
     * недоступен). Сообщение теряется ровно так же, значит и отбивка нужна такая же —
     * иначе пользователь молча теряет текст при любой заминке gRPC.
     */
    @Test
    void membershipLookupFailureSendsFeedbackToSender() {
        Mockito.when(membership.members(5L))
                .thenReturn(Mono.error(new IllegalStateException("messegerparody недоступен")));

        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setText("привет");
        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());

        controller().HandleChatMessage("5", principal, null, "sess-1", dto);

        Mockito.verify(errorNotifier).sendToUser(Mockito.eq("9"), Mockito.eq("5"),
                Mockito.eq("MEMBERSHIP_UNAVAILABLE"), Mockito.notNull());
    }

    /**
     * Стоп-кран beads isf: отказ по членству — это то, что аутентифицированный клиент может
     * повторять бесконечно, каждый раз оплачивая серверу gRPC-вызов. Контроллер обязан
     * отметить его в счётчике сессии, иначе StompAuthChannelInterceptor не сможет оборвать серию.
     */
    @Test
    void membershipDenialIsCountedAgainstSession() {
        Mockito.when(membership.members(5L))
                .thenReturn(Mono.just(List.of(user(7L, "Оля"))));

        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setText("привет");
        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());

        controller().HandleChatMessage("5", principal, null, "sess-1", dto);

        Mockito.verify(denialCounter).recordDenial("sess-1");
    }

    /**
     * Недоступность MessegerParody в счётчик НЕ идёт (beads isf): это отказ сервера, а не
     * клиента, и считать его против сессии значило бы рвать соединения всем живым
     * пользователям после пятого сообщения на время любой заминки gRPC.
     */
    @Test
    void membershipLookupFailureIsNotCountedAgainstSession() {
        Mockito.when(membership.members(5L))
                .thenReturn(Mono.error(new IllegalStateException("messegerparody недоступен")));

        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setText("привет");
        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());

        controller().HandleChatMessage("5", principal, null, "sess-1", dto);

        Mockito.verifyNoInteractions(denialCounter);
    }

    /**
     * До 8wh у пути SEND не было таймаута вовсе: зависший gRPC-вызов MessegerParody просто
     * никогда не вызывал колбэк, и сообщение исчезало молча без единой отбивки. Таймаут
     * в members() (beads 8wh) превращает зависание в TimeoutException, но здесь важно не
     * само наличие таймаута (это стережёт тест ChatMembershipService), а то, что эта ошибка
     * уходит в ту же ветку сбоя сервиса, что и любой другой отказ MessegerParody — а не
     * притворяется отказом по членству и не рвёт сессию живого пользователя через счётчик.
     */
    @Test
    void sendWithHangingMembershipCallReportsServiceFailureNotDenial() {
        Mockito.when(membership.members(5L))
                .thenReturn(Mono.error(new java.util.concurrent.TimeoutException("проба")));

        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setText("привет");
        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());

        controller().HandleChatMessage("5", principal, null, "sess-1", dto);

        Mockito.verify(errorNotifier).sendToUser(Mockito.eq("9"), Mockito.eq("5"),
                Mockito.eq("MEMBERSHIP_UNAVAILABLE"), Mockito.anyString());
        Mockito.verify(denialCounter, Mockito.never()).recordDenial(Mockito.anyString());
    }

    /**
     * Статус набора текста — фоновое событие, пользователь его осознанно не отправлял, и
     * отбивка на каждое нажатие клавиши превратилась бы в поток тостов. Но фрейм стоит
     * ровно того же gRPC-вызова, что и сообщение, поэтому в счётчик отказов он идёт (beads isf).
     */
    @Test
    void typingDenialSendsNoFeedbackButIsCountedAgainstSession() {
        Mockito.when(membership.members(5L))
                .thenReturn(Mono.just(List.of(user(7L, "Оля"))));

        StatusUserDTO dto = new StatusUserDTO();
        dto.setStatus("START");
        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());

        controller().HandleChangeOfUserStatus("5", principal, "sess-1", dto);

        Mockito.verify(template, Mockito.never()).convertAndSend(Mockito.anyString(), Mockito.any(Object.class));
        Mockito.verify(denialCounter).recordDenial("sess-1");
    }

    /**
     * До beads isf в репозитории не было ни одного @MessageExceptionHandler: любое
     * необработанное исключение в @MessageMapping тонуло так же бесшумно, как отказ по
     * членству. Отправителю уходит нейтральная отбивка, внутренности исключения — только в лог.
     */
    @Test
    void uncaughtHandlerExceptionSendsNeutralFeedbackWithoutInternals() {
        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());

        controller().handleUncaughtStompException(
                new IllegalStateException("jdbc:postgresql://user:hunter2@db/messeger"),
                principal, "/app/chat/send/5");

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(errorNotifier).sendToUser(Mockito.eq("9"), Mockito.eq("5"),
                Mockito.eq("INTERNAL_ERROR"), messageCaptor.capture());
        assertFalse(messageCaptor.getValue().contains("hunter2"),
                "текст исключения наружу не уезжает — там бывают внутренности вроде строки подключения");
    }

    /**
     * Идентификатор сообщения — такое же серверное поле, как user_id/chat_id/username/
     * timestamp (инвариант beads g9x). Клиент, которому удалось бы протащить своё значение
     * в тело фрейма, смог бы заранее занять чужой message_id и тем самым заблокировать
     * вставку настоящего сообщения: ON CONFLICT DO NOTHING на стороне консьюмера молча
     * выбросил бы его как дубль.
     */
    @Test
    void serverOverwritesForgedMessageIdFromFrameBody() {
        Mockito.when(membership.members(5L))
                .thenReturn(Mono.just(List.of(user(9L, "Дима"))));

        ChatMessageDTO forged = new ChatMessageDTO();
        forged.setText("привет");
        forged.setMessage_id("00000000-0000-0000-0000-000000000000");

        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());

        controller().HandleChatMessage("5", principal, null, "sess-1", forged);

        ArgumentCaptor<ChatMessageDTO> sent = ArgumentCaptor.forClass(ChatMessageDTO.class);
        Mockito.verify(template).convertAndSend(Mockito.eq("/mutual/chat/5"), sent.capture());

        String actual = sent.getValue().getMessage_id();
        assertNotNull(actual, "message_id обязан проставлять сервер");
        assertNotEquals("00000000-0000-0000-0000-000000000000", actual,
                "клиентское значение message_id обязано быть затёрто");
        assertDoesNotThrow(() -> java.util.UUID.fromString(actual));
    }

    /**
     * Один и тот же идентификатор обязан уйти и в realtime-эхо, и в Kafka: по нему консьюмер
     * отличает переигранную запись от нового сообщения (ON CONFLICT DO NOTHING). Если бы id
     * генерировался после convertAndSend или дважды, идемпотентность стала бы фикцией —
     * каждая переигровка давала бы новую строку.
     */
    @Test
    void sameMessageIdGoesToBroadcastAndToKafka() {
        Mockito.when(membership.members(5L))
                .thenReturn(Mono.just(List.of(user(9L, "Дима"))));

        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setText("привет");
        Principal principal = new UsernamePasswordAuthenticationToken("9", null, List.of());

        controller().HandleChatMessage("5", principal, null, "sess-1", dto);

        ArgumentCaptor<ChatMessageDTO> sent = ArgumentCaptor.forClass(ChatMessageDTO.class);
        Mockito.verify(template).convertAndSend(Mockito.eq("/mutual/chat/5"), sent.capture());
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        Mockito.verify(kafkaProducer).send(payload.capture());

        String broadcastId = sent.getValue().getMessage_id();
        Map<String, Object> json = new com.google.gson.Gson().fromJson(payload.getValue(), Map.class);
        assertEquals(broadcastId, json.get("message_id"),
                "имя поля в JSON и само значение обязаны совпасть с разосланным эхо");
    }

    /**
     * StompErrorNotifier — отдельный владелец отправки отбивок (beads 8wh), вынесенный из
     * контроллера, потому что тот же механизм понадобился StompAuthChannelInterceptor.
     * Проверяется напрямую, в обход контроллера: адрес, форма ChatErrorDTO и то, что все
     * четыре параметра доходят до шаблона без потерь.
     */
    @Test
    void notifierSendsErrorToPersonalDestination() {
        SimpMessagingTemplate template = Mockito.mock(SimpMessagingTemplate.class);
        StompErrorNotifier notifier = new StompErrorNotifier(template);

        notifier.sendToUser("9", "5", "SUBSCRIPTION_UNAVAILABLE", "текст");

        ArgumentCaptor<ChatErrorDTO> captor = ArgumentCaptor.forClass(ChatErrorDTO.class);
        Mockito.verify(template).convertAndSend(Mockito.eq("/private/9"), captor.capture());
        assertEquals("error", captor.getValue().getType());
        assertEquals("5", captor.getValue().getChat_id());
        assertEquals("SUBSCRIPTION_UNAVAILABLE", captor.getValue().getCode());
        assertEquals("текст", captor.getValue().getMessage());
    }
}
