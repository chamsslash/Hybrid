package com.example.springexample.Services;
import com.example.grpc.DataTransferService;
import com.example.springexample.*;
import com.example.springexample.Utils.*;
import com.google.gson.*;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.*;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.ISpringWebFluxTemplateEngine;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import reactor.core.scheduler.Schedulers;
import java.io.IOException;
import java.net.URI;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Controller
public class WEBFLUX_Service {
    @Autowired
    ReactiveRedisTemplate<String,String> rredisTemplate;
    @Autowired
    ReactiveGrpcClient reactiveGrpcClient;
    @Autowired
    private KafkaProducer kafkaProducer;
    @Autowired
    private ImageStorageService imageStorageService;
    @Autowired
   private MyPasswordEncoder passwordEncoder;
    @Autowired
    ParsingDataService dataParser;
    @Autowired
    GeminiService geminiService;
    @Autowired
    ChatMembershipService chatMembershipService;
    @Autowired
    AuthGrpc authGrpc;
    @Autowired
    TokensResolver tokensResolver;
    private final Gson gson= new Gson();
    @Autowired
    private MvcJwtAuthFilter mvcJwtAuthFilter;

    private boolean isNullOrEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
    private record RegistrationData(String username, String password, FilePart imagePart) {}
    public Mono<ServerResponse> registerHandle(ServerRequest request) {
        // Извлекаем все данные один раз
        String meta =request.headers().firstHeader("X-Client-Meta");
        String uuid = request.headers().firstHeader("X-SecureUUID");
        String fpId = request.headers().firstHeader("X-Fingerprint");
        AtomicReference<FpSimilarityScore.ClientMeta> newMeta = new AtomicReference<>();
        return request.multipartData()
                .flatMap(parts -> {
                    FormFieldPart usernamePart = (FormFieldPart) parts.getFirst("username");
                    FormFieldPart passwordPart = (FormFieldPart) parts.getFirst("password");
                    FormFieldPart fpCompsPart =  (FormFieldPart) parts.getFirst("FpComponents");
                    FilePart imagePart = (FilePart) parts.getFirst("userimage");

                    String resultJson = null;
                    JsonObject jsonObject = gson.fromJson(meta, JsonObject.class);
                    String ptr=  new ReverseDnsResolver().getPTR(jsonObject.get("ip").getAsString());
                    try {
                        resultJson = dataParser.JsonStreamingParsing(fpCompsPart.value(),new FpSimilarityScore());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    jsonObject.addProperty("ptr",ptr);
                    jsonObject.addProperty("secureUUID",uuid);
                    jsonObject.addProperty("visitorId", fpId);
                    jsonObject.addProperty("components",resultJson);
                    newMeta.set(gson.fromJson(jsonObject, FpSimilarityScore.ClientMeta.class));

                    if (usernamePart == null || passwordPart == null ||  imagePart == null) {
                        return Mono.error(new IllegalArgumentException("Имя пользователя, пароль, фингерпринт и изображение обязательны."));
                    }
                    return Mono.just(new RegistrationData(usernamePart.value(), passwordPart.value(),imagePart));
                })
                .flatMap(regData -> {
                    return Mono.fromCallable(() -> {

                                DataTransferService.UserDataRequest grpcRequest = DataTransferService.UserDataRequest.newBuilder()
                                        .setUsername(regData.username())
                                        .setPassword(regData.password())
                                        .build();
                                return authGrpc.authRegister(grpcRequest);
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(authResponse -> Upload_image(regData.imagePart(), authResponse.getSub(), "userimage")
                                    .then(Mono.just(authResponse)))
                            .flatMap(authResponse -> Mono.fromCallable(() -> {
                                try {
                                    return tokensResolver.genPairOfToken(
                                            DataTransferService.Sub_Role.newBuilder()
                                                    .setRole(authResponse.getRole())
                                                    .setSub(authResponse.getSub())
                                                    .build(),
                                            newMeta.get()
                                    );
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            }));
                })
                .flatMap(this::ResultSet)
                .switchIfEmpty(ServerResponse.badRequest().bodyValue("Отсутствуют данные формы."))
                .onErrorResume(IllegalArgumentException.class, e ->
                        ServerResponse.badRequest().bodyValue(e.getMessage()))

                .onErrorResume(AuthResponseException.class, e -> {
                    HttpStatus code = "666".equals(e.getStatus())
                            ? HttpStatus.CONFLICT
                            : HttpStatus.BAD_REQUEST;
                    log.warn("Регистрация отклонена сервисом: status={}, message={}", e.getStatus(), e.getMessage());
                    return ServerResponse.status(code).bodyValue(e.getMessage());
                }).onErrorResume(Exception.class, e -> {
                    log.error("Непредвиденная ошибка при регистрации", e);
                    return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .bodyValue("Ошибка на сервере. Пожалуйста, попробуйте снова.");
                });
    }

    /**
     * Исправленный хендлер входа.
     */
    public Mono<ServerResponse> loginHandle(ServerRequest request) {
        String SecureUUID= request.headers().firstHeader("X-SecureUUID");
        String FpId =request.headers().firstHeader("X-Fingerprint");
        String clientmeta =request.headers().firstHeader("X-Client-Meta");
        JsonObject jsonObject = gson.fromJson(clientmeta, JsonObject.class);

        return request.formData()
                .flatMap(formData -> {
                    String username = formData.getFirst("username");
                    String password = formData.getFirst("password");
                    String fpcomps = formData.getFirst("FpComponents");
                    FpSimilarityScore.ClientMeta newMeta;
                    try {
                        String ptr=  new ReverseDnsResolver().getPTR(jsonObject.get("ip").getAsString());
                        String resultJson = dataParser.JsonStreamingParsing(fpcomps,new FpSimilarityScore());
                        jsonObject.addProperty("ptr",ptr);
                        jsonObject.addProperty("secureUUID",SecureUUID);
                        jsonObject.addProperty("visitorId", FpId);
                        jsonObject.addProperty("components",resultJson);
                        newMeta = gson.fromJson(jsonObject, FpSimilarityScore.ClientMeta.class);
                    } catch (Exception e) {
                        log.warn("Json streaming error");
                        throw new RuntimeException(e);
                    }
                    if (username == null || password == null || FpId == null || SecureUUID == null) {
                        return Mono.error(new IllegalArgumentException("Имя пользователя, пароль и фингерпринт обязательны."));
                    }

                    return Mono.fromCallable(() -> {
                                DataTransferService.UserDataRequest grpcRequest = DataTransferService.UserDataRequest.newBuilder()
                                        .setUsername(username).setPassword(password).build();
                                return authGrpc.authlogin(grpcRequest);
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(authResponse -> {
                                if (!"200".equals(authResponse.getStatus())) {
                                    // Для неуспешного входа лучше вернуть ошибку, чтобы клиент ее обработал,
                                    // а не делать редирект на стороне сервера.
                                    return Mono.error(new SecurityException("Неверные учетные данные"));
                                }

                                try {
                                    MvcJwtAuthFilter.jwt_refresh_auths tokens = tokensResolver.genPairOfToken(
                                            DataTransferService.Sub_Role.newBuilder()
                                                    .setRole(authResponse.getRole())
                                                    .setSub(authResponse.getSub())
                                                    .build(),
                                            newMeta);

                                    return ResultSet(tokens);
                                } catch (IOException e) {
                                    return Mono.error(new RuntimeException("Ошибка при создании токенов", e));
                                } catch (NoSuchAlgorithmException e) {
                                    throw new RuntimeException(e);
                                } catch (InvalidKeySpecException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                })
                .onErrorResume(IllegalArgumentException.class, e ->
                        ServerResponse.badRequest().bodyValue(e.getMessage()))
                .onErrorResume(SecurityException.class, e ->
                        ServerResponse.status(HttpStatus.UNAUTHORIZED).bodyValue("Неверное имя пользователя или пароль."))
                .onErrorResume(AuthResponseException.class, e -> {
                    log.warn("Вход отклонён сервисом: status={}, message={}", e.getStatus(), e.getMessage());
                    return ServerResponse.status(HttpStatus.UNAUTHORIZED).bodyValue(e.getMessage());
                })
                .onErrorResume(Exception.class, e -> {
                    log.error("Непредвиденная ошибка при входе", e);
                    return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).bodyValue("Внутренняя ошибка сервера.");
                });
    }


    public Mono<ServerResponse> ResultSet( MvcJwtAuthFilter.jwt_refresh_auths tokens) {
        ResponseCookie refreshCookie = ResponseCookie.from("refresh", tokens.refresh())
                .httpOnly(true)
//                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ofDays(7))
                .build();
        Map<String, Object> responseBody = Map.of(
                "redirectUri", "/reactive/chatlist",
                "accessToken", tokens.jwt(),
                "tokenType", "Bearer"
        );

        return ServerResponse.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .bodyValue(responseBody);
    }


    public Mono<ServerResponse> handleCreateChat(ServerRequest request) {
            return request.multipartData()
                    .flatMap(parts -> {
                        List<Part> usernamesParts = parts.get("userlistname");
                        FormFieldPart titlePart = (FormFieldPart) parts.getFirst("title");
                        FilePart file = (FilePart) parts.getFirst("file");

                        if (usernamesParts.isEmpty() || titlePart == null ) {
                            return Mono.error(new IllegalArgumentException("Missing required form parts: userlistname, title"));
                        }

                        String chatTitle = titlePart.value();
                        List<String> usernames = usernamesParts.stream()
                                .map(part -> ((FormFieldPart) part).value())
                                .collect(Collectors.toList());
                        var repeated_unames = DataTransferService.RepeatedUsernames
                                .newBuilder().addAllNames(usernames).build();

                        return authGrpc.GetUsersByUnames(repeated_unames)
                                .flatMap(ids -> {
                                    List<DataTransferService.User> userList = ids.stream()
                                            .map(id -> DataTransferService.User.newBuilder().setId(String.valueOf(id)).build())
                                            .collect(Collectors.toList());

                                    return ReactiveSecurityContextHolder.getContext()
                                            .map(ctx -> (String) ctx.getAuthentication().getPrincipal())

                                            .flatMap(authorId -> {
                                                // Картинка чата опциональна (во фронте у поля file нет required).
                                                // Без файла НЕ помечаем "pending" (иначе список чатов вечно крутит
                                                // спиннер и Upload_image падает с NPE на file.content()) — пустой
                                                // imageUrl даёт дефолтную аватарку. beads 2q5.
                                                var chatdata = DataTransferService.ChatData.newBuilder()
                                                        .setAuthorId(DataTransferService.User.newBuilder().setId(authorId).build())
                                                        .setTitle(chatTitle)
                                                        .addAllUser(userList)
                                                        .setImageUrl(file != null ? "pending" : "")
                                                        .build();

                                                return reactiveGrpcClient.reactiveChatServe(chatdata)
                                                        .flatMap(resp -> {
                                                            JsonObject jsonObj = JsonParser.parseString(resp).getAsJsonObject();
                                                            if (file != null && "pending".equals(jsonObj.get("image_id").getAsString())) {
                                                                return Upload_image(file, jsonObj.get("id").getAsString(), "chatimage")
                                                                        .thenReturn(jsonObj);
                                                            } else {
                                                                return Mono.just(jsonObj);
                                                            }
                                                        }).onErrorResume(err -> {
                                                            log.error("chatserving failed with", err);

                                                            return Mono.<JsonObject>empty();
                                                        });
                                            });
                                });
                    })
                    .flatMap(jsonObj -> {
                        String status = jsonObj.get("status").getAsString();
                        if ("666".equals(status)) {
                            return ServerResponse.ok()
                                    .contentType(MediaType.TEXT_PLAIN)
                                    .bodyValue("Chat already exists");
                        } else if ("500".equals(status)) {
                            return ServerResponse.status(HttpStatus.CONFLICT)
                                    .contentType(MediaType.TEXT_PLAIN)
                                    .bodyValue(jsonObj.get("message").getAsString());
                        } else {
                            return createChatSuccess(jsonObj);
                        }
                    })
                    // УЛУЧШЕНИЕ: Теперь мы точно знаем, что empty() возникает из-за ошибки gRPC
                    .switchIfEmpty(ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .bodyValue("Chat creation failed due to an error in the backend service."))
                    .onErrorResume(IllegalArgumentException.class, e ->
                            ServerResponse.badRequest().bodyValue(e.getMessage()))
                    .onErrorResume(e -> {
                        log.error("Unexpected error in handleCreateChat", e);
                        return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .contentType(MediaType.TEXT_PLAIN)
                                .bodyValue("Unexpected error: " + e.getMessage());
                    });
        }

    /**
     * Success-ветка создания чата (beads SPA): вместо 303-редиректа на /reactive/chatlist
     * возвращаем 200 JSON {chatId, title}, чтобы клиентская вью навигировала без перезагрузки.
     */
    Mono<ServerResponse> createChatSuccess(JsonObject jsonObj) {
        long chatId = jsonObj.get("id").getAsLong();
        Map<String, Object> body = new HashMap<>();
        body.put("chatId", chatId);
        if (jsonObj.has("title")) {
            body.put("title", jsonObj.get("title").getAsString());
        }
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body);
    }



    /**
     * SPA-шелл списка чатов (beads 57): страница публична, данные подтягивает JS
     * через /api/chatlist с Authorization-заголовком.
     */
    public Mono<ServerResponse> getChatList(ServerRequest request, ISpringWebFluxTemplateEngine templateEngine) {
        Map<String, Object> model = new HashMap<>();
        model.put("pathPrefix", "/reactive");
        return ParseWithThymeLeaf(model, "app", templateEngine)
                .flatMap(htmlContent -> ServerResponse.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .bodyValue(htmlContent))
                .onErrorResume(e -> {
                    log.error("chats_list shell render failed", e);
                    return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .contentType(MediaType.TEXT_PLAIN)
                            .bodyValue("Произошла внутренняя ошибка: " + e.getMessage());
                });
    }

    /**
     * SPA-шелл страницы чата (beads 57): страница публична, id/title JS читает
     * из query-параметров и грузит данные через /api/chat с Authorization.
     */
    public Mono<ServerResponse> renderChatPage(ServerRequest request, ISpringWebFluxTemplateEngine templateEngine) {
        Map<String, Object> model = new HashMap<>();
        return ParseWithThymeLeaf(model, "app", templateEngine)
                .flatMap(htmlContent -> ServerResponse.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .bodyValue(htmlContent))
                .onErrorResume(e -> {
                    log.error("chat shell render failed", e);
                    return ServerResponse.status(500).contentType(MediaType.TEXT_PLAIN)
                            .bodyValue("Internal Server Error: " + e.getMessage());
                });
    }

    /**
     * SPA-шелл страницы создания чата: отдаёт единый app-shell, клиентская
     * вью рисует форму и шлёт POST /reactive/createchat.
     */
    public Mono<ServerResponse> renderCreateChatPage(ServerRequest request, ISpringWebFluxTemplateEngine templateEngine) {
        Map<String, Object> model = new HashMap<>();
        return ParseWithThymeLeaf(model, "app", templateEngine)
                .flatMap(htmlContent -> ServerResponse.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .bodyValue(htmlContent))
                .onErrorResume(e -> {
                    log.error("createchat shell render failed", e);
                    return ServerResponse.status(500).contentType(MediaType.TEXT_PLAIN)
                            .bodyValue("Internal Server Error: " + e.getMessage());
                });
    }



    public Mono<String> ParseWithThymeLeaf(Map<String,Object> model, String tmpl_name,  ISpringWebFluxTemplateEngine templateEngine){
        final Context thymeleafContext = new Context();
        String nonceId;
        try {
            nonceId  = Base64.getEncoder().encodeToString(
                    SecureRandom.getInstanceStrong().generateSeed(16));

        }catch (NoSuchAlgorithmException noSuchAlgorithmException){
            log.warn("no such alg for nonce");
            nonceId= null;
        }
        if (nonceId!=null){
            model.put("nonce",nonceId);

        }
        thymeleafContext.setVariables(model);
        return  Mono.defer(()->Mono.just( templateEngine.process(tmpl_name, thymeleafContext)));

    }
    // Возвращаем Mono (не Callable): вызов Gemini долгий (~секунды), реактивная
    // модель не держит поток и не упирается в таймаут async-сервлета.
    // @ResponseBody ОБЯЗАТЕЛЕН: класс — @Controller (не @RestController), без него
    // Spring MVC трактует Mono<String>/String как ИМЯ ВЬЮХИ для рендера шаблоном —
    // отсюда "class path resource [templates/<текст ответа>.html] cannot be opened"
    // при любом реальном тексте ответа (был баг ДО фикса 6i5, не связан с ним).
    //
    // Тип ответа — Mono<ResponseEntity<String>>, а не Mono<String> (beads dz5): отказ в
    // доступе обязан быть настоящим статусом 403. Прежний хендлер на любой проблеме
    // отдавал 200 с текстом в теле, и авторизационный отказ, оформленный так же, клиент
    // не отличил бы от ответа ассистента. Фронт (chat.view.js#requestAIResponse) от этого
    // не ломается: axios-интерцептор перехватывает только 401 (обновление токена),
    // а 403 уходит в .catch и показывается тостом об ошибке.
    @PostMapping(path = "/AiAssist")
    @ResponseBody
    public Mono<ResponseEntity<String>> aiAssistHandler(Principal principal,
                                            @RequestPart("TargetUsername") String targetUsername,
                                            @RequestPart("chat_id") String chatId) {
        // Principal раньше в сигнатуре ОТСУТСТВОВАЛ — хендлер физически не знал, кто его
        // вызвал, и читал Redis-контекст любого чата по chat_id из тела (beads dz5).
        if (principal == null) {
            log.warn("AiAssist: запрос без принципала — отказ");
            return Mono.just(aiAssistForbidden());
        }
        // Строгая валидация формата и канонизация — как в ChatBoxStompController (beads g9x):
        // голый Long.parseLong принимает "+7"/"-5", а "003" дал бы отдельный Redis-ключ
        // newmessages-003 вместо newmessages-3. "\\d+" пропускает и число длиннее long,
        // поэтому parseLong обёрнут в try: исключение здесь превратилось бы в 500.
        // Отказ — ДО чтения Redis и до gRPC-вызова.
        if (chatId == null || !chatId.matches("\\d+")) {
            log.warn("AiAssist: chatId {} не в каноническом числовом формате — отказ", chatId);
            return Mono.just(aiAssistForbidden());
        }
        final long chat;
        try {
            chat = Long.parseLong(chatId);
        } catch (NumberFormatException ex) {
            log.warn("AiAssist: chatId {} не помещается в long — отказ", chatId);
            return Mono.just(aiAssistForbidden());
        }
        final String canonicalChatId = String.valueOf(chat);
        final String userId = principal.getName();

        // Проверка членства встроена в реактивную цепочку, а не через block(): хендлер
        // выполняется в общем пуле, и блокирующее ожидание gRPC там уже приводило к
        // деградации (beads 8wh). Fail-closed: ошибка gRPC, таймаут и пустой список
        // участников одинаково означают «не участник» — отказываем, а не делаем вид,
        // что проверка прошла.
        return chatMembershipService.members(chat)
                .timeout(chatMembershipService.membershipTimeout())
                .map(members -> members.stream()
                        .anyMatch(u -> String.valueOf(u.getId()).equals(userId)))
                .onErrorResume(err -> {
                    log.warn("AiAssist: проверка членства в чате {} не удалась — отказ (fail-closed)",
                            canonicalChatId, err);
                    return Mono.just(false);
                })
                .defaultIfEmpty(false)
                .flatMap(isMember -> {
                    if (!isMember) {
                        log.warn("AiAssist по чату {} отклонён: пользователь {} не найден среди участников",
                                canonicalChatId, userId);
                        return Mono.just(aiAssistForbidden());
                    }
                    return generateAssistantAnswer(targetUsername, canonicalChatId);
                });
    }

    private ResponseEntity<String> aiAssistForbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Нет доступа к этому чату.");
    }

    /**
     * Сам ассистент: вызывается только после подтверждённого членства (beads dz5).
     * Все ошибки генерации по-прежнему отдаются как 200 с текстом — это ответ
     * ассистента «не получилось», а не отказ в доступе, и фронт показывает его
     * пользователю как подсказку.
     */
    private Mono<ResponseEntity<String>> generateAssistantAnswer(String targetUsername, String canonicalChatId) {
        return Mono.defer(() -> {
                    ChatContextService contextService = new ChatContextService(rredisTemplate, canonicalChatId);

                    return contextService.getFullContext()
                            .switchIfEmpty(Mono.error(new IllegalStateException("Контекст чата пуст...")))
                            // Каждый элемент — валидный JSON-объект ({"user":...,"message":...}),
                            // но joining() без разделителей/скобок склеивал их в невалидный JSON
                            // (несколько top-level объектов подряд) -> JsonSyntaxException при
                            // ЛЮБОМ непустом контексте (baг вскрылся только после фикса
                            // ChatContextService.addMessage — раньше контекст был всегда пуст).
                            .collect(Collectors.joining(",", "[", "]"))
                            .publishOn(Schedulers.boundedElastic())
                            .map(jsonString -> {
                                JsonArray jsonArray = JsonParser.parseString(jsonString).getAsJsonArray();
                                Map<String, List<String>> nameMessagesMap = new HashMap<>();

                                for (JsonElement element : jsonArray) {
                                    JsonObject obj = element.getAsJsonObject();
                                    String user = obj.get("user").getAsString();
                                    String message = obj.get("message").getAsString();
                                    nameMessagesMap.computeIfAbsent(user, k -> new ArrayList<>()).add(message);
                                }

                                return nameMessagesMap;
                            })
                            .map(nameMessagesMap -> {
                                String promptTemplate = "Ты — AI-ассистент в чате. Помоги составить дружелюбный ответ пользователю с ником %s на его сообщение в контексте последних сообщений других участников. Обязательно упоминай %s, не отвечай самому себе, поддерживай беседу, тон вежливый и корректный, не придумывай новых участников, соблюдай неформальный стиль.";
                                String promptWithNick = String.format(promptTemplate, targetUsername, "@" + targetUsername);
                                return geminiService.BuildJsonPrompt(promptWithNick, nameMessagesMap);
                            })
                            .flatMap(geminiService::GetAssistantAnswer);
                })
                .map(ResponseEntity::ok)
                .onErrorResume(IllegalStateException.class, ex -> {
                    log.warn("Не удалось сгенерировать ответ: {}", ex.getMessage());
                    return Mono.just(ResponseEntity.ok(ex.getMessage()));
                })
                .onErrorResume(Exception.class, ex -> {
                    log.error("Произошла непредвиденная ошибка при обработке /AiAssist", ex);
                    return Mono.just(ResponseEntity.ok(
                            "Извините, сервис временно недоступен. Не удалось сгенерировать ответ."));
                });
    }



    /**
     * Грузит файл в MinIO по ключу &lt;targetType&gt;/&lt;targetId&gt;/&lt;uuid&gt;.&lt;ext&gt;
     * и публикует событие { targetType, targetId, objectKey } в топик "Images".
     * targetType — параметр: регистрация → "userimage", создание чата → "chatimage".
     */
    public Mono<Void> Upload_image(FilePart file, String targetId, String targetType) {
        // Картинка опциональна и для чата, и для аватара при регистрации — без файла
        // просто ничего не грузим (иначе file.content() кидает NPE). beads 2q5.
        if (file == null) {
            return Mono.empty();
        }

        // Используем DataBufferUtils.join для безопасного объединения всех частей файла
        Mono<DataBuffer> joinedBuffers = DataBufferUtils.join(file.content());

        return joinedBuffers
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);

                    String filename = file.filename();
                    String extension = (filename != null && filename.contains(".")) ?
                            filename.substring(filename.lastIndexOf(".") + 1) : "jpg";
                    String contentType = Objects.toString(file.headers().getContentType(), "application/octet-stream");
                    String objectKey = targetType + "/" + targetId + "/" + UUID.randomUUID() + "." + extension;

                    return imageStorageService.putObject(objectKey, bytes, contentType)
                            .then(Mono.fromRunnable(() -> {
                                JsonObject buildObj = new JsonObject();
                                buildObj.addProperty("targetType", targetType);
                                buildObj.addProperty("targetId", targetId);
                                buildObj.addProperty("objectKey", objectKey);
                                kafkaProducer.sendImage(buildObj.toString());
                            }));
                })
                .then();
    }
}
