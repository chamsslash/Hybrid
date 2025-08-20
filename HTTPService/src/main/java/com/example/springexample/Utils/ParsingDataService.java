package com.example.springexample.Utils;

import com.example.springexample.AsyncConfiguration;
import com.example.springexample.FullOAuth2Data;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
@Service
public class ParsingDataService {
    @Autowired
    AsyncConfiguration parsingDataService;
    @Autowired
    Gson gson;
    public FullOAuth2Data ParseDataFromJwt(String JWTToken){
        FullOAuth2Data data = gson.fromJson(new String(Base64.getDecoder().decode(JWTToken), StandardCharsets.UTF_8), FullOAuth2Data.class);
        return data;
    }
    public  String JsonStreamingParsing(String json,FpSimilarityScore similarityScore) throws Exception {
        JsonReader reader = new JsonReader(new StringReader(json));
        StringWriter writer = new StringWriter();
        JsonWriter jsonWriter = new JsonWriter(writer);

        parsingDataService.handle(reader,jsonWriter,similarityScore);

        return writer.toString();
    };

    }
