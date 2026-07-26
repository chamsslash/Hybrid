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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
        auth = new Auth_impl(authRep, redisTemplate, oauth2Utils, passwordEncoder);
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
}
