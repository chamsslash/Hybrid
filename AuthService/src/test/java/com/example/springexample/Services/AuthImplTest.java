package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import com.example.springexample.JPA_Entities.User;
import com.example.springexample.Repositories.Auth_rep;
import com.example.springexample.Utils.MyPasswordEncoder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AuthImplTest {

    private Auth_rep authRep;
    private RedisTemplate<String, String> redisTemplate;
    private ValueOperations<String, String> valueOps;
    private MyPasswordEncoder passwordEncoder;
    private Oauth2Utils oauth2Utils;
    private Auth_impl auth;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        authRep = mock(Auth_rep.class);
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        passwordEncoder = mock(MyPasswordEncoder.class);
        oauth2Utils = mock(Oauth2Utils.class);
        auth = new Auth_impl(authRep, redisTemplate, oauth2Utils, passwordEncoder,
                new UsernameSearchService(authRep));
    }

    private User user(long id, String name, String role, String encodedPassword) {
        User u = new User();
        u.setId(id);
        u.setName(name);
        u.setUser_role(role);
        u.setMyapppassword(encodedPassword);
        return u;
    }

    // --- A1: login success sets sub = DB user id ---
    @Test
    @SuppressWarnings("unchecked")
    void loginSuccessReturnsSubAsUserId() {
        User u = user(42L, "alice", "USER", "hashed");
        when(authRep.findFirstByName("alice")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("pw", "hashed")).thenReturn(true);

        StreamObserver<DataTransferService.AuthResponse> obs = mock(StreamObserver.class);
        auth.login(DataTransferService.UserDataRequest.newBuilder()
                .setUsername("alice").setPassword("pw").build(), obs);

        ArgumentCaptor<DataTransferService.AuthResponse> captor =
                ArgumentCaptor.forClass(DataTransferService.AuthResponse.class);
        verify(obs).onNext(captor.capture());
        verify(obs).onCompleted();
        verify(obs, never()).onError(any());

        DataTransferService.AuthResponse resp = captor.getValue();
        assertEquals("200", resp.getStatus());
        assertEquals("42", resp.getSub());
        assertEquals("USER", resp.getRole());
    }

    // --- A2 (x7m): bad password -> AuthResponse 401, NOT onError ---
    @Test
    @SuppressWarnings("unchecked")
    void loginBadPasswordReturns401NotOnError() {
        User u = user(7L, "bob", "USER", "hashed");
        when(authRep.findFirstByName("bob")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        StreamObserver<DataTransferService.AuthResponse> obs = mock(StreamObserver.class);
        auth.login(DataTransferService.UserDataRequest.newBuilder()
                .setUsername("bob").setPassword("wrong").build(), obs);

        ArgumentCaptor<DataTransferService.AuthResponse> captor =
                ArgumentCaptor.forClass(DataTransferService.AuthResponse.class);
        verify(obs).onNext(captor.capture());
        verify(obs).onCompleted();
        verify(obs, never()).onError(any());

        assertEquals("401", captor.getValue().getStatus());
    }

    // regression: unknown user still returns 401
    @Test
    @SuppressWarnings("unchecked")
    void loginUnknownUserReturns401() {
        when(authRep.findFirstByName("ghost")).thenReturn(Optional.empty());

        StreamObserver<DataTransferService.AuthResponse> obs = mock(StreamObserver.class);
        auth.login(DataTransferService.UserDataRequest.newBuilder()
                .setUsername("ghost").setPassword("pw").build(), obs);

        ArgumentCaptor<DataTransferService.AuthResponse> captor =
                ArgumentCaptor.forClass(DataTransferService.AuthResponse.class);
        verify(obs).onNext(captor.capture());
        verify(obs, never()).onError(any());
        assertEquals("401", captor.getValue().getStatus());
    }

    // --- Занятость ника решает БД, а не предпроверка (UNIQUE ux_users_lower_name) ---
    @Test
    @SuppressWarnings("unchecked")
    void registerDuplicateInDifferentCaseReturns666() {
        // findFirstByName сравнивает точно, поэтому предпроверка "МИША" при живой "Миша"
        // ничего не находит и пропускает запрос дальше — отбивает уже уникальный индекс.
        when(authRep.findFirstByName("МИША")).thenReturn(Optional.empty());
        when(passwordEncoder.encodePassword("pw")).thenReturn("hashed");
        when(authRep.save(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint \"ux_users_lower_name\""));

        StreamObserver<DataTransferService.AuthResponse> obs = mock(StreamObserver.class);
        auth.register(DataTransferService.UserDataRequest.newBuilder()
                .setUsername("МИША")
                .setPassword("pw")
                .setImageUrl("")
                .build(), obs);

        ArgumentCaptor<DataTransferService.AuthResponse> captor =
                ArgumentCaptor.forClass(DataTransferService.AuthResponse.class);
        verify(obs).onNext(captor.capture());
        verify(obs).onCompleted();
        verify(obs, never()).onError(any());
        assertEquals("666", captor.getValue().getStatus());
        assertEquals("User with such name already exists", captor.getValue().getMessage());
    }

    // --- A4: getUserBySub numeric sub -> findById ---
    @Test
    @SuppressWarnings("unchecked")
    void getUserBySubNumericResolvesById() {
        User u = user(42L, "alice", "USER", "hashed");
        when(authRep.findById(42L)).thenReturn(Optional.of(u));

        StreamObserver<DataTransferService.User> obs = mock(StreamObserver.class);
        auth.getUserBySub(DataTransferService.Sub_Role.newBuilder().setSub("42").build(), obs);

        ArgumentCaptor<DataTransferService.User> captor =
                ArgumentCaptor.forClass(DataTransferService.User.class);
        verify(obs).onNext(captor.capture());
        verify(obs).onCompleted();
        verify(authRep).findById(42L);
        verify(authRep, never()).findByGoogleSub(anyString());

        DataTransferService.User resp = captor.getValue();
        assertEquals(42L, resp.getId());
        assertEquals("alice", resp.getUsername());
        assertEquals("USER", resp.getRole());
    }

    // --- A4: non-numeric sub -> NOT_FOUND, no NumberFormatException crash ---
    @Test
    @SuppressWarnings("unchecked")
    void getUserBySubNonNumericReturnsNotFound() {
        StreamObserver<DataTransferService.User> obs = mock(StreamObserver.class);

        assertDoesNotThrow(() -> auth.getUserBySub(
                DataTransferService.Sub_Role.newBuilder().setSub("not-a-number").build(), obs));

        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(obs).onError(captor.capture());
        verify(obs, never()).onNext(any());
        assertInstanceOf(StatusRuntimeException.class, captor.getValue());
    }

    // --- A3: checkOneTimeCodeAndGetSubRole -> sub is numeric user id, not google_sub ---
    @Test
    @SuppressWarnings("unchecked")
    void checkOneTimeCodeReturnsNumericUserIdAsSub() {
        String googleSub = "google-sub-xyz";
        User u = user(99L, "carol", "USER", "hashed");
        when(valueOps.get("UserOneTimeCodeFastCheckCODE")).thenReturn(googleSub);
        when(authRep.findByGoogleSub(googleSub)).thenReturn(Optional.of(u));

        StreamObserver<DataTransferService.Sub_Role> obs = mock(StreamObserver.class);
        auth.checkOneTimeCodeAndGetSubRole(
                DataTransferService.idToken.newBuilder().setId("CODE").build(), obs);

        ArgumentCaptor<DataTransferService.Sub_Role> captor =
                ArgumentCaptor.forClass(DataTransferService.Sub_Role.class);
        verify(obs).onNext(captor.capture());
        verify(obs).onCompleted();

        DataTransferService.Sub_Role resp = captor.getValue();
        assertEquals("99", resp.getSub());
        assertEquals("USER", resp.getRole());
    }

    // --- Смена ника (beads ehe) ---
    @Test
    @SuppressWarnings("unchecked")
    void changeUsernameSuccessPersistsNewName() {
        User u = user(42L, "Миша", "USER", "hashed");
        when(authRep.findFirstById(42L)).thenReturn(Optional.of(u));
        when(authRep.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        StreamObserver<DataTransferService.AuthResponse> obs = mock(StreamObserver.class);
        auth.changeUsername(DataTransferService.ChangeUsernameRequest.newBuilder()
                .setUserId(42L).setNewUsername("МишаНовый").build(), obs);

        ArgumentCaptor<DataTransferService.AuthResponse> resp =
                ArgumentCaptor.forClass(DataTransferService.AuthResponse.class);
        verify(obs).onNext(resp.capture());
        verify(obs).onCompleted();
        assertEquals("200", resp.getValue().getStatus());

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(authRep).saveAndFlush(saved.capture());
        assertEquals("МишаНовый", saved.getValue().getName());
    }

    @Test
    @SuppressWarnings("unchecked")
    void changeUsernameToTakenNameReturns666() {
        User u = user(42L, "Миша", "USER", "hashed");
        when(authRep.findFirstById(42L)).thenReturn(Optional.of(u));
        when(authRep.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key ... ux_users_lower_name"));

        StreamObserver<DataTransferService.AuthResponse> obs = mock(StreamObserver.class);
        auth.changeUsername(DataTransferService.ChangeUsernameRequest.newBuilder()
                .setUserId(42L).setNewUsername("САША").build(), obs);

        ArgumentCaptor<DataTransferService.AuthResponse> resp =
                ArgumentCaptor.forClass(DataTransferService.AuthResponse.class);
        verify(obs).onNext(resp.capture());
        verify(obs).onCompleted();
        verify(obs, never()).onError(any());
        assertEquals("666", resp.getValue().getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void changeUsernameToBlankReturns400WithoutTouchingRepository() {
        StreamObserver<DataTransferService.AuthResponse> obs = mock(StreamObserver.class);
        auth.changeUsername(DataTransferService.ChangeUsernameRequest.newBuilder()
                .setUserId(42L).setNewUsername("   ").build(), obs);

        ArgumentCaptor<DataTransferService.AuthResponse> resp =
                ArgumentCaptor.forClass(DataTransferService.AuthResponse.class);
        verify(obs).onNext(resp.capture());
        assertEquals("400", resp.getValue().getStatus());
        verify(authRep, never()).saveAndFlush(any(User.class));
        verify(authRep, never()).findFirstById(anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void changeUsernameForUnknownUserReturns404() {
        when(authRep.findFirstById(999L)).thenReturn(Optional.empty());

        StreamObserver<DataTransferService.AuthResponse> obs = mock(StreamObserver.class);
        auth.changeUsername(DataTransferService.ChangeUsernameRequest.newBuilder()
                .setUserId(999L).setNewUsername("кто-то").build(), obs);

        ArgumentCaptor<DataTransferService.AuthResponse> resp =
                ArgumentCaptor.forClass(DataTransferService.AuthResponse.class);
        verify(obs).onNext(resp.capture());
        assertEquals("404", resp.getValue().getStatus());
        verify(authRep, never()).saveAndFlush(any(User.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void changeUsernameFlushesImmediatelySoConstraintViolationIsCatchable() {
        User u = user(42L, "Миша", "USER", "hashed");
        when(authRep.findFirstById(42L)).thenReturn(Optional.of(u));
        when(authRep.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        StreamObserver<DataTransferService.AuthResponse> obs = mock(StreamObserver.class);
        auth.changeUsername(DataTransferService.ChangeUsernameRequest.newBuilder()
                .setUserId(42L).setNewUsername("МишаНовый").build(), obs);

        // Именно saveAndFlush, а не save: под отложенным flush нарушение UNIQUE прилетело бы
        // после ответа клиенту, вне catch, и ветка "666" стала бы недостижимой.
        verify(authRep).saveAndFlush(any(User.class));
        verify(authRep, never()).save(any(User.class));
    }

    // --- Смена пароля (beads ehe) ---
    @Test
    @SuppressWarnings("unchecked")
    void changePasswordWithCorrectCurrentPersistsNewHash() {
        User u = user(42L, "Миша", "USER", "old-hash");
        when(authRep.findFirstById(42L)).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("old", "old-hash")).thenReturn(true);
        when(passwordEncoder.encodePassword("new")).thenReturn("new-hash");
        when(authRep.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        StreamObserver<DataTransferService.AuthResponse> obs = mock(StreamObserver.class);
        auth.changePassword(DataTransferService.ChangePasswordRequest.newBuilder()
                .setUserId(42L).setCurrentPassword("old").setNewPassword("new").build(), obs);

        ArgumentCaptor<DataTransferService.AuthResponse> resp =
                ArgumentCaptor.forClass(DataTransferService.AuthResponse.class);
        verify(obs).onNext(resp.capture());
        assertEquals("200", resp.getValue().getStatus());

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(authRep).save(saved.capture());
        assertEquals("new-hash", saved.getValue().getMyapppassword());
    }

    @Test
    @SuppressWarnings("unchecked")
    void changePasswordWithWrongCurrentReturns401AndKeepsHash() {
        User u = user(42L, "Миша", "USER", "old-hash");
        when(authRep.findFirstById(42L)).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("wrong", "old-hash")).thenReturn(false);

        StreamObserver<DataTransferService.AuthResponse> obs = mock(StreamObserver.class);
        auth.changePassword(DataTransferService.ChangePasswordRequest.newBuilder()
                .setUserId(42L).setCurrentPassword("wrong").setNewPassword("new").build(), obs);

        ArgumentCaptor<DataTransferService.AuthResponse> resp =
                ArgumentCaptor.forClass(DataTransferService.AuthResponse.class);
        verify(obs).onNext(resp.capture());
        assertEquals("401", resp.getValue().getStatus());
        verify(authRep, never()).save(any(User.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void changePasswordOnAccountWithoutPasswordReturns409() {
        User u = user(1L, "ДМИТРИЙ ХОРОХОРИН", "USER", "");
        when(authRep.findFirstById(1L)).thenReturn(Optional.of(u));

        StreamObserver<DataTransferService.AuthResponse> obs = mock(StreamObserver.class);
        auth.changePassword(DataTransferService.ChangePasswordRequest.newBuilder()
                .setUserId(1L).setCurrentPassword("whatever").setNewPassword("new").build(), obs);

        ArgumentCaptor<DataTransferService.AuthResponse> resp =
                ArgumentCaptor.forClass(DataTransferService.AuthResponse.class);
        verify(obs).onNext(resp.capture());
        assertEquals("409", resp.getValue().getStatus());
        verify(authRep, never()).save(any(User.class));
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void changePasswordToBlankReturns400WithoutTouchingRepository() {
        StreamObserver<DataTransferService.AuthResponse> obs = mock(StreamObserver.class);
        auth.changePassword(DataTransferService.ChangePasswordRequest.newBuilder()
                .setUserId(42L).setCurrentPassword("old").setNewPassword("   ").build(), obs);

        ArgumentCaptor<DataTransferService.AuthResponse> resp =
                ArgumentCaptor.forClass(DataTransferService.AuthResponse.class);
        verify(obs).onNext(resp.capture());
        assertEquals("400", resp.getValue().getStatus());
        verify(authRep, never()).findFirstById(anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void changePasswordForUnknownUserReturns404() {
        when(authRep.findFirstById(999L)).thenReturn(Optional.empty());

        StreamObserver<DataTransferService.AuthResponse> obs = mock(StreamObserver.class);
        auth.changePassword(DataTransferService.ChangePasswordRequest.newBuilder()
                .setUserId(999L).setCurrentPassword("old").setNewPassword("new").build(), obs);

        ArgumentCaptor<DataTransferService.AuthResponse> resp =
                ArgumentCaptor.forClass(DataTransferService.AuthResponse.class);
        verify(obs).onNext(resp.capture());
        assertEquals("404", resp.getValue().getStatus());
    }

    // --- Признак «есть пароль» (beads ehe) ---
    @Test
    @SuppressWarnings("unchecked")
    void hasPasswordTrueForLocalAccount() {
        when(authRep.findFirstById(42L)).thenReturn(Optional.of(user(42L, "Миша", "USER", "hash")));

        StreamObserver<DataTransferService.ProfileFlags> obs = mock(StreamObserver.class);
        auth.hasPassword(DataTransferService.UserDataRequest.newBuilder().setId(42L).build(), obs);

        ArgumentCaptor<DataTransferService.ProfileFlags> flags =
                ArgumentCaptor.forClass(DataTransferService.ProfileFlags.class);
        verify(obs).onNext(flags.capture());
        verify(obs).onCompleted();
        assertTrue(flags.getValue().getHasPassword());
    }

    @Test
    @SuppressWarnings("unchecked")
    void hasPasswordFalseForGoogleAccountAndForUnknownUser() {
        when(authRep.findFirstById(1L)).thenReturn(Optional.of(user(1L, "Google", "USER", "")));
        when(authRep.findFirstById(999L)).thenReturn(Optional.empty());

        StreamObserver<DataTransferService.ProfileFlags> google = mock(StreamObserver.class);
        auth.hasPassword(DataTransferService.UserDataRequest.newBuilder().setId(1L).build(), google);
        ArgumentCaptor<DataTransferService.ProfileFlags> googleFlags =
                ArgumentCaptor.forClass(DataTransferService.ProfileFlags.class);
        verify(google).onNext(googleFlags.capture());
        assertFalse(googleFlags.getValue().getHasPassword());

        StreamObserver<DataTransferService.ProfileFlags> unknown = mock(StreamObserver.class);
        auth.hasPassword(DataTransferService.UserDataRequest.newBuilder().setId(999L).build(), unknown);
        ArgumentCaptor<DataTransferService.ProfileFlags> unknownFlags =
                ArgumentCaptor.forClass(DataTransferService.ProfileFlags.class);
        verify(unknown).onNext(unknownFlags.capture());
        assertFalse(unknownFlags.getValue().getHasPassword());
    }
}
