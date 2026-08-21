package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import com.example.springexample.Metrics.GrpcRequestsMetric;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.ReactorReactiveTransferServiceGrpc;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatMembershipServiceTest {

    private final ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub stub =
            Mockito.mock(ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub.class);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final GrpcRequestsMetric metric = new GrpcRequestsMetric(registry);

    private final ChatMembershipService service = new ChatMembershipService(stub, metric);

    private Timer membersTimer(String outcome) {
        return registry.find(GrpcRequestsMetric.CALL_TIMER)
                .tags("method", "members", "outcome", outcome)
                .timer();
    }

    private static DataTransferService.UserListResponse responseWith(long... ids) {
        DataTransferService.UserListResponse.Builder b =
                DataTransferService.UserListResponse.newBuilder();
        for (long id : ids) {
            b.addUsers(DataTransferService.UserDataRequest.newBuilder()
                    .setId(id)
                    .setUsername("user-" + id)
                    .build());
        }
        return b.build();
    }

    @Test
    void membersReturnsUsersWithIdAndUsername() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.just(responseWith(7L, 9L)));

        List<DataTransferService.UserDataRequest> members = service.members(5L).block();

        assertNotNull(members);
        assertEquals(2, members.size());
        assertEquals(7L, members.get(0).getId());
        assertEquals("user-7", members.get(0).getUsername());
    }

    @Test
    void isMemberTrueWhenUserPresent() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.just(responseWith(7L, 9L)));

        assertTrue(service.isMember(5L, "9"));
    }

    @Test
    void isMemberFalseWhenUserAbsent() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.just(responseWith(7L, 9L)));

        assertFalse(service.isMember(5L, "42"));
    }

    @Test
    void isMemberFalseOnEmptyMemberList() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.just(responseWith()));

        assertFalse(service.isMember(5L, "9"));
    }

    @Test
    void isMemberFalseWhenGrpcFails() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.error(new IllegalStateException("messegerparody недоступен")));

        assertFalse(service.isMember(5L, "9"));
    }

    @Test
    void isMemberFalseOnTimeout() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.never());

        ChatMembershipService fastService = new ChatMembershipService(stub, metric) {
            @Override
            Duration membershipTimeout() {
                return Duration.ofMillis(100);
            }
        };

        assertFalse(fastService.isMember(5L, "9"));
    }

    @Test
    void membersRecordsLatencyUnderItsOwnMethodTag() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.just(responseWith(7L, 9L)));

        service.members(5L).block();

        assertNotNull(membersTimer("success"));
        assertEquals(1L, membersTimer("success").count());
        assertNull(membersTimer("error"));
    }

    @Test
    void membersLatencyIsCountedOnSubscriptionNotOnAssembly() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.just(responseWith(7L)));

        service.members(5L);

        assertNull(membersTimer("success"));
    }

    @Test
    void failedMembershipCallIsRecordedAsError() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.error(new IllegalStateException("messegerparody недоступен")));

        assertFalse(service.isMember(5L, "9"));

        assertNotNull(membersTimer("error"));
        assertEquals(1L, membersTimer("error").count());
        assertNull(membersTimer("success"));
    }

    @Test
    void timedOutMembershipCallIsRecordedAsCancelled() {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.never());

        ChatMembershipService fastService = new ChatMembershipService(stub, metric) {
            @Override
            Duration membershipTimeout() {
                return Duration.ofMillis(100);
            }
        };

        assertFalse(fastService.isMember(5L, "9"));

        assertNotNull(membersTimer("cancel"));
        assertEquals(1L, membersTimer("cancel").count());
        assertNull(membersTimer("success"));
        assertNull(membersTimer("error"));
    }
}
