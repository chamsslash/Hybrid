package com.example.springexample;

import lombok.*;

import java.util.ArrayList;
import java.util.List;
@NoArgsConstructor
@Setter
@Getter
@AllArgsConstructor
@RequiredArgsConstructor
public class NotificationDTO {
    @NonNull
    private Long authorId;
    private List<Long> user_id=new ArrayList<>();
    @NonNull
    private String text;
    private Long chat_id;
    @NonNull
    private String type;
}
