package com.example.springexample.StompHandlers;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDTO {
    String type = "message";
    // Сквозной идентификатор сообщения: генерируется сервером в ChatBoxStompController и
    // едет одним значением в realtime-эхо и в Kafka. По нему консьюмер MessegerParody
    // отличает переигранную at-least-once запись от нового сообщения (beads myl).
    // Из тела фрейма не принимается — затирается наравне с user_id/chat_id/username/
    // timestamp (инвариант beads g9x).
    private String message_id;
    private String chat_id;
    private String user_id;
    private String username;
    private String timestamp;
    private String text;
    private String imageurl;
}
