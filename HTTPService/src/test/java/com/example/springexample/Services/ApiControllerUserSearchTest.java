package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import com.example.springexample.Metrics.GrpcRequestsMetric;
import com.example.springexample.Metrics.MembershipCacheMetric;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import reactor.ReactorReactiveTransferServiceGrpc;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ApiControllerUserSearchTest {

    private AuthGrpc authGrpc;
    private ApiController controller;

    @BeforeEach
    void setUp() {
        authGrpc = mock(AuthGrpc.class);
        ReactiveGrpcClient grpc = mock(ReactiveGrpcClient.class);
        ImageStorageService imageStorage = mock(ImageStorageService.class);
        ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub stub =
                mock(ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub.class);
        ChatMembershipService membership = new ChatMembershipService(
                stub, mock(GrpcRequestsMetric.class), mock(MembershipCacheMetric.class));
        controller = new ApiController(grpc, imageStorage, membership, authGrpc);
    }

    private Authentication authOf(String userId) {
        return new UsernamePasswordAuthenticationToken(userId, null, List.of());
    }

    private DataTransferService.UserDataRequest found(long id, String name, String image) {
        return DataTransferService.UserDataRequest.newBuilder()
                .setId(id).setUsername(name).setImageUrl(image).build();
    }

    @Test
    void returnsUserIdUsernameAndImageKeyForEachHit() throws Exception {
        when(authGrpc.searchUsersByPrefix(anyString(), anyLong(), anyInt()))
                .thenReturn(Mono.just(List.of(
                        found(2L, "Миша", "userimage/2/a.jpg"),
                        found(3L, "Мишель", ""))));

        List<Map<String, Object>> body = controller.userSearch(authOf("1"), "ми").call();

        assertEquals(2, body.size());
        assertEquals("2", body.get(0).get("userId"));
        assertEquals("Миша", body.get(0).get("username"));
        assertEquals("userimage/2/a.jpg", body.get(0).get("imageUrl"));
        assertEquals("3", body.get(1).get("userId"));
        assertEquals("", body.get(1).get("imageUrl"));
    }

    @Test
    void requesterIdComesFromAuthenticationNotFromRequest() throws Exception {
        when(authGrpc.searchUsersByPrefix(anyString(), anyLong(), anyInt()))
                .thenReturn(Mono.just(List.of()));

        controller.userSearch(authOf("77"), "ми").call();

        ArgumentCaptor<Long> requester = ArgumentCaptor.forClass(Long.class);
        verify(authGrpc).searchUsersByPrefix(eq("ми"), requester.capture(), eq(10));
        assertEquals(77L, requester.getValue());
    }

    @Test
    void nonNumericPrincipalYieldsEmptyListWithoutCallingGrpc() throws Exception {
        List<Map<String, Object>> body = controller.userSearch(authOf("not-a-number"), "ми").call();

        assertTrue(body.isEmpty());
        verify(authGrpc, never()).searchUsersByPrefix(anyString(), anyLong(), anyInt());
    }

    @Test
    void grpcFailurePropagatesInsteadOfLookingLikeEmptyResult() {
        when(authGrpc.searchUsersByPrefix(anyString(), anyLong(), anyInt()))
                .thenReturn(Mono.error(new IllegalStateException("auth service down")));

        assertThrows(IllegalStateException.class, () -> controller.userSearch(authOf("1"), "ми").call());
    }
}
