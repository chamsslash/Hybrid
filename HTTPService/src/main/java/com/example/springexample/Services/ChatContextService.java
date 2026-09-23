package com.example.springexample.Services;


import com.example.springexample.ChatContextMessage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.TimeUnit;


/**
 * Контекст чата для AI-ассистента: окно последних сообщений (`newmessages-<chatId>`)
 * плюс вытесненный из окна хвост (`oldmessages-<chatId>`), оба — Redis-LIST с TTL 30 минут.
 *
 * Единое соглашение о порядке (beads j97): ОБА списка хранятся хронологически, голова —
 * самое старое сообщение, хвост — самое новое. Поэтому запись всегда `rightPush`,
 * вытеснение — `leftPop`, а чтение — `range(0,-1)` без разворотов. Раньше запись шла
 * `leftPush`, а чтение `range(0,-1)`, то есть порядок был закодирован в двух местах
 * по-разному: модель получала диалог от конца к началу и отвечала на первое сообщение
 * чата вместо последнего.
 *
 * Данные, лежащие в Redis в прежнем формате, этим кодом читаются неверно: окно —
 * наизнанку, а `oldmessages-*` как SET вызовет WRONGTYPE. Это сознательный размен:
 * контекст — не ценные данные, живёт 30 минут по TTL и самовосстанавливается по мере
 * переписки. При раскатке ключи `newmessages-*`/`oldmessages-*` можно просто удалить.
 *
 * Порядок ЗАПИСИ при этом не гарантирован, и одного соглашения выше мало (beads u8m).
 * `ChatBoxStompController` пишет контекст как fire-and-forget (`.subscribe()`), да ещё
 * и внутри колбэка gRPC-проверки членства: поток, принявший фрейм, не ждёт Redis, а
 * задержка MessegerParody гуляет. Поэтому записи соседних сообщений гоняются, и на
 * живом стенде четыре сообщения подряд без пауз легли как 3,4,1,2. Ждать Redis на
 * горячем пути нельзя — это и есть причина, по которой запись асинхронная.
 *
 * Значит порядок надо задавать не очерёдностью записи, а данными: каждая реплика несёт
 * серверное время фрейма (`StompFrameTimestampInterceptor`, beads 525), снятое в потоке
 * сессии ДО раздачи фрейма в пул обработчиков, то есть монотонное по порядку прихода.
 * {@link #getFullContext()} сортирует по нему, и гонка записи перестаёт влиять на
 * результат.
 */
public class ChatContextService {
    String oldname;
    String newname;
    ReactiveRedisTemplate<String,String> rredisTemplate;
    ReactiveListOperations<String,String> news;
    // Хвост — тоже LIST, а не SET (j97): SET не хранит порядок и молча проглатывает
    // дубликаты, поэтому два одинаковых «ок» превращались в одно, а из хвоста было
    // не восстановить, что за чем шло.
    ReactiveListOperations<String,String> old;

    public ChatContextService(ReactiveRedisTemplate<String, String> rredisTemplate, String chatId) {
        this.rredisTemplate = rredisTemplate;
        this.oldname = "oldmessages-" + chatId;
        this.newname = "newmessages-" + chatId;
        this.news = rredisTemplate.opsForList();
        this.old = rredisTemplate.opsForList();
    }
    // Была классическая ловушка Reactor: цепочка rightPush().then()...
    // строилась, но не возвращалась (метод возвращал Mono.empty()) — без
    // подписки Redis-запись НИКОГДА не выполнялась (AI-assist всегда видел
    // пустой контекст). Тот же баг был во вложенном if(size>10): цепочка
    // leftPop().flatMap(...) строилась и отбрасывалась без return.
    /**
     * @param timestamp серверное время фрейма (ISO-8601) из
     *                  {@code StompFrameTimestampInterceptor}. По нему, а не по
     *                  очерёдности записи, восстанавливается порядок при чтении
     *                  (beads u8m). {@code null}/пустое — берём текущее время: лучше
     *                  слегка неточная метка, чем реплика, которую нечем упорядочить.
     */
    public Mono<Void> addMessage(String user,String message,String timestamp){
        JsonObject json = new JsonObject();
        json.addProperty("user",user);
        json.addProperty("message",message);
        json.addProperty("timestamp", (timestamp == null || timestamp.isBlank())
                ? Instant.now().toString()
                : timestamp);
        return news.rightPush(newname,json.toString())
                .then(news.size(newname))
                .flatMap(size -> {
                    if (size > 10){
                        return news.leftPop(newname)
                                .flatMap(oldest -> old.rightPush(oldname,oldest))
                                .then();
                    }
                    return Mono.empty();
                })
                // TTL нужен обоим ключам. У окна его не было вовсе: контекст висел в Redis
                // вечно (утечка памяти), и вместе с ним вечно жили записи в старом формате.
                .then(rredisTemplate.expire(newname, Duration.ofSeconds(1800)))
                .then(rredisTemplate.expire(oldname, Duration.ofSeconds(1800)))
                .then();
    }
    /**
     * Контекст чата в хронологическом порядке — от самой старой реплики к самой новой.
     *
     * Разбор JSON живёт здесь, а не у вызывающего (beads u8m): формат хранения —
     * внутреннее дело этого класса, и вызывающему незачём знать имена полей, чтобы
     * получить диалог. Раньше `/AiAssist` парсил его сам.
     *
     * Сортировка по времени, а не доверие порядку в списках: см. javadoc класса —
     * записи гоняются между собой. Порядок в самих списках при этом остаётся значимым и
     * поддерживается (rightPush/leftPop): он задаёт запасной порядок для реплик с
     * одинаковым или отсутствующим временем, потому что сортировка стабильная.
     */
    public Flux<ChatContextMessage> getFullContext() {
        // Хвост идёт перед окном: всё, что вытеснено, старше всего, что в окне осталось.
        // Это же даёт верный порядок записям БЕЗ времени (из прежнего формата) — они
        // получают Instant.EPOCH и сохраняют относительный порядок за счёт стабильности
        // сортировки.
        return old.range(oldname, 0, -1)
                .concatWith(news.range(newname, 0, -1))
                .map(ChatContextService::parse)
                .sort(Comparator.comparing(Stored::at))
                .map(Stored::message);
    }

    /** Реплика вместе со временем, по которому её упорядочивают. */
    private record Stored(Instant at, ChatContextMessage message) {}

    /**
     * Одна запись Redis в разобранном виде.
     *
     * Битая запись не роняет чтение всего контекста: реплика, которую не удалось
     * разобрать, получает EPOCH и пустые поля — один испорченный ключ не должен лишать
     * ассистента остальной переписки.
     */
    private static Stored parse(String raw) {
        try {
            JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
            String user = obj.has("user") ? obj.get("user").getAsString() : "";
            String message = obj.has("message") ? obj.get("message").getAsString() : "";
            // Записи прежнего формата поля нет вовсе — они старше любой новой, и EPOCH
            // ставит их в начало, что и требуется.
            Instant at = Instant.EPOCH;
            if (obj.has("timestamp")) {
                try {
                    at = Instant.parse(obj.get("timestamp").getAsString());
                } catch (Exception malformed) {
                    at = Instant.EPOCH;
                }
            }
            return new Stored(at, new ChatContextMessage(user, message));
        } catch (JsonSyntaxException | IllegalStateException broken) {
            return new Stored(Instant.EPOCH, new ChatContextMessage("", ""));
        }
    }
}
