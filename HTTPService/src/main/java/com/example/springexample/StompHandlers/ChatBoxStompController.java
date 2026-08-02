package com.example.springexample.StompHandlers;




import com.example.grpc.DataTransferService;
import com.example.springexample.ImageUploadDTO;
import com.example.springexample.KafkaProducer;
import com.example.springexample.Services.AuthGrpc;
import com.example.springexample.Services.ChatContextService;
import com.example.springexample.Services.ReactiveGrpcClient;
import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import reactor.ReactiveTransferServiceGrpc;

import java.util.ArrayList;
import java.util.List;

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
    ReactiveGrpcClient reactiveGrpcClient;
    @Autowired
    AuthGrpc authGrpc;
    public ChatBoxStompController(KafkaProducer kafkaProducer) {
        this.kafkaProducer = kafkaProducer;
    }

    @MessageMapping("/chat/send/{chatId}")
    @SendTo("/mutual/chat/{chatId}")
    public com.example.springexample.StompHandlers.ChatMessageDTO HandleChatMessage(com.example.springexample.StompHandlers.ChatMessageDTO chatMessageDTO) {
        kafkaProducer.send(gson.toJson(chatMessageDTO));
        ChatContextService contextService = new ChatContextService(redisTemplate, chatMessageDTO.getChat_id());
        // Mono не выполнится без подписки (fire-and-forget — не блокируем STOMP-поток
        // ожиданием Redis; addMessage() раньше вообще не подписывался нигде, поэтому
        // AI-assist всегда видел пустой контекст).
        contextService.addMessage(chatMessageDTO.getUsername(),chatMessageDTO.getText())
                .subscribe(v -> {}, err -> log.error("Не удалось сохранить сообщение в Redis-контекст чата", err));
        List<String> client_ids = authGrpc.GetAllIdsByChat(DataTransferService.ChatData.newBuilder().setChatId(Long.parseLong(chatMessageDTO.getChat_id())).build());
        ArrayList<String> users = new ArrayList<>(client_ids);
        com.example.springexample.StompHandlers.ChatListShortObjDTO chatListShortObjDTO = new com.example.springexample.StompHandlers.ChatListShortObjDTO();
        chatListShortObjDTO.setChat_id(chatMessageDTO.getChat_id());
        chatListShortObjDTO.setText(chatMessageDTO.getText());
        chatListShortObjDTO.setUsername(chatMessageDTO.getUsername());
        chatListShortObjDTO.setTimestamp(chatMessageDTO.getTimestamp());
        chatListController.ChangeChatPreview(users,chatListShortObjDTO);
        return chatMessageDTO;

    }
    @MessageMapping("/chat/user_statuses")

    public void HandleChangeOfUserStatus(com.example.springexample.StompHandlers.StatusUserDTO statusDto) {
        template.convertAndSend("/mutual/typing_statuses_channel"+statusDto.chat_id,statusDto);
        template.convertAndSend("/mutual/typing_statuses_channel",statusDto);
    }
    public void UploadMessageImageFromKafka(ImageUploadDTO imageUploadDTO) {
        template.convertAndSend("/mutual/chat/image_message_channel", imageUploadDTO);

    }

    public void UploadChatImageFromKafka(ImageUploadDTO imageUploadDTO) {
        template.convertAndSend("/mutual/chat/image_chat_channel", imageUploadDTO);


    }
}
