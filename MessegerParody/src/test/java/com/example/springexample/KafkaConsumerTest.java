package com.example.springexample;

import com.example.springexample.Metrics.MessagePersistenceMetric;
import com.example.springexample.R2DBC_Repositories.ReactiveRepository;
import com.example.springexample.Services.ImageUrlPersistenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты парсинга контрактов топиков в KafkaConsumer.
 *
 * "Images" (beads se2): { "targetType": "userimage"|"chatimage", "targetId": "<id>",
 * "objectKey": "<key>" } — ImageUrlPersistenceService замокан, проверяем передачу
 * распарсенных полей.
 *
 * "Messages" (beads myl): проверяем, что серверный message_id доезжает из JSON до
 * репозитория. Саму уникальность здесь проверить нельзя — её обеспечивает индекс
 * Postgres, см. R2DBC_Repositories/MessageIdempotencyIT.
 */
@ExtendWith(MockitoExtension.class)
class KafkaConsumerTest {

    @Mock
    private ImageUrlPersistenceService imageUrlPersistenceService;

    @Mock
    private ReactiveRepository reactiveRepository;

    // Счётчик персистентности сообщений (beads c2k). Мок обязателен: listenChatMessages
    // инкрементирует его на обеих ветках, а @InjectMocks не заполняет поля, для которых
    // нет @Mock — без него оба сценария падали с NullPointerException внутри catch.
    @Mock
    private MessagePersistenceMetric messagePersistenceMetric;

    @InjectMocks
    private KafkaConsumer kafkaConsumer;

    @Test
    void parsesUserimageContractAndDelegatesToPersistenceService() {
        String event = "{\"targetType\":\"userimage\",\"targetId\":\"42\",\"objectKey\":\"userimage/42/uuid.png\"}";

        kafkaConsumer.listenOauthImage(event);

        verify(imageUrlPersistenceService).persistImageUrl("userimage", "42", "userimage/42/uuid.png");
    }

    @Test
    void parsesChatimageContractAndDelegatesToPersistenceService() {
        String event = "{\"targetType\":\"chatimage\",\"targetId\":\"7\",\"objectKey\":\"chatimage/7/uuid.jpg\"}";

        kafkaConsumer.listenOauthImage(event);

        verify(imageUrlPersistenceService).persistImageUrl("chatimage", "7", "chatimage/7/uuid.jpg");
    }

    @Test
    void ignoresExtraFieldsNotInContract() {
        String event = "{\"targetType\":\"userimage\",\"targetId\":\"1\",\"objectKey\":\"userimage/1/x.png\",\"extra\":\"ignored\"}";

        kafkaConsumer.listenOauthImage(event);

        verify(imageUrlPersistenceService).persistImageUrl("userimage", "1", "userimage/1/x.png");
    }

    /**
     * Имя JSON-поля — единственная связь между HTTPService/StompHandlers/ChatMessageDTO и
     * MessegerParody/ChatMessageDTO (Java-классы сервисы не шарят). Разъедься эти имена —
     * id молча приехал бы как null, ON CONFLICT перестал бы срабатывать, и защита от
     * дублей тихо выключилась бы, ничего не сломав видимым образом.
     */
    @Test
    void serverMessageIdFromPayloadReachesRepository() {
        when(reactiveRepository.insertMessage(any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        String message = "{\"message_id\":\"11111111-1111-1111-1111-111111111111\",\"chat_id\":\"5\","
                + "\"user_id\":\"9\",\"username\":\"Дима\",\"timestamp\":\"2026-08-21T10:00:00Z\","
                + "\"text\":\"привет\"}";

        kafkaConsumer.listenChatMessages(message);

        verify(reactiveRepository).insertMessage(5L, 9L, "привет",
                Instant.parse("2026-08-21T10:00:00Z"), "11111111-1111-1111-1111-111111111111");
    }

    /**
     * Записи из бэклога топика, сделанные до появления message_id, обязаны по-прежнему
     * вставляться: их в консьюмере нельзя ни отбросить, ни дополнить своим id
     * (сгенерированный на переигровке был бы каждый раз новым — идемпотентность стала бы
     * фикцией). Ожидание — null доезжает до репозитория как есть.
     */
    @Test
    void legacyPayloadWithoutMessageIdPassesNullToRepository() {
        when(reactiveRepository.insertMessage(any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        String message = "{\"chat_id\":\"5\",\"user_id\":\"9\",\"username\":\"Дима\","
                + "\"timestamp\":\"2026-08-21T10:00:00Z\",\"text\":\"привет\"}";

        kafkaConsumer.listenChatMessages(message);

        verify(reactiveRepository).insertMessage(5L, 9L, "привет",
                Instant.parse("2026-08-21T10:00:00Z"), null);
    }
}
