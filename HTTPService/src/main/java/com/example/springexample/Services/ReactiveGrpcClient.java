package com.example.springexample.Services;



import com.example.grpc.DataTransferService;
import com.example.springexample.MessageEvent;
import com.example.springexample.Metrics.GrpcRequestsMetric;
import com.example.springexample.ShortChatObject;
import com.example.springexample.StompHandlers.ChatListShortObjDTO;
import com.example.springexample.StompHandlers.ChatListStompController;
import com.google.gson.JsonObject;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.ReactorReactiveTransferServiceGrpc;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.management.modelmbean.ModelMBeanNotificationInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
@Slf4j
@Service
@AllArgsConstructor
public class ReactiveGrpcClient {
    @Autowired
    ChatListStompController chatListStomp;
    @Autowired
    private final ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub reactiveTransferServiceStub;
    @Autowired
    GrpcRequestsMetric grpcRequestsMetric;
    public Mono<List<String>> reactiveGetAllUsernamesByChatId(DataTransferService.ChatData chatData) {
        grpcRequestsMetric.increment();
        return reactiveTransferServiceStub.getAllUsersByChatId(chatData)
                .map(users->users.getUsersList().stream()
                .map(userdata->userdata.getUsername())
                        .collect(Collectors.toList()));


    }

    public Mono<List<String>> reactiveGetAllIdsByChatId(DataTransferService.ChatData chatData) {
        grpcRequestsMetric.increment();
        return reactiveTransferServiceStub.getAllUsersByChatId(chatData)
                .map(users -> users.getUsersList().stream()
                        .map(userdata -> String.valueOf(userdata.getId()))
                        .collect(Collectors.toList()));
    }

    public Mono<ShortChatObject> reactiveGetNewestMessage(DataTransferService.ChatData chatData) {
        grpcRequestsMetric.increment();
        return  reactiveTransferServiceStub.getnewest(chatData).map(message -> {
            log.info("got newest message from chat {}",message.getChatId());
            return  new ShortChatObject(
                    message.getChatId(),
                    message.getUserName(), message.getChatName(),
                    message.getUserId(),
                    message.getText(), message.getTimestamp(),
                    chatData.getImageUrl());
        });

    }
    public Mono<String> reactiveChatServe(DataTransferService.ChatData chatData) {
        grpcRequestsMetric.increment();

        return reactiveTransferServiceStub.transferchat(chatData)
                .flatMap(chatResponse -> {
                    return this.reactiveGetNewestMessage(
                                    DataTransferService.ChatData.newBuilder()
                                            .setChatId(chatResponse.getId())
                                            .build())
                            .map(shortChatObject -> {
                                if (
                                        chatResponse.getStatus().equals("200")
                                                || chatResponse.getStatus().equals("666")
                                                || chatResponse.getMessage().equals("Chat has been created")) {

                                    ChatListShortObjDTO chatpreview = new ChatListShortObjDTO(
                                            String.valueOf(shortChatObject.getId()),
                                            shortChatObject.getPreview(),
                                            shortChatObject.getPreview_username(),
                                            shortChatObject.getLastMessageTime(),
                                            shortChatObject.getTitle(),
                                            chatResponse.getImageUrl());

                                    ArrayList<String> users = chatData.getUserList().stream()
                                            .map(user -> String.valueOf(user.getId()))
                                            .collect(Collectors.toCollection(ArrayList::new));
                                    users.add(String.valueOf(chatData.getAuthorId()));

                                    if (!shortChatObject.getPreview().isEmpty()
                                            && !shortChatObject.getPreview_username().isEmpty()) {
                                        chatListStomp.ChangeChatPreview(users, chatpreview);
                                        log.info("stomp change preview success");
                                    }

                                    chatListStomp.NewChatAddToList(users, chatpreview);
                                    log.info("stomp add new chat success");

                                    JsonObject JSON = new JsonObject();
                                    JSON.addProperty("status", chatResponse.getStatus());
                                    JSON.addProperty("message", chatResponse.getMessage());
                                    JSON.addProperty("id", String.valueOf(chatResponse.getId()));
                                    JSON.addProperty("image_id", chatResponse.getImageUrl());
                                    return JSON.toString();

                                } else {
                                    JsonObject JSON = new JsonObject();
                                    JSON.addProperty("status", chatResponse.getStatus());
                                    JSON.addProperty("message", chatResponse.getMessage());
                                    return JSON.toString();
                                }
                            });
                });
    }
    public Mono<List<MessageEvent>> reactiveGetAllMessages(DataTransferService.ChatData chatData) {
        grpcRequestsMetric.increment();

        return reactiveTransferServiceStub.transferAllMessages(chatData)
                .flatMapMany(event->{
                    if(event.getMessageListList().isEmpty()){
                        log.warn("Нет сообщений в ListOfMessages");
                        return Flux.empty();
                    }
                    return parseOnMessageEvent(event);
                })
                .doOnNext(event -> event.setChat_id(chatData.getChatId()))   // присваиваем chatId каждому
                .collectList()   ;                                            // Mono<List<MessageEvent>>


    }


    public  Flux<MessageEvent> parseOnMessageEvent(DataTransferService.ListOfMessages listOfMessages) {
        return Flux.fromIterable(listOfMessages.getMessageListList()).map(message -> {
            MessageEvent event = new MessageEvent();
            event.setUsername(message.getUserName());
            event.setChat_id(message.getChatId());
            event.setText(message.getText());
            event.setTimestamp(message.getTimestamp());
            event.setUser_id(message.getUserId());
            event.setImage_url(message.getImageUrl());
            return event;
        });
    }
    public Mono<DataTransferService.ListOfChats> reactiveGetAllChatsById(DataTransferService.ChatData chatData) {
        grpcRequestsMetric.increment();
        return reactiveTransferServiceStub.getallchatsbyid(chatData);
    }
    public Mono<String> reactiveGetUsernameById(String chatId) {
        grpcRequestsMetric.increment();
        return  reactiveTransferServiceStub.getUsernameById(DataTransferService.User.newBuilder().setId(chatId).build()).map(DataTransferService.User::getUsername);
    }
    public  Mono<String> reactiveGetImageUrl(Long chatId) {
        grpcRequestsMetric.increment();
        return  reactiveTransferServiceStub.getimageurl(DataTransferService.ChatData.newBuilder().setChatId(chatId).build()).map(DataTransferService.DriveUrl::getUrl);
    }
    public  Mono<String> reactiveGetUserImageUrl(Long userId) {
        grpcRequestsMetric.increment();
        return reactiveTransferServiceStub.getUserImageurl(DataTransferService.UserDataRequest.newBuilder().setId(userId).build()).map(DataTransferService.DriveUrl::getUrl);
    }
}

