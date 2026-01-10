package com.example.springexample;

import com.example.springexample.JPA_Entities.User;
import com.example.springexample.Repositories.Auth_rep;
import com.example.springexample.Services.ImageGrpcService;
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

    @Autowired
    ImageGrpcService imageGrpcService;

    private final Auth_rep auth_rep;

    public CustomOAuth2UserService(Auth_rep authRep) {
        this.auth_rep = authRep;
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
//                imageGrpcService.SaveImage(B64_string,"image/jpeg ",".jpeg","avatar_"+name); //change to kafka implementation
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

    public void Upload_image(String b64, String chatId) throws IOException {
        JsonObject buildObj = new JsonObject();

        try {
            buildObj.addProperty("Base64Image", b64);
            buildObj.addProperty("type", "image");
            buildObj.addProperty("ImageName", UUID.randomUUID().toString());
            buildObj.addProperty("MimeType", "image/jpeg");
            buildObj.addProperty("Extension", "jpg");
            buildObj.addProperty("Target", chatId);
            buildObj.addProperty("TargetType", "userimage");
            kafkaProducer.send(buildObj.toString(), "Images");
        } catch (Exception e) {
            throw e;
        }
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
