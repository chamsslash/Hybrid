package com.example.springexample;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Контракт сообщения топика "Messages" — зеркало HTTPService/StompHandlers/ChatMessageDTO.java
 * (сервисы не шарят Java-классы, только JSON-поля).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDTO {
    String type = "message";
    private String chat_id;
    private String user_id;
    private String username;
    private String timestamp;
    private String text;
    private String imageurl;
}
