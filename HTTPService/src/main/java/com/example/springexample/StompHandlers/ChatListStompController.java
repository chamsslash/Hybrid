package com.example.springexample.StompHandlers;

import com.example.springexample.ImageUploadDTO;
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

    public void UploadChatImageFromKafka(ImageUploadDTO imageUploadDTO) {


        template.convertAndSend("/mutual/chat_list/image_chat_channel", imageUploadDTO);
    }


}
