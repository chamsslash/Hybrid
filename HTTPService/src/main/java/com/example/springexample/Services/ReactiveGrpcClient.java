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
    /**
     * Получатели STOMP-уведомлений о новом чате: участники плюс автор (beads 1e2).
     *
     * Каждый id подставляется прямо в адрес рассылки (/mutual/chatlist/list_update/{id},
     * /mutual/chatlist/change_chatpreview/{id}), поэтому здесь важен ровно формат строки,
     * а не только её наличие.
     *
     * Автор берётся через getAuthorId().getId(), а не String.valueOf(getAuthorId()): по
     * контракту proto поле author_id имеет тип User, то есть вложенное СООБЩЕНИЕ, и
     * String.valueOf печатал его текстовый формат — адрес получался
     * /mutual/chatlist/list_update/id: "6" вместо .../6. Подписчиков у такого адреса нет,
     * поэтому автор чата не получал ни list_update, ни change_chatpreview: его собственный
     * список не обновлялся на лету, тогда как остальным участникам всё доезжало. Дыры в
     * доступе тут не было — было тихое исчезновение уведомления.
     *
     * Метод вынесен из лямбды именно ради проверяемости: формат адреса раньше не
     * утверждался ничем, и ошибка жила незамеченной до живого прогона на стенде.
     */
    static ArrayList<String> stompRecipients(DataTransferService.ChatData chatData) {
        ArrayList<String> users = chatData.getUserList().stream()
                .map(DataTransferService.User::getId)
                .collect(Collectors.toCollection(ArrayList::new));
        users.add(chatData.getAuthorId().getId());
        return users;
    }

    public Mono<List<String>> reactiveGetAllUsernamesByChatId(DataTransferService.ChatData chatData) {
        return grpcRequestsMetric.measure("getAllUsersByChatId", reactiveTransferServiceStub.getAllUsersByChatId(chatData))
                .map(users->users.getUsersList().stream()
                .map(userdata->userdata.getUsername())
                        .collect(Collectors.toList()));


    }

    public Mono<ShortChatObject> reactiveGetNewestMessage(DataTransferService.ChatData chatData) {
        return  grpcRequestsMetric.measure("getnewest", reactiveTransferServiceStub.getnewest(chatData)).map(message -> {
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
        return grpcRequestsMetric.measure("transferchat", reactiveTransferServiceStub.transferchat(chatData))
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

                                    ArrayList<String> users = stompRecipients(chatData);

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
        return grpcRequestsMetric.measure("transferAllMessages", reactiveTransferServiceStub.transferAllMessages(chatData))
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
        return grpcRequestsMetric.measure("getallchatsbyid", reactiveTransferServiceStub.getallchatsbyid(chatData));
    }
    public Mono<String> reactiveGetUsernameById(String chatId) {
        return  grpcRequestsMetric.measure("getUsernameById", reactiveTransferServiceStub.getUsernameById(DataTransferService.User.newBuilder().setId(chatId).build())).map(DataTransferService.User::getUsername);
    }
    public  Mono<String> reactiveGetImageUrl(Long chatId) {
        return  grpcRequestsMetric.measure("getimageurl", reactiveTransferServiceStub.getimageurl(DataTransferService.ChatData.newBuilder().setChatId(chatId).build())).map(DataTransferService.DriveUrl::getUrl);
    }
    public  Mono<String> reactiveGetUserImageUrl(Long userId) {
        return grpcRequestsMetric.measure("getUserImageurl", reactiveTransferServiceStub.getUserImageurl(DataTransferService.UserDataRequest.newBuilder().setId(userId).build())).map(DataTransferService.DriveUrl::getUrl);
    }
}

