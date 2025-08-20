package com.example.springexample.StompHandlers;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
    public class StatusUserDTO {
        String user_id;
        String status;
        String user_name;
        String chat_id;
    }
