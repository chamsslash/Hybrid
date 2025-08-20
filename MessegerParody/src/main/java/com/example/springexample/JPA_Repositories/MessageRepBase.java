package com.example.springexample.JPA_Repositories;
import java.util.List;
import java.util.Optional;

import com.example.springexample.JPA_Entities.Message;
import com.example.springexample.JPA_Entities.r2dbc_message
;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepBase extends JpaRepository<Message
,Long> {


    List<Message
> getMessagesByChatId(Long chatId);

    Optional<Message
> getMessageById(Long id);
    @Query(value = """
    SELECT * FROM message 
    WHERE chat_id = :chatId
    ORDER BY time_stamp DESC
    LIMIT 1
""", nativeQuery = true)
    r2dbc_message
 findTopByChatIdOrderByTimestampDesc(@Param("chatId") Long chatId);
}
