package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import com.example.springexample.Metrics.GrpcRequestsMetric;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.example.springexample.MessageEvent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import reactor.ReactorReactiveTransferServiceGrpc;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Контроль доступа GET /api/chat (beads 7f7).
 *
 * Членство проверяется настоящим ChatMembershipService поверх замоканного gRPC-стаба,
 * а не заглушкой самой проверки: тесты обязаны стеречь fail-closed-поведение целиком,
 * от ответа MessegerParody до HTTP-статуса. ReactiveGrpcClient замокан отдельно —
 * именно на нём проверяется, что за данными чужого чата не ушло ни одного вызова.
 */
class ApiControllerChatAccessTest {

    private final ReactiveGrpcClient grpc = Mockito.mock(ReactiveGrpcClient.class);
    private final ImageStorageService imageStorage = Mockito.mock(ImageStorageService.class);
    // RETURNS_SELF (beads 8wh, F3): ChatMembershipService.members() зовёт
    // stub.withDeadlineAfter(...) перед getAllUsersByChatId; у настоящего AbstractStub это
    // возвращает новый стаб с тем же каналом, а у мока без явного стаба вернуло бы null
    // и роняло бы цепочку NPE до gRPC-вызова.
    private final ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub stub =
            Mockito.mock(ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub.class,
                    Mockito.RETURNS_SELF);

    private final GrpcRequestsMetric grpcMetric = new GrpcRequestsMetric(new SimpleMeterRegistry());

    private final ApiController controller =
            new ApiController(grpc, imageStorage, new ChatMembershipService(stub, grpcMetric));

    private static final Authentication SUNNY =
            new UsernamePasswordAuthenticationToken("4", null, List.of());

    private static DataTransferService.UserListResponse membersResponse(long... ids) {
        DataTransferService.UserListResponse.Builder b = DataTransferService.UserListResponse.newBuilder();
        for (long id : ids) {
            b.addUsers(DataTransferService.UserDataRequest.newBuilder()
                    .setId(id).setUsername("user-" + id).build());
        }
        return b.build();
    }

    private void membersOfChatAre(Mono<DataTransferService.UserListResponse> response) {
        Mockito.when(stub.getAllUsersByChatId(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(response);
    }

    /** Полный набор заглушек «чат существует и отдаётся» — нужен только участнику. */
    private void chatDataIsAvailable() {
        MessageEvent message = new MessageEvent();
        message.setUsername("user-7");
        message.setText("привет");
        Mockito.when(grpc.reactiveGetUsernameById("4")).thenReturn(Mono.just("sunny"));
        Mockito.when(grpc.reactiveChatServe(Mockito.any()))
                .thenReturn(Mono.just("{\"status\":\"200\",\"message\":\"ok\",\"id\":\"3\"}"));
        Mockito.when(grpc.reactiveGetAllUsernamesByChatId(Mockito.any()))
                .thenReturn(Mono.just(List.of("sunny", "user-7")));
        Mockito.when(grpc.reactiveGetImageUrl(3L)).thenReturn(Mono.just("chat-3.png"));
        Mockito.when(grpc.reactiveGetUserImageUrl(4L)).thenReturn(Mono.just("sunny.png"));
        Mockito.when(grpc.reactiveGetAllMessages(Mockito.any())).thenReturn(Mono.just(List.of(message)));
    }

    @Test
    void memberGetsChatHistoryAndMembers() throws Exception {
        membersOfChatAre(Mono.just(membersResponse(4L, 7L)));
        chatDataIsAvailable();

        ResponseEntity<Map<String, Object>> response = controller.chat(SUNNY, 3L, "мой чат").call();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> model = response.getBody();
        assertNotNull(model);
        assertEquals(3L, model.get("chatId"));
        assertEquals("sunny", model.get("username"));
        assertEquals(List.of("sunny", "user-7"), model.get("members"));
        assertEquals(1, ((List<?>) model.get("messages")).size(), "история участнику отдаётся как раньше");
    }

    @Test
    void outsiderGetsForbiddenAndNoChatDataIsFetched() throws Exception {
        // sunny (id=4) состоит только в чате 3, участники чата 1 — другие люди.
        membersOfChatAre(Mono.just(membersResponse(1L, 2L, 7L)));

        ResponseEntity<Map<String, Object>> response = controller.chat(SUNNY, 1L, "fwt probe chat").call();

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNull(response.getBody(), "в теле отказа не должно быть ничего из чужого чата");
        // Главное в этом тесте: отказ ДО побочных эффектов. Ни история, ни список
        // участников, ни картинка чата не запрашивались вовсе.
        Mockito.verifyNoInteractions(grpc);
    }

    @Test
    void membershipGrpcFailureIsForbiddenNotOpenDoor() throws Exception {
        membersOfChatAre(Mono.error(new IllegalStateException("messegerparody недоступен")));

        ResponseEntity<Map<String, Object>> response = controller.chat(SUNNY, 1L, "").call();

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        Mockito.verifyNoInteractions(grpc);
    }

    @Test
    void emptyMemberListIsForbidden() throws Exception {
        membersOfChatAre(Mono.just(membersResponse()));

        ResponseEntity<Map<String, Object>> response = controller.chat(SUNNY, 1L, "").call();

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        Mockito.verifyNoInteractions(grpc);
    }

    @Test
    void unknownChatIdIsForbiddenNotServerError() throws Exception {
        // Несуществующий чат: ответа от MessegerParody нет вовсе (пустой Mono).
        // Раньше такой сценарий уходил в данные чата и падал 500 на .block().
        membersOfChatAre(Mono.empty());

        ResponseEntity<Map<String, Object>> response =
                assertDoesNotThrow(() -> controller.chat(SUNNY, 999_999L, "").call());

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        Mockito.verifyNoInteractions(grpc);
    }
}
