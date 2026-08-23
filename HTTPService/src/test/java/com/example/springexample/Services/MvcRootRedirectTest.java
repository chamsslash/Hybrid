package com.example.springexample.Services;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Стережёт корневой маршрут (beads 9kn). Маппинга на "/" не существовало, и голый хост
 * отдавал сырую страницу Tomcat «HTTP Status 404 – Not Found» вместо точки входа.
 *
 * Тест standalone: GetRoot не трогает ни одной зависимости контроллера, поэтому поднимать
 * контекст не нужно — проверяется ровно наличие маппинга и цель редиректа.
 */
class MvcRootRedirectTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MVC_Service()).build();

    @Test
    void rootRedirectsToWelcome() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/welcome"));
    }
}
