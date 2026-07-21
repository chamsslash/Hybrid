package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import com.example.springexample.MessageEvent;
import com.example.springexample.ShortChatObject;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
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

/**
 * JSON API для SPA-шеллов (beads 56).
 * Аутентификация: ingress auth_request -> X-User-ID/X-Authorities -> MvcJwtAuthFilter.
 * Страницы-шеллы публичны, данные ходят только сюда с Authorization: Bearer.
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final ReactiveGrpcClient reactiveGrpcClient;
    private final ImageStorageService imageStorageService;

    /**
     * Прокси-отдача картинок из MinIO (beads 6s0). Ключ может содержать слэши
     * (&lt;targetType&gt;/&lt;targetId&gt;/&lt;uuid&gt;.&lt;ext&gt;), поэтому используется {*key}.
     */
    @GetMapping("/images/{*key}")
    public Mono<ResponseEntity<byte[]>> image(@PathVariable("key") String key) {
        String objectKey = key.startsWith("/") ? key.substring(1) : key;
        return imageStorageService.getObject(objectKey)
                .map(obj -> ResponseEntity.ok()
                        .contentType(obj.contentType() != null
                                ? MediaType.parseMediaType(obj.contentType())
                                : MediaType.APPLICATION_OCTET_STREAM)
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                        .body(obj.data()))
                .onErrorResume(e -> {
                    log.warn("image fetch failed for {}: {}", objectKey, e.getMessage());
                    return Mono.just(ResponseEntity.notFound().build());
                });
    }

    @GetMapping("/me")
    public Mono<Map<String, Object>> me(Authentication auth) {
        String userId = auth.getName();
        Mono<String> usernameMono = reactiveGrpcClient.reactiveGetUsernameById(userId)
                .onErrorReturn("");
        Mono<String> imageMono = reactiveGrpcClient.reactiveGetUserImageUrl(Long.valueOf(userId))
                .onErrorReturn("");
        return Mono.zip(usernameMono, imageMono).map(tuple -> {
            Map<String, Object> me = new HashMap<>();
            me.put("userId", userId);
            me.put("username", tuple.getT1());
            me.put("imageUrl", tuple.getT2());
            me.put("authorities", auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority).toList());
            return me;
        });
    }

    @GetMapping("/chatlist")
    public Mono<List<ShortChatObject>> chatlist(Authentication auth) {
        String userId = auth.getName();
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
                .collectList();
    }

    @GetMapping("/chat")
    public Mono<Map<String, Object>> chat(Authentication auth,
                                          @RequestParam("id") long chatId,
                                          @RequestParam(value = "title", defaultValue = "") String title) {
        String userId = auth.getName();
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
                });
    }
}
