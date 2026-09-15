package com.example.springexample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.springexample.JPA_Entities.User;
import com.example.springexample.Repositories.Auth_rep;
import com.example.springexample.Services.ImageStorageService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

/**
 * Поведение третьего писателя {@code users.name} при коллизии ников.
 *
 * {@code register} и {@code changeUsername} уже считаются с UNIQUE-индексом
 * {@code ux_users_lower_name}, а путь Google-логина — нет, и покрыт он не был
 * ничем: единственный тест класса ({@link CustomOAuth2UserServiceUploadImageTest})
 * проверяет только выгрузку аватарки.
 *
 * Тесты бьют в {@code resolveUser}, а не в {@code loadUser}: последний первым делом
 * ходит в Google через {@code super.loadUser}, и без поднятой сети до интересующей
 * ветки не добраться.
 */
class CustomOAuth2UserServiceResolveUserTest {

    private Auth_rep authRep;
    private CustomOAuth2UserService service;

    @BeforeEach
    void setUp() {
        authRep = mock(Auth_rep.class);
        service = new CustomOAuth2UserService(authRep, mock(ImageStorageService.class));
    }

    @Test
    void existingUserIsReturnedWithoutWriting() {
        User existing = new User();
        existing.setId(7L);
        existing.setName("misha");
        when(authRep.findFirstByName("misha")).thenReturn(Optional.of(existing));

        User resolved = service.resolveUser("misha", "google-sub-1");

        assertSame(existing, resolved, "найденный пользователь должен возвращаться как есть");
        verify(authRep, never()).save(any());
    }

    @Test
    void freeNameCreatesUserPendingAvatar() {
        when(authRep.findFirstByName("newcomer")).thenReturn(Optional.empty());
        when(authRep.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.resolveUser("newcomer", "google-sub-2");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(authRep).save(saved.capture());
        assertEquals("newcomer", saved.getValue().getName());
        assertEquals("google-sub-2", saved.getValue().getGoogle_sub());
        assertEquals(
            "pending",
            saved.getValue().getImageUrl(),
            "маркер pending — на нём фронт рисует спиннер, пока аватарка едет в MinIO"
        );
        assertEquals("USER", saved.getValue().getUser_role());
    }

    /**
     * Ключевой тест. До индекса такой вход тихо заводил второго пользователя; после
     * появления {@code ux_users_lower_name} нарушение вылетало из OAuth-флоу как 500.
     * Ожидаем внятный отказ авторизации.
     */
    @Test
    void caseOnlyCollisionFailsAuthenticationInsteadOf500() {
        // "Misha" из Google при живой "misha" в базе: findFirstByName сравнивает точно,
        // поэтому промахивается и уводит код на создание строки.
        when(authRep.findFirstByName("Misha")).thenReturn(Optional.empty());
        when(authRep.save(any(User.class)))
            .thenThrow(new DataIntegrityViolationException("ux_users_lower_name"));

        OAuth2AuthenticationException thrown = assertThrows(
            OAuth2AuthenticationException.class,
            () -> service.resolveUser("Misha", "google-sub-3")
        );

        assertEquals(
            "username_taken",
            thrown.getError().getErrorCode(),
            "ошибка должна быть распознаваемой, а не голым RuntimeException"
        );
    }

    /**
     * Стережёт намеренное решение, а не текущую реализацию: на коллизии НЕЛЬЗЯ
     * перечитывать пользователя по имени и возвращать его. Ники совпали — это не
     * значит, что человек тот же: поиск идёт по имени, а не по google_sub, и подхват
     * пустил бы владельца Google-аккаунта в чужой локальный аккаунт со всеми чатами.
     *
     * Тест покраснеет ровно тогда, когда кто-то «починит» отказ во входе подхватом.
     */
    @Test
    void collisionDoesNotHandOverTheExistingAccount() {
        User someoneElse = new User();
        someoneElse.setId(42L);
        someoneElse.setName("misha");

        when(authRep.findFirstByName("Misha")).thenReturn(Optional.empty());
        when(authRep.save(any(User.class)))
            .thenThrow(new DataIntegrityViolationException("ux_users_lower_name"));

        assertThrows(
            OAuth2AuthenticationException.class,
            () -> service.resolveUser("Misha", "google-sub-4")
        );

        // Ни одного чтения, которое могло бы вернуть чужую строку после коллизии.
        verify(authRep, never()).findByGoogleSub(any());
        verify(authRep, never()).findFirstById(any());
    }
}
