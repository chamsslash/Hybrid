//package com.example.springexample.Services;
//
//import org.springframework.context.ApplicationContext;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.thymeleaf.spring6.ISpringWebFluxTemplateEngine;
//import org.thymeleaf.spring6.SpringWebFluxTemplateEngine;
//import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
//import org.thymeleaf.templatemode.TemplateMode;
//import org.thymeleaf.templateresolver.ITemplateResolver;
//import org.springframework.web.reactive.result.view.ViewResolver;
//import org.springframework.web.reactive.result.view.thymeleaf.ThymeleafReactiveViewResolver;
//
//@Configuration
//public class ThymeleafConfig {
//
//    /**
//     * Этот бин настраивает, ГДЕ искать шаблоны.
//     */
//    @Bean
//    public ITemplateResolver thymeleafTemplateResolver(ApplicationContext applicationContext) {
//        SpringResourceTemplateResolver resolver = new SpringResourceTemplateResolver();
//        resolver.setApplicationContext(applicationContext);
//        resolver.setPrefix("classpath:/templates/"); // Ваша папка
//        resolver.setSuffix(".html"); // Ваше расширение
//        resolver.setTemplateMode(TemplateMode.HTML);
//        resolver.setCharacterEncoding("UTF-8");
//        resolver.setCacheable(false); // Отключить кэш для разработки, чтобы видеть изменения сразу
//        return resolver;
//    }
//
//    /**
//     * Этот бин - сам движок Thymeleaf для WebFlux.
//     */
//    @Bean
//    public ISpringWebFluxTemplateEngine thymeleafTemplateEngine(ITemplateResolver templateResolver) {
//        SpringWebFluxTemplateEngine engine = new SpringWebFluxTemplateEngine();
//        engine.setTemplateResolver(templateResolver);
//        return engine;
//    }
//
//    /**
//     * А вот и главный бин! Это ViewResolver, который Spring WebFlux будет использовать.
//     * Он связывает все воедино.
//     */
//    @Bean
//    public ViewResolver thymeleafReactiveViewResolver(ISpringWebFluxTemplateEngine templateEngine) {
//        ThymeleafReactiveViewResolver viewResolver = new ThymeleafReactiveViewResolver();
//        viewResolver.setTemplateEngine(templateEngine);
//        // Можно установить и другие параметры, например, Content-Type по умолчанию
//        // viewResolver.setDefaultCharset(StandardCharsets.UTF_8);
//        return viewResolver;
//    }
//}