package com.example.springexample.Services;

import com.example.springexample.Utils.TokensResolver;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * GET /api/stickers — личный набор стикеров текущего пользователя (beads a22).
 *
 * Проверяется ровно то, что нельзя увидеть по ответу: ЧЕЙ набор спрашивается. Владелец
 * берётся из {@link Authentication}, а параметром наружу не принимается — иначе любой
 * залогиненный читал бы чужой набор, подставив чужой id, а вместе с набором и ключи
 * чужих картинок (префикс sticker/ отдаётся любому аутентифицированному, см.
 * ApiControllerImageAccessTest). Тот же инвариант, что у /api/usersearch (beads cdn).
 *
 * Порядок и схлопывание дублей внутри набора — забота SQL, они закрыты
 * MessegerParody/R2DBC_Repositories/StickerSetIT на живом Postgres.
 */
class ApiControllerStickerSetTest {

    private final ReactiveGrpcClient grpc = Mockito.mock(ReactiveGrpcClient.class);

    private final ApiController controller = new ApiController(grpc, mock(ImageStorageService.class),
            mock(ChatMembershipService.class), mock(AuthGrpc.class), mock(TokensResolver.class));

    private static final Authentication DIMA =
            new UsernamePasswordAuthenticationToken("9", null, List.of());

    @Test
    void ownerComesFromPrincipal() throws Exception {
        Mockito.when(grpc.reactiveGetMyStickers(9L))
                .thenReturn(Mono.just(List.of("sticker/9/uuid.png")));

        List<String> keys = controller.stickers(DIMA).call();

        assertEquals(List.of("sticker/9/uuid.png"), keys);
        Mockito.verify(grpc).reactiveGetMyStickers(9L);
    }

    /**
     * Сбой gRPC отдаёт пустой набор, а не ошибку: вкладка «Мои стикеры» вспомогательная,
     * и 500 отсюда уронил бы открытие всей панели вместе со вкладкой «Загрузить», которая
     * от набора не зависит вовсе.
     */
    @Test
    void grpcFailureYieldsEmptySetInsteadOfError() throws Exception {
        Mockito.when(grpc.reactiveGetMyStickers(9L))
                .thenReturn(Mono.error(new IllegalStateException("messegerparody недоступен")));

        List<String> keys = controller.stickers(DIMA).call();

        assertTrue(keys.isEmpty());
    }

    /**
     * Нечисловой принципал — это сломанный токен, а не запрос «найди что-нибудь»: ходить
     * с ним в MessegerParody незачем. Та же ветка, что в /api/usersearch (beads cdn); в
     * норме sub access-токена — это id пользователя в БД (beads 820).
     */
    @Test
    void nonNumericPrincipalYieldsEmptySetWithoutGrpcCall() throws Exception {
        Authentication broken = new UsernamePasswordAuthenticationToken("не-число", null, List.of());

        List<String> keys = controller.stickers(broken).call();

        assertTrue(keys.isEmpty());
        Mockito.verifyNoInteractions(grpc);
    }
}
