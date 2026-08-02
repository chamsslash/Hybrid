package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import com.example.springexample.ConvertToProto;
import com.example.springexample.JPA_Entities.r2dbc_chat;

import com.example.springexample.JPA_Entities.r2dbc_user;
import com.example.springexample.JPA_Entities.r2dbc_user_Chat;
import com.example.springexample.R2DBC_Repositories.ReactiveChatRepository;
import com.example.springexample.R2DBC_Repositories.ReactiveRepository;
import com.example.springexample.R2DBC_Repositories.ReactiveUserChatRepository;
import com.example.springexample.R2DBC_Repositories.ReactiveUserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import reactor.ReactorReactiveTransferServiceGrpc;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import java.util.stream.Collectors;
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class ReactiveImpl extends ReactorReactiveTransferServiceGrpc.ReactiveTransferServiceImplBase {
    private  final ConvertToProto toProto;
    private  final ReactiveRepository customReactiveRepository;
    private final ReactiveUserChatRepository reactiveUserChatRepository;
    private final ReactiveUserRepository reactiveUserRepository;
    private final ReactiveChatRepository reactiveChatRepository;
    @Override
    public Mono<DataTransferService.UserListResponse> getAllUsersByChatId(DataTransferService.ChatData request) {
        return customReactiveRepository.findAllUsersByChatId(request.getChatId())
                .collectList()
                .as(toProto::toUserListResponse);
    }

    @Override
    public Mono<DataTransferService.ChatResponse> transferchat(DataTransferService.ChatData newChat) {
        if (newChat.getUserList().isEmpty() || !newChat.hasAuthorId()) {
            return customReactiveRepository.findChatById(newChat.getChatId())
                    .map(toProto::toChatResp)
                    .switchIfEmpty(Mono.just(DataTransferService.ChatResponse.newBuilder()
                            .setStatus("500")
                            .setMessage("Cannot find chat ERROR")
                            .build()))
                    .onErrorResume(error -> Mono.just(DataTransferService.ChatResponse.newBuilder()
                            .setStatus("500")
                            .setMessage(error.getMessage())
                            .build()));
        }

        List<Long> userIds = newChat.getUserList().stream()
                .map(DataTransferService.User::getId)
                .map(Long::parseLong)
                .filter(id -> id != 0)
                .toList();

        if (userIds.size() != newChat.getUserList().size()) {
            return Mono.just(DataTransferService.ChatResponse.newBuilder()
                    .setStatus("500")
                    .setMessage("Chat with such users doesn't exist")
                    .build());
        }

        List<Mono<r2dbc_user>> userMonos = userIds.stream()
                .map(userId -> reactiveUserRepository.findById(userId)
                        .switchIfEmpty(Mono.error(new IllegalStateException("Пользователь не найден по ID: " + userId))))
                .toList();

        Mono<r2dbc_user> authorMono = reactiveUserRepository.findById(Long.parseLong(newChat.getAuthorId().getId()))
                .switchIfEmpty(Mono.error(new IllegalStateException("User-author not found")));

        return Mono.zip(authorMono, Mono.zip(userMonos, res -> Arrays.stream(res)
                        .map(obj -> (r2dbc_user) obj)
                        .collect(Collectors.toList())))
                .flatMap(tuple -> {
                    r2dbc_user author = tuple.getT1();
                    List<r2dbc_user> users = tuple.getT2();

                    if (users.stream().noneMatch(u -> u.getId().equals(author.getId()))) {
                        users.add(author);
                    }

                    List<Long> allIds = users.stream()
                            .map(r2dbc_user::getId)
                            .distinct()
                            .toList();

                    return customReactiveRepository.findChatByTitleAndExactUsers(newChat.getTitle(), allIds)
                            .flatMap(exchat -> {
                                return Mono.just(DataTransferService.ChatResponse.newBuilder()
                                        .setMessage("Chat already exists")
                                        .setId(exchat.getId())
                                        .setImageUrl(exchat.getImageUrl())
                                        .setStatus("666")
                                        .build());
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                r2dbc_chat newChatEntity = new r2dbc_chat();
                                newChatEntity.setTitle(newChat.getTitle());
                                newChatEntity.setImageUrl(newChat.getImageUrl());

                                return reactiveChatRepository.save(newChatEntity).flatMap(savedChat -> {
                                    List<Mono<r2dbc_user_Chat>> bindings = users.stream()
                                            .map(usr -> {
                                                r2dbc_user_Chat uc = new r2dbc_user_Chat();
                                                uc.setUserId(usr.getId());
                                                uc.setChatId(savedChat.getId());
                                                return reactiveUserChatRepository.save(uc);
                                            })
                                            .toList();

                                    return Flux.merge(bindings)
                                            .then(Mono.just(savedChat)); // вернуть chat дальше по цепочке
                                }).map(saved -> DataTransferService.ChatResponse.newBuilder()
                                        .setMessage("Chat has been created")
                                        .setStatus("200")
                                        .setImageUrl(saved.getImageUrl())
                                        .setId(saved.getId())
                                        .build());
                            }));
                })
                .onErrorResume(e -> Mono.just(DataTransferService.ChatResponse.newBuilder()
                        .setStatus("500")
                        .setMessage("Unexpected error in chat serving: " + e.getMessage())
                        .build()));
    }




    // message
    @Override
    public Mono<DataTransferService.Message> getnewest(DataTransferService.ChatData request) {
        Long chatId = request.getChatId();

        return reactiveChatRepository.findById(chatId)
                .flatMap(chat -> customReactiveRepository.findTopByChatIdOrderByTimestampDesc(chat.getId())
                        .flatMap(message -> reactiveUserRepository.findById(message.getUserId())
                                .map(user -> DataTransferService.Message.newBuilder()
                                        .setUserName(user.getName())
                                        .setChatName(chat.getTitle())
                                        .setText(message.getText())
                                        .setId(message.getId())
                                        .setTimestamp(message.getTimeStamp().toString())
                                        .setChatId(chat.getId())
                                        .setUserId(user.getId())
                                        .setImageUrl(user.getImageUrl())
                                        .build()))
                        .switchIfEmpty(Mono.just(DataTransferService.Message.newBuilder()
                                .setChatId(chat.getId())
                                .setChatName(chat.getTitle())
                                .build())))
                .switchIfEmpty(Mono.just(DataTransferService.Message.getDefaultInstance()));
    }

    @Override
    public Mono<DataTransferService.ListOfChats> getallchatsbyid(DataTransferService.ChatData request) {
        long id = Long.parseLong(request.getUser(0).getId());
        return customReactiveRepository.findAllOrderedChatsByUserId(id)
                .map(chat -> DataTransferService.ChatData.newBuilder()
                        .setChatId(chat.getId())
                        .setTitle(chat.getTitle())
                        .setImageUrl(chat.getImageUrl())
                        .build())
                .collectList()
                .map(listOfChatData -> DataTransferService.ListOfChats.newBuilder()
                        .addAllChatdataList(listOfChatData)
                        .build())
                .doOnError(err -> log.error("Error in reactive stream", err));
    }

    @Override
    public Mono<DataTransferService.ListOfMessages> transferAllMessages(DataTransferService.ChatData request) {

        return customReactiveRepository.getMessagesByChatId(request.getChatId())
                .flatMap(msg -> {
                    Mono<r2dbc_user> userMono = reactiveUserRepository.findById(msg.getUserId());
                    Mono<r2dbc_chat> chatMono = reactiveChatRepository.findById(msg.getChatId());

                    return Mono.zip(userMono, chatMono)
                            .map(tuple -> {
                                r2dbc_user user = tuple.getT1();
                                r2dbc_chat chat = tuple.getT2();

                                return DataTransferService.Message.newBuilder()
                                        .setId(msg.getId())
                                        .setUserName(user.getName())
                                        .setUserId(user.getId())
                                        .setText(msg.getText())
                                        .setChatId(chat.getId())
                                        .setChatName(chat.getTitle()) // если надо
                                        .setTimestamp(msg.getTimeStamp().toString())
                                        .setImageUrl(user.getImageUrl())
                                        .build();
                            });
                })
                .collectList()
                .map(messageList -> DataTransferService.ListOfMessages.newBuilder()
                        .addAllMessageList(messageList)
                        .build());
    }


    @Override
    public Mono<DataTransferService.User> getUsernameById(DataTransferService.User request) {
        return customReactiveRepository.getUsernameById(Long.parseLong(request.getId()))
                .filter(Objects::nonNull)
                .map(uname -> DataTransferService.User.newBuilder()
                        .setId(request.getId())
                        .setUsername(uname)
                        .build())
                .switchIfEmpty(Mono.just(DataTransferService.User.newBuilder()
                        .setId(request.getId())
                        .build()));
    }


    @Override
    public Mono<DataTransferService.DriveUrl> getimageurl(DataTransferService.ChatData request) {
        return reactiveChatRepository.findById(request.getChatId())
                .map(r2dbc_chat::getImageUrl)
                .filter(url -> url != null)
                .map(url -> DataTransferService.DriveUrl.newBuilder()
                        .setUrl(url)
                        .setChatId(String.valueOf(request.getChatId()))
                        .build())
                .switchIfEmpty(Mono.just(DataTransferService.DriveUrl.newBuilder()
                        .setChatId(String.valueOf(request.getChatId()))
                        .build()));
    }


    @Override
    public Mono<DataTransferService.DriveUrl> getUserImageurl(DataTransferService.UserDataRequest request) {
        return customReactiveRepository.getUserImageUrl(request.getId())
                .filter(Objects::nonNull)
                .map(url -> DataTransferService.DriveUrl.newBuilder()
                        .setUrl(url)
                        .setChatId(String.valueOf(request.getId()))
                        .build())
                .switchIfEmpty(Mono.just(DataTransferService.DriveUrl.newBuilder()
                        .setChatId(String.valueOf(request.getId()))
                        .build()));
    }
}
