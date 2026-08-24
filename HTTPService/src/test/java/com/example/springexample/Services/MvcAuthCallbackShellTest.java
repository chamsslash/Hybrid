package com.example.springexample.Services;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Стережёт, что OAuth-callback Google обслуживается SPA-шеллом, а не отдельным документом
 * (beads j35). До этой ветки `/authcallback` отдавал вьюху `callback` — самостоятельный
 * Thymeleaf-документ со своим спиннером и своим набором CDN-скриптов (в том числе
 * FingerprintJS v3 против v4 в шелле). Побочный эффект отдельного документа был не
 * косметическим: accessToken, положенный после `/verifylogin` в память (`inmemory.js`),
 * умирал при уходе на `redirectUri`, потому что это смена документа, и восстанавливался
 * silent refresh'ем ценой лишнего `/exchangeTokens`. Внутри шелла переход идёт клиентским
 * роутером, документ тот же, память живёт.
 *
 * Тест standalone: `authcallbackpage` не обращается ни к одной зависимости контроллера
 * (в отличие от `/welcome`, который дёргает `csrfToken.getToken()` и без подложенного
 * атрибута падает в standalone-MockMvc с NPE), поэтому поднимать контекст не нужно.
 */
class MvcAuthCallbackShellTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MVC_Service()).build();

    @Test
    void authCallbackRendersAppShell() throws Exception {
        mvc.perform(get("/authcallback").param("code", "one-time-code").param("state", "st-1"))
                .andExpect(status().isOk())
                .andExpect(view().name("app"));
    }

    @Test
    void authCallbackPutsNonceInModelAndCspHeader() throws Exception {
        mvc.perform(get("/authcallback"))
                .andExpect(model().attribute("nonce", notNullValue()))
                .andExpect(header().string("Content-Security-Policy", matchesPattern(".*'nonce-[^']+'.*")));
    }
}
