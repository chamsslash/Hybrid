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
   private MyPasswordEncoder passwordEncoder;
    @Autowired
    ParsingDataService dataParser;
    @Autowired
    private gRPC_Client grpc;
    @Autowired
    YandexGptService yandexGptService;
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
                            .flatMap(authResponse -> {
                                if (!"200".equals(authResponse.getStatus())) {
                                    return Mono.error(new RuntimeException("Ошибка регистрации от сервиса: " + authResponse.getMessage()));
                                }
                                if (authResponse.getStatus().equals("666")){
                                    return Mono.error(new InternalError(authResponse.getMessage()));
                                }
                                return Upload_image(regData.imagePart(), authResponse.getSub())
                                        .then(Mono.just(authResponse));
                            })
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

                .onErrorResume(InternalError.class, e -> {
                    log.error("Юзер с таким именемм существует", e);
                    return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .bodyValue(e.getMessage());
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
                .onErrorResume(Exception.class, e -> {
                    log.error("Непредвиденная ошибка при входе", e);
                    return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).bodyValue("Внутренняя ошибка сервера.");
                });
    }


    public Mono<ServerResponse> ResultSet( MvcJwtAuthFilter.jwt_refresh_auths tokens) {
        ResponseCookie rc= ResponseCookie.from("access",tokens.jwt())
                .httpOnly(true)
//                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ofMinutes(10))
                .build();
        ResponseCookie refreshCookie = ResponseCookie.from("refresh", tokens.refresh())
                .httpOnly(true)
//                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ofDays(7))
                .build();
        Map<String, String> responseBody = Map.of(
                "redirectUri","/reactive/chatlist"
        );

        return ServerResponse.ok()
                .header(HttpHeaders.SET_COOKIE, rc.toString())
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
                                                var chatdata = DataTransferService.ChatData.newBuilder()
                                                        .setAuthorId(DataTransferService.User.newBuilder().setId(authorId).build())
                                                        .setTitle(chatTitle)
                                                        .addAllUser(userList)
                                                        .setImageUrl("pending")
                                                        .build();

                                                return reactiveGrpcClient.reactiveChatServe(chatdata)
                                                        .flatMap(resp -> {
                                                            JsonObject jsonObj = JsonParser.parseString(resp).getAsJsonObject();
                                                            if ("pending".equals(jsonObj.get("image_id").getAsString())) {
                                                                return Upload_image(file, jsonObj.get("id").getAsString())
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
                            return ServerResponse.status(HttpStatus.SEE_OTHER)
                                    .location(URI.create("/reactive/chatlist"))
                                    .build();
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



    public Mono<ServerResponse> getChatList(ServerRequest request, ISpringWebFluxTemplateEngine templateEngine) {
        log.info("===> [1] Метод getChatList вызван.");

        // ШАГ 1: Получаем контекст и НЕМЕДЛЕННО КЭШИРУЕМ его, чтобы избежать повторных подписок.
        Mono<Authentication> authMono = ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .cache(); // <--- САМОЕ ВАЖНОЕ ИЗМЕНЕНИЕ

        return authMono
                .flatMap(authentication -> {
                    // ШАГ 2: Извлекаем ID пользователя как СТРОКУ, чтобы избежать NumberFormatException.
                    String principalName = authentication.getName();
                    log.info("===> [2] Principal '{}' найден. Начинаю сборку модели.", principalName);

                    // ШАГ 3: Вся gRPC и логика сборки модели находится внутри этого flatMap.
                    // Ошибки здесь будут перехвачены ниже в onErrorResume.
                    Mono<Map<String, Object>> modelMono = Mono.fromCallable(() -> {
                                // Эта часть теперь просто для логической группировки, так как парсинг не нужен
                                log.info("===> [4] Пользователь {} найден. Начинаю gRPC...", principalName);
                                return principalName;
                            })
                            .flatMap(userId -> {
                                DataTransferService.ChatData chatDataRequest = DataTransferService.ChatData.newBuilder()
                                        // Используем userId (String) напрямую, без парсинга в Long
                                        .addUser(DataTransferService.User.newBuilder().setId(userId).build())
                                        .build();

                                return reactiveGrpcClient.reactiveGetAllChatsById(chatDataRequest)
                                        .doOnError(e -> log.error("===> [X] Ошибка при получении списка чатов", e))
                                        .flatMapMany(list -> Flux.fromIterable(list.getChatdataListList()))
                                        .flatMap(chat -> reactiveGrpcClient.reactiveGetNewestMessage(chat)
                                                .onErrorResume(err -> {
                                                    log.warn("===> [X] Ошибка при получении сообщения для чата {}: {}", chat.getChatId(), err.getMessage());
                                                    return Mono.just(new ShortChatObject()); // Возвращаем пустой объект или маркер
                                                }))
                                        .collectList()
                                        .map(previews -> {
                                            Map<String, Object> model = new HashMap<>();
                                            model.put("pathPrefix", "/reactive");
                                            model.put("chats", previews);
                                            model.put("user_id", userId);
                                            log.info("===> [5] Данные для модели собраны.");
                                            return model;
                                        });
                            });

                    return modelMono.flatMap(model -> {
                        log.info("===> [6] Начинаю рендеринг шаблона 'chats_list'.");
                        return ParseWithThymeLeaf(model, "chats_list", templateEngine)
                                .flatMap(htmlContent -> {
                                    log.info("===> [7] Рендеринг завершен. Отдаю успешный ответ.");
                                    return ServerResponse.ok()
                                            .contentType(MediaType.TEXT_HTML)
                                            .bodyValue(htmlContent);
                                });
                    });
                })
                // Срабатывает, только если authMono изначально пуст.
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("===> [X] Аутентификация отсутствует. Редирект на страницу входа.");
                    return ServerResponse.temporaryRedirect(URI.create("/welcome")).build();
                }))
                // ШАГ 5: ЕДИНЫЙ глобальный обработчик ошибок для всей цепочки.
                // Перехватит любые ошибки (от gRPC, рендеринга и т.д.), которые не были обработаны ранее.
                .onErrorResume(e -> {
                    log.error("===> [X] Непредвиденная глобальная ошибка в цепочке getChatList", e);
                    return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .contentType(MediaType.TEXT_PLAIN)
                            .bodyValue("Произошла внутренняя ошибка: " + e.getMessage());
                });
    }

    public Mono<ServerResponse> renderChatPage(ServerRequest request, ISpringWebFluxTemplateEngine templateEngine) {
        log.info("===> [1] Метод renderChatPage вызван.");

        // 1. Получаем параметры запроса
        long chatId = Long.parseLong(request.queryParam("id").orElseThrow(() -> new IllegalArgumentException("Query param 'id' is required")));
        String title = request.queryParam("title").orElse("Unknown Chat");

        // 2. Начинаем цепочку с получения контекста безопасности (как в примере)
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .cast(Principal.class)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("===> [X] SecurityContext пуст. Сигнал для перенаправления на авторизацию.");
                    return Mono.empty();
                }))
                .flatMap(principal -> {
                    // 3. Получаем ID пользователя и начинаем собирать данные
                    return Mono.fromCallable(principal::getName)
                            .doOnNext(userId -> log.info("===> [4] Пользователь {} найден. Начинаю параллельные gRPC запросы...", userId))
                            .flatMap(userId -> {
                                // 4. Подготавливаем все независимые gRPC вызовы
                                DataTransferService.ChatData chatData = DataTransferService.ChatData.newBuilder()
                                        .setChatId(chatId).setTitle(title).build();

                                Mono<String> usernameMono = reactiveGrpcClient.reactiveGetUsernameById(userId);
                                Mono<String> chatResponseMono = reactiveGrpcClient.reactiveChatServe(chatData);
                                Mono<List<String>> chatMembersMono = reactiveGrpcClient.reactiveGetAllUsernamesByChatId(chatData);
                                Mono<String> chatImageUrlMono = reactiveGrpcClient.reactiveGetImageUrl(chatId);
                                Mono<String> userImageUrlMono = reactiveGrpcClient.reactiveGetUserImageUrl(Long.valueOf(userId));

                                // 5. Выполняем их параллельно с помощью Mono.zip
                                return Mono.zip(
                                                usernameMono,
                                                chatResponseMono,
                                                chatMembersMono,
                                                chatImageUrlMono,
                                                userImageUrlMono
                                        )
                                        .flatMap(tuple -> {
                                            // 6. Распаковываем результаты первого этапа
                                            String username = tuple.getT1();
                                            String rawChatResponse = tuple.getT2();
                                            List<String> chatMembers = tuple.getT3();
                                            String chatImageUrl = tuple.getT4();
                                            String userImageUrl = tuple.getT5();

                                            JsonObject chatResponseJson = JsonParser.parseString(rawChatResponse).getAsJsonObject();
                                            Mono<List<MessageEvent>> messagesMono;

                                            if ("500".equals(chatResponseJson.get("status").getAsString())) {
                                                String errorMessage = chatResponseJson.get("message").getAsString();
                                                MessageEvent me = new MessageEvent();
                                                me.setText(errorMessage);
                                                messagesMono = Mono.just(Collections.singletonList(me));
                                            } else {
                                                messagesMono = reactiveGrpcClient.reactiveGetAllMessages(chatData);
                                            }

                                            // 8. Объединяем результаты первого этапа с результатом второго
                                            return messagesMono.map(messages -> {
                                                // 9. Собираем финальную модель для Thymeleaf
                                                Map<String, Object> model = new HashMap<>();
                                                model.put("username", username);
                                                model.put("title", title);
                                                model.put("user_id", userId);
                                                model.put("chat_id", chatId);
                                                model.put("usernames", chatMembers);
                                                model.put("messages", messages);
                                                model.put("chat_image_url", chatImageUrl);
                                                model.put("image_url", userImageUrl);
                                                log.info("===> [5] Данные для модели страницы чата собраны.");
                                                return model;
                                            });
                                        })
                                        .flatMap(model -> {
                                            log.info("===> [6] Начинаю рендеринг шаблона 'index' в строку.");
                                            // 10. Вызываем ВАШ кастомный метод рендеринга
                                            return ParseWithThymeLeaf(model, "index", templateEngine)
                                                    .flatMap(htmlContent -> {
                                                        log.info("===> [7] Рендеринг в строку завершен. Отдаю ServerResponse с bodyValue.");
                                                        return ServerResponse.ok()
                                                                .contentType(MediaType.TEXT_HTML)
                                                                .bodyValue(htmlContent);
                                                    });
                                        });
                            })
                            // Обработка ошибки парсинга ID пользователя
                            .onErrorResume(NumberFormatException.class, e -> {
                                log.warn("===> [X] Имя principal невалидно: '{}'. Редирект.", principal.getName());
                                return ServerResponse.temporaryRedirect(URI.create("/startauth")).build();
                            });
                })
                // Обработка пустого SecurityContext
                .switchIfEmpty(ServerResponse.temporaryRedirect(URI.create("/startauth")).build())
                // Глобальный обработчик всех остальных ошибок
                .onErrorResume(e -> {
                    log.error("===> [X] Непредвиденная глобальная ошибка в цепочке renderChatPage", e);
                    return ServerResponse.status(500).contentType(MediaType.TEXT_PLAIN).bodyValue("Internal Server Error: " + e.getMessage());
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
    @PostMapping(path = "/AiAssist")
    public Mono<String> aiAssistHandler(@RequestPart("TargetUsername") String targetUsername,
                                        @RequestPart("chat_id") String chatId) {

        return Mono.defer(() -> {
                    ChatContextService contextService = new ChatContextService(rredisTemplate, chatId);

                    return contextService.getFullContext()
                            .switchIfEmpty(Mono.error(new IllegalStateException("Контекст чата пуст...")))
                            .collect(Collectors.joining())
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
                                return yandexGptService.BuildJsonPrompt(promptWithNick, nameMessagesMap);
                            })
                            .flatMap(prompt ->
                                    yandexGptService.GetAssistantAnswer(prompt) // Исправлено
                            );
                })
                .onErrorResume(IllegalStateException.class, ex -> {
                    log.warn("Не удалось сгенерировать ответ: {}", ex.getMessage());
                    return Mono.just(ex.getMessage());
                })
                .onErrorResume(Exception.class, ex -> {
                    log.error("Произошла непредвиденная ошибка при обработке /AiAssist", ex);
                    return Mono.just("Извините, сервис временно недоступен. Не удалось сгенерировать ответ.");
                });
    }



    public Mono<Void> Upload_image(FilePart file, String chatId) {

        // Используем DataBufferUtils.join для безопасного объединения всех частей файла
        Mono<DataBuffer> joinedBuffers = DataBufferUtils.join(file.content());

        return joinedBuffers
                .flatMap(dataBuffer -> {
                    return Mono.fromRunnable(() -> {
                                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                                dataBuffer.read(bytes);
                                DataBufferUtils.release(dataBuffer);

                                String B64string = Base64.getEncoder().encodeToString(bytes);
                                String filename = file.filename();
                                String extension = filename.contains(".") ?
                                        filename.substring(filename.lastIndexOf(".") + 1) : "jpg";
                                JsonObject buildObj = new JsonObject();
                                buildObj.addProperty("Base64Image", B64string);
                                buildObj.addProperty("type", "image");
                                buildObj.addProperty("ImageName", UUID.randomUUID().toString());
                                buildObj.addProperty("MimeType", Objects.toString(file.headers().getContentType(), "application/octet-stream"));
                                buildObj.addProperty("Extension", extension);
                                buildObj.addProperty("Target", chatId);
                                buildObj.addProperty("TargetType", "chatimage");

                                // Отправляем в Kafka
                                kafkaProducer.send(buildObj.toString());
                            })
                            .subscribeOn(Schedulers.boundedElastic()) // Выполняем на потоке для блокирующих операций
                            .then(); // Преобразуем в Mono<Void> после завершения
                });}
}
