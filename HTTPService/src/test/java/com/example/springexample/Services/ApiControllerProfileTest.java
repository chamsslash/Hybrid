package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import com.example.springexample.Metrics.GrpcRequestsMetric;
import com.example.springexample.Metrics.MembershipCacheMetric;
import com.example.springexample.Utils.TokensResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import reactor.ReactorReactiveTransferServiceGrpc;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ApiControllerProfileTest {

    private AuthGrpc authGrpc;
    private ReactiveGrpcClient grpc;
    private TokensResolver tokensResolver;
    private ApiController controller;

    @BeforeEach
    void setUp() {
        authGrpc = mock(AuthGrpc.class);
        grpc = mock(ReactiveGrpcClient.class);
        tokensResolver = mock(TokensResolver.class);
        ImageStorageService imageStorage = mock(ImageStorageService.class);
        ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub stub =
                mock(ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub.class);
        ChatMembershipService membership = new ChatMembershipService(
                stub, mock(GrpcRequestsMetric.class), mock(MembershipCacheMetric.class));
        controller = new ApiController(grpc, imageStorage, membership, authGrpc, tokensResolver);
    }

    private Authentication authOf(String userId, String sid) {
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(userId, null, List.of());
        token.setDetails(sid);
        return token;
    }

    private DataTransferService.AuthResponse grpcStatus(String status) {
        return DataTransferService.AuthResponse.newBuilder().setStatus(status).build();
    }

    // --- /api/me ---

    @Test
    void meCarriesHasPasswordFlag() throws Exception {
        when(grpc.reactiveGetUsernameById("42")).thenReturn(Mono.just("Миша"));
        when(grpc.reactiveGetUserImageUrl(42L)).thenReturn(Mono.just("userimage/42/a.jpg"));
        when(authGrpc.hasPassword(42L)).thenReturn(Mono.just(true));

        Map<String, Object> me = controller.me(authOf("42", "sid-1")).call();

        assertEquals("42", me.get("userId"));
        assertEquals("Миша", me.get("username"));
        assertEquals("userimage/42/a.jpg", me.get("imageUrl"));
        assertEquals(Boolean.TRUE, me.get("hasPassword"));
    }

    @Test
    void meReportsNoPasswordWhenAuthServiceIsUnreachable() throws Exception {
        when(grpc.reactiveGetUsernameById("42")).thenReturn(Mono.just("Миша"));
        when(grpc.reactiveGetUserImageUrl(42L)).thenReturn(Mono.just(""));
        when(authGrpc.hasPassword(42L)).thenReturn(Mono.error(new IllegalStateException("down")));

        Map<String, Object> me = controller.me(authOf("42", "sid-1")).call();

        assertEquals(Boolean.FALSE, me.get("hasPassword"));
    }

    // --- смена ника ---

    @Test
    void changeUsernameSuccessReturns200() throws Exception {
        when(authGrpc.changeUsername(42L, "Новый")).thenReturn(Mono.just(grpcStatus("200")));

        ResponseEntity<Map<String, String>> response =
                controller.changeUsername(authOf("42", "sid-1"), Map.of("username", "Новый")).call();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("ok", response.getBody().get("status"));
    }

    @Test
    void changeUsernameTakenReturns409() throws Exception {
        when(authGrpc.changeUsername(anyLong(), anyString())).thenReturn(Mono.just(grpcStatus("666")));

        ResponseEntity<Map<String, String>> response =
                controller.changeUsername(authOf("42", "sid-1"), Map.of("username", "Занят")).call();

        assertEquals(409, response.getStatusCode().value());
        assertEquals("USERNAME_TAKEN", response.getBody().get("error"));
    }

    @Test
    void changeUsernameTakesUserIdFromAuthenticationNotFromBody() throws Exception {
        when(authGrpc.changeUsername(anyLong(), anyString())).thenReturn(Mono.just(grpcStatus("200")));

        controller.changeUsername(authOf("42", "sid-1"),
                Map.of("username", "Новый", "userId", "999")).call();

        verify(authGrpc).changeUsername(eq(42L), eq("Новый"));
        verify(authGrpc, never()).changeUsername(eq(999L), anyString());
    }

    // --- смена пароля ---

    @Test
    void changePasswordSuccessKillsOtherSessionsAndKeepsCurrent() throws Exception {
        when(authGrpc.changePassword(42L, "old", "new")).thenReturn(Mono.just(grpcStatus("200")));

        ResponseEntity<Map<String, String>> response = controller.changePassword(
                authOf("42", "sid-1"),
                Map.of("currentPassword", "old", "newPassword", "new")).call();

        assertEquals(200, response.getStatusCode().value());
        verify(tokensResolver).deleteOtherSessionsByUser("42", "sid-1");
    }

    @Test
    void wrongCurrentPasswordReturns403NotUnauthorized() throws Exception {
        when(authGrpc.changePassword(anyLong(), anyString(), anyString()))
                .thenReturn(Mono.just(grpcStatus("401")));

        ResponseEntity<Map<String, String>> response = controller.changePassword(
                authOf("42", "sid-1"),
                Map.of("currentPassword", "wrong", "newPassword", "new")).call();

        // Ровно 403, а НЕ 401: интерцептор axios.js на 401 делает refresh и повторяет запрос,
        // и ошибка ввода ушла бы в цикл повторов вместо экрана.
        assertEquals(403, response.getStatusCode().value());
        assertEquals("WRONG_CURRENT_PASSWORD", response.getBody().get("error"));
        verify(tokensResolver, never()).deleteOtherSessionsByUser(anyString(), anyString());
    }

    @Test
    void accountWithoutPasswordReturns409() throws Exception {
        when(authGrpc.changePassword(anyLong(), anyString(), anyString()))
                .thenReturn(Mono.just(grpcStatus("409")));

        ResponseEntity<Map<String, String>> response = controller.changePassword(
                authOf("1", "sid-1"),
                Map.of("currentPassword", "x", "newPassword", "new")).call();

        assertEquals(409, response.getStatusCode().value());
        assertEquals("NO_PASSWORD_ON_ACCOUNT", response.getBody().get("error"));
        verify(tokensResolver, never()).deleteOtherSessionsByUser(anyString(), anyString());
    }

    @Test
    void blankNewPasswordReturns400() throws Exception {
        when(authGrpc.changePassword(anyLong(), anyString(), anyString()))
                .thenReturn(Mono.just(grpcStatus("400")));

        ResponseEntity<Map<String, String>> response = controller.changePassword(
                authOf("42", "sid-1"),
                Map.of("currentPassword", "old", "newPassword", "   ")).call();

        assertEquals(400, response.getStatusCode().value());
        assertEquals("PASSWORD_BLANK", response.getBody().get("error"));
    }
}
