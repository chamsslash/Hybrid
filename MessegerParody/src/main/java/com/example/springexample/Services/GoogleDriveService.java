package com.example.springexample.Services;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.InputStreamContent;
import com.google.api.services.drive.model.File;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GoogleDriveService {
    @Autowired
    Drive googleDrive;
    private final String folderId= "1EL8RRZloDhpf8kNWXAZDptQNsiAhBnKe";




    public String SaveImage(String b64String) throws IOException {
        byte[] bytes = decodeBase64(b64String);
        File fileMeta = new File();
        fileMeta.setName(UUID.randomUUID().toString());
        fileMeta.setParents(Collections.singletonList(folderId));
        InputStreamContent data = new InputStreamContent("image/jpeg",new ByteArrayInputStream(bytes));
        File file = googleDrive.files().create(fileMeta,data).setFields("id,name,webViewLink").execute();
        return file.getId();
    }

    private static byte[] decodeBase64(String payload) {
        try {
            return Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid base64 payload", e);
        }
    }

//    private static String shortHash(byte[] bytes) {
//        try {
//            MessageDigest digest = MessageDigest.getInstance("SHA-256");
//            byte[] hash = digest.digest(bytes);
//            String hex = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
//            return hex.substring(0, 12);
//        } catch (NoSuchAlgorithmException e) {
//            return UUID.randomUUID().toString().substring(0, 12);
//        }
//    }
}
