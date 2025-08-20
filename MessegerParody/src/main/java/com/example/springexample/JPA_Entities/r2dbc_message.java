package com.example.springexample.JPA_Entities;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.relational.core.mapping.Column;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table("message")
public class r2dbc_message {

    @Id
    private Long id;

    private String text;

    @Column("time_stamp")
    private String timeStamp;

    @Column("user_id")
    private Long userId;

    @Column("chat_id")
    private Long chatId;
}
