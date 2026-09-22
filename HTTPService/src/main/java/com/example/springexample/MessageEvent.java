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
    // Ключ стикера в MinIO (beads a22). Имя поля совпадает с ChatMessageDTO.sticker_key
    // намеренно: история чата (этот класс) и живое эхо по STOMP приезжают на фронт в
    // одну и ту же функцию рендера, и разные имена потребовали бы её ветвить.
    //
    // Не путать с соседним image_url — там ключ аватарки отправителя, а не вложение.
    String sticker_key;

}
