package com.example.springexample.R2DBC_Repositories;

import com.example.springexample.JPA_Entities.r2dbc_user_Chat;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface ReactiveUserChatRepository extends R2dbcRepository<r2dbc_user_Chat,Long> {
    //r2dbc cannot autogen id(pk) that why we need to ignore it in saving method in repo
    @Query("INSERT INTO user_chat (user_id, chat_id) VALUES ($1, $2)")
    Mono<Void> saveLink(Long userId, Long chatId);
}
