package com.example.springexample.Services;


import com.google.gson.JsonObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
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
    public Mono<Void> addMessage(String user,String message){
        JsonObject json = new JsonObject();
        json.addProperty("user",user);
        json.addProperty("message",message);
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
    public Flux<String> getFullContext() {
        // Хвост идёт перед окном: всё, что вытеснено, старше всего, что в окне осталось.
        // Простой concat вместо прежнего zip+collectList — оба списка уже хронологичны,
        // собирать их в память и переупорядочивать не нужно.
        return old.range(oldname, 0, -1)
                .concatWith(news.range(newname, 0, -1));
    }
}
