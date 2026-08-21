package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import com.example.springexample.MessageEvent;
import com.example.springexample.ShortChatObject;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * JSON API для SPA-шеллов (beads 56).
 * Страницы-шеллы публичны, данные ходят только сюда с Authorization: Bearer.
 * Аутентификация: MvcJwtAuthFilter проверяет подпись access-JWT и берёт принципала
 * из sub (beads 1fs); ingress auth_request -> /jwtcheck остаётся поверх и отвечает
 * за ревокацию — жива ли ещё refresh-сессия по sid.
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final ReactiveGrpcClient reactiveGrpcClient;
    private final ImageStorageService imageStorageService;
    private final ChatMembershipService chatMembershipService;

    /**
     * Прокси-отдача картинок из MinIO (beads 6s0). Ключ может содержать слэши
     * (&lt;targetType&gt;/&lt;targetId&gt;/&lt;uuid&gt;.&lt;ext&gt;), поэтому используется {*key}.
     */
    @GetMapping("/images/{*key}")
    public Callable<ResponseEntity<byte[]>> image(@PathVariable("key") String key) {
        String objectKey = key.startsWith("/") ? key.substring(1) : key;
        return () -> imageStorageService.getObject(objectKey)
                .map(obj -> ResponseEntity.ok()
                        .contentType(obj.contentType() != null
                                ? MediaType.parseMediaType(obj.contentType())
                                : MediaType.APPLICATION_OCTET_STREAM)
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                        .body(obj.data()))
                .onErrorResume(e -> {
                    log.warn("image fetch failed for {}: {}", objectKey, e.getMessage());
                    return Mono.just(ResponseEntity.notFound().build());
                })
                .block();
    }

    @GetMapping("/me")
    public Callable<Map<String, Object>> me(Authentication auth) {
        String userId = auth.getName();
        List<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList();
        return () -> {
            Mono<String> usernameMono = reactiveGrpcClient.reactiveGetUsernameById(userId)
                    .onErrorReturn("");
            Mono<String> imageMono = reactiveGrpcClient.reactiveGetUserImageUrl(Long.valueOf(userId))
                    .onErrorReturn("");
            return Mono.zip(usernameMono, imageMono).map(tuple -> {
                Map<String, Object> me = new HashMap<>();
                me.put("userId", userId);
                me.put("username", tuple.getT1());
                me.put("imageUrl", tuple.getT2());
                me.put("authorities", authorities);
                return me;
            }).block();
        };
    }

    @GetMapping("/chatlist")
    public Callable<List<ShortChatObject>> chatlist(Authentication auth) {
        String userId = auth.getName();
        return () -> {
            DataTransferService.ChatData request = DataTransferService.ChatData.newBuilder()
                    .addUser(DataTransferService.User.newBuilder().setId(userId).build())
                    .build();
            return reactiveGrpcClient.reactiveGetAllChatsById(request)
                    .flatMapMany(list -> Flux.fromIterable(list.getChatdataListList()))
                    .flatMap(chat -> reactiveGrpcClient.reactiveGetNewestMessage(chat)
                            .onErrorResume(err -> {
                                log.warn("chatlist preview failed for chat {}: {}", chat.getChatId(), err.getMessage());
                                return Mono.just(new ShortChatObject());
                            }))
                    .collectList()
                    .block();
        };
    }

    /**
     * Членство проверяется ПЕРВЫМ звеном цепочки, до любого обращения к данным чата
     * (beads 7f7). Раньше Authentication здесь был, но использовался только чтобы
     * подставить в ответ своё имя и аватарку, а chatId брался из query-параметра и уходил
     * в gRPC без единой проверки: любой залогиненный читал всю историю и список участников
     * ЛЮБОГО чата, просто поменяв id в адресной строке. Идентификаторы чатов —
     * последовательные bigint, так что перебор всей системы был тривиален.
     *
     * Проверка встроена в ту же цепочку, что и остальные вызовы, а не сделана отдельным
     * блокирующим вызовом перед ней: метод по-прежнему блокируется ровно один раз, второй
     * .block() в общем пуле уже приводил к таймаутам (beads 8wh).
     *
     * Отказ — 403 без тела. Fail-closed-семантику (ошибка gRPC, таймаут, пустой список,
     * несуществующий чат) целиком держит ChatMembershipService — единственный источник
     * ответа на вопрос о членстве, тот же, что у STOMP-пути (beads g9x).
     */
    @GetMapping("/chat")
    public Callable<ResponseEntity<Map<String, Object>>> chat(Authentication auth,
                                                              @RequestParam("id") long chatId,
                                                              @RequestParam(value = "title", defaultValue = "") String title) {
        String userId = auth.getName();
        return () -> chatMembershipService.isMemberReactive(chatId, userId)
                .flatMap(isMember -> {
                    if (!isMember) {
                        log.warn("GET /api/chat?id={} отклонён: пользователь {} не участник чата", chatId, userId);
                        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).<Map<String, Object>>build());
                    }

                    DataTransferService.ChatData chatData = DataTransferService.ChatData.newBuilder()
                            .setChatId(chatId).setTitle(title).build();

                    Mono<String> usernameMono = reactiveGrpcClient.reactiveGetUsernameById(userId).onErrorReturn("");
                    Mono<String> chatResponseMono = reactiveGrpcClient.reactiveChatServe(chatData);
                    Mono<List<String>> membersMono = reactiveGrpcClient.reactiveGetAllUsernamesByChatId(chatData)
                            .onErrorReturn(List.of());
                    Mono<String> chatImageMono = reactiveGrpcClient.reactiveGetImageUrl(chatId).onErrorReturn("");
                    Mono<String> myImageMono = reactiveGrpcClient.reactiveGetUserImageUrl(Long.valueOf(userId)).onErrorReturn("");

                    return Mono.zip(usernameMono, chatResponseMono, membersMono, chatImageMono, myImageMono)
                            .flatMap(tuple -> {
                                JsonObject chatResp = JsonParser.parseString(tuple.getT2()).getAsJsonObject();
                                Mono<List<MessageEvent>> messagesMono =
                                        "500".equals(chatResp.get("status").getAsString())
                                                ? Mono.just(List.of())
                                                : reactiveGrpcClient.reactiveGetAllMessages(chatData)
                                                    .onErrorReturn(List.of());
                                return messagesMono.map(messages -> {
                                    Map<String, Object> model = new HashMap<>();
                                    model.put("userId", userId);
                                    model.put("username", tuple.getT1());
                                    model.put("chatId", chatId);
                                    model.put("title", title);
                                    model.put("members", tuple.getT3());
                                    model.put("chatImageUrl", tuple.getT4());
                                    model.put("myImageUrl", tuple.getT5());
                                    model.put("messages", messages);
                                    return model;
                                });
                            })
                            .map(ResponseEntity::ok);
                })
                .block();
    }
}
