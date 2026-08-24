package com.example.springexample.Services;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Заголовок Content-Security-Policy как контракт (beads szm).
 *
 * CspNonce — общая точка для обеих половин приложения: сервлетной (MVC_Service,
 * маршруты /welcome, /registerpage, /authcallback) и реактивной (WEBFLUX_Service,
 * маршруты /reactive/*). Поэтому проверять сам заголовок дешевле и надёжнее здесь, а
 * не гонять шесть маршрутов: расхождение политики между половинами и было тем багом,
 * из-за которого CspNonce появился.
 *
 * Тесты стерегут не «строка выглядит правильно», а конкретные режимы отказа, каждый из
 * которых уже случался на стенде.
 */
class CspNonceTest {

    private String header() {
        return CspNonce.headerValue("TESTNONCE");
    }

    @Test
    void enforcementDirectiveIsPresent() {
        // Режим отказа: директива trusted-types объявляет только разрешённые ИМЕНА политик
        // и сама по себе не принуждает ничего. Ровно так и было — политика создавалась,
        // атрибуты nonce стояли, а строку можно было присвоить в innerHTML в обход
        // политики, и '<img src=x onerror=alert(1)>' доезжал до DOM целиком. Принуждение
        // включает только эта директива; убрать её — вернуть защиту в состояние декорации,
        // причём молча: страница продолжит работать как ни в чём не бывало.
        assertThat(header()).contains("require-trusted-types-for 'script'");
    }

    @Test
    void dompurifyPolicyNameIsAllowedAlongsideDefault() {
        // Режим отказа, стоивший одного слёгшего стенда: DOMPurify санитизирует, записывая
        // вход в innerHTML временного документа, и под принуждением эта запись — тоже sink.
        // Своей политикой (имя 'dompurify', createHTML сквозной) он её оборачивает, но
        // только если имя разрешено. Не разрешено — createPolicy бросает, DOMPurify тихо
        // остаётся без политики, и внутренняя запись уходит в ДЕФОЛТНУЮ, то есть в него же.
        // Рекурсия DOMPurify -> default -> DOMPurify глушится, sanitize() возвращает пустую
        // строку на любой разметке, и приложение отрисовывается пустым.
        //
        // Поэтому в allowlist обязаны быть ОБА имени, и убрать 'dompurify' как «лишнее»
        // нельзя: он несущий.
        assertThat(header()).contains("trusted-types default dompurify");
    }

    @Test
    void strictDynamicIsPresentForDynamicViewImports() {
        // app.js — ES-модуль, догружающий вью динамическим import(). Без 'strict-dynamic'
        // от политики отваливается каждая вью, и SPA перестаёт открывать экраны.
        assertThat(header()).contains("'strict-dynamic'");
    }

    @Test
    void nonceIsInterpolatedIntoScriptSrc() {
        assertThat(header()).contains("script-src 'nonce-TESTNONCE'");
    }

    @Test
    void objectAndBaseUriStayLockedDown() {
        assertThat(header()).contains("object-src 'none'");
        assertThat(header()).contains("base-uri 'none'");
    }

    @Test
    void generatedNoncesAreUnpredictable() {
        // Остальные тесты довольствуются подставленной константой и прошли бы с
        // захардкоженным nonce, а предсказуемый nonce не защищает ни от чего: CSP
        // script-src 'nonce-...' держится ровно на неугадываемости значения.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String nonce = CspNonce.generate();
            assertThat(nonce).isNotBlank();
            seen.add(nonce);
        }
        assertThat(seen).as("50 вызовов дали одинаковые значения").hasSize(50);
    }
}
