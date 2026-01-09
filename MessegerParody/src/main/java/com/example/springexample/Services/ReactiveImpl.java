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
import com.example.springexample.NotificationDTO;
import com.example.springexample.Notification_gRPC_Client;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import reactor.ReactiveTransferServiceGrpc;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import java.util.stream.Collectors;
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class ReactiveImpl extends ReactiveTransferServiceGrpc.ReactiveTransferServiceImplBase {
    private  final ConvertToProto toProto;
    private  final ReactiveRepository customReactiveRepository;
    private final ReactiveUserChatRepository reactiveUserChatRepository;
    private final ReactiveUserRepository reactiveUserRepository;
    private final ReactiveChatRepository reactiveChatRepository;
    private final Notification_gRPC_Client notification_gRPC_Client;
    @Override
    public void getAllUsersByChatId(DataTransferService.ChatData request, StreamObserver<DataTransferService.UserListResponse> responseObserver) {
        customReactiveRepository.findAllUsersByChatId(request.getChatId()).collectList().as(toProto::toUserListResponse)
                .doOnNext(responseObserver::onNext)
                .doOnSuccess(ignored -> responseObserver.onCompleted())
                .doOnError(responseObserver::onError)
                .subscribe();
    }

    @Override
    public void transferchat(DataTransferService.ChatData newChat, StreamObserver<DataTransferService.ChatResponse> responseObserver) {
        if (newChat.getUserList().isEmpty() || !newChat.hasAuthorId()) {
            customReactiveRepository.findChatById(newChat.getChatId())
                    .switchIfEmpty(Mono.fromRunnable(() -> {
                        responseObserver.onNext(DataTransferService.ChatResponse.newBuilder()
                                .setStatus("500")
                                .setMessage("Cannot find chat ERROR")
                                .build());
                        responseObserver.onCompleted();
                    }))
                    .map(toProto::toChatResp)
//                    .map(a->toProto.toChatResp())
                    .subscribe(
                            response -> {
                                responseObserver.onNext(response);
                                responseObserver.onCompleted();
                            },
                            error -> {
                                responseObserver.onNext(DataTransferService.ChatResponse.newBuilder()
                                        .setStatus("500")
                                        .setMessage(error.getMessage())
                                        .build());
                                responseObserver.onCompleted();
                            }
                    );
            return;
        }

        List<Long> userIds = newChat.getUserList().stream()
                .map(DataTransferService.User::getId)
                .map(Long::parseLong)
                .filter(id -> id != 0)
                .toList();

        if (userIds.size() != newChat.getUserList().size()) {
            responseObserver.onNext(DataTransferService.ChatResponse.newBuilder()
                    .setStatus("500")
                    .setMessage("Chat with such users doesn't exist")
                    .build());
            responseObserver.onCompleted();
            return;
        }

        List<Mono<r2dbc_user>> userMonos = new ArrayList<>();
        for (Long user_id : userIds) {
            Mono<r2dbc_user> userMono = reactiveUserRepository.findById(user_id).switchIfEmpty(Mono.defer(() -> {
                responseObserver.onNext(DataTransferService.ChatResponse.newBuilder()
                        .setStatus("500")
                        .setMessage("Пользователь не найден по ID: " + user_id)
                        .build());
                responseObserver.onCompleted();
                return Mono.empty();
            }));
            userMonos.add(userMono);
        }

        Mono<r2dbc_user> authorMono = reactiveUserRepository.findById(Long.parseLong(newChat.getAuthorId().getId()))
                .switchIfEmpty(Mono.defer(() -> {
                    responseObserver.onError(new Throwable("User-author not found"));
                    return Mono.empty();
                }));

        Mono.zip(authorMono, Mono.zip(userMonos, res -> Arrays.stream(res)
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
                                responseObserver.onNext(DataTransferService.ChatResponse.newBuilder()
                                        .setMessage("Chat already exists")
                                        .setId(exchat.getId())
                                        .setImageUrl(exchat.getImageUrl())
                                        .setStatus("666")
                                        .build());
                                responseObserver.onCompleted();
                                return Mono.empty();
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                r2dbc_chat newChatEntity = new r2dbc_chat();
                                newChatEntity.setTitle(newChat.getTitle());
                                newChatEntity.setImageUrl(newChat.getImageUrl());

                                return reactiveChatRepository.save(newChatEntity).flatMap(savedchat->{
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
                                    });


                                        })
                                        .doOnNext(saved -> {
                                            NotificationDTO notify = new NotificationDTO();
                                            notify.setAuthorId(author.getId());
                                            notify.setUser_id(allIds);
                                            notify.setText("Chat by " + author.getName() + " --" + author.getId() + " created with users: " +
                                                    users.stream().map(r2dbc_user::getName).toList());
                                            notify.setType("ChatCreated");
                                            notification_gRPC_Client.notifyme(notify);

                                            responseObserver.onNext(DataTransferService.ChatResponse.newBuilder()
                                                    .setMessage("Chat has been created")
                                                    .setStatus("200")
                                                    .setImageUrl(saved.getImageUrl())
                                                            .setId(saved.getId())
                                                    .build());
                                            responseObserver.onCompleted();
                                        });
                            }));
                })
                .doOnError(e -> {
                    responseObserver.onNext(DataTransferService.ChatResponse.newBuilder()
                            .setStatus("500")
                            .setMessage("Unexpected error in chat serving: " + e.getMessage())
                            .build());
                    responseObserver.onCompleted();
                })
                .subscribe();
    }




    // message
    @Override
    public void getnewest(DataTransferService.ChatData request, StreamObserver<DataTransferService.Message
> responseObserver) {
        Long chatId = request.getChatId();

        reactiveChatRepository.findById(chatId)
                .switchIfEmpty(Mono.defer(() -> {
//                    responseObserver.onError(new Throwable("Chat not found"));
                    responseObserver.onNext(DataTransferService.Message.getDefaultInstance());
                    responseObserver.onCompleted();
                    return Mono.empty();
                }))
                .flatMap(chat -> customReactiveRepository.findTopByChatIdOrderByTimestampDesc(chat.getId())
                        .switchIfEmpty(Mono.defer(() -> {
                            DataTransferService.Message
 fallback = DataTransferService.Message
.newBuilder()
                                    .setChatId(chat.getId())
                                    .setChatName(chat.getTitle())
                                    .build();
                            responseObserver.onNext(fallback);
                            responseObserver.onCompleted();
                            return Mono.empty();
                        }))
                        .flatMap(message -> reactiveUserRepository.findById(message.getUserId())
                                .map(user -> DataTransferService.Message
.newBuilder()
                                        .setUserName(user.getName())
                                        .setChatName(chat.getTitle())
                                        .setText(message.getText())
                                        .setId(message.getId())
                                        .setTimestamp(message.getTimeStamp())
                                        .setChatId(chat.getId())
                                        .setUserId(user.getId())
                                        .setImageUrl(user.getImageUrl())
                                        .build())
                        )
                )
                .subscribe(
                        response -> {
                            responseObserver.onNext(response);
                            responseObserver.onCompleted();
                        },
                        responseObserver::onError
                );
    }

    @Override
    public void getallchatsbyid(DataTransferService.ChatData request, StreamObserver<DataTransferService.ListOfChats> responseObserver) {
        long id = Long.parseLong(request.getUser(0).getId());
        customReactiveRepository.findAllOrderedChatsByUserId(id).map(chat -> {
            return DataTransferService.ChatData.newBuilder()
                    .setChatId(id)
                    .setTitle(chat.getTitle())
                    .setImageUrl(chat.getImageUrl())
                    .build();


        }).collectList().map(listOfChatData-> DataTransferService.ListOfChats.newBuilder().addAllChatdataList(listOfChatData).build()).subscribe(sub->{
            responseObserver.onNext(sub);
            responseObserver.onCompleted();
        },err->{
            log.error("Error in reactive stream", err);
            responseObserver.onError(err);
        });

    }

    @Override
    public void transferAllMessages(DataTransferService.ChatData request,
                                    StreamObserver<DataTransferService.ListOfMessages> responseObserver) {

        customReactiveRepository.getMessagesByChatId(request.getChatId())
                .flatMap(msg -> {
                    Mono<r2dbc_user>
 userMono = reactiveUserRepository.findById(msg.getUserId());
                    Mono<r2dbc_chat>
 chatMono = reactiveChatRepository.findById(msg.getChatId());

                    return Mono.zip(userMono, chatMono)
                            .map(tuple -> {
                                r2dbc_user user = tuple.getT1();
                                r2dbc_chat chat = tuple.getT2();

                                return DataTransferService.Message
.newBuilder()
                                        .setId(msg.getId())
                                        .setUserName(user.getName())
                                        .setUserId(user.getId())
                                        .setText(msg.getText())
                                        .setChatId(chat.getId())
                                        .setChatName(chat.getTitle()) // если надо
                                        .setTimestamp(msg.getTimeStamp())
                                        .setImageUrl(user.getImageUrl())
                                        .build();
                            });
                })
                .collectList()
                .doOnNext(messageList -> {
                    DataTransferService.ListOfMessages response = DataTransferService.ListOfMessages.newBuilder()
                            .addAllMessageList(messageList)
                            .build();
                    responseObserver.onNext(response);
                    responseObserver.onCompleted();
                })
                .doOnError(responseObserver::onError)
                .subscribe();
    }


    @Override
    public void getUsernameById(DataTransferService.User request,
                                StreamObserver<DataTransferService.User> responseObserver) {

        customReactiveRepository.getUsernameById(Long.parseLong(request.getId()))
                .filter(Objects::nonNull)
                .subscribe(
                        uname -> {
                            responseObserver.onNext(DataTransferService.User.newBuilder()
                                    .setId(request.getId()) // добавлено — желательно сохранять ID
                                    .setUsername(uname)
                                    .build());
                            responseObserver.onCompleted();
                        },
                        err -> responseObserver.onError(err)
                );
    }


    @Override
    public void getimageurl(DataTransferService.ChatData request, StreamObserver<DataTransferService.DriveUrl> responseObserver) {
        reactiveChatRepository.findById(request.getChatId())
                .map(r2dbc_chat::getImageUrl)
                .filter(url -> url != null)
                .map(url -> DataTransferService.DriveUrl.newBuilder()
                        .setUrl(url)
                        .setChatId(String.valueOf(request.getChatId()))
                        .build())
                .subscribe(
                        response -> {
                            responseObserver.onNext(response);
                            responseObserver.onCompleted();
                        },
                        responseObserver::onError,
                        responseObserver::onCompleted
                );
    }


    @Override
    public void getUserImageurl(DataTransferService.UserDataRequest request,
                                StreamObserver<DataTransferService.DriveUrl> responseObserver) {

        customReactiveRepository.getUserImageUrl(request.getId())
                .filter(Objects::nonNull)
                .subscribe(
                        url -> {
                            responseObserver.onNext(DataTransferService.DriveUrl.newBuilder()
                                    .setUrl(url)
                                    .setChatId(String.valueOf(request.getId()))
                                    .build());
                            responseObserver.onCompleted();
                        },
                        error -> responseObserver.onError(error)
                );
    }}
