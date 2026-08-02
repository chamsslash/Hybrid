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
}
//    @KafkaListener(topics = "Messages")
//    public void listenMessages(String message) throws Exception {
//        JsonObject messagejson = JsonParser.parseString(message).getAsJsonObject();
//        MessageEvent messageEvent = new MessageEvent(messagejson);
//        messageWebSocketHandler.sendMessageToClient(messageEvent);
//
//    }



































