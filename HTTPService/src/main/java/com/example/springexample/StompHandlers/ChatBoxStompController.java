package com.example.springexample.StompHandlers;




import com.example.springexample.ImageUploadDTO;
import com.example.springexample.KafkaProducer;
import com.example.springexample.Services.ChatContextService;
import com.example.springexample.Services.ChatMembershipService;
import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Controller
public class ChatBoxStompController {
    private final KafkaProducer kafkaProducer;
    private final Gson gson = new Gson();
    @Autowired
    SimpMessagingTemplate template;
    @Autowired
    ReactiveRedisTemplate<String,String> redisTemplate;
    @Autowired
    com.example.springexample.StompHandlers.ChatListStompController chatListController;
    @Autowired
    ChatMembershipService chatMembershipService;
    @Autowired
    com.example.springexample.StompDenialCounter denialCounter;
    public ChatBoxStompController(KafkaProducer kafkaProducer) {
        this.kafkaProducer = kafkaProducer;
    }

    /**
     * Личность отправителя и чат берутся из принципала и адреса, а не из тела фрейма (beads g9x).
     * Клиентские chat_id/user_id/username игнорируются: раньше их можно было подделать,
     * и подделка оседала в БД навсегда. Рассылка идёт ПОСЛЕ ответа gRPC — так в Kafka
     * не может уехать сообщение, чьё авторство не подтверждено.
     * Членство в чате проверяется здесь же, по списку участников из members(chat): если
     * отправителя в списке нет — он не член чата, разбор фрейма прерывается без побочных
     * эффектов. StompAuthChannelInterceptor эту проверку на SEND намеренно не делает —
     * дублировала бы тот же gRPC-вызов и блокировала пул clientInboundChannel (beads g9x).
     * Отказ не бесшумный (beads isf): отправитель получает отбивку на /private/{userId},
     * а сам отказ отмечается в StompDenialCounter — по нему интерцептор рвёт сессию, если
     * отказы пошли серией.
     */
    @MessageMapping("/chat/send/{chatId}")
    public void HandleChatMessage(@DestinationVariable String chatId,
                                  Principal principal,
                                  @org.springframework.messaging.handler.annotation.Header(
                                          name = com.example.springexample.StompFrameTimestampInterceptor.SERVER_TIMESTAMP_HEADER,
                                          required = false) String frameTimestamp,
                                  @org.springframework.messaging.handler.annotation.Header(
                                          name = org.springframework.messaging.simp.SimpMessageHeaderAccessor.SESSION_ID_HEADER,
                                          required = false) String sessionId,
                                  com.example.springexample.StompHandlers.ChatMessageDTO chatMessageDTO) {
        // Строгая валидация формата: на SEND StompAuthChannelInterceptor больше не парсит
        // chatId (beads g9x, проверка перенесена сюда), а голый Long.parseLong принимает
        // "+7", "-5" и другой мусор. Отказ — ДО любых побочных эффектов.
        if (!chatId.matches("\\d+")) {
            log.warn("HandleChatMessage: chatId {} не в каноническом числовом формате — отказ", chatId);
            return;
        }
        final long chat = Long.parseLong(chatId);
        // "\\d+" пропускает и "007": Long.parseLong("007") == 7, но буквальная рассылка на
        // "/mutual/chat/007" ушла бы без подписчиков, а Redis-контекст чата 7 расщепился бы
        // на два ключа (сценарий 007 из beads g9x). Поэтому все побочные эффекты ниже используют
        // канонический вид уже распарсенного числа, а не сырую строку chatId.
        final String canonicalChatId = String.valueOf(chat);
        final String senderId = principal.getName();
        // Время фрейма приходит заголовком от StompFrameTimestampInterceptor (beads 525),
        // который снимает его в потоке сессии, ДО раздачи фрейма в пул обработчиков.
        // Снимать Instant.now() здесь нельзя: clientInboundChannel раскидывает вызовы
        // @MessageMapping по пулу потоков, поэтому порядок входа в хендлер не совпадает с
        // порядком прихода фреймов. Первая попытка фикса 525 снимала время именно здесь —
        // разброс упал с 90 мс до 7 мс (gRPC-round-trip ушёл из-под времени), но 10 сообщений
        // подряд всё равно приходили вперемешку, что и показала живая проверка.
        // Инверсия персистентная: история чата сортируется по time_stamp.
        // Fallback на локальное время — на случай, если фрейм пришёл мимо интерцептора
        // (иной канал, тест): лучше слегка неупорядоченное время, чем пустое.
        // Порядок между РАЗНЫМИ отправителями по-прежнему определяется временем прихода
        // на сервер — это нормально и здесь не решается.
        final String serverTimestamp = (frameTimestamp != null && !frameTimestamp.isBlank())
                ? frameTimestamp
                : java.time.Instant.now().toString();

        if (chatMessageDTO.getUser_id() != null && !senderId.equals(chatMessageDTO.getUser_id())) {
            log.warn("Тело фрейма разошлось с принципалом: тело user_id={}, принципал={} — берём принципал",
                    chatMessageDTO.getUser_id(), senderId);
        }

        chatMembershipService.members(chat).subscribe(
                members -> {
                    Optional<String> senderUsername = members.stream()
                            .filter(u -> String.valueOf(u.getId()).equals(senderId))
                            .map(com.example.grpc.DataTransferService.UserDataRequest::getUsername)
                            .findFirst();
                    if (senderUsername.isEmpty()) {
                        log.warn("SEND в чат {} отклонён: пользователь {} не найден среди участников",
                                chatId, senderId);
                        // Отбивка отправителю + счётчик отказов (beads isf): раньше здесь был
                        // только этот log.warn, и пользователь со старой вкладкой терял текст
                        // молча, а серия отказов ничем не ограничивалась.
                        denyToSender(sessionId, senderId, canonicalChatId, "NOT_A_MEMBER",
                                "Вы не участник этого чата — сообщение не отправлено");
                        return;
                    }
                    String username = senderUsername.get();

                    chatMessageDTO.setChat_id(canonicalChatId);
                    chatMessageDTO.setUser_id(senderId);
                    chatMessageDTO.setUsername(username);
                    // Клиентское значение из тела фрейма затирается так же, как user_id/chat_id/
                    // username (beads g9x) — иначе клиент выбирал бы себе место в истории.
                    chatMessageDTO.setTimestamp(serverTimestamp);
                    // Идентификатор сообщения — тоже серверное поле (beads myl): Kafka даёт
                    // at-least-once, и переигранная запись обязана нести ТОТ ЖЕ id, иначе
                    // ON CONFLICT DO NOTHING в консьюмере ничего не отловит. Генерируется до
                    // рассылки, чтобы одно значение ушло и в эхо, и в Kafka. Клиентское
                    // значение затирается по той же причине, что и остальные поля (beads g9x):
                    // иначе можно было бы заранее занять чужой id и подавить чужую вставку.
                    chatMessageDTO.setMessage_id(java.util.UUID.randomUUID().toString());

                    template.convertAndSend("/mutual/chat/" + canonicalChatId, chatMessageDTO);
                    kafkaProducer.send(gson.toJson(chatMessageDTO));

                    ChatContextService contextService = new ChatContextService(redisTemplate, canonicalChatId);
                    // Mono не выполнится без подписки (fire-and-forget — не блокируем STOMP-поток
                    // ожиданием Redis; addMessage() раньше вообще не подписывался нигде, поэтому
                    // AI-assist всегда видел пустой контекст).
                    contextService.addMessage(username, chatMessageDTO.getText())
                            .subscribe(v -> {}, err -> log.error("Не удалось сохранить сообщение в Redis-контекст чата", err));

                    com.example.springexample.StompHandlers.ChatListShortObjDTO chatListShortObjDTO =
                            new com.example.springexample.StompHandlers.ChatListShortObjDTO();
                    chatListShortObjDTO.setChat_id(canonicalChatId);
                    chatListShortObjDTO.setText(chatMessageDTO.getText());
                    chatListShortObjDTO.setUsername(username);
                    chatListShortObjDTO.setTimestamp(chatMessageDTO.getTimestamp());
                    chatListController.ChangeChatPreview(
                            members.stream()
                                    .map(u -> String.valueOf(u.getId()))
                                    .collect(Collectors.toCollection(ArrayList::new)),
                            chatListShortObjDTO);
                },
                err -> {
                    log.error("Не удалось получить участников чата {} — сообщение не отправлено", chatId, err);
                    // Сообщение теряется ровно так же, как при отказе по членству, значит и
                    // отбивка нужна такая же (beads isf). В счётчик отказов эта ветка НЕ идёт:
                    // это отказ сервера, а не клиента, и рвать по нему сессии значило бы
                    // превращать заминку MessegerParody в отключение живых пользователей.
                    sendErrorToSender(senderId, canonicalChatId, "MEMBERSHIP_UNAVAILABLE",
                            "Сервис чатов недоступен — сообщение не отправлено");
                }
        );
    }
    /**
     * Личность и чат берутся из принципала и адреса, как и в HandleChatMessage (beads g9x).
     * Глобальный канал /mutual/typing_statuses_channel удалён: на него был подписан
     * список чатов, из-за чего любой пользователь получал события набора текста во всей
     * системе — утечка графа общения без всякой атаки. Вместо него — веерная рассылка
     * участникам чата на персональные адреса, уже защищённые PER_USER_PREFIXES.
     * Членство проверяется по тому же списку участников, что и в HandleChatMessage:
     * отправитель не найден среди них — статус не рассылается (beads g9x).
     */
    @MessageMapping("/chat/user_statuses/{chatId}")
    public void HandleChangeOfUserStatus(@DestinationVariable String chatId,
                                         Principal principal,
                                         @org.springframework.messaging.handler.annotation.Header(
                                                 name = org.springframework.messaging.simp.SimpMessageHeaderAccessor.SESSION_ID_HEADER,
                                                 required = false) String sessionId,
                                         com.example.springexample.StompHandlers.StatusUserDTO statusDto) {
        // Строгая валидация формата: на SEND StompAuthChannelInterceptor больше не парсит
        // chatId (beads g9x, проверка перенесена сюда), а голый Long.parseLong принимает
        // "+7", "-5" и другой мусор. Отказ — ДО любых побочных эффектов.
        if (!chatId.matches("\\d+")) {
            log.warn("HandleChangeOfUserStatus: chatId {} не в каноническом числовом формате — отказ", chatId);
            return;
        }
        final long chat = Long.parseLong(chatId);
        // "\\d+" пропускает и "007": Long.parseLong("007") == 7, но буквальная рассылка на
        // "/mutual/typing/007" ушла бы без подписчиков (сценарий 007 из beads g9x). Поэтому
        // все побочные эффекты ниже используют канонический вид уже распарсенного числа,
        // а не сырую строку chatId.
        final String canonicalChatId = String.valueOf(chat);
        final String senderId = principal.getName();

        chatMembershipService.members(chat).subscribe(
                members -> {
                    Optional<String> senderUsername = members.stream()
                            .filter(u -> String.valueOf(u.getId()).equals(senderId))
                            .map(com.example.grpc.DataTransferService.UserDataRequest::getUsername)
                            .findFirst();
                    if (senderUsername.isEmpty()) {
                        log.warn("SEND статуса набора текста в чат {} отклонён: пользователь {} не найден среди участников",
                                chatId, senderId);
                        // Отбивки нет намеренно (beads isf): статус набора — фоновое событие,
                        // пользователь его осознанно не отправлял, и тост на каждое нажатие
                        // клавиши был бы шумом. В счётчик отказов фрейм идёт: стоит он ровно
                        // того же gRPC-вызова, что и сообщение, и повторять его можно так же долго.
                        denialCounter.recordDenial(sessionId);
                        return;
                    }
                    String username = senderUsername.get();

                    statusDto.setChat_id(canonicalChatId);
                    statusDto.setUser_id(senderId);
                    statusDto.setUser_name(username);

                    template.convertAndSend("/mutual/typing/" + canonicalChatId, statusDto);
                    for (com.example.grpc.DataTransferService.UserDataRequest member : members) {
                        template.convertAndSend("/mutual/chatlist/typing/" + member.getId(), statusDto);
                    }
                },
                err -> log.error("Не удалось получить участников чата {} — статус набора не разослан", chatId, err)
        );
    }
    /**
     * Аватарка пользователя (событие userimage топика "Images") — на пер-юзерный адрес
     * (beads bwh). Раньше это был глобальный /mutual/chat/image_message_channel: один адрес
     * на всю систему, откуда любой аутентифицированный подписчик вычитывал targetId и
     * objectKey каждой загруженной картинки. Per-chat адрес здесь невозможен в принципе:
     * targetId события userimage — это userId (WEBFLUX_Service.Upload_image(..., "userimage")
     * при регистрации), чата в этом событии нет вообще. Отсюда пер-юзерное семейство
     * /mutual/user_image/, закрытое проверкой личности в StompAuthChannelInterceptor.
     */
    public void UploadMessageImageFromKafka(ImageUploadDTO imageUploadDTO) {
        String userId = imageUploadDTO.canonicalTargetId();
        if (userId == null) {
            log.warn("Событие userimage с некорректным targetId {} — рассылки нет",
                    imageUploadDTO.getTargetId());
            return;
        }
        template.convertAndSend("/mutual/user_image/" + userId, imageUploadDTO);
    }

    /**
     * Аватарка чата (событие chatimage) — на адрес самого чата (beads bwh), для открытой
     * страницы чата. Раньше — глобальный /mutual/chat/image_chat_channel, который вдобавок
     * был синтаксически неотличим от /mutual/chat/{chatId} и потому держал в интерцепторе
     * поимённое исключение из проверки членства. Здесь targetId — это именно chatId
     * (WEBFLUX_Service.Upload_image(..., "chatimage") при создании чата), так что подписку
     * на новый адрес закрывает обычная проверка участия.
     * Второй получатель того же события — список чатов; его веером раскладывает по
     * участникам ChatListStompController, см. KafkaConsumer.listenImagesEvents.
     */
    public void UploadChatImageFromKafka(ImageUploadDTO imageUploadDTO) {
        String chatId = imageUploadDTO.canonicalTargetId();
        if (chatId == null) {
            log.warn("Событие chatimage с некорректным targetId {} — рассылки нет",
                    imageUploadDTO.getTargetId());
            return;
        }
        template.convertAndSend("/mutual/chat_image/" + chatId, imageUploadDTO);
    }

    /**
     * Отказ, который клиент может повторять: отбивка отправителю + отметка в счётчике сессии
     * (beads isf). Счётчик читает StompAuthChannelInterceptor и после порога рвёт сессию —
     * иначе серия отказов не стоит клиенту ничего, а серверу стоит gRPC-вызова на каждый фрейм.
     */
    private void denyToSender(String sessionId, String senderId, String chatId,
                              String code, String message) {
        denialCounter.recordDenial(sessionId);
        sendErrorToSender(senderId, chatId, code, message);
    }

    /**
     * Отбивка уходит на /private/{userId} — семейство уже закрыто проверкой личности в
     * PER_USER_PREFIXES, так что чужую отбивку никто не прочитает. Персональный адрес, а не
     * адрес чата: в /mutual/chat/{id} её увидели бы все участники.
     */
    private void sendErrorToSender(String senderId, String chatId, String code, String message) {
        ChatErrorDTO error = new ChatErrorDTO();
        error.setChat_id(chatId);
        error.setCode(code);
        error.setMessage(message);
        template.convertAndSend("/private/" + senderId, error);
    }

    /**
     * Сторож от бесшумных падений (beads isf): до этого тикета в репозитории не было
     * ни одного обработчика ошибок STOMP, и любое необработанное исключение внутри
     * {@code @MessageMapping} тонуло так же тихо, как отказ по членству — клиент терял
     * сообщение и не узнавал об этом.
     * Наружу идёт нейтральный текст: сообщение исключения может нести внутренности (строки
     * подключения, имена таблиц), подробности остаются в логе.
     * Область действия — синхронная часть хендлеров этого контроллера. Ошибки внутри
     * subscribe(...) сюда не попадают: они приходят на другом потоке и обрабатываются
     * собственной веткой err -> у каждой подписки.
     */
    @org.springframework.messaging.handler.annotation.MessageExceptionHandler(Exception.class)
    public void handleUncaughtStompException(
            Exception ex,
            Principal principal,
            @org.springframework.messaging.handler.annotation.Header(
                    name = org.springframework.messaging.simp.SimpMessageHeaderAccessor.DESTINATION_HEADER,
                    required = false) String destination) {
        log.error("Необработанное исключение в STOMP-хендлере, адрес {}", destination, ex);
        if (principal == null) {
            return;
        }
        sendErrorToSender(principal.getName(), chatIdFromDestination(destination), "INTERNAL_ERROR",
                "Не удалось обработать сообщение — попробуйте ещё раз");
    }

    /** Хвост адреса /app/chat/send/{chatId}: нужен фронту, чтобы понять, в каком чате отказ. */
    private static String chatIdFromDestination(String destination) {
        if (destination == null) {
            return null;
        }
        String tail = destination.substring(destination.lastIndexOf('/') + 1);
        return tail.matches("\\d+") ? tail : null;
    }
}
