package com.example.springexample.JPA_Entities;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.relational.core.mapping.Column;

import java.time.Instant;

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
    private Instant timeStamp;

    // Фактическое имя колонки — user_id_id (см. MessageMapper.map, ReactiveRepository.insertMessage):
    // создано дрейфом Hibernate ddl-auto vs Liquibase-декларации (тикет Hybrid-kubernetes-non-local-fwt).
    @Column("user_id_id")
    private Long userId;

    @Column("chat_id")
    private Long chatId;
}
