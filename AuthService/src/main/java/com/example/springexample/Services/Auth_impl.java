package com.example.springexample.Services;

import com.example.grpc.AuthTransferServiceGrpc;
import com.example.grpc.DataTransferService;
import com.example.springexample.CustomOAuth2User;
import com.example.springexample.JPA_Entities.User;
import com.example.springexample.Repositories.Auth_rep;
import com.example.springexample.Utils.MyPasswordEncoder;
import com.google.gson.Gson;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@GrpcService
@RequiredArgsConstructor // Автоматически создает конструктор для final полей
public class Auth_impl extends AuthTransferServiceGrpc.AuthTransferServiceImplBase {

    // Внедрение зависимостей через final поля и конструктор (Lombok @RequiredArgsConstructor)
    private final Auth_rep auth_rep;
    private final RedisTemplate<String, String> redisTemplate;
    private final Oauth2Utils oauth2Utils;
    private final MyPasswordEncoder passwordEncoder;
    private final Gson gson = new Gson();

    @Override
    @Transactional(readOnly = true) // Используем readOnly, т.к. тут только чтение
    public void getUserImageurl(DataTransferService.UserDataRequest request, StreamObserver<DataTransferService.DriveUrl> responseObserver) {
        auth_rep.findFirstById(request.getId())
                .ifPresentOrElse(
                        user -> {
                            responseObserver.onNext(DataTransferService.DriveUrl.newBuilder().setUrl(user.getImageUrl()).build());
                            responseObserver.onCompleted();
                        },
                        () -> responseObserver.onError(Status.NOT_FOUND
                                .withDescription("User with id " + request.getId() + " not found")
                                .asException())
                );
    }

    @Override
    @Transactional(readOnly = true)
    public void getAllUsersByChatid(DataTransferService.ChatData request, StreamObserver<DataTransferService.ChatData> responseObserver) {
        List<DataTransferService.User> users = auth_rep.findAllByChatId(request.getChatId()).stream()
                .map(user -> DataTransferService.User.newBuilder()
                        .setId(user.getId())
                        .setUsername(user.getName())
                        .build())
                .collect(Collectors.toList());

        DataTransferService.ChatData response = DataTransferService.ChatData.newBuilder()
                .setChatId(request.getChatId())
                .addAllUser(users)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
        log.info("Successfully sent all users for chat id: {}", request.getChatId());
    }

    @Override
    public void login(DataTransferService.UserDataRequest request, StreamObserver<DataTransferService.AuthResponse> responseObserver) {
        auth_rep.findFirstByName(request.getUsername())
                .ifPresentOrElse(
                        user -> {
                            if (!passwordEncoder.matches(request.getPassword(),user.getMyapppassword())){
                                responseObserver.onNext(DataTransferService.AuthResponse.newBuilder()
                                        .setStatus("401") // UNAUTHORIZED
                                        .setMessage("Invalid username or password")
                                        .build());
                                responseObserver.onCompleted();
                                return;
                            }
                            DataTransferService.AuthResponse authResponse = DataTransferService.AuthResponse.newBuilder()
                                    .setStatus("200")
                                    .setMessage("Successfully logged in")
                                    .setRole(user.getUser_role())
                                    .setSub(String.valueOf(user.getId()))
                                    .build();
                            responseObserver.onNext(authResponse);
                            responseObserver.onCompleted();

                        },
                        () -> {
                            log.warn("Failed login attempt for user: {}", request.getUsername());
                            DataTransferService.AuthResponse errorResponse = DataTransferService.AuthResponse.newBuilder()
                                    .setStatus("401") // UNAUTHORIZED
                                    .setMessage("Invalid username or password")
                                    .build();
                            responseObserver.onNext(errorResponse);
                            responseObserver.onCompleted();

                        }
                );
    }

    @Override
    @Transactional(readOnly = true)
    public void getUserByUsername(DataTransferService.RepeatedUsernames request, StreamObserver<DataTransferService.UserListResponse> responseObserver) {
        List<DataTransferService.UserDataRequest> users = request.getNamesList().stream()
                .map(auth_rep::findFirstByName)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(user -> DataTransferService.UserDataRequest.newBuilder()
                        .setUsername(user.getName())
                        .setId(user.getId())
                        .build())
                .collect(Collectors.toList());

        // Отправка пустого списка - это не ошибка, а нормальный ответ "ничего не найдено"
        DataTransferService.UserListResponse resp = DataTransferService.UserListResponse.newBuilder()
                .addAllUsers(users)
                .build();
        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    @Override
    public void register(DataTransferService.UserDataRequest request, StreamObserver<DataTransferService.AuthResponse> responseObserver) {
        Optional<User> user =auth_rep.findFirstByName(request.getUsername());
        if (user.isPresent()){
            responseObserver.onNext(DataTransferService.AuthResponse.newBuilder().setStatus("666").setMessage("User with such name already exists").build());
            responseObserver.onCompleted();
            return;
        }else {
            try {
                User creation = new User();
                creation.setName(request.getUsername());
                creation.setMyapppassword(passwordEncoder.encodePassword(request.getPassword()));
                creation.setImageUrl("pending");
                creation.setUser_role("USER");
                User created = auth_rep.save(creation);
                responseObserver.onNext(DataTransferService.AuthResponse.newBuilder().setStatus("200").setMessage("Ahueno").setRole(created.getUser_role()).setSub(String.valueOf(created.getId())).build());
                responseObserver.onCompleted();
            } catch (Exception e) {
                log.error("Reg error",e);
                responseObserver.onError(e);
           }


        }
    }

    @Override
    public void getUsernameById(DataTransferService.User request, StreamObserver<DataTransferService.User> responseObserver) {
        auth_rep.findById(request.getId())
                .ifPresentOrElse(
                        user -> {
                            responseObserver.onNext(DataTransferService.User.newBuilder().setUsername(user.getName()).build());
                            responseObserver.onCompleted();
                        },
                        () -> responseObserver.onError(Status.NOT_FOUND
                                .withDescription("User with id " + request.getId() + " not found")
                                .asRuntimeException())
                );
    }

    @Override
    public void checkAuthorization(DataTransferService.idToken request, StreamObserver<DataTransferService.AccessResponse> responseObserver) {
        String id = request.getId();
        if (id == null || id.isEmpty()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Id is missing")
                    .asRuntimeException());
            return;
        }

        try {
            String refresh = redisTemplate.opsForValue().get(id);
            // ПРАВИЛЬНАЯ ПРОВЕРКА НА NULL
            if (refresh != null && !refresh.isEmpty()) {
                OAuth2AccessToken access = oauth2Utils.exchangeTokens(refresh, responseObserver);
                CustomOAuth2User full_user_data = oauth2Utils.getUserByAccessToken(access);

                DataTransferService.AccessResponse accessToken = DataTransferService.AccessResponse.newBuilder()
                        .setName(full_user_data.getName())
                        .setId(full_user_data.getId())
                        .setAccess(gson.toJson(access))
                        .build();

                responseObserver.onNext(accessToken);
                responseObserver.onCompleted();
            } else {
                responseObserver.onError(Status.UNAUTHENTICATED // Более подходящий статус для отсутствия авторизации
                        .withDescription("Not authorized or session expired")
                        .asRuntimeException());
            }
        } catch (Exception e) {
            log.error("Unexpected error during token exchange for id: {}", id, e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Unexpected error: " + e.getMessage())
                    .asRuntimeException());
        }
    }


    @Override
    public void getUserBySub(DataTransferService.Sub_Role request, StreamObserver<DataTransferService.User> responseObserver) {
        final Long id;
        try {
            id = Long.parseLong(request.getSub());
        } catch (NumberFormatException e) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Auth Error: invalid sub " + request.getSub())
                    .asRuntimeException());
            return;
        }
        auth_rep.findById(id)
                .ifPresentOrElse(
                        usr -> {
                            responseObserver.onNext(DataTransferService.User.newBuilder()
                                    .setId(usr.getId())
                                    .setUsername(usr.getName())
                                    .setRole(usr.getUser_role())
                                    .build());
                            responseObserver.onCompleted();
                        },
                        () -> responseObserver.onError(Status.NOT_FOUND
                                .withDescription("Auth Error: user with sub " + request.getSub() + " does not exist")
                                .asRuntimeException())
                );
    }
    @Transactional
    @Override
    public void checkOneTimeCodeAndGetSubRole(DataTransferService.idToken request, StreamObserver<DataTransferService.Sub_Role> responseObserver) {
        if (request.getId() == null || request.getId().isEmpty()) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("OneTimeToken is missing").asRuntimeException());
            return;
        }

        String sub = redisTemplate.opsForValue().get("UserOneTimeCodeFastCheck" + request.getId());
        if (sub == null || sub.isEmpty()) {
            responseObserver.onError(Status.NOT_FOUND.withDescription("One time code is invalid or expired").asRuntimeException());
            return;
        }

        // Удаляем код после использования, если это требуется
        // redisTemplate.delete("UserOneTimeCodeFastCheck" + request.getId());

        auth_rep.findByGoogleSub(sub)
                .ifPresentOrElse(
                        user -> {
                            responseObserver.onNext(DataTransferService.Sub_Role.newBuilder()
                                    .setSub(String.valueOf(user.getId()))
                                    .setRole(user.getUser_role())
                                    .build());
                            responseObserver.onCompleted();
                        },
                        () -> responseObserver.onError(Status.NOT_FOUND
                                .withDescription("Auth Error: user with sub " + sub + " does not exist")
                                .asRuntimeException())
                );
    }
}