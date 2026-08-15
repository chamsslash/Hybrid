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
     */
    @MessageMapping("/chat/send/{chatId}")
    public void HandleChatMessage(@DestinationVariable String chatId,
                                  Principal principal,
                                  com.example.springexample.StompHandlers.ChatMessageDTO chatMessageDTO) {
        final long chat = Long.parseLong(chatId);
        final String senderId = principal.getName();

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
                        return;
                    }
                    String username = senderUsername.get();

                    chatMessageDTO.setChat_id(chatId);
                    chatMessageDTO.setUser_id(senderId);
                    chatMessageDTO.setUsername(username);
                    chatMessageDTO.setTimestamp(java.time.Instant.now().toString());

                    template.convertAndSend("/mutual/chat/" + chatId, chatMessageDTO);
                    kafkaProducer.send(gson.toJson(chatMessageDTO));

                    ChatContextService contextService = new ChatContextService(redisTemplate, chatId);
                    // Mono не выполнится без подписки (fire-and-forget — не блокируем STOMP-поток
                    // ожиданием Redis; addMessage() раньше вообще не подписывался нигде, поэтому
                    // AI-assist всегда видел пустой контекст).
                    contextService.addMessage(username, chatMessageDTO.getText())
                            .subscribe(v -> {}, err -> log.error("Не удалось сохранить сообщение в Redis-контекст чата", err));

                    com.example.springexample.StompHandlers.ChatListShortObjDTO chatListShortObjDTO =
                            new com.example.springexample.StompHandlers.ChatListShortObjDTO();
                    chatListShortObjDTO.setChat_id(chatId);
                    chatListShortObjDTO.setText(chatMessageDTO.getText());
                    chatListShortObjDTO.setUsername(username);
                    chatListShortObjDTO.setTimestamp(chatMessageDTO.getTimestamp());
                    chatListController.ChangeChatPreview(
                            members.stream()
                                    .map(u -> String.valueOf(u.getId()))
                                    .collect(Collectors.toCollection(ArrayList::new)),
                            chatListShortObjDTO);
                },
                err -> log.error("Не удалось получить участников чата {} — сообщение не отправлено", chatId, err)
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
                                         com.example.springexample.StompHandlers.StatusUserDTO statusDto) {
        final long chat = Long.parseLong(chatId);
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
                        return;
                    }
                    String username = senderUsername.get();

                    statusDto.setChat_id(chatId);
                    statusDto.setUser_id(senderId);
                    statusDto.setUser_name(username);

                    template.convertAndSend("/mutual/typing/" + chatId, statusDto);
                    for (com.example.grpc.DataTransferService.UserDataRequest member : members) {
                        template.convertAndSend("/mutual/chatlist/typing/" + member.getId(), statusDto);
                    }
                },
                err -> log.error("Не удалось получить участников чата {} — статус набора не разослан", chatId, err)
        );
    }
    public void UploadMessageImageFromKafka(ImageUploadDTO imageUploadDTO) {
        template.convertAndSend("/mutual/chat/image_message_channel", imageUploadDTO);

    }

    public void UploadChatImageFromKafka(ImageUploadDTO imageUploadDTO) {
        template.convertAndSend("/mutual/chat/image_chat_channel", imageUploadDTO);


    }
}
