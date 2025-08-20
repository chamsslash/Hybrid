//package com.example.springexample.Services;
//
//
//import com.example.springexample.JPA_Entities.Chat;
//import com.example.springexample.JPA_Entities.Message;
//import com.example.springexample.JPA_Entities.User;
//import com.example.springexample.JPA_Repositories.ChatRepBase;
//import com.example.springexample.JPA_Repositories.MessageRepBase;
//import com.example.springexample.JPA_Repositories.UserRepBase;
//import com.example.springexample.NotificationDTO;
//import com.example.springexample.Notification_gRPC_Client;
//import common.ChatServiceGrpc;
//import common.DataTransferService;
//import io.grpc.Status;
//import io.grpc.stub.StreamObserver;
//import jakarta.persistence.EntityManager;
//import jakarta.persistence.PersistenceContext;
//import jakarta.transaction.Transactional;
//import lombok.Data;
//import lombok.extern.slf4j.Slf4j;
//import net.devh.boot.grpc.server.service.GrpcService;
//import org.springframework.stereotype.Service;
//
//import java.text.MessageFormat;
//import java.util.*;
//import java.util.stream.Collector;
//import java.util.stream.Collectors;
//@GrpcService
//@Slf4j
//public class CS_impl extends ChatServiceGrpc.ChatServiceImplBase {
//
//    private final ChatRepBase chatRepBase;
//    private final UserRepBase userRepBase;
//
//    private final  Notification_gRPC_Client notification_gRPC_Client;
//    private DataTransferService.ChatResponse resp;
//    @PersistenceContext
//    EntityManager entityManager;
//    public CS_impl(ChatRepBase chatRepBase, UserRepBase userRepBase, MessageRepBase messageRepBase, Notification_gRPC_Client notificationGRPCClient) {
//
//        this.chatRepBase = chatRepBase;
//        this.userRepBase = userRepBase;
//
//
//
//        this.notification_gRPC_Client = notificationGRPCClient;
//    }
//
//    @Override
//    public void getimageurl(DataTransferService.ChatData request, StreamObserver<DataTransferService.DriveUrl> responseObserver) {
//        long chatId = request.getChatId();
//        String url = chatRepBase.findChatsById(chatId)
//                .map(Chat::getImage_url)
//                .orElse(null);
//        responseObserver.onNext(DataTransferService.DriveUrl.newBuilder().setUrl(url).build());
//        responseObserver.onCompleted();
//    }
////
//    @Transactional
//    @Override
//    public void transferchat(DataTransferService.ChatData newChat, StreamObserver<DataTransferService.ChatResponse> responseObserver) {
//        try {
//            if (newChat.getUserList().isEmpty() || !newChat.hasAuthorId()) {
//                try {
//                    Optional<Chat> chatOPT = chatRepBase.findChatsById(newChat.getChatId());
//                    if (chatOPT.isEmpty()) {
//                        responseObserver.onNext(DataTransferService.ChatResponse.newBuilder()
//                                .setStatus("500")
//                                .setMessage("Cannot find chat ERROR")
//                                .build());
//                        responseObserver.onCompleted();
//                        return;
//                    }
//
//                    responseObserver.onNext(DataTransferService.ChatResponse.newBuilder()
//                            .setStatus("200")
//                            .setMessage("Chat is founded ready to use")
//                            .setId(chatOPT.get().getId())
//                            .setImageUrl(chatOPT.get().getImage_url())
//                            .build());
//                    responseObserver.onCompleted();
//                    return;
//                } catch (Exception e) {
//                    responseObserver.onNext(DataTransferService.ChatResponse.newBuilder()
//                            .setStatus("500")
//                            .setMessage(e.getMessage())
//                            .build());
//                    responseObserver.onCompleted();
//                    return;
//                }
//            }
//
//            List<User> users = new ArrayList<>();
//            List<Long> ids = new ArrayList<>();
//
//            for (var userData : newChat.getUserList()) {
//                if (userData == null || userData.getId() == 0) {
//                    responseObserver.onNext(DataTransferService.ChatResponse.newBuilder()
//                            .setStatus("500")
//                            .setMessage("Не найден пользователь: null или ID = 0")
//                            .build());
//                    responseObserver.onCompleted();
//                    return;
//                }
//
//                Optional<User> found = userRepBase.findById(userData.getId());
//                if (found.isEmpty()) {
//                    responseObserver.onNext(DataTransferService.ChatResponse.newBuilder()
//                            .setStatus("500")
//                            .setMessage("Пользователь не найден по ID: " + userData.getId())
//                            .build());
//                    responseObserver.onCompleted();
//                    return;
//                }
//
//                User user = found.get();
//                users.add(user);
//                ids.add(user.getId());
//            }
//
//            Optional<User> authorOpt = userRepBase.findById(newChat.getAuthorId().getId());
//            if (authorOpt.isEmpty()) {
//                responseObserver.onError(new Throwable("User-author not found"));
//                return;
//            }
//
//            User author = authorOpt.get();
//            if (!users.contains(author)) {
//                users.add(author);
//                if (!ids.contains(author.getId())) {
//                    ids.add(author.getId());
//                }
//            }
//
//            Optional<Chat> existingChatOpt = chatRepBase.findChatByTitleAndExactUsers(newChat.getTitle(), ids, ids.size());
//            if (existingChatOpt.isPresent()) {
//                responseObserver.onNext(DataTransferService.ChatResponse.newBuilder()
//                        .setMessage("Chat already exists")
//                        .setId(existingChatOpt.get().getId())
//                        .setImageUrl(existingChatOpt.get().getImage_url())
//                        .setStatus("666")
//                        .build());
//                responseObserver.onCompleted();
//                return;
//            }
//
//            Chat chatToCreate = new Chat();
//            chatToCreate.setTitle(newChat.getTitle());
//            chatToCreate.setImage_url(newChat.getImageUrl());
//
//            for (User u : users) {
//                u.getChats().add(chatToCreate);
//            }
//            chatToCreate.setUsers(users);
//
//            Chat savedChat = chatRepBase.save(chatToCreate);
//            entityManager.flush();
//            NotificationDTO notificationDTO = new NotificationDTO();
//            notificationDTO.setAuthorId(author.getId());
//            notificationDTO.setUser_id(users.stream().map(User::getId).collect(Collectors.toList()));
//            notificationDTO.setText(MessageFormat.format("Chat by {0} --{1} created with users: {2}", author.getName(), author.getId(), users.stream().map(User::getName).collect(Collectors.toList())));
//            notificationDTO.setType("ChatCreated");
//
//            notification_gRPC_Client.notifyme(notificationDTO);
//
//            DataTransferService.ChatResponse response = DataTransferService.ChatResponse.newBuilder()
//                    .setMessage("Chat has been created")
//                    .setStatus("200")
//                    .setImageUrl(savedChat.getImage_url())
//                    .setId(savedChat.getId())
//                    .build();
//
//            responseObserver.onNext(response);
//            responseObserver.onCompleted();
//
//        } catch (Exception e) {
//            log.error("Ошибка при создании чата", e);
//            responseObserver.onNext(DataTransferService.ChatResponse.newBuilder()
//                    .setStatus("500")
//                    .setMessage("Unexpected error in chat serving: " + e.getMessage())
//                    .build());
//            responseObserver.onCompleted();
//        }
//    }
//@Override
//public void getallchatsbyid(DataTransferService.ChatData chatData, StreamObserver<DataTransferService.ListOfChats> responseObserver) {
//    long id = chatData.getUser(0).getId();
//    List<DataTransferService.ChatData> chatDataList = new ArrayList<>();
//    chatRepBase.findAllOrderedChatsByUserId(id).forEach(chat -> {
//        DataTransferService.ChatData chatData1 = DataTransferService.ChatData.newBuilder()
//                .setChatId(chat.getId())
//                .setTitle(chat.getTitle())
//                .setImageUrl(chat.getImage_url())
//                .build();
//        chatDataList.add(chatData1);
//    });
//    DataTransferService.ListOfChats listOfChats = DataTransferService.ListOfChats.newBuilder().addAllChatdataList(chatDataList).build();
//    responseObserver.onNext(listOfChats);
//    log.info("List of chats have been successfully retrieved");
//    responseObserver.onCompleted();
//
//}
//
//}
