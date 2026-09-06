package com.example.springexample.Utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Скоринг схожести отпечатков: устойчивость к незаполненным сетевым полям (beads kz6).
 *
 * <p>Блок начисления баллов за ASN/Org был единственным участком метода без единой
 * проверки на {@code null}, в отличие от соседних {@code ip}, {@code country},
 * {@code city}, {@code secureUUID}, {@code visitorId}. NPE оттуда уходил мимо фолбэка
 * в {@code MVC_Service.computeLikelihood} и превращался в 500 на {@code /exchangeTokens}.
 */
class FpSimilarityScoreTest {

    private final FpSimilarityScore scorer = new FpSimilarityScore();

    private FpSimilarityScore.ClientMeta meta(String asn, String org, String ptr) {
        return new FpSimilarityScore.ClientMeta(
                "1.2.3.4", "RU", "Moscow", asn, org, "visitor-1", "{}", "uuid-1", ptr);
    }

    /**
     * Что делает: сравнивает два отпечатка, у которых asn/org/ptr равны null у ОБОИХ.
     * Что проверяет: исключения нет, балл конечный и неотрицательный.
     * Зачем: {@code asn.split(" ")} и {@code normalize(ptr)} разыменовывали null
     * напрямую — это готовый NPE на пути аутентификации.
     */
    @Test
    void survivesNullNetworkFieldsOnBothSides() {
        FpSimilarityScore.ClientMeta both = meta(null, null, null);

        assertThatCode(() -> scorer.similarCheck(both, both)).doesNotThrowAnyException();
        assertThat(scorer.similarCheck(both, both)).isGreaterThanOrEqualTo(0.0);
    }

    /**
     * Что делает: сравнивает отпечаток с заполненными сетевыми полями и отпечаток,
     * где они пусты.
     * Что проверяет: исключения нет.
     * Зачем: асимметричный случай реальнее симметричного — сохранённая сессия могла быть
     * записана до того, как поля начали заполняться.
     */
    @Test
    void survivesNullNetworkFieldsOnOneSide() {
        FpSimilarityScore.ClientMeta filled = meta("AS1299 Telia", "Telia", "ptr.telia.net");
        FpSimilarityScore.ClientMeta empty = meta(null, null, null);

        assertThatCode(() -> scorer.similarCheck(filled, empty)).doesNotThrowAnyException();
        assertThatCode(() -> scorer.similarCheck(empty, filled)).doesNotThrowAnyException();
    }

    /**
     * Что делает: сравнивает два одинаковых отпечатка с заполненными сетевыми полями.
     * Что проверяет: балл строго выше, чем у той же пары с обнулёнными asn/org/ptr.
     * Зачем: страховка от «починки» через удаление блока целиком — guard обязан
     * пропускать заполненный случай, а не гасить его вместе с пустым.
     */
    @Test
    void filledNetworkFieldsStillScoreHigherThanEmptyOnes() {
        FpSimilarityScore.ClientMeta filled = meta("AS1299 Telia", "Telia", "telia");
        FpSimilarityScore.ClientMeta empty = meta(null, null, null);

        assertThat(scorer.similarCheck(filled, filled))
                .isGreaterThan(scorer.similarCheck(empty, empty));
    }
}
