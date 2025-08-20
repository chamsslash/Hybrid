package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import com.example.grpc.MessageTransferServiceGrpc;
import com.example.springexample.MessageEvent;
import com.example.springexample.Metrics.GrpcRequestsMetric;
import com.example.springexample.StompHandlers.ChatListStompController;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


@Slf4j
@Service
public class gRPC_Client {
    @GrpcClient("MessageTransferService")
    private MessageTransferServiceGrpc.MessageTransferServiceBlockingStub messageTransferServiceBlockingStub;
    @Autowired
    ChatListStompController chatListStomp;
    @Autowired
    GrpcRequestsMetric grpcRequestsMetric;





//    public Mono<String>
//    ChatServe(common.DataTransferService.ChatData chatData) throws IOException {
//        grpcRequestsMetric.increment();
//        common.DataTransferService.ChatResponse ChatResp= chatServiceBlockingStub.transferchat(chatData);
//        log.info("transferchat success");
//        ShortChatObject shortChatObject = GetNewestMessage(common.DataTransferService.ChatData.newBuilder().setChatId(ChatResp.getId()).build());
//        log.info("getnewestmessage success");
//        log.info(ChatResp.getMessage());
//        if (String.valueOf(ChatResp.getStatus()).equals("200") || ChatResp.getStatus().equals("666") || ChatResp.getMessage().equals("Chat has been created")) {
//            ChatListShortObjDTO chatpreview = new ChatListShortObjDTO(
//                    String.valueOf(shortChatObject.getId()),
//                    shortChatObject.getPreview(),
//                    shortChatObject.getPreview_username(),
//                    shortChatObject.getLastMessageTime(),
//                    shortChatObject.getTitle(),
//                    ChatResp.getImageUrl()
//                    );
//
//            ArrayList<String> users = chatData.getUserList().stream()
//                    .map(user -> String.valueOf(user.getId())).collect(Collectors.toCollection(ArrayList::new));
//            users.add(String.valueOf(chatData.getAuthorId()));
//            if (!shortChatObject.getPreview().isEmpty() && !shortChatObject.getPreview_username().isEmpty()) {
//                chatListStomp.ChangeChatPreview(users,chatpreview);
//                log.info("stomp change preview success success");
//            }//ERRROR
//            chatListStomp.NewChatAddToList(users,chatpreview);
//            log.info("stomp add new chat success");
//            JsonObject JSON = new JsonObject();
//            JSON.addProperty("status", ChatResp.getStatus());
//            JSON.addProperty("message", ChatResp.getMessage());
//            JSON.addProperty("id", String.valueOf(ChatResp.getId()));
//            JSON.addProperty("image_id",ChatResp.getImageUrl());
//            return Mono.just(JSON.toString());
//        } else  {
//            JsonObject JSON = new JsonObject();
//            JSON.addProperty("status", ChatResp.getStatus());
//            JSON.addProperty("message", ChatResp.getMessage());
//            return Mono.just(JSON.toString());
//        }
//
//
//    }
    public List<MessageEvent> GetAllMessages(DataTransferService.ChatData chatData) throws IOException {
        grpcRequestsMetric.increment();
        DataTransferService.ListOfMessages listOfMessages = messageTransferServiceBlockingStub.transferAllMessages(chatData);
        List<MessageEvent> messageEvents = getMessageEvents(listOfMessages);
        if (!messageEvents.isEmpty()){log.info("received list with chat id {}",messageEvents.get(0).getChat_id());}

        messageEvents.stream().forEach(a->a.setChat_id(chatData.getChatId()));
        return messageEvents;
    }
    private  List<MessageEvent> getMessageEvents(DataTransferService.ListOfMessages listOfMessages) {
        List<MessageEvent> messageEvents = new ArrayList<>();
        for (DataTransferService.Message message : listOfMessages.getMessageListList()) {
            MessageEvent messageEvent = new MessageEvent();
            messageEvent.setUsername(message.getUserName());
            messageEvent.setChat_id(message.getChatId());
            messageEvent.setText(message.getText());
            messageEvent.setTimestamp(message.getTimestamp());
            messageEvent.setUser_id(message.getUserId());

            messageEvent.setImage_url(message.getImageUrl());
            messageEvents.add(messageEvent);
        }
        return messageEvents;
    }

//    public ShortChatObject GetNewestMessage(DataTransferService.ChatData chatData) throws InvalidProtocolBufferException {
//        grpcRequestsMetric.increment();
//        DataTransferService.Message message = messageTransferServiceBlockingStub.getnewest(chatData);
//
//        log.info("got newest message from chat {}",chatData.getChatId());
//        ShortChatObject shortChatObject = new ShortChatObject(message.getChatId(),message.getUserName(),message.getChatName(),message.getUserId(), message.getText(), message.getTimestamp(),chatData.getImageUrl());
//            return shortChatObject;
//
//    }
}
