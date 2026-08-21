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
import java.util.regex.Pattern;

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
    private final ChatMembershipService chatMembershipService;

    /** Префикс ключа для аватарки пользователя: userimage/&lt;userId&gt;/&lt;uuid&gt;.&lt;ext&gt;. */
    private static final String USER_IMAGE_PREFIX = "userimage";
    /** Префикс ключа для картинки чата: chatimage/&lt;chatId&gt;/&lt;uuid&gt;.&lt;ext&gt;. */
    private static final String CHAT_IMAGE_PREFIX = "chatimage";
    /** Идентификатор в ключе — только десятичные цифры, как их пишут производители ключей. */
    private static final Pattern TARGET_ID = Pattern.compile("\\d+");

    /**
     * Прокси-отдача картинок из MinIO (beads 6s0). Ключ может содержать слэши
     * (&lt;targetType&gt;/&lt;targetId&gt;/&lt;uuid&gt;.&lt;ext&gt;), поэтому используется {*key}.
     *
     * Владение объектом (beads e1o). Раньше Authentication здесь не было вовсе: ключ
     * приходил от клиента и уходил в MinIO без единой проверки, поэтому любой залогиненный
     * скачивал ЛЮБОЙ объект бакета. Подтверждено живьём на стенде kind: аккаунт sunny
     * (id=4), состоящий только в чате 3, забрал аватарки пользователей 10, 11 и 12,
     * которых в текущей БД не существует вовсе. Перебирать ключи даже не требовалось —
     * они сами приезжают по глобальным каналам картинок в STOMP (смежный тикет).
     *
     * Правило владения выводится из префикса ключа — другого признака принадлежности у нас
     * нет, ключи строятся только в двух местах (WEBFLUX_Service.Upload_image для
     * userimage/chatimage и CustomOAuth2UserService.Upload_image для userimage при
     * Google-OAuth), и оба пишут ровно &lt;targetType&gt;/&lt;targetId&gt;/&lt;uuid&gt;.&lt;ext&gt;:
     *
     *  - userimage/... — отдаётся любому аутентифицированному. Это осознанное решение,
     *    а не недосмотр: аватарки участников видны и в списке чатов, и в списке участников
     *    чата, а чтобы ответить «есть ли у нас общий чат», пришлось бы на КАЖДЫЙ запрос
     *    картинки обходить весь граф чатов обоих пользователей. Утечка здесь — факт
     *    существования аватарки, а не содержимое переписки.
     *  - chatimage/&lt;chatId&gt;/... — только участникам этого чата. Это и есть содержимое
     *    переписки, ради которого заведён тикет.
     *  - всё остальное — отказ. Fail-closed: неизвестный префикс, кривой ключ, попытка
     *    выйти вверх через ".." или лишний уровень вложенности не разбираются «как-нибудь»,
     *    а отвергаются до обращения к MinIO.
     *
     * Отказ — 403 без тела, как у /api/chat (beads 7f7) и /AiAssist (beads dz5).
     *
     * Проверка членства встроена в ту же цепочку, что и чтение объекта: метод по-прежнему
     * блокируется ровно один раз, второй .block() в общем пуле уже приводил к таймаутам
     * (beads 8wh). Fail-closed-семантику проверки (ошибка gRPC, таймаут, пустой список
     * участников, несуществующий чат) целиком держит ChatMembershipService — единственный
     * источник ответа о членстве, тот же, что у HTTP- и STOMP-путей (beads g9x).
     */
    @GetMapping("/images/{*key}")
    public Callable<ResponseEntity<byte[]>> image(Authentication auth, @PathVariable("key") String key) {
        // Страховка на случай, если запрос дойдёт сюда мимо MvcJwtAuthFilter: отказываем
        // сами, а не падаем с NPE на auth.getName().
        String userId = auth != null ? auth.getName() : null;
        String objectKey = canonicalObjectKey(key);
        return () -> authorizeObjectAccess(objectKey, userId)
                .flatMap(allowed -> {
                    if (!allowed) {
                        log.warn("GET /api/images/{} отклонён для пользователя {}", key, userId);
                        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).<byte[]>build());
                    }
                    return imageStorageService.getObject(objectKey)
                            .map(obj -> ResponseEntity.ok()
                                    .contentType(obj.contentType() != null
                                            ? MediaType.parseMediaType(obj.contentType())
                                            : MediaType.APPLICATION_OCTET_STREAM)
                                    .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                                    .body(obj.data()))
                            .onErrorResume(e -> {
                                log.warn("image fetch failed for {}: {}", objectKey, e.getMessage());
                                return Mono.just(ResponseEntity.notFound().<byte[]>build());
                            });
                })
                .block();
    }

    /**
     * Приводит путь из URL к ключу MinIO и отвергает всё, что не является ключом,
     * который мы сами могли записать. Возвращает null, если разобрать не удалось —
     * вызывающий трактует null как отказ (fail-closed), а не как «проверить нечего».
     *
     * Требуется ровно три непустых сегмента: оба производителя ключей пишут
     * &lt;targetType&gt;/&lt;targetId&gt;/&lt;uuid&gt;.&lt;ext&gt;, а UUID слэшей не содержит.
     * Любая другая глубина — либо мусор, либо попытка адресовать соседний префикс,
     * поэтому строгое «ровно три» дешевле и безопаснее, чем «не меньше трёх».
     *
     * Сегменты "." и ".." отвергаются: Spring декодирует %2e%2e ещё до маршрутизации,
     * так что обход каталога пришёл бы сюда уже в открытом виде. Обратный слэш
     * запрещён отдельно — в ключах MinIO его нет, а в качестве разделителя его
     * трактуют некоторые клиенты.
     */
    private static String canonicalObjectKey(String rawKey) {
        if (rawKey == null) {
            return null;
        }
        String key = rawKey;
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        if (key.isEmpty() || key.indexOf('\\') >= 0) {
            return null;
        }
        String[] segments = key.split("/", -1);
        if (segments.length != 3) {
            return null;
        }
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return null;
            }
        }
        return key;
    }

    /**
     * Отвечает, вправе ли пользователь читать объект. Для аватарок ответ известен сразу
     * и gRPC не тревожится вовсе, для картинок чата вопрос уходит в ChatMembershipService.
     */
    private Mono<Boolean> authorizeObjectAccess(String objectKey, String userId) {
        if (objectKey == null || userId == null) {
            return Mono.just(false);
        }
        String[] segments = objectKey.split("/", -1);
        String prefix = segments[0];
        String targetId = segments[1];
        if (!TARGET_ID.matcher(targetId).matches()) {
            return Mono.just(false);
        }

        if (USER_IMAGE_PREFIX.equals(prefix)) {
            return Mono.just(true);
        }
        if (CHAT_IMAGE_PREFIX.equals(prefix)) {
            long chatId;
            try {
                chatId = Long.parseLong(targetId);
            } catch (NumberFormatException tooLongForLong) {
                // "\d+" пропускает числа, не помещающиеся в long; без catch это был бы 500.
                return Mono.just(false);
            }
            // Членство спрашивается про chatId, а объект читается по сырому сегменту ключа —
            // они обязаны означать одно и то же. "003" проходит "\d+" и авторизовался бы как
            // чат 3, поэтому неканоническая запись id отвергается (тот же сценарий 007, что
            // и в beads g9x/dz5, но здесь он ещё и расщепил бы объект на два разных ключа).
            if (!String.valueOf(chatId).equals(targetId)) {
                return Mono.just(false);
            }
            return chatMembershipService.isMemberReactive(chatId, userId);
        }
        return Mono.just(false);
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
