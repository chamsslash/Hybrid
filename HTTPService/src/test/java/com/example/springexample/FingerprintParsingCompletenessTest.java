package com.example.springexample;

import com.example.springexample.Utils.FpSimilarityScore;
import com.example.springexample.Utils.ParsingDataService;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Свёртка отпечатка обязана возвращать ПОЛНЫЙ и валидный JSON.
 *
 * <p><b>Какой баг стережёт.</b> На {@code AsyncConfiguration.handle} стояла аннотация
 * {@code @Async}. Вызывающий обращается к методу через прокси-бин и сразу следующей
 * строкой делает {@code writer.toString()} — с {@code @Async} внешний вызов уходил в
 * другой поток, и {@code toString()} снимал срез буфера, в который ещё шла запись.
 * Наружу выходил оборванный JSON.
 *
 * <p>Дальше он падал в {@code FpSimilarityScore.similarCheck} с
 * {@code JsonSyntaxException: EOFException}, исключение уходило мимо фолбэка в
 * {@code MVC_Service.computeLikelihood} (тот накрывает только {@code .block()}) и
 * превращалось в <b>500 на {@code /exchangeTokens}</b>: новая вкладка не могла обменять
 * refresh на access и уезжала на {@code /welcome}. Живьём воспроизводилось примерно
 * раз на десяток запросов.
 *
 * <p><b>Разделение ответственности между тестами класса — важное.</b> Проверено
 * экспериментом: аннотация {@code @Async} временно возвращалась на место, и тесты
 * полноты JSON ниже <b>остались зелёными</b>. Так и должно быть — они собирают
 * {@link AsyncConfiguration} через {@code new}, минуя контейнер, поэтому прокси не
 * создаётся и аннотация инертна. Сами по себе они регрессию НЕ ловят.
 *
 * <p>Регрессию ловит {@link #handleMustNotBeAsync()}: он проверяет отсутствие аннотации
 * напрямую. Выглядит грубо, но бьёт ровно в причину — баг был не в алгоритме свёртки,
 * а в способе её вызова, и воспроизводится только через прокси Spring.
 *
 * <p>Тесты полноты при этом не лишние: они сторожат сам алгоритм (что свёртка
 * возвращает валидный и полный JSON) и служат опорой для
 * {@link #similarCheckAcceptsFoldedComponents()} — того кадра стека, где падал стенд.
 * {@link RepeatedTest} и намеренно большой вход оставлены как страховка на случай,
 * если параллелизм в свёртку вернут другим способом.
 */
class FingerprintParsingCompletenessTest {

    private final Gson gson = new Gson();

    /**
     * Что делает: смотрит рефлексией на метод {@code AsyncConfiguration.handle}.
     * Что проверяет: на нём нет {@code @Async}.
     * Зачем: это единственный тест класса, который РЕАЛЬНО ловит исходный баг.
     * Проверено экспериментом — с возвращённой аннотацией он краснеет, а остальные
     * тесты остаются зелёными, потому что собирают объект через {@code new} и прокси
     * Spring не создаётся.
     *
     * <p>Проверять аннотацию вместо поведения здесь оправдано: поведенческая
     * альтернатива требует поднять контекст с {@code @EnableAsync} и ловить
     * недетерминированную гонку, то есть тест был бы одновременно дороже и менее
     * надёжен. Причина же формулируется одним предложением: этот метод обязан
     * выполняться синхронно, потому что вызывающий читает буфер сразу после возврата.
     */
    @Test
    void handleMustNotBeAsync() throws NoSuchMethodException {
        var method = AsyncConfiguration.class.getMethod(
                "handle",
                com.google.gson.stream.JsonReader.class,
                com.google.gson.stream.JsonWriter.class,
                FpSimilarityScore.class);

        assertThat(method.isAnnotationPresent(org.springframework.scheduling.annotation.Async.class))
                .as("@Async на handle() детачит внешний вызов, и ParsingDataService читает "
                        + "StringWriter во время записи — оборванный отпечаток превращается "
                        + "в 500 на /exchangeTokens")
                .isFalse();
    }

    /**
     * Собирает вход, похожий по форме и объёму на настоящие components FingerprintJS:
     * вложенные объекты вида {@code {"имя": {"value": ...}}}, среди них — те самые
     * поля, которые свёртка заменяет хешами ({@code geometry}, {@code text},
     * {@code webGlExtensions}), и длинные строковые значения.
     */
    private String buildLargeComponents() {
        StringBuilder sb = new StringBuilder("{");
        String filler = "x".repeat(400);
        for (int i = 0; i < 60; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"component").append(i).append("\":{\"value\":{")
              .append("\"geometry\":\"geo-").append(filler).append("\",")
              .append("\"text\":\"txt-").append(filler).append("\",")
              .append("\"touchEvent\":").append(i % 2 == 0).append(',')
              .append("\"n\":").append(i)
              .append("}}");
        }
        sb.append(",\"webGlExtensions\":{\"ext\":[\"a\",\"b\",\"c\"]}}");
        return sb.toString();
    }

    private ParsingDataService newService() {
        // Бин собирается вручную, без контекста Spring: контекст поднял бы @EnableAsync
        // и вернул прокси — то есть ровно то, из-за чего баг и существовал. Здесь нужен
        // голый объект, чтобы тест проверял САМ метод, а не поведение прокси.
        ParsingDataService service = new ParsingDataService();
        AsyncConfiguration handler = new AsyncConfiguration();
        try {
            Field f = ParsingDataService.class.getDeclaredField("parsingDataService");
            f.setAccessible(true);
            f.set(service, handler);
            Field g = ParsingDataService.class.getDeclaredField("gson");
            g.setAccessible(true);
            g.set(service, gson);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("поля ParsingDataService переименованы — обнови тест", e);
        }
        return service;
    }

    /**
     * Что делает: гоняет свёртку на большом входе 25 раз подряд.
     * Что проверяет: каждый результат разбирается Gson без исключения и содержит все
     * 60 компонентов.
     * Зачем: плавающий обрыв ловится только повторами — один прогон сломанный код
     * проходил бы примерно в девяти случаях из десяти.
     */
    @RepeatedTest(25)
    void foldedFingerprintIsCompleteJson() throws Exception {
        String result = newService().JsonStreamingParsing(buildLargeComponents(), new FpSimilarityScore());

        assertThatCode(() -> gson.fromJson(result, Map.class))
                .as("свёртка отпечатка вернула невалидный JSON — вероятнее всего вернулся @Async на handle()")
                .doesNotThrowAnyException();

        Map<?, ?> parsed = gson.fromJson(result, Map.class);
        assertThat(parsed).hasSize(61); // 60 component* + webGlExtensions
    }

    /**
     * Что делает: разбирает результат свёртки и смотрит на поля, которые она обязана
     * заменить хешами.
     * Что проверяет: geometry и text больше не содержат исходную строку-заполнитель.
     * Зачем: сторожит смысл самой свёртки. Без неё в Redis уезжали бы десятки килобайт
     * сырого отпечатка на каждую сессию, а гонку выше можно было бы «починить»,
     * выбросив обработку полей целиком — тест это заметит.
     */
    @Test
    void heavyFieldsAreReplacedByHashes() throws Exception {
        String result = newService().JsonStreamingParsing(buildLargeComponents(), new FpSimilarityScore());

        assertThat(result)
                .as("исходные объёмные значения обязаны быть свёрнуты в хеш")
                .doesNotContain("geo-xxxx")
                .doesNotContain("txt-xxxx");
    }

    /**
     * Что делает: подаёт заведомо оборванный JSON.
     * Что проверяет: свёртка падает, а не возвращает молча половину результата.
     * Зачем: фиксирует границу ответственности. Обрыв входа — это ошибка вызывающего,
     * и она обязана быть шумной; тихий частичный результат — ровно то поведение,
     * которое породило исходный баг.
     */
    @Test
    void truncatedInputFailsLoudly() {
        String broken = "{\"a\":{\"value\":{\"geometry\":\"abc\"";
        assertThatCode(() -> newService().JsonStreamingParsing(broken, new FpSimilarityScore()))
                .as("оборванный вход обязан приводить к исключению, а не к частичному результату")
                .isInstanceOf(Exception.class);
    }

    /**
     * Что делает: сравнивает два одинаковых отпечатка через similarCheck на реальном
     * выходе свёртки.
     * Что проверяет: сравнение отрабатывает без JsonSyntaxException и даёт высокий балл.
     * Зачем: это и есть тот кадр стека, где падал прод (FpSimilarityScore:200 —
     * разбор components ВХОДЯЩЕГО отпечатка). Тест связывает свёртку с её потребителем,
     * а не проверяет её в вакууме.
     */
    @Test
    void similarCheckAcceptsFoldedComponents() throws Exception {
        FpSimilarityScore scorer = new FpSimilarityScore();
        String folded = newService().JsonStreamingParsing(buildLargeComponents(), scorer);

        FpSimilarityScore.ClientMeta meta = new FpSimilarityScore.ClientMeta(
                "1.2.3.4", "RU", "Moscow", "AS1", "org", "visitor-1", folded, "uuid-1", "ptr");

        double score = scorer.similarCheck(meta, meta);

        assertThat(score)
                .as("одинаковые отпечатки обязаны давать высокий балл, а не падать на разборе components")
                .isGreaterThan(60.0);
    }
}
