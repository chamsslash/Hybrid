package com.example.springexample;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;

@Configuration
public class GoogleDriveProvider {
    @Bean
    public Drive Drivegen() throws IOException, GeneralSecurityException {
        GoogleCredentials credits = GoogleCredentials.fromStream(
            getClass().getResourceAsStream("/googleCloud.json")
        ).createScoped(Collections.singleton(DriveScopes.DRIVE_FILE));
        return new Drive.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(),new HttpCredentialsAdapter(credits)).setApplicationName("hybrid").build();
    }
}
