package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import com.example.springexample.Metrics.GrpcRequestsMetric;
import com.example.springexample.ShortChatObject;
import com.example.springexample.StompHandlers.ChatListShortObjDTO;
import com.example.springexample.StompHandlers.ChatListStompController;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.ReactorReactiveTransferServiceGrpc;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Превью списка чатов и история сообщений для стикера (beads a22) — ХОЛОДНЫЙ путь, тот,
 * что отрабатывает при загрузке страницы, в отличие от живого STOMP-пути в
 * ChatBoxStompController.
 *
 * Оба решения задублированы по необходимости (данные приходят из разных мест), и именно
 * поэтому нужны тесты с обеих сторон: разъедься они, симптом был бы виден только после
 * перезагрузки страницы — живьём стикер отрисован и превью подписано, а после F5 на том
 * же месте пустой пузырь и пустая строка в списке чатов.
 */
class ReactiveGrpcClientStickerPreviewTest {

    private final ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub stub =
            Mockito.mock(ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub.class,
                    Mockito.RETURNS_SELF);

    private final ReactiveGrpcClient client = new ReactiveGrpcClient(
            Mockito.mock(ChatListStompController.class), stub,
            new GrpcRequestsMetric(new SimpleMeterRegistry()));

    private void newestMessageIs(DataTransferService.Message message) {
        Mockito.when(stub.getnewest(Mockito.any(DataTransferService.ChatData.class)))
                .thenReturn(Mono.just(message));
    }

    @Test
    void stickerPreviewIsPlaceholderInsteadOfEmptyText() {
        newestMessageIs(DataTransferService.Message.newBuilder()
                .setChatId(5).setUserName("Дима").setText("")
                .setStickerKey("sticker/9/uuid.png")
                .build());

        ShortChatObject preview = client.reactiveGetNewestMessage(
                DataTransferService.ChatData.newBuilder().setChatId(5).build()).block();

        assertThat(preview).isNotNull();
        assertThat(preview.getPreview()).isEqualTo(ChatListShortObjDTO.STICKER_PREVIEW);
    }

    @Test
    void textPreviewIsStillTheMessageItself() {
        newestMessageIs(DataTransferService.Message.newBuilder()
                .setChatId(5).setUserName("Дима").setText("привет")
                .build());

        ShortChatObject preview = client.reactiveGetNewestMessage(
                DataTransferService.ChatData.newBuilder().setChatId(5).build()).block();

        assertThat(preview).isNotNull();
        assertThat(preview.getPreview()).isEqualTo("привет");
    }

    /**
     * История чата обязана донести ключ стикера до фронта: без него перезагрузка страницы
     * превращала бы отправленные стикеры в пустые пузыри. Имя поля в JSON (sticker_key)
     * совпадает с тем, что приходит живьём по STOMP, — иначе рендер пришлось бы ветвить.
     */
    @Test
    void historyCarriesStickerKeyIntoMessageEvent() {
        DataTransferService.ListOfMessages history = DataTransferService.ListOfMessages.newBuilder()
                .addMessageList(DataTransferService.Message.newBuilder()
                        .setChatId(5).setUserId(9).setUserName("Дима").setText("")
                        .setStickerKey("sticker/9/uuid.png"))
                .addMessageList(DataTransferService.Message.newBuilder()
                        .setChatId(5).setUserId(9).setUserName("Дима").setText("привет"))
                .build();

        var events = client.parseOnMessageEvent(history).collectList().block();

        assertThat(events).hasSize(2);
        assertThat(events.get(0).getSticker_key()).isEqualTo("sticker/9/uuid.png");
        assertThat(events.get(1).getSticker_key()).isEmpty();
    }
}
