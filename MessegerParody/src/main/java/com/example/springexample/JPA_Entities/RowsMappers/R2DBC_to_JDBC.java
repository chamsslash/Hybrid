package com.example.springexample.JPA_Entities.RowsMappers;

import com.example.springexample.JPA_Entities.Chat;
import com.example.springexample.JPA_Entities.Message;
import com.example.springexample.JPA_Entities.r2dbc_chat;
import com.example.springexample.JPA_Entities.r2dbc_message;
import com.example.springexample.JPA_Repositories.ChatRepBase;
import com.example.springexample.JPA_Repositories.MessageRepBase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class R2DBC_to_JDBC {
    @Autowired
    ChatRepBase chatRepository;
    @Autowired
    private MessageRepBase messageRepBase;

    public Chat toChat(r2dbc_chat rChat){
        Chat chat = new Chat();
        chat.setId(rChat.getId());
        chat.setTitle(rChat.getTitle());
        chat.setImage_url(rChat.getImageUrl());
        Chat chatEntity = chatRepository.findById(rChat.getId())
                .orElseThrow(() -> new RuntimeException("No chat found"));
        chat.setMessages(chatEntity.getMessages());
        chat.setUsers(chatEntity.getUsers());
        return chat;
    }
    public Message toMessage(r2dbc_message rmessage){
        Message message = new Message();
        message.setId(rmessage.getId());
        message.setText(rmessage.getText());
        message.setTime_stamp(rmessage.getTimeStamp());
        Message messageEntity = messageRepBase.findById(rmessage.getId()).orElseThrow(() -> new RuntimeException("No messages found"));;
        message.setUser_id(messageEntity.getUser_id());
        message.setChat(messageEntity.getChat());
        return message;
    }
}
