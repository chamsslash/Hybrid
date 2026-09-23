package com.example.springexample.Services;

import com.example.springexample.ChatContextMessage;
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

    /** Время, которым запись легла бы в Redis. ISO-8601, как его пишет интерцептор. */
    private static final String T1 = "2026-09-23T10:00:01Z";
    private static final String T2 = "2026-09-23T10:00:02Z";
    private static final String T3 = "2026-09-23T10:00:03Z";
    private static final String T4 = "2026-09-23T10:00:04Z";

    private static String json(String user, String message, String timestamp) {
        return "{\"user\":\"" + user + "\",\"message\":\"" + message
                + "\",\"timestamp\":\"" + timestamp + "\"}";
    }

    /** Запись прежнего формата — без поля времени (такие ещё лежат в Redis после раскатки). */
    private static String legacyJson(String user, String message) {
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

        StepVerifier.create(context.addMessage("gyattalert", "привет", T1)).verifyComplete();

        // Хвост списка = самое новое сообщение. `leftPush` здесь означал бы, что index 0 —
        // новейшая реплика, и `range(0,-1)` отдавал бы диалог наизнанку (собственно j97).
        Mockito.verify(list).rightPush(NEW, json("gyattalert", "привет", T1));
        Mockito.verify(list, Mockito.never()).leftPush(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void overflowMovesTheOldestHeadIntoTheTailList() {
        ChatContextService context = contextOfChat3();
        writeSucceedsWithWindowSize(11L);
        Mockito.when(list.leftPop(NEW)).thenReturn(Mono.just(json("yamam", "самое старое", T1)));

        StepVerifier.create(context.addMessage("gyattalert", "одиннадцатое", T1)).verifyComplete();

        // Вытесняется ГОЛОВА (самое старое), а не хвост, и уезжает в КОНЕЦ oldmessages —
        // так оба списка остаются в одной хронологии и склеиваются простой конкатенацией.
        Mockito.verify(list).leftPop(NEW);
        Mockito.verify(list).rightPush(OLD, json("yamam", "самое старое", T1));
        // Заодно сторож старой ловушки Reactor: вложенная цепочка вытеснения обязана быть
        // возвращена наружу. Пока её результат отбрасывали без return, она не подписывалась
        // никогда и хвост в Redis не появлялся вовсе.
    }

    @Test
    void windowUnderTheLimitKeepsEverythingInOneList() {
        ChatContextService context = contextOfChat3();
        writeSucceedsWithWindowSize(10L);

        StepVerifier.create(context.addMessage("gyattalert", "десятое", T1)).verifyComplete();

        Mockito.verify(list, Mockito.never()).leftPop(Mockito.anyString());
        Mockito.verify(list, Mockito.never()).rightPush(Mockito.eq(OLD), Mockito.anyString());
    }

    @Test
    void tailKeepsRepeatedMessagesInsteadOfCollapsingThem() {
        ChatContextService context = contextOfChat3();
        writeSucceedsWithWindowSize(11L);
        Mockito.when(list.leftPop(NEW)).thenReturn(Mono.just(json("yamam", "ок", T1)));

        StepVerifier.create(context.addMessage("gyattalert", "раз", T1)).verifyComplete();
        StepVerifier.create(context.addMessage("gyattalert", "два", T2)).verifyComplete();

        // Два одинаковых «ок» обязаны доехать до хвоста обоими. Прежний Redis-SET на этом
        // месте оставлял один: `SADD` тихо проглатывает дубликат, и из контекста исчезала
        // реплика, которая в чате была.
        Mockito.verify(list, Mockito.times(2)).rightPush(OLD, json("yamam", "ок", T1));
        // Структурный сторож того же решения: набор больше не участвует нигде. Поведением
        // его не поймать — дедупликация видна только на совпадающих строках.
        Mockito.verify(redis, Mockito.never()).opsForSet();
    }

    @Test
    void bothKeysGetTheSameThirtyMinuteTtl() {
        ChatContextService context = contextOfChat3();
        writeSucceedsWithWindowSize(3L);

        StepVerifier.create(context.addMessage("gyattalert", "привет", T1)).verifyComplete();

        // TTL стоит на ОБОИХ ключах: окно без него жило в Redis вечно (утечка памяти),
        // и вместе с ним вечно жили данные в старом, вывернутом формате.
        Mockito.verify(redis).expire(NEW, Duration.ofSeconds(1800));
        Mockito.verify(redis).expire(OLD, Duration.ofSeconds(1800));
    }

    @Test
    void getFullContextReturnsTailThenWindowFromOldestToNewest() {
        ChatContextService context = contextOfChat3();
        Mockito.when(list.range(OLD, 0, -1))
                .thenReturn(Flux.just(json("gyattalert", "привет", T1), json("yamam", "🙂", T2)));
        Mockito.when(list.range(NEW, 0, -1))
                .thenReturn(Flux.just(json("gyattalert", "рад видеть", T3),
                        json("yamam", "как настроение?", T4)));

        StepVerifier.create(context.getFullContext())
                .expectNext(new ChatContextMessage("gyattalert", "привет"))
                .expectNext(new ChatContextMessage("yamam", "🙂"))
                .expectNext(new ChatContextMessage("gyattalert", "рад видеть"))
                .expectNext(new ChatContextMessage("yamam", "как настроение?"))
                .verifyComplete();
        // Вытесненный хвост идёт ПЕРЕД окном: он старше по определению. Прежний порядок
        // (сначала окно, потом хвост, да ещё и внутри окна наизнанку) давал модели диалог,
        // из которого невозможно понять, кто кому отвечал последним.
    }

    @Test
    void outOfOrderWritesAreSortedBackByServerTimestamp() {
        // Ровно то, что поймала живая проверка (beads u8m): четыре сообщения, отправленные
        // подряд без пауз, легли в Redis как 3,4,1,2 — записи асинхронные и гоняются между
        // собой. Читаться они обязаны в порядке отправки.
        ChatContextService context = contextOfChat3();
        Mockito.when(list.range(OLD, 0, -1)).thenReturn(Flux.empty());
        Mockito.when(list.range(NEW, 0, -1)).thenReturn(Flux.just(
                json("nickname", "тоже хорошо, спасибо", T3),
                json("nickname", "во сколько завтра встреча", T4),
                json("nickname", "привет, как дела", T1),
                json("nickname", "нормально, а у тебя", T2)));

        StepVerifier.create(context.getFullContext())
                .expectNext(new ChatContextMessage("nickname", "привет, как дела"))
                .expectNext(new ChatContextMessage("nickname", "нормально, а у тебя"))
                .expectNext(new ChatContextMessage("nickname", "тоже хорошо, спасибо"))
                .expectNext(new ChatContextMessage("nickname", "во сколько завтра встреча"))
                .verifyComplete();
        // Зачем сортировка, а не доверие порядку списка: порядок записи задаёт гонка, а
        // порядок разговора — серверное время фрейма, снятое до раздачи в пул (beads 525).
    }

    @Test
    void recordsWithoutTimestampKeepTheirRelativeOrderAndComeFirst() {
        // Записи прежнего формата (без поля времени) живут в Redis до истечения TTL после
        // раскатки. Они старше любой новой, и терять их порядок нельзя.
        ChatContextService context = contextOfChat3();
        Mockito.when(list.range(OLD, 0, -1)).thenReturn(Flux.empty());
        Mockito.when(list.range(NEW, 0, -1)).thenReturn(Flux.just(
                legacyJson("nickname", "старое первое"),
                legacyJson("nickname", "старое второе"),
                json("nickname", "новое", T1)));

        StepVerifier.create(context.getFullContext())
                .expectNext(new ChatContextMessage("nickname", "старое первое"))
                .expectNext(new ChatContextMessage("nickname", "старое второе"))
                .expectNext(new ChatContextMessage("nickname", "новое"))
                .verifyComplete();
        // Им ставится EPOCH, поэтому они идут первыми, а между собой сохраняют порядок
        // списка — сортировка стабильная, и запасной порядок остаётся значимым.
    }

    @Test
    void brokenRecordDoesNotKillTheWholeContext() {
        ChatContextService context = contextOfChat3();
        Mockito.when(list.range(OLD, 0, -1)).thenReturn(Flux.empty());
        Mockito.when(list.range(NEW, 0, -1)).thenReturn(Flux.just(
                "не json вовсе",
                json("nickname", "нормальная реплика", T2)));

        StepVerifier.create(context.getFullContext())
                .expectNext(new ChatContextMessage("", ""))
                .expectNext(new ChatContextMessage("nickname", "нормальная реплика"))
                .verifyComplete();
        // Один испорченный ключ не должен лишать ассистента всей остальной переписки:
        // раньше JsonSyntaxException на разборе ронял чтение контекста целиком, и
        // /AiAssist отвечал «не удалось сгенерировать».
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
