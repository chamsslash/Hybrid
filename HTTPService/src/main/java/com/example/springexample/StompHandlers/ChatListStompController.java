package com.example.springexample.StompHandlers;

import com.example.grpc.DataTransferService;
import com.example.springexample.ImageUploadDTO;
import com.example.springexample.Services.ChatMembershipService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.ArrayList;

@Slf4j
@Controller
public class ChatListStompController {
    @Autowired
    SimpMessagingTemplate template;
    @Autowired
    ChatMembershipService chatMembershipService;

    public void NewChatAddToList(ArrayList<String> user_ids, ChatListShortObjDTO chatlistpreview){
        try {
            for (String user_id : user_ids){
                template.convertAndSend("/mutual/chatlist/list_update/"+user_id, chatlistpreview);
                log.info("/mutual/chatlist/list_update/"+user_id);
            }
        }catch (Exception e){
            log.error("Error in  NewChatAddToList", e);
        }



    }
    public  void ChangeChatPreview(ArrayList<String> user_ids, ChatListShortObjDTO chatlistpreview){
        try {
            for (String user_id : user_ids){
                template.convertAndSend("/mutual/chatlist/change_chatpreview/"+user_id, chatlistpreview);
            }
        }catch (Exception e){
            log.error("Error in   ChangeChatPreview", e);
        }
    }

    /**
     * Аватарка чата в списке чатов — веером по участникам этого чата (beads bwh).
     *
     * Раньше уходило одним сообщением на /mutual/chat_list/image_chat_channel. Этот адрес
     * был не просто глобальным: chat_list в нём написан ЧЕРЕЗ ПОДЧЁРКИВАНИЕ, тогда как
     * PER_USER_PREFIXES знает /mutual/chatlist/ без подчёркивания, — то есть он не подходил
     * вообще ни под один префикс интерцептора и проваливался в allow-by-default. Любой
     * аутентифицированный пользователь собирал по нему chatId и ключи MinIO всех чатов
     * системы (живая проверка 2026-08-21: SUBSCRIBE -> ALLOWED).
     *
     * Схема рассылки взята у typing-статусов (beads g9x, ChatBoxStompController): подписаться
     * на /mutual/chat_image/{chatId} список чатов не может — он не знает заранее, какие чаты
     * в нём окажутся, и подписки пришлось бы заводить и гасить по мере появления плиток.
     * Поэтому у него один пер-юзерный адрес, а раскладку по получателям делает сервер.
     */
    public void UploadChatImageFromKafka(ImageUploadDTO imageUploadDTO) {
        String canonicalChatId = imageUploadDTO.canonicalTargetId();
        if (canonicalChatId == null) {
            log.warn("Событие chatimage с некорректным targetId {} — рассылки нет",
                    imageUploadDTO.getTargetId());
            return;
        }
        chatMembershipService.members(Long.parseLong(canonicalChatId)).subscribe(
                members -> {
                    for (DataTransferService.UserDataRequest member : members) {
                        template.convertAndSend("/mutual/chatlist/image/" + member.getId(), imageUploadDTO);
                    }
                },
                // Fail-closed, как и в остальных местах, где нужен список участников:
                // без ответа MessegerParody неизвестно, кому событие адресовано.
                err -> log.error("Не удалось получить участников чата {} — аватарка чата не разослана",
                        canonicalChatId, err)
        );
    }


}
