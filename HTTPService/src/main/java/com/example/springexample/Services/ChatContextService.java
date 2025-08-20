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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;


public class ChatContextService {
    String oldname;
    String newname;
    ReactiveRedisTemplate<String,String> rredisTemplate;
    ReactiveListOperations<String,String> news;
    ReactiveSetOperations<String,String> old;

    public ChatContextService(ReactiveRedisTemplate<String, String> rredisTemplate, String chatId) {
        this.rredisTemplate = rredisTemplate;
        this.oldname = "oldmessages-" + chatId;
        this.newname = "newmessages-" + chatId;
        this.news = rredisTemplate.opsForList();
        this.old = rredisTemplate.opsForSet();
    }
    public Mono<Void> addMessage(String user,String message){
        JsonObject json = new JsonObject();
        json.addProperty("user",user);
        json.addProperty("message",message);
        news.leftPush(newname,json.toString()).then(news.size(newname)).flatMap(size->{
            if (size > 10){
                news.rightPop(newname).flatMap(oldest->{
                    old.add(oldname,oldest);
                    return Mono.empty();
                });
            }else return Mono.empty();
            return Mono.empty();
        }).then(rredisTemplate.expire(oldname, Duration.ofSeconds(1800)));
        return Mono.empty();
    }
    public Flux<String> getFullContext() {
        Mono<List<String>> newMessages = news.range(newname, 0, -1).collectList();
        Mono<List<String>> oldMessages = old.members(oldname).collectList();

        return Mono.zip(newMessages, oldMessages)
                .flatMapMany(tuple -> {
                    List<String> combined = new ArrayList<>(tuple.getT1());
                    combined.addAll(tuple.getT2());
                    return Flux.fromIterable(combined);
                });
    }
}
