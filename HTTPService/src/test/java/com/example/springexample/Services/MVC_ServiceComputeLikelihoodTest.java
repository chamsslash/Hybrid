package com.example.springexample.Services;

import com.example.springexample.Utils.FpSimilarityScore;
import com.example.springexample.GeminiPrompt;
import com.example.springexample.GeminiService;
import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MVC_ServiceComputeLikelihoodTest {

    @Mock
    private GeminiService gptService;
    @Mock
    private FpSimilarityScore fpUtils;

    @InjectMocks
    private MVC_Service mvcService;

    private void stubAi(double heuristicScore, String aiProbability) {
        when(fpUtils.similarCheck(any(), any())).thenReturn(heuristicScore);
        when(gptService.BuildSecurityCheckPrompt(any(), any()))
                .thenReturn(new GeminiPrompt("sys", new JsonArray()));
        when(gptService.aiSecurePredict(any())).thenReturn(Mono.just(aiProbability));
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
}
