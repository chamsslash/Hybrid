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

    @Column("user_id")
    private Long userId;

    @Column("chat_id")
    private Long chatId;

    // Ключ стикера в MinIO (beads a22): sticker/<ownerUserId>/<uuid>.<ext>. null —
    // обычное текстовое сообщение. Не путать с users.image_url (аватарка отправителя):
    // это вложение самого сообщения.
    @Column("sticker_key")
    private String stickerKey;
}
