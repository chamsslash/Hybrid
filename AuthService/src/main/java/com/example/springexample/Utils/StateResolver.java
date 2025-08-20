package com.example.springexample.Utils;

import com.example.springexample.HttpService;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
@Component
@RequiredArgsConstructor
@Slf4j
public class StateResolver {
    private final Gson gson= new Gson();
private  final RedisTemplate<String,String> redisTemplate;
public String bindFingerPrint(HttpService.ClientMeta meta) {
    String bindingToken = UUID.randomUUID().toString();
    redisTemplate.opsForValue().set("FingerPrint:"+bindingToken,gson.toJson(meta),300000, TimeUnit.MILLISECONDS);
    log.info("Meta for fingerPrint of user was saved for 5 mins");
    return bindingToken;
}
    public  String JsonStreamingParsing(String json) throws Exception {
        JsonReader reader = new JsonReader(new StringReader(json));
        StringWriter writer = new StringWriter();
        JsonWriter jsonWriter = new JsonWriter(writer);

        handle(reader,jsonWriter);

        return writer.toString();
    };
    private void handle(JsonReader reader, JsonWriter writer) throws Exception {
        JsonToken token = reader.peek();
        log.info("TOKEN: {}", token);

        switch (token) {
            case BEGIN_OBJECT:
                reader.beginObject();
                writer.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    writer.name(name);

                    if (name.equals("geometry")) {
                        String geometryValue = reader.nextString();
                        String geometryHash = hash(geometryValue);
                        writer.value(geometryHash);
                    } else if (name.equals("webGlExtensions")) {

                        JsonElement element = JsonParser.parseReader(reader);
                        String res = element.toString();
                        String glHash = hash(res);
                        writer.value(glHash);}
                    else if (name.equals("text")) {
                        String textValue = reader.nextString();
                        String textHash = hash(textValue);
                        writer.value(textHash);
                    }
                    else {
                        handle(reader, writer);
                    }
                }
                reader.endObject();
                writer.endObject();
                break;
            case BEGIN_ARRAY:
                reader.beginArray();
                writer.beginArray();
                while (reader.hasNext()) {
                    handle(reader, writer);
                }
                reader.endArray();
                writer.endArray();
                break;
            case STRING:
                writer.value(reader.nextString());
                break;
            case NUMBER:
                writer.value(reader.nextDouble());
                break;
            case BOOLEAN:
                writer.value(reader.nextBoolean());
                break;
            case NULL:
                reader.nextNull();
                writer.nullValue();
                break;
            default:
                reader.skipValue();
                break;
        }
    }
    public String hash(String string) {
        try {
            MessageDigest alg = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = alg.digest(string.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashBytes);
        } catch (NoSuchAlgorithmException algorithmException) {
            throw new RuntimeException("Не найден алгоритм хэширования SHA-256", algorithmException);
        }
    }
    }

