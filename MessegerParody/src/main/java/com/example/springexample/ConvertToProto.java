package com.example.springexample;

import com.example.grpc.DataTransferService;
import com.example.springexample.JPA_Entities.Chat;
import com.example.springexample.JPA_Entities.User;
import com.example.springexample.JPA_Entities.r2dbc_chat;
import com.example.springexample.JPA_Entities.r2dbc_user;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
@Component
public class ConvertToProto {
    public DataTransferService.ChatResponse toChatResp(r2dbc_chat chat){
        return DataTransferService.ChatResponse.newBuilder().setId(chat.getId())
                .setStatus("200")
                .setMessage("Chat is founded ready to use")
                .setImageUrl(chat.getImageUrl())
                .build();

    }
    public DataTransferService.UserDataRequest toUser(r2dbc_user user){
        return DataTransferService.UserDataRequest.newBuilder()
                .setId(user.getId())
                .setUsername(user.getName())
                .build();
    }
    public Mono<DataTransferService.UserListResponse> toUserListResponse(Mono<List<r2dbc_user>> users){
        return users.map(us -> DataTransferService.UserListResponse.newBuilder()
                .addAllUsers(us.stream().map(this::toUser).toList())
                .build());
    }
    }


