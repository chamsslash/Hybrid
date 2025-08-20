package com.example.springexample.JPA_Entities;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
@Getter
@Setter

@NoArgsConstructor
@AllArgsConstructor
@Table("user_chat")
public class r2dbc_user_Chat {
    private Long userId;
    private Long chatId;
}

