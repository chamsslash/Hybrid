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

    /**
     * Свёртка отпечатка браузера: читает components из запроса и переписывает их в новый
     * JSON, заменяя самые объёмные поля (geometry, text, webGlExtensions) их хешами.
     *
     * <p><b>Здесь НЕ должно быть {@code @Async}.</b> Аннотация тут стояла и ломала вход в
     * систему. Вызывающий ({@code ParsingDataService.JsonStreamingParsing}) обращается к
     * этому методу через прокси-бин, а сразу следующей строкой делает
     * {@code writer.toString()}. С {@code @Async} внешний вызов уходит в другой поток и
     * возвращает управление немедленно — то есть {@code toString()} снимает срез
     * {@code StringWriter}, в который ВСЁ ЕЩЁ ИДЁТ ЗАПИСЬ.
     *
     * <p>Результат — оборванный JSON, который дальше падает в
     * {@code FpSimilarityScore.similarCheck} с {@code JsonSyntaxException: EOFException}.
     * Исключение уходит наверх необработанным (фолбэк в
     * {@code MVC_Service.computeLikelihood} накрывает только {@code .block()}, а
     * {@code similarCheck} стоит выше {@code try}) и превращается в 500 на
     * {@code /exchangeTokens} — то есть новая вкладка не может обменять refresh на
     * access и уезжает на {@code /welcome}.
     *
     * <p>Подпись гонки: точка обрыва ПЛАВАЕТ. Живьём поймано на колонке 2066
     * ({@code $..value.touchEvent}) и на колонке 494 ({@code $..value.geometry}) —
     * при фиксированном лимите длины она была бы одинаковой. Воспроизводилось примерно
     * раз на десяток запросов.
     *
     * <p>Рекурсивные вызовы ниже — самовызовы, мимо прокси, поэтому они всегда шли
     * синхронно; детачился ровно один внешний вызов, и этого хватало.
     *
     * <p>Синхронность здесь ничего не стоит: коннектор Tomcat переведён на виртуальные
     * потоки бином {@code VirtualThreadsFactory} выше, блокировка на разборе JSON не
     * занимает потока платформы.
     */
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
