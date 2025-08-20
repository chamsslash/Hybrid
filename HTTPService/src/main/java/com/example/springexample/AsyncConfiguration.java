package com.example.springexample;

import com.example.springexample.Utils.FpSimilarityScore;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
@EnableAsync
@Configuration
public class AsyncConfiguration {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> VirtualThreadsFactory(){
        //кароче это лямбда для бина(она заменяет в данном случае   customize(T factory) который сбствно получает в парамсах factory(она также идет как дано в лямбду)
        return factory -> factory.addConnectorCustomizers(con->con.getProtocolHandler().setExecutor(Executors.newVirtualThreadPerTaskExecutor()));
    }
    @Bean
    public Executor VirtualThreadsAsyncExec(){
        return  Executors.newVirtualThreadPerTaskExecutor();
    }

    @Async
    public void handle(JsonReader reader, JsonWriter writer, FpSimilarityScore hasher) throws Exception {
        JsonToken token = reader.peek();


        switch (token) {
            case BEGIN_OBJECT:
                reader.beginObject();
                writer.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    writer.name(name);
                    if (name.equals("geometry")) {
                        String geometryValue = reader.nextString();
                        String geometryHash = hasher.hash(geometryValue);
                        writer.value(geometryHash);
                    } else if (name.equals("text")) {
                        String textValue = reader.nextString();
                        String textHash = hasher.hash(textValue);
                        writer.value(textHash);
                    } else if (name.equals("webGlExtensions")) {

                        JsonElement element = JsonParser.parseReader(reader);
                        String res = element.toString();
                        String glHash = hasher.hash(res);
                        writer.value(glHash);}
                    else {
                        handle(reader, writer,hasher);
                    }
                }
                reader.endObject(); // ← не забудь завершить
                writer.endObject();
                break;
            case BEGIN_ARRAY:
                reader.beginArray();
                writer.beginArray();
                while (reader.hasNext()) {
                    handle(reader, writer,hasher);
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
}
