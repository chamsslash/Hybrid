package com.example.springexample;

import com.example.grpc.DataTransferService;
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
                // Ключ аватарки нужен экрану состава чата: без него список участников
                // рисуется одними заглушками. Поле уже есть в UserDataRequest (image_url = 5),
                // тут оно просто перестаёт теряться при маппинге.
                //
                // Пароль в ответ НЕ кладётся и класться не должен: r2dbc_user тянет
                // myapppassword из строки таблицы, а UserDataRequest.password уезжает
                // на HTTPService и дальше в браузер.
                //
                // Пустая строка вместо null обязательна, а не аккуратность: сеттеры
                // protobuf бросают NPE на null, а image_url в БД нullable — у любого, кто
                // не грузил аватарку. Без защиты падал бы весь getAllUsersByChatId, то есть
                // и проверка членства на горячем STOMP-пути.
                .setImageUrl(user.getImageUrl() == null ? "" : user.getImageUrl())
                .build();
    }
    public Mono<DataTransferService.UserListResponse> toUserListResponse(Mono<List<r2dbc_user>> users){
        return users.map(us -> DataTransferService.UserListResponse.newBuilder()
                .addAllUsers(us.stream().map(this::toUser).toList())
                .build());
    }
    }


