package com.example.springexample.JPA_Entities.RowsMappers;

import com.example.springexample.JPA_Entities.r2dbc_chat
;
import io.r2dbc.spi.Row;

public class ChatMapper {
    public static r2dbc_chat
 map(Row row) {
        r2dbc_chat
 chat = new r2dbc_chat
();
        chat.setId(row.get("id", Long.class));
        chat.setTitle(row.get("title", String.class));
        chat.setImageUrl(row.get("image_url", String.class));
        return chat;
    }
}
