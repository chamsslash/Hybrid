package com.example.springexample;

import lombok.*;

import java.util.ArrayList;
import java.util.List;
@ToString
@Setter
@Getter
@AllArgsConstructor

public class NotificationDTO {

    private Long authorId;

    private List<Long> user_id=new ArrayList<>();

    private String text;
    private Long chat_id;

    private String type;
}
