package com.example.springexample.StompHandlers;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
