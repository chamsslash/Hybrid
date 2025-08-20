package com.example.springexample;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.context.annotation.Primary;
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor;
import org.springframework.web.servlet.support.RequestDataValueProcessor;
import org.springframework.web.servlet.ViewResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.view.ContentNegotiatingViewResolver;
import org.thymeleaf.spring6.ISpringTemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templatemode.TemplateMode;

import java.util.ArrayList;
import java.util.List;

@Configuration

public class MvcConfig implements WebMvcConfigurer { // <--- Мы снова реализуем этот интерфейс

    private final ApplicationContext applicationContext;

    public MvcConfig(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    // ===== ВАЖНО: Мы должны настроить обработку статики (CSS, JS), так как @EnableWebMvc ее отключает =====
//    @Override
//    public void addResourceHandlers(ResourceHandlerRegistry registry) {
//        registry
//                .addResourceHandler("/resources/**", "/static/**", "/css/**", "/js/**", "/images/**")
//                .addResourceLocations(
//                        "classpath:/META-INF/resources/",
//                        "classpath:/resources/static/",
//                        "classpath:/resources",
//                        "classpath:/resources/images/",
//                        "classpath:/static/css/",
//                        "classpath:/public/"
//                );
//    }

//    @Bean(name = "requestDataValueProcessor")
//    public RequestDataValueProcessor mvcRequestDataValueProcessor() {
//         return new org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor();
//    }

    @Primary
    @Bean(name = "mvcTemplateResolver") // 1. Даем имя резолверу
    public SpringResourceTemplateResolver mvcTemplateResolver() {
        SpringResourceTemplateResolver templateResolver = new SpringResourceTemplateResolver();
        templateResolver.setApplicationContext(applicationContext);
        templateResolver.setPrefix("classpath:/templates/");
        templateResolver.setSuffix(".html");
        templateResolver.setTemplateMode(TemplateMode.HTML);
        templateResolver.setCharacterEncoding("UTF-8");
        templateResolver.setCacheable(false);
        return templateResolver;
    }

    @Primary
    @Bean(name = "mvcTemplateEngine") // 2. Даем имя движку
    public ISpringTemplateEngine mvcTemplateEngine(
            // и явно просим резолвер по имени
            @Qualifier("mvcTemplateResolver") SpringResourceTemplateResolver templateResolver) {
        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(templateResolver);
        templateEngine.setEnableSpringELCompiler(true);
        return templateEngine;
    }

    @Primary
    @Bean(name = "viewResolver") // 3. Это главный ViewResolver для MVC
    public ViewResolver viewResolver(
            // и он должен использовать только движок MVC
            @Qualifier("mvcTemplateEngine") ISpringTemplateEngine templateEngine) {
        ThymeleafViewResolver thymeleafResolver = new ThymeleafViewResolver();
        thymeleafResolver.setTemplateEngine(templateEngine);
        thymeleafResolver.setCharacterEncoding("UTF-8");

        // ContentNegotiatingViewResolver здесь не обязателен, если у вас только Thymeleaf.
        // Но если он нужен, то конфигурация правильная.
        ContentNegotiatingViewResolver mainResolver = new ContentNegotiatingViewResolver();
        mainResolver.setViewResolvers(List.of(thymeleafResolver));
        return mainResolver;
    }
//    @Bean(name = "requestDataValueProcessor")
//    public org.springframework.web.servlet.support.RequestDataValueProcessor servletCsrfProcessor() {
//        return new org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor();
//    }
}