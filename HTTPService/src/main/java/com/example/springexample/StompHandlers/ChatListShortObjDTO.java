package com.example.springexample.StompHandlers;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatListShortObjDTO {
    private String  chat_id;
    private String  text;
    private String  username;
    private String  timestamp;
    private String  title;
    private String image_url;//For adding new chat to list

}

