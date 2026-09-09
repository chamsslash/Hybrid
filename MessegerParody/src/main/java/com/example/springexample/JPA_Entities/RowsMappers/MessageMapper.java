package com.example.springexample.JPA_Entities.RowsMappers;

import com.example.springexample.JPA_Entities.r2dbc_message
;
import com.example.springexample.JPA_Entities.r2dbc_message;
import io.r2dbc.spi.Row;

public class MessageMapper {
    public static r2dbc_message
 map(Row row) {
        r2dbc_message
 message = new r2dbc_message
();
        message.setId(row.get("id", Long.class));
        message.setText(row.get("text", String.class));
        message.setTimeStamp(row.get("time_stamp", java.time.Instant.class));
        message.setUserId(row.get("user_id", Long.class));
        message.setChatId(row.get("chat_id", Long.class));
        return message;
    }
}
