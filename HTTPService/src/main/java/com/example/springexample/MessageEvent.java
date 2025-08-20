package com.example.springexample;

import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class MessageEvent {
    public MessageEvent(JsonObject message){
        this.username=message.get("username").getAsString();
        this.chat_id = message.get("chat_id").getAsLong();
        this.text = message.get("text").getAsString();
        this.timestamp = message.get("timestamp").getAsString();
        this.user_id = message.get("user_id").getAsLong();
        try{this.image_url=message.get("image_url").getAsString();}catch (Exception e){this.image_url="";}

    }
    String username;
    String type = "message";
    Long chat_id;
    Long user_id;
    String text;
    String timestamp;
    String image_url;

}
