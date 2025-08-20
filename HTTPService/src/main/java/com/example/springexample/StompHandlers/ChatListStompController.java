package com.example.springexample.StompHandlers;

import com.example.springexample.ImageUploadDTO;
import com.example.springexample.NotificationDTO;
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
    public  void ShowNotificationInChatList(NotificationDTO notificationDTO){
        try {
            for (long user_id : notificationDTO.getUser_id()){
                template.convertAndSend("/private/chatlist/notify/"+ user_id, notificationDTO);
            }
        }catch (Exception e){
            log.error("Error in sending notification to user in chatlist", e);
        }

        try {
            long author_id = notificationDTO.getAuthorId();
            String author_message_of_notification = "You" + notificationDTO.getText().split("has been")[1];
            template.convertAndSend("/private/chatlist/notify/" + author_id, author_message_of_notification);
        }catch (Exception e){
            log.error("Error in sending notification to author in chatlist ", e);
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
