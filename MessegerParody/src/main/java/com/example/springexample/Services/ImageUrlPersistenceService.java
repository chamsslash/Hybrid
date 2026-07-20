package com.example.springexample.Services;

import com.example.springexample.JPA_Entities.User;
import com.example.springexample.JPA_Entities.r2dbc_chat;
import com.example.springexample.JPA_Repositories.UserRepBase;
import com.example.springexample.R2DBC_Repositories.ReactiveChatRepository;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Persists image URLs coming from the Kafka "Images" topic into the database.
 * Extracted from the former gRPC MTS_impl.transferimageUrltoDB: this path is
 * driven in-process by KafkaConsumer, never over gRPC.
 */
@Slf4j
@Service
public class ImageUrlPersistenceService {

    private final UserRepBase userRepBase;
    private final ReactiveChatRepository reactiveChatRepository;

    @Autowired
    public ImageUrlPersistenceService(
        UserRepBase userRepBase,
        ReactiveChatRepository reactiveChatRepository
    ) {
        this.userRepBase = userRepBase;
        this.reactiveChatRepository = reactiveChatRepository;
    }

    public void persistImageUrl(String type, String targetId, String url) {
        try {
            if ("userimage".equals(type)) {
                Optional<User> userOptional = userRepBase.findById(
                    Long.parseLong(targetId)
                );
                userOptional.ifPresent(user -> {
                    user.setImageUrl(url);
                    userRepBase.save(user);
                });
            } else if ("chatimage".equals(type)) {
                r2dbc_chat updatedChat = reactiveChatRepository
                    .findById(Long.parseLong(targetId))
                    .map(chat -> {
                        chat.setImageUrl(url);
                        return chat;
                    })
                    .flatMap(reactiveChatRepository::save)
                    .block();
                if (updatedChat == null) {
                    throw new RuntimeException("Chat not found for image update");
                }
            } else {
                throw new RuntimeException("error in transfer image to DB");
            }
        } catch (Exception e) {
            log.error("error in transfer image to DB");
            throw new RuntimeException(e);
        }
    }
}
