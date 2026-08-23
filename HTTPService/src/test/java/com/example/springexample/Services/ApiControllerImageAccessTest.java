package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import com.example.springexample.Metrics.GrpcRequestsMetric;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import reactor.ReactorReactiveTransferServiceGrpc;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Контроль доступа GET /api/images/{*key} (beads e1o).
 *
 * Схема та же, что у ApiControllerChatAccessTest (beads 7f7): настоящий
 * ChatMembershipService поверх замоканного gRPC-стаба, чтобы тесты стерегли
 * fail-closed целиком — от ответа MessegerParody до HTTP-статуса. Замоканный
 * ImageStorageService служит детектором утечки: на нём проверяется, что за чужим
 * объектом в MinIO не ушло ни одного запроса.
 *
 * Сценарии повторяют живое подтверждение из тикета: аккаунт sunny (id=4) состоит
 * только в чате 3 и скачивал объекты, к которым отношения не имеет.
 */
class ApiControllerImageAccessTest {

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

    private void minioHas(String contentType, byte[] data) {
        Mockito.when(imageStorage.getObject(Mockito.anyString()))
                .thenReturn(Mono.just(new ImageStorageService.StoredObject(data, contentType)));
    }

    @Test
    void userAvatarIsServedToAnyAuthenticatedUser() throws Exception {
        minioHas("image/png", new byte[]{1, 2, 3});

        ResponseEntity<byte[]> response = controller.image(SUNNY, "/userimage/10/e3cfae8e.png").call();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertArrayEquals(new byte[]{1, 2, 3}, response.getBody());
        Mockito.verifyNoInteractions(stub);
    }

    @Test
    void leadingSlashIsStrippedBeforeMinioLookup() throws Exception {
        minioHas("image/png", new byte[]{7});

        controller.image(SUNNY, "/userimage/4/avatar.png").call();

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        Mockito.verify(imageStorage).getObject(key.capture());
        assertEquals("userimage/4/avatar.png", key.getValue());
    }

    @Test
    void chatImageIsServedToChatMember() throws Exception {
        membersOfChatAre(Mono.just(membersResponse(4L, 7L)));
        minioHas("image/jpeg", new byte[]{9});

        ResponseEntity<byte[]> response = controller.image(SUNNY, "/chatimage/3/uuid.jpg").call();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.IMAGE_JPEG, response.getHeaders().getContentType());
        assertArrayEquals(new byte[]{9}, response.getBody());
    }

    @Test
    void chatImageOfForeignChatIsForbiddenAndMinioUntouched() throws Exception {
        membersOfChatAre(Mono.just(membersResponse(1L, 2L, 7L)));

        ResponseEntity<byte[]> response = controller.image(SUNNY, "/chatimage/1/uuid.jpg").call();

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNull(response.getBody());
        Mockito.verifyNoInteractions(imageStorage);
    }

    @Test
    void membershipGrpcFailureIsForbidden() throws Exception {
        membersOfChatAre(Mono.error(new IllegalStateException("messegerparody недоступен")));

        ResponseEntity<byte[]> response = controller.image(SUNNY, "/chatimage/1/uuid.jpg").call();

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        Mockito.verifyNoInteractions(imageStorage);
    }

    @Test
    void membershipTimeoutIsForbidden() throws Exception {
        membersOfChatAre(Mono.never());
        ApiController fastController = new ApiController(grpc, imageStorage,
                new ChatMembershipService(stub, grpcMetric) {
                    @Override
                    Duration membershipTimeout() {
                        return Duration.ofMillis(200);
                    }
                });

        ResponseEntity<byte[]> response = fastController.image(SUNNY, "/chatimage/1/uuid.jpg").call();

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        Mockito.verifyNoInteractions(imageStorage);
    }

    @Test
    void emptyMemberListIsForbidden() throws Exception {
        membersOfChatAre(Mono.just(membersResponse()));

        ResponseEntity<byte[]> response = controller.image(SUNNY, "/chatimage/1/uuid.jpg").call();

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        Mockito.verifyNoInteractions(imageStorage);
    }

    @Test
    void unknownChatIsForbiddenNotServerError() throws Exception {
        membersOfChatAre(Mono.empty());

        ResponseEntity<byte[]> response =
                assertDoesNotThrow(() -> controller.image(SUNNY, "/chatimage/999999/uuid.jpg").call());

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        Mockito.verifyNoInteractions(imageStorage);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "/",
            "///",
            "/../../etc/passwd",
            "/userimage/../chatimage/1/secret.jpg",
            "/chatimage/1/../../userimage/9/a.png",
            "/userimage/./4/a.png",
            "/userimage//a.png",
            "/userimage/4",
            "/userimage/4/nested/a.png",
            "/secret/1/x.png",
            "/chatimage/abc/x.png",
            "/chatimage/+3/x.png",
            "/chatimage/-3/x.png",
            "/chatimage/003/x.png",
            "/chatimage/99999999999999999999/x.png",
            "/userimage/abc/x.png",
            "/USERIMAGE/4/x.png",
            "/userimage/4/a\\b.png",
    })
    void malformedKeyIsRefusedBeforeAnyLookup(String key) throws Exception {
        ResponseEntity<byte[]> response = controller.image(SUNNY, key).call();

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), "ключ: " + key);
        Mockito.verifyNoInteractions(imageStorage);
        Mockito.verifyNoInteractions(stub);
    }

    @Test
    void missingPrincipalIsRefused() throws Exception {
        ResponseEntity<byte[]> response = controller.image(null, "/userimage/4/a.png").call();

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        Mockito.verifyNoInteractions(imageStorage);
    }

    @Test
    void minioFailureStaysNotFound() throws Exception {
        Mockito.when(imageStorage.getObject(Mockito.anyString()))
                .thenReturn(Mono.error(new IllegalStateException("нет такого объекта")));

        ResponseEntity<byte[]> response = controller.image(SUNNY, "/userimage/4/missing.png").call();

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
