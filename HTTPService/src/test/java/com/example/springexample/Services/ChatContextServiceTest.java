package com.example.springexample.Services;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ReactiveListOperations;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

/**
 * Порядок и полнота Redis-контекста AI-ассистента (beads j97).
 *
 * До фикса запись и чтение жили по разным соглашениям: `addMessage` клала сообщение
 * в ГОЛОВУ списка (`leftPush`), а `getFullContext` читала `range(0,-1)` — то есть
 * отдавала диалог от самого нового к самому старому, и модель отвечала на первое
 * сообщение чата вместо последнего. Вытесненный хвост при этом уезжал в Redis-SET,
 * который не хранит порядок вовсе и молча склеивает одинаковые реплики («ок», «+»).
 *
 * Ключи в ассертах написаны буквально (`newmessages-3`/`oldmessages-3`): тест стережёт
 * именно тот формат, по которому контекст читает `/AiAssist`.
 */
class ChatContextServiceTest {

    private static final String NEW = "newmessages-3";
    private static final String OLD = "oldmessages-3";

    @SuppressWarnings("unchecked")
    private final ReactiveRedisTemplate<String, String> redis = Mockito.mock(ReactiveRedisTemplate.class);

    // Оба списка (окно и хвост) берутся из одного opsForList(), поэтому и мок один —
    // различаются они только ключом, и ассерты бьют по ключу.
    @SuppressWarnings("unchecked")
    private final ReactiveListOperations<String, String> list = Mockito.mock(ReactiveListOperations.class);

    private static String json(String user, String message) {
        return "{\"user\":\"" + user + "\",\"message\":\"" + message + "\"}";
    }

    private ChatContextService contextOfChat3() {
        Mockito.when(redis.opsForList()).thenReturn(list);
        return new ChatContextService(redis, "3");
    }

    /** Запись прошла успешно, а в окне после неё лежит {@code sizeAfterPush} сообщений. */
    private void writeSucceedsWithWindowSize(long sizeAfterPush) {
        Mockito.when(list.rightPush(Mockito.anyString(), Mockito.anyString())).thenReturn(Mono.just(1L));
        Mockito.when(list.size(NEW)).thenReturn(Mono.just(sizeAfterPush));
        Mockito.when(redis.expire(Mockito.anyString(), Mockito.any())).thenReturn(Mono.just(true));
    }

    @Test
    void addMessageAppendsToTailSoTheListStaysChronological() {
        ChatContextService context = contextOfChat3();
        writeSucceedsWithWindowSize(3L);

        StepVerifier.create(context.addMessage("gyattalert", "привет")).verifyComplete();

        // Хвост списка = самое новое сообщение. `leftPush` здесь означал бы, что index 0 —
        // новейшая реплика, и `range(0,-1)` отдавал бы диалог наизнанку (собственно j97).
        Mockito.verify(list).rightPush(NEW, json("gyattalert", "привет"));
        Mockito.verify(list, Mockito.never()).leftPush(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void overflowMovesTheOldestHeadIntoTheTailList() {
        ChatContextService context = contextOfChat3();
        writeSucceedsWithWindowSize(11L);
        Mockito.when(list.leftPop(NEW)).thenReturn(Mono.just(json("yamam", "самое старое")));

        StepVerifier.create(context.addMessage("gyattalert", "одиннадцатое")).verifyComplete();

        // Вытесняется ГОЛОВА (самое старое), а не хвост, и уезжает в КОНЕЦ oldmessages —
        // так оба списка остаются в одной хронологии и склеиваются простой конкатенацией.
        Mockito.verify(list).leftPop(NEW);
        Mockito.verify(list).rightPush(OLD, json("yamam", "самое старое"));
        // Заодно сторож старой ловушки Reactor: вложенная цепочка вытеснения обязана быть
        // возвращена наружу. Пока её результат отбрасывали без return, она не подписывалась
        // никогда и хвост в Redis не появлялся вовсе.
    }

    @Test
    void windowUnderTheLimitKeepsEverythingInOneList() {
        ChatContextService context = contextOfChat3();
        writeSucceedsWithWindowSize(10L);

        StepVerifier.create(context.addMessage("gyattalert", "десятое")).verifyComplete();

        Mockito.verify(list, Mockito.never()).leftPop(Mockito.anyString());
        Mockito.verify(list, Mockito.never()).rightPush(Mockito.eq(OLD), Mockito.anyString());
    }

    @Test
    void tailKeepsRepeatedMessagesInsteadOfCollapsingThem() {
        ChatContextService context = contextOfChat3();
        writeSucceedsWithWindowSize(11L);
        Mockito.when(list.leftPop(NEW)).thenReturn(Mono.just(json("yamam", "ок")));

        StepVerifier.create(context.addMessage("gyattalert", "раз")).verifyComplete();
        StepVerifier.create(context.addMessage("gyattalert", "два")).verifyComplete();

        // Два одинаковых «ок» обязаны доехать до хвоста обоими. Прежний Redis-SET на этом
        // месте оставлял один: `SADD` тихо проглатывает дубликат, и из контекста исчезала
        // реплика, которая в чате была.
        Mockito.verify(list, Mockito.times(2)).rightPush(OLD, json("yamam", "ок"));
        // Структурный сторож того же решения: набор больше не участвует нигде. Поведением
        // его не поймать — дедупликация видна только на совпадающих строках.
        Mockito.verify(redis, Mockito.never()).opsForSet();
    }

    @Test
    void bothKeysGetTheSameThirtyMinuteTtl() {
        ChatContextService context = contextOfChat3();
        writeSucceedsWithWindowSize(3L);

        StepVerifier.create(context.addMessage("gyattalert", "привет")).verifyComplete();

        // TTL стоит на ОБОИХ ключах: окно без него жило в Redis вечно (утечка памяти),
        // и вместе с ним вечно жили данные в старом, вывернутом формате.
        Mockito.verify(redis).expire(NEW, Duration.ofSeconds(1800));
        Mockito.verify(redis).expire(OLD, Duration.ofSeconds(1800));
    }

    @Test
    void getFullContextReturnsTailThenWindowFromOldestToNewest() {
        ChatContextService context = contextOfChat3();
        Mockito.when(list.range(OLD, 0, -1))
                .thenReturn(Flux.just(json("gyattalert", "привет"), json("yamam", "🙂")));
        Mockito.when(list.range(NEW, 0, -1))
                .thenReturn(Flux.just(json("gyattalert", "рад видеть"), json("yamam", "как настроение?")));

        StepVerifier.create(context.getFullContext())
                .expectNext(json("gyattalert", "привет"))
                .expectNext(json("yamam", "🙂"))
                .expectNext(json("gyattalert", "рад видеть"))
                .expectNext(json("yamam", "как настроение?"))
                .verifyComplete();
        // Вытесненный хвост идёт ПЕРЕД окном: он старше по определению. Прежний порядок
        // (сначала окно, потом хвост, да ещё и внутри окна наизнанку) давал модели диалог,
        // из которого невозможно понять, кто кому отвечал последним.
    }

    @Test
    void getFullContextIsEmptyWhenNothingWasStored() {
        ChatContextService context = contextOfChat3();
        Mockito.when(list.range(OLD, 0, -1)).thenReturn(Flux.empty());
        Mockito.when(list.range(NEW, 0, -1)).thenReturn(Flux.empty());

        StepVerifier.create(context.getFullContext()).verifyComplete();
        // Пустой контекст обязан оставаться пустым Flux, а не зависать: на этом сигнале
        // /AiAssist отвечает «Контекст чата пуст...» вместо похода в Gemini.
    }
}
