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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

        User user = auth_rep
            .findFirstByName(name)
            .orElseGet(() -> {
                User user1 = new User();
                user1.setName(name);
                user1.setGoogle_sub(oauth2User.getAttribute("sub"));
                user1.setImageUrl("pending");
                user1.setUser_role("USER");
                return auth_rep.save(user1);
            });

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
