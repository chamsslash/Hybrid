package com.example.springexample;


import com.example.grpc.DataTransferService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Locale;
@Setter
@Getter
@NoArgsConstructor
public class FullOAuth2Data {
    String id;
    String name;
    String accessToken;
    String expiresAt;
    public FullOAuth2Data(DataTransferService.AccessResponse accessResponse) {
        JsonObject json_access = JsonParser.parseString(accessResponse.getAccess()).getAsJsonObject();
        this.expiresAt = (json_access.get("expires_at").getAsString());
        this.id = accessResponse.getId();
        this.name=accessResponse.getName();
    }


}
