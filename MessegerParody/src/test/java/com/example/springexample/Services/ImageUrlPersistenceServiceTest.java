package com.example.springexample.Services;

import com.example.springexample.JPA_Entities.User;
import com.example.springexample.JPA_Entities.r2dbc_chat;
import com.example.springexample.JPA_Repositories.UserRepBase;
import com.example.springexample.R2DBC_Repositories.ReactiveChatRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты обеих веток ImageUrlPersistenceService (beads se2):
 * userimage -> UserRepBase, chatimage -> ReactiveChatRepository. Репозитории замоканы.
 */
@ExtendWith(MockitoExtension.class)
class ImageUrlPersistenceServiceTest {

    @Mock
    private UserRepBase userRepBase;

    @Mock
    private ReactiveChatRepository reactiveChatRepository;

    private ImageUrlPersistenceService service;

    private void initService() {
        service = new ImageUrlPersistenceService(userRepBase, reactiveChatRepository);
    }

    @Test
    void userimageBranchPersistsObjectKeyOnUser() {
        initService();
        User user = new User();
        user.setId(42L);
        when(userRepBase.findById(42L)).thenReturn(Optional.of(user));

        service.persistImageUrl("userimage", "42", "userimage/42/uuid.png");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepBase).save(captor.capture());
        assertThat(captor.getValue().getImageUrl()).isEqualTo("userimage/42/uuid.png");
        verify(reactiveChatRepository, never()).findById(any(Long.class));
    }

    @Test
    void userimageBranchNoOpWhenUserMissing() {
        initService();
        when(userRepBase.findById(99L)).thenReturn(Optional.empty());

        service.persistImageUrl("userimage", "99", "userimage/99/uuid.png");

        verify(userRepBase, never()).save(any(User.class));
    }

    @Test
    void chatimageBranchPersistsObjectKeyOnChat() {
        initService();
        r2dbc_chat chat = new r2dbc_chat(7L, "title", "old-key");
        when(reactiveChatRepository.findById(7L)).thenReturn(Mono.just(chat));
        when(reactiveChatRepository.save(any(r2dbc_chat.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        service.persistImageUrl("chatimage", "7", "chatimage/7/uuid.jpg");

        ArgumentCaptor<r2dbc_chat> captor = ArgumentCaptor.forClass(r2dbc_chat.class);
        verify(reactiveChatRepository).save(captor.capture());
        assertThat(captor.getValue().getImageUrl()).isEqualTo("chatimage/7/uuid.jpg");
        verify(userRepBase, never()).findById(any(Long.class));
    }

    @Test
    void chatimageBranchThrowsWhenChatMissing() {
        initService();
        when(reactiveChatRepository.findById(404L)).thenReturn(Mono.empty());

        assertThatThrownBy(() -> service.persistImageUrl("chatimage", "404", "chatimage/404/uuid.jpg"))
                .isInstanceOf(RuntimeException.class);

        verify(reactiveChatRepository, never()).save(any(r2dbc_chat.class));
    }

    @Test
    void unknownTargetTypeThrows() {
        initService();

        assertThatThrownBy(() -> service.persistImageUrl("bogus", "1", "key"))
                .isInstanceOf(RuntimeException.class);
    }
}
