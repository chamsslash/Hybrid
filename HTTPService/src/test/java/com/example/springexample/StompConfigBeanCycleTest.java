package com.example.springexample;

import com.example.springexample.Services.ChatMembershipService;
import com.example.springexample.StompHandlers.StompErrorNotifier;
import com.example.springexample.Utils.AccessTokenVerifier;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Срез контекста на цикл бинов вокруг StompConfig (beads 8wh, F5 финального ревью).
 *
 * В HTTPService нет ни одного {@code @SpringBootTest} — контекст Spring в тестах нигде не
 * поднимается, поэтому цикл {@code StompAuthChannelInterceptor -> StompErrorNotifier ->
 * SimpMessagingTemplate -> конфигурация брокера -> StompConfig -> StompAuthChannelInterceptor},
 * разорванный {@code @Lazy} на последнем параметре конструктора интерцептора (javadoc
 * {@link StompAuthChannelInterceptor#StompAuthChannelInterceptor}), был найден живьём на
 * стенде, а не тестом. Следующая правка того конструктора — например, возврат к
 * {@code @RequiredArgsConstructor}, о чём тот же javadoc прямо предупреждает, — уронила бы
 * контекст на старте пода, а не в CI.
 *
 * Полный {@code @SpringBootTest} здесь намеренно не заводится: он тянет Kafka, Redis, MinIO,
 * три gRPC-канала и Security — отдельная (и куда более дорогая) задача. {@code
 * ApplicationContextRunner} с {@code @EnableWebSocketMessageBroker} на {@code StompConfig}
 * поднимает ровно ту инфраструктуру, где живёт цикл: аннотация — это {@code
 * @Import(DelegatingWebSocketMessageBrokerConfiguration.class)}, а он и есть источник
 * настоящего бина {@code SimpMessagingTemplate} (метод {@code brokerMessagingTemplate()}),
 * которого требует {@code StompErrorNotifier}. {@code StompDenialCounter} и {@code
 * StompFrameTimestampInterceptor} — компоненты без собственных зависимостей, включены как
 * есть; {@code AccessTokenVerifier} (требует JWT_PUBLIC_KEY_PEM) и {@code
 * ChatMembershipService} (требует gRPC-стаб) замоканы — их устройство к самому циклу бинов
 * отношения не имеет.
 */
class StompConfigBeanCycleTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    StompConfig.class,
                    StompAuthChannelInterceptor.class,
                    StompErrorNotifier.class,
                    StompFrameTimestampInterceptor.class,
                    StompDenialCounter.class)
            .withBean(AccessTokenVerifier.class, () -> Mockito.mock(AccessTokenVerifier.class))
            .withBean(ChatMembershipService.class, () -> Mockito.mock(ChatMembershipService.class));

    /**
     * Контекст обязан подниматься с {@code @Lazy} на месте. Без него тест краснеет —
     * проверено вручную (временно снята аннотация, тест упал на BeanCurrentlyInCreationException
     * с сообщением про цикл StompErrorNotifier/StompAuthChannelInterceptor/StompConfig,
     * аннотация возвращена, см. отчёт ревью).
     */
    @Test
    void contextLoadsWithLazyErrorNotifier() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
