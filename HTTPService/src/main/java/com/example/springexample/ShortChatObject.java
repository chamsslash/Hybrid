package com.example.springexample;

import lombok.*;

@NoArgsConstructor
@RequiredArgsConstructor
@Data
@AllArgsConstructor
public class ShortChatObject {
    @NonNull
    private Long id;
    private String preview_username ;
    private String title;
    private Long user_id;
    @NonNull
    private String preview;
    @NonNull
    private String lastMessageTime;
    private String chat_image_url;
}
