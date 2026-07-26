package com.example.springexample;

import com.example.springexample.StompHandlers.ChatListStompController;
import com.example.springexample.StompHandlers.ChatBoxStompController;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
@Slf4j
@Service
public class KafkaConsumer {

    @Autowired
    private ChatBoxStompController chatBoxStompController;
    @Autowired
    private ChatListStompController chatListStompController;
    @Autowired
    private Gson gson;


    @KafkaListener(topics = "Images")
    public void  listenImagesEvents(String message){
        ImageUploadDTO imageUploadDTO = gson.fromJson(message, ImageUploadDTO.class);
        switch (imageUploadDTO.getTargetType()){
            case "userimage":
                chatBoxStompController.UploadMessageImageFromKafka(imageUploadDTO);
                break;
            case "chatimage":
                chatBoxStompController.UploadChatImageFromKafka(imageUploadDTO);
                chatListStompController.UploadChatImageFromKafka(imageUploadDTO);
                break;
            default:
                log.warn("Unknown targetType in imageUploadDTO: {}", imageUploadDTO.getTargetType());
        }
    }
    @KafkaListener(topics = "Events")
    public void listenNotifications(String message) throws Exception {


        NotificationDTO notification = gson.fromJson(message, NotificationDTO.class);
        log.info("notification received {}",notification.toString());


        NotificationDTO notificationDTO= new NotificationDTO(notification.getAuthorId(),notification.getUser_id(),notification.getText(), notification.getChat_id(), notification.getType());


        switch (notificationDTO.getType()) {
            case "MessageCreated":
                log.info("preparing notification");
                chatBoxStompController.SendNotificationToChatBox(notificationDTO);
                break;


            case "ChatCreated":
                log.info("preparing notification to chatList");
                chatListStompController.ShowNotificationInChatList(notification);
                break;

        }


    }





    }
//    @KafkaListener(topics = "Messages")
//    public void listenMessages(String message) throws Exception {
//        JsonObject messagejson = JsonParser.parseString(message).getAsJsonObject();
//        MessageEvent messageEvent = new MessageEvent(messagejson);
//        messageWebSocketHandler.sendMessageToClient(messageEvent);
//
//    }



































