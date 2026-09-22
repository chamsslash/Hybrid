package com.example.springexample.StompHandlers;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatListShortObjDTO {
    /**
     * Текст превью для сообщения-стикера (beads a22). У стикера текста нет, и пустая
     * строка в списке чатов выглядела бы так, будто собеседник ничего не писал.
     *
     * Константа живёт здесь, потому что превью собирают ДВА независимых пути и оба уже
     * знают этот класс: живой — ChatBoxStompController по STOMP, холодный —
     * ReactiveGrpcClient по ответу getnewest при загрузке списка чатов. Две разные
     * формулировки для одного и того же события пользователю не нужны.
     */
    public static final String STICKER_PREVIEW = "Стикер";

    private String  chat_id;
    private String  text;
    private String  username;
    private String  timestamp;
    private String  title;
    private String image_url;//For adding new chat to list

}

