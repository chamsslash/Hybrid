package com.example.springexample.Services;

import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringWebFluxTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.context.Context;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppShellRenderTest {

    private SpringWebFluxTemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringWebFluxTemplateEngine e = new SpringWebFluxTemplateEngine();
        e.setTemplateResolver(resolver);
        return e;
    }

    @Test
    void appShellRendersRootAndEntrypoint() {
        Context ctx = new Context();
        Map<String, Object> model = new HashMap<>();
        model.put("nonce", "testnonce");
        ctx.setVariables(model);

        String html = engine().process("app", ctx);

        assertThat(html).contains("id=\"app\"");
        assertThat(html).contains("src=\"/app.js\"");
        assertThat(html).contains("nonce=\"testnonce\"");
    }
}
