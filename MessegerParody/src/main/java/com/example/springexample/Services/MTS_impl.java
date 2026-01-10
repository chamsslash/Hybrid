package com.example.springexample.Services;

import static reactor.core.publisher.Signal.subscribe;

import com.example.grpc.DataTransferService;
import com.example.grpc.DataTransferService.DriveUrl;
import com.example.grpc.MessageTransferServiceGrpc;
import com.example.springexample.JPA_Entities.Chat;
import com.example.springexample.JPA_Entities.Message;
import com.example.springexample.JPA_Entities.RowsMappers.R2DBC_to_JDBC;
import com.example.springexample.JPA_Entities.User;
import com.example.springexample.JPA_Entities.r2dbc_chat;
import com.example.springexample.JPA_Repositories.MessageRepBase;
import com.example.springexample.JPA_Repositories.UserRepBase;
import com.example.springexample.NotificationDTO;
import com.example.springexample.Notification_gRPC_Client;
import com.example.springexample.R2DBC_Repositories.ReactiveChatRepository;
import com.example.springexample.R2DBC_Repositories.ReactiveUserChatRepository;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

@Slf4j
@GrpcService
public class MTS_impl
    extends MessageTransferServiceGrpc.MessageTransferServiceImplBase
{

    private final MessageRepBase messageRepBase;
    private final UserRepBase userRepBase;
    private final ReactiveUserChatRepository userChatRepository;
    private final Notification_gRPC_Client ngc;
    private final ReactiveChatRepository reactiveChatRepository;
    private final R2DBC_to_JDBC reactiveparser;

    @Autowired
    public MTS_impl(
        MessageRepBase messageRepBase,
        UserRepBase userRepBase,
        ReactiveUserChatRepository userChatRepository,
        Notification_gRPC_Client ngc,
        ReactiveChatRepository reactiveChatRepository,
        R2DBC_to_JDBC reactiveparser
    ) {
        this.messageRepBase = messageRepBase;
        this.userRepBase = userRepBase;
        this.userChatRepository = userChatRepository;
        this.ngc = ngc;
        this.reactiveChatRepository = reactiveChatRepository;
        this.reactiveparser = reactiveparser;
    }

    public DriveUrl  transferimageUrltoDB(DataTransferService.DriveUrl request) {
        try {
            if (request.getType().equals("userimage")) {
                Optional<User> userOptional = userRepBase.findById(
                    Long.parseLong(request.getChatId())
                );
                userOptional.ifPresent(user -> {
                    user.setImageUrl(request.getUrl());
                    userRepBase.save(user);
                });
            } else {
                Mono.defer(() -> {
                    throw new RuntimeException("error in transfer image to DB");
                }).subscribe();
            }
            return DataTransferService.DriveUrl.newBuilder()
                .setType(request.getType())
                .setUrl(request.getUrl())
                .setChatId(request.getChatId())
                .build();
        } catch (Exception e) {
            log.error("error in transfer image to DB");
            throw new RuntimeException(e);
        }
    }

    @Transactional
    @Override
    public void transferMessage(
        DataTransferService.Message newMessage,
        StreamObserver<DataTransferService.Message> responseObserver
    ) {
        try {
            log.info("Message not found,preparing to create");
            Message messagetocreate = new Message();
            messagetocreate.setId(newMessage.getId());
            messagetocreate.setText(newMessage.getText());
            messagetocreate.setTime_stamp(newMessage.getTimestamp());
            Optional<User> userOptional = userRepBase.findById(
                newMessage.getUserId()
            );
            Chat chatOptional = reactiveparser.toChat(
                Objects.requireNonNull(
                    reactiveChatRepository
                        .findById(newMessage.getChatId())
                        .switchIfEmpty(
                            Mono.defer(() -> {
                                log.error(
                                    "Chat isnt exists for transfer message method "
                                );
                                responseObserver.onError(
                                    new Throwable(
                                        "Chat isnt exists for transfer message method "
                                    )
                                );
                                return Mono.empty();
                            })
                        )
                        .block()
                )
            );
            if (userOptional.isPresent() && chatOptional != null) {
                messagetocreate.setChat(chatOptional);
                messagetocreate.setUser_id(userOptional.get());
                messageRepBase.save(messagetocreate);
                log.info("Message created in db");
                NotificationDTO tosend = new NotificationDTO(
                    newMessage.getUserId(),
                    "Message has been successfully sent",
                    "MessageCreated"
                );
                tosend.setChat_id(chatOptional.getId());
                ngc.notifyme(tosend);

                responseObserver.onNext(newMessage);
            } else log.error(
                "User or chat not found,cant create new message --> skip"
            );

            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error(e.getMessage());
        }
    }

    @Transactional
    @Override
    public void transferAllMessages(
        DataTransferService.ChatData request,
        StreamObserver<DataTransferService.ListOfMessages> responseObserver
    ) {
        List<Message> messageListoOptional = messageRepBase.getMessagesByChatId(
            request.getChatId()
        );
        List<DataTransferService.Message> messages_list = messageListoOptional
            .stream()
            .map(msg -> {
                User usr = userRepBase
                    .findById(msg.getUser_id().getId())
                    .orElseGet(User::new);
                Chat cht = reactiveparser.toChat(
                    Objects.requireNonNull(
                        reactiveChatRepository
                            .findById(msg.getChat().getId())
                            .block()
                    )
                );
                if (cht != null) {
                    return DataTransferService.Message.newBuilder()
                        .setId(msg.getId())
                        .setUserName(usr.getName())
                        .setUserId(usr.getId())
                        .setText(msg.getText())
                        .setChatId(cht.getId())
                        .setTimestamp(msg.getTime_stamp())
                        .setImageUrl(usr.getImageUrl())
                        .build();
                }
                responseObserver.onError(
                    new Throwable(
                        "Error in getting all messages,no chat found of these messages"
                    )
                );
                return null;
            })
            .collect(Collectors.toList());
        log.info(
            "preparing all messages to send from chat{}",
            request.getChatId()
        );
        DataTransferService.ListOfMessages resp =
            DataTransferService.ListOfMessages.newBuilder()
                .addAllMessageList(messages_list)
                .build();
        log.info("successfully found,ready to send");
        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    @Transactional
    @Override
    public void getnewest(
        DataTransferService.ChatData chatData,
        StreamObserver<DataTransferService.Message> responseObserver
    ) {
        Long id = chatData.getChatId();

        try {
            Chat chatOpt = reactiveparser.toChat(
                Objects.requireNonNull(
                    reactiveChatRepository
                        .findById(id)
                        .switchIfEmpty(
                            Mono.fromRunnable(() -> {
                                log.error("chatisempty");
                                responseObserver.onError(
                                    new Throwable(
                                        "chat is empty in get the newest message in chat"
                                    )
                                );
                            }).then(Mono.just(new r2dbc_chat()))
                        )
                        .block()
                )
            );

            Message message = reactiveparser.toMessage(
                messageRepBase.findTopByChatIdOrderByTimestampDesc(id)
            );
            User userOpt = userRepBase
                .findById(message.getUser_id().getId())
                .orElseGet(() -> {
                    log.error(
                        "user of message isnt dound in get newest message in chat"
                    );
                    responseObserver.onError(
                        new Throwable(
                            "user of message isnt dound in get newest message in chat"
                        )
                    );
                    return new User();
                });
            if (message != null) {
                DataTransferService.Message response =
                    DataTransferService.Message.newBuilder()
                        .setUserName(userOpt.getName())
                        .setChatName(chatOpt.getTitle())
                        .setText(message.getText())
                        .setId(message.getId())
                        .setTimestamp(message.getTime_stamp())
                        .setChatId(chatOpt.getId())
                        .setUserId(userOpt.getId())
                        .setImageUrl(userOpt.getImageUrl())
                        .build();

                log.info("First message found, sending full response");
                responseObserver.onNext(response);
            } else {
                log.info("No messages found, sending empty preview");
                DataTransferService.Message fallback =
                    DataTransferService.Message.newBuilder()
                        .setChatId(chatOpt.getId())
                        .setChatName(chatOpt.getTitle())
                        .build();
                responseObserver.onNext(fallback);
            }

            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(
                Status.INTERNAL.withDescription(
                    "Internal error: " + e.getMessage()
                )
                    .augmentDescription("in getnewest()")
                    .asRuntimeException()
            );
        }
    }
}
