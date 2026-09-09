package com.example.springexample.Services;

import com.example.springexample.Utils.FpSimilarityScore;
import com.example.springexample.GeminiPrompt;
import com.example.springexample.GeminiService;
import com.example.springexample.Metrics.FpCheckMetric;
import com.google.gson.JsonArray;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MVC_ServiceComputeLikelihoodTest {

    @Mock
    private GeminiService gptService;
    @Mock
    private FpSimilarityScore fpUtils;

    // Настоящий счётчик поверх SimpleMeterRegistry, а не мок: тест обязан проверять
    // ИМЯ ряда, потому что имя — контракт с keepMetrics и правилами алертинга.
    // Порядок объявления полей значим: registry инициализируется раньше метрики.
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    @Spy
    private FpCheckMetric fpCheckMetric = new FpCheckMetric(registry);

    @InjectMocks
    private MVC_Service mvcService;

    private void stubAi(double heuristicScore, String aiProbability) {
        when(fpUtils.similarCheck(any(), any())).thenReturn(heuristicScore);
        when(gptService.BuildSecurityCheckPrompt(any(), any()))
                .thenReturn(new GeminiPrompt("sys", new JsonArray()));
        when(gptService.aiSecurePredict(any())).thenReturn(Mono.just(aiProbability));
    }

    private double degraded(String reason) {
        return registry.get(FpCheckMetric.METRIC).tag("reason", reason).counter().count();
    }

    @Test
    void passesWhenAverageAboveThreshold() {
        stubAi(40.0, "90"); // (90 + 40) / 2 = 65 >= 60
        assertTrue(mvcService.computeLikelihood(null, null, fpUtils));
    }

    @Test
    void failsWhenAverageBelowThreshold() {
        // До фикса "20" + 40.0 конкатенировалось в "2040.0" и проверка проходила всегда
        stubAi(40.0, "20"); // (20 + 40) / 2 = 30 < 60
        assertFalse(mvcService.computeLikelihood(null, null, fpUtils));
    }

    @Test
    void fallsBackToHeuristicWhenAiFails() {
        when(fpUtils.similarCheck(any(), any())).thenReturn(75.0);
        when(gptService.BuildSecurityCheckPrompt(any(), any()))
                .thenReturn(new GeminiPrompt("sys", new JsonArray()));
        when(gptService.aiSecurePredict(any())).thenReturn(Mono.error(new RuntimeException("ai down")));
        assertTrue(mvcService.computeLikelihood(null, null, fpUtils));
    }

    /**
     * Что делает: BuildSecurityCheckPrompt бросает исключение (строка 299 старого кода —
     * она стояла ВНЕ try).
     * Что проверяет: метод не бросает наружу, решает по эвристике 75 -> true,
     * инкрементит ряд reason="ai".
     * Зачем: это одна из трёх строк, чьё исключение уходило мимо фолбэка и становилось
     * 500 на /exchangeTokens (beads kz6).
     */
    @Test
    void fallsBackToHeuristicWhenPromptBuildThrows() {
        when(fpUtils.similarCheck(any(), any())).thenReturn(75.0);
        when(gptService.BuildSecurityCheckPrompt(any(), any()))
                .thenThrow(new RuntimeException("prompt build failed"));

        assertTrue(mvcService.computeLikelihood(null, null, fpUtils));
        assertEquals(1.0, degraded(FpCheckMetric.REASON_AI));
    }

    /**
     * Что делает: aiSecurePredict бросает СИНХРОННО, до возврата Mono — ровно так ведёт
     * себя requireApiKey() при пустом GEMINI_API_KEY (GeminiService:47,203).
     * Что проверяет: метод не бросает наружу, решает по эвристике 75 -> true,
     * инкрементит ряд reason="ai".
     * Зачем: отличается от существующего fallsBackToHeuristicWhenAiFails, который
     * подаёт Mono.error — то есть отказ ВНУТРИ .block(), единственный случай, который
     * старый catch и так ловил. Синхронный бросок он не ловил.
     */
    @Test
    void fallsBackToHeuristicWhenAiCallThrowsSynchronously() {
        when(fpUtils.similarCheck(any(), any())).thenReturn(75.0);
        when(gptService.BuildSecurityCheckPrompt(any(), any()))
                .thenReturn(new GeminiPrompt("sys", new JsonArray()));
        when(gptService.aiSecurePredict(any()))
                .thenThrow(new IllegalStateException("Gemini API key is not set"));

        assertTrue(mvcService.computeLikelihood(null, null, fpUtils));
        assertEquals(1.0, degraded(FpCheckMetric.REASON_AI));
    }

    /**
     * Что делает: aiSecurePredict возвращает Mono.never() — ответ не приходит никогда.
     * Что проверяет: метод возвращается за 10 секунд (таймаут вызова 4 с) с решением
     * по эвристике, инкрементит ряд reason="ai".
     * Зачем: старый .block() без аргумента ждал бесконечно. /exchangeTokens браузер
     * выполняет в фоне каждые 15 минут на вкладку — зависший внешний вызов держал бы
     * запрос столько, сколько молчит Google.
     *
     * <p>Именно assertTimeoutPreemptively, а не assertTimeout: второй НЕ прерывает
     * выполнение, он лишь замеряет его постфактум, поэтому на сломанном коде тест не
     * упал бы, а повесил весь прогон Maven целиком (проверено — так и произошло).
     * Preemptively выполняет вызов в отдельном потоке и снимает его по истечении срока.
     */
    @Test
    void fallsBackToHeuristicWhenAiTimesOut() {
        when(fpUtils.similarCheck(any(), any())).thenReturn(75.0);
        when(gptService.BuildSecurityCheckPrompt(any(), any()))
                .thenReturn(new GeminiPrompt("sys", new JsonArray()));
        when(gptService.aiSecurePredict(any())).thenReturn(Mono.never());

        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertTrue(mvcService.computeLikelihood(null, null, fpUtils)));
        assertEquals(1.0, degraded(FpCheckMetric.REASON_AI));
    }

    /**
     * Что делает: similarCheck бросает исключение (строка 298 старого кода — вне try).
     * Что проверяет: метод возвращает false, а НЕ бросает; инкрементит ряд
     * reason="unavailable"; ряд reason="ai" остаётся нулевым.
     * Зачем: главный тест задачи. Именно это падало в beads uok (JsonSyntaxException на
     * оборванных components) и становилось 500. Fail-closed выбран сознательно: отказ
     * computeLikelihood не сносит сессию (TokensResolver.rotateTokens:124 кидает
     * FORBIDDEN "NotSimilar", запись в Redis жива), то есть цена — один перелогин.
     */
    @Test
    void failsClosedWhenHeuristicThrows() {
        when(fpUtils.similarCheck(any(), any()))
                .thenThrow(new RuntimeException("components parse failed"));

        assertFalse(mvcService.computeLikelihood(null, null, fpUtils));
        assertEquals(1.0, degraded(FpCheckMetric.REASON_UNAVAILABLE));
        assertEquals(0.0, degraded(FpCheckMetric.REASON_AI));
    }

    /**
     * Что делает: similarCheck бросает, при этом GeminiService не настроен вовсе.
     * Что проверяет: false; Gemini не вызывался ни разу.
     * Зачем: фиксирует, что при отсутствующем вердикте эвристики AI не может «спасти»
     * решение. Наивный фикс «занести все три строки в один try» именно это и ломал бы:
     * фолбэк catch опирается на checkresult, которого в этой ветке не существует.
     */
    @Test
    void failsClosedWhenHeuristicThrowsAndAiIsNeverCalled() {
        when(fpUtils.similarCheck(any(), any()))
                .thenThrow(new RuntimeException("components parse failed"));

        assertFalse(mvcService.computeLikelihood(null, null, fpUtils));
        Mockito.verifyNoInteractions(gptService);
    }
}
