package com.example.springexample;

import com.example.springexample.JPA_Entities.User;
import com.example.springexample.Repositories.Auth_rep;
import com.example.springexample.Services.ImageStorageService;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Base64;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    @Autowired
    KafkaProducer kafkaProducer;


    private final Auth_rep auth_rep;
    private final ImageStorageService imageStorageService;

    public CustomOAuth2UserService(Auth_rep authRep, ImageStorageService imageStorageService) {
        this.auth_rep = authRep;
        this.imageStorageService = imageStorageService;
    }

    @Transactional
    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest)
        throws OAuth2AuthenticationException {
        String image_url = null;
        OAuth2User oauth2User = super.loadUser(userRequest);
        String name = oauth2User.getAttribute("name");

        User user = resolveUser(name, oauth2User.getAttribute("sub"));

        try {
            if (
                user.getImageUrl() == null ||
                user.getImageUrl().equals("pending")
            ) {
                String pictureUrl = (String) oauth2User.getAttribute("picture");
                assert pictureUrl != null;
                CompletableFuture<String> b64Future = GetImageAndConvertToB64(
                    pictureUrl
                );
                String B64_string = b64Future.get();
                Upload_image(B64_string, user.getId().toString());
            }
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        OAuth2UserAuthority authority = new OAuth2UserAuthority(
            "USER",
            oauth2User.getAttributes()
        );
        CustomOAuth2User oAuth2User = new CustomOAuth2User();
        oAuth2User.setSub(oauth2User.getAttribute("sub"));
        oAuth2User.setName(name);
        oAuth2User.setAuthorities(Collections.singletonList(authority));
        oAuth2User.setId(String.valueOf(user.getId()));

        return oAuth2User;
    }

    /**
     * Находит Google-пользователя по нику или заводит нового.
     *
     * Вынесено из {@link #loadUser} отдельным методом ради теста: сам loadUser первым делом
     * ходит в Google через {@code super.loadUser}, и проверить на нём поведение при
     * коллизии ников можно было бы только подняв сеть.
     *
     * ПОЧЕМУ ЗДЕСЬ CATCH. Это третий писатель {@code users.name} — наравне с
     * {@code register} и {@code changeUsername} в {@link com.example.springexample.Services.Auth_impl},
     * и единственный, который до сих пор не считался с UNIQUE-индексом
     * {@code ux_users_lower_name (lower(name) text_pattern_ops)}. Предпроверка
     * {@code findFirstByName} сравнивает ТОЧНО, с учётом регистра, поэтому «Миша» из Google
     * при живой «миша» в базе её не находит, идёт создавать новую строку и упирается в
     * индекс. Id генерится IDENTITY — INSERT выпускается прямо на save(), внутри этого try.
     *
     * Без catch нарушение улетало бы наружу из OAuth-флоу как 500: до появления индекса
     * такой вход тихо заводил второго пользователя, а теперь он просто не работал бы.
     *
     * ПОЧЕМУ FAIL-CLOSED, А НЕ ПОДХВАТ СУЩЕСТВУЮЩЕЙ СТРОКИ. Соблазнительно на коллизии
     * перечитать пользователя регистронезависимо и вернуть его — вход бы «заработал».
     * Но пользователь ищется ПО ИМЕНИ, а не по {@code google_sub}: совпадение ников ничего
     * не говорит о том, что это один и тот же человек. Подхват пустил бы владельца
     * Google-аккаунта «Миша» в чужой локальный аккаунт «миша» вместе со всеми его чатами.
     * Отказ во входе — исправимое неудобство, чужой аккаунт — нет.
     *
     * Настоящее лечение — искать по {@code google_sub} (поле в сущности есть, и репозиторий
     * умеет {@code findByGoogleSub}), но это меняет семантику связывания аккаунтов и живёт
     * отдельной задачей.
     */
    User resolveUser(String name, String googleSub) {
        return auth_rep
            .findFirstByName(name)
            .orElseGet(() -> {
                User user1 = new User();
                user1.setName(name);
                user1.setGoogle_sub(googleSub);
                user1.setImageUrl("pending");
                user1.setUser_role("USER");
                try {
                    return auth_rep.save(user1);
                } catch (DataIntegrityViolationException duplicate) {
                    log.info(
                        "Вход через Google отклонён: ник {} уже занят (нарушен ux_users_lower_name)",
                        name
                    );
                    throw new OAuth2AuthenticationException(
                        new OAuth2Error(
                            "username_taken",
                            "Имя " + name + " уже занято другим аккаунтом",
                            null
                        ),
                        duplicate
                    );
                }
            });
    }

    /**
     * Загружает аватарку нового Google-юзера в MinIO и публикует событие в топик
     * {@code Images} по общему контракту пайплайна картинок (beads lyo):
     * {@code { "targetType": "userimage", "targetId": <userId>, "objectKey": <key> }}.
     * Сначала кладёт байты в MinIO, и только после успешной загрузки шлёт в Kafka
     * ссылку на objectKey (без Base64) — так консюмеры (MessegerParody / HTTPService)
     * получают ровно те три lowercase-поля, которые ожидают.
     */
    public void Upload_image(String b64, String userId) throws IOException {
        byte[] bytes = Base64.getDecoder().decode(b64);
        String key = "userimage/" + userId + "/" + UUID.randomUUID() + ".jpg";

        imageStorageService.putObject(key, bytes, "image/jpeg");

        JsonObject buildObj = new JsonObject();
        buildObj.addProperty("targetType", "userimage");
        buildObj.addProperty("targetId", userId);
        buildObj.addProperty("objectKey", key);
        kafkaProducer.send(buildObj.toString(), "Images");
    }

    @Async
    public CompletableFuture<String> GetImageAndConvertToB64(String pictureURL)
        throws IOException {
        URL parsed_UrlOfImage = new URL(pictureURL);
        InputStream inputStream = parsed_UrlOfImage.openStream();
        BufferedImage bufferedImage = ImageIO.read(inputStream);
        ByteArrayOutputStream byteArrayOutputStream =
            new ByteArrayOutputStream();
        ImageIO.write(bufferedImage, "jpg", byteArrayOutputStream);
        byte[] imageBytes = byteArrayOutputStream.toByteArray();

        String B64_string = Base64.getEncoder().encodeToString(imageBytes);
        return CompletableFuture.completedFuture(B64_string);
    }
}
