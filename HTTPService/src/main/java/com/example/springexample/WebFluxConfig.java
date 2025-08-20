package com.example.springexample;

import com.example.springexample.Services.WEBFLUX_Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ServletHttpHandlerAdapter;
import org.springframework.security.web.context.AbstractSecurityWebApplicationInitializer;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.reactive.result.view.CsrfRequestDataValueProcessor;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.RequestDataValueProcessor;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import org.springframework.web.server.session.WebSessionManager;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.ISpringWebFluxTemplateEngine;
import org.thymeleaf.spring6.SpringWebFluxTemplateEngine;
import org.thymeleaf.spring6.view.reactive.ThymeleafReactiveViewResolver;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.ITemplateResolver;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static org.springframework.web.reactive.function.server.RouterFunctions.toHttpHandler;

@Configuration
public class WebFluxConfig {
    @Bean
    public HttpHandler webFluxHttpHandler(
            RouterFunction<ServerResponse> chatListRouter,
            RouterFunction<ServerResponse> staticResourceRouter,
            ReactiveHybridAuthFilter frankensteinSecurityFilter,
            RouterFunction<ServerResponse> chatPageRouter,
            RouterFunction<ServerResponse> registerHandle, // <-- 1. ДОБАВЛЕНО ЗДЕСЬ
            RouterFunction<ServerResponse> loginHandle,   // <-- И этот тоже, на будущее
            SecurityWebFilterChain securityWebFilterChain,

            RouterFunction<ServerResponse> createchatHandle,
            @Qualifier("thymeleafReactiveViewResolver") ViewResolver reactiveViewResolver,
            WebFilter webfluxRequestDataValueProcessorFilter) {
        HandlerStrategies.Builder strategiesBuilder = HandlerStrategies.builder();
        strategiesBuilder.viewResolver(reactiveViewResolver);
        HandlerStrategies strategies = strategiesBuilder.build();
        WebSessionManager sessionManager = exchange -> Mono.empty();
        RouterFunction<?> combinedRoutes = chatListRouter
                .and(chatPageRouter)
                .and(createchatHandle)
                .and(registerHandle) // <-- 2. ДОБАВЛЕНО ЗДЕСЬ
                .and(loginHandle)    // <-- И этот
                .and(staticResourceRouter); // Статику лучше ставить в конец
        WebHandler routerHandler = (WebHandler) toHttpHandler(combinedRoutes, strategies);
        WebFilter securityFilter = new WebFilterChainProxy(List.of(securityWebFilterChain));
        return WebHttpHandlerBuilder
                .webHandler(routerHandler)
                .sessionManager(sessionManager)
                .filters(filters -> {
                    filters.add(securityFilter);

        }).build();
    }

    /// Настраивает резолвер шаблонов для Thymeleaf. Он будет искать HTML-файлы по пути templates/*.html и парсить их как HTML.
    @Bean(name = "webfluxTemplateResolver") // 1. Даем имя резолверу (без @Primary)
    public ITemplateResolver thymeleafTemplateResolver() {
        final ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);
        return resolver;
    }
    @Bean
    public WebFilter webfluxRequestDataValueProcessorFilter() {

        return (exchange, chain) -> {
            exchange.getAttributes().put(
                    "requestDataValueProcessor",
                    new org.springframework.security.web.reactive.result.view.CsrfRequestDataValueProcessor()
            );
            return chain.filter(exchange);
        };
    }
    /// Он компилирует шаблоны в финальные HTML-страницы.
    //Он берет найденный Resolver-ом HTML-файл и данные из вашего контроллера, а затем "собирает" из них финальную HTML-страницу, заменяя переменные (th:text, th:each и т.д.) на реальные значения.
    @Bean(name = "thymeleafTemplateEngine") // 2. Даем имя движку (без @Primary)
    public ISpringWebFluxTemplateEngine thymeleafTemplateEngine(
            // и явно просим резолвер по имени
            @Qualifier("webfluxTemplateResolver") ITemplateResolver templateResolver) {
        SpringWebFluxTemplateEngine engine = new SpringWebFluxTemplateEngine();
        engine.setTemplateResolver(templateResolver);
        return engine;
    }
/// Использует движок Thymeleaf, чтобы преобразовать ModelAndView или шаблонные маршруты (ServerResponse.render(...)) в HTML.
    @Bean(name = "thymeleafReactiveViewResolver") // 3. Даем имя ViewResolver'у (без @Primary)
    public ViewResolver thymeleafViewResolver(
            // и он должен использовать только движок WebFlux
            @Qualifier("thymeleafTemplateEngine") ISpringWebFluxTemplateEngine templateEngine) {
        ThymeleafReactiveViewResolver viewResolver = new ThymeleafReactiveViewResolver();
        viewResolver.setTemplateEngine(templateEngine);
        return viewResolver;
    }


    @Bean
    public  RouterFunction<ServerResponse> chatPageRouter(WEBFLUX_Service wf_handler,ISpringWebFluxTemplateEngine templateEngine){
        return route(GET("/chat"),req->wf_handler.renderChatPage(req,templateEngine));
    }
    @Bean
    public RouterFunction<ServerResponse> createchatHandle(
            WEBFLUX_Service chatListHandler) {
        return route(POST("/createchat"),req->chatListHandler.handleCreateChat(req));
    }
    @Bean
    public RouterFunction<ServerResponse> loginHandle(
            WEBFLUX_Service chatListHandler) {
        return route(POST("/login"),req->chatListHandler.loginHandle(req));
    }
    @Bean
    public RouterFunction<ServerResponse> registerHandle(
            WEBFLUX_Service chatListHandler) {
        return route(POST("/register"),req->chatListHandler.registerHandle(req));
    }

    @Bean
    public RouterFunction<ServerResponse> chatListRouter(
            WEBFLUX_Service chatListHandler,
            ISpringWebFluxTemplateEngine templateEngine) { // <-- Инжектируем сюда и сервис, и движок

        return route(
                GET("/chatlist"),
                // Вместо ссылки на метод, используем лямбду,
                // чтобы передать движок в ваш обновленный метод.
                request -> chatListHandler.getChatList(request,templateEngine)
        );
    }

    @Bean
    public RouterFunction<ServerResponse> staticResourceRouter() {

        return RouterFunctions.resources("/**", new ClassPathResource("static/"));
    }

//    @Bean
//    public HttpHandler webFluxHttpHandler(RouterFunction<ServerResponse> chatListRouter,
//                                          RouterFunction<ServerResponse> staticResourceRouter,
//                                          ViewResolver thymeleafViewResolver) {
//
//        // ИСПРАВЛЕНИЕ: Объединяем оба роутера. Сначала проверяется API, потом статика.
//        RouterFunction<?> combinedRoutes = chatListRouter.and(staticResourceRouter);
//
//        HandlerStrategies strategies = HandlerStrategies.builder()
//                .viewResolver(thymeleafViewResolver)
//                .build();
//
//        return toHttpHandler(combinedRoutes, strategies);
//    }

    // берет реактивный HttpHandler (бин webFluxHttpHandler) и оборачивает его в класс ServletHttpHandlerAdapter, который реализует стандартный интерфейс javax.servlet.http.HttpServlet. Теперь ваш реактивный код "выглядит" как обычный сервлет
    @Bean
    public ServletHttpHandlerAdapter webFluxServletAdapter(HttpHandler webFluxHttpHandler) {
        return new ServletHttpHandlerAdapter(webFluxHttpHandler);
    }
    // Создает регистрацию для нашего сервлета-адаптера и говорит контейнеру: "Все запросы, которые приходят на URL, начинающийся с /reactive/, нужно отправлять на обработку этому сервлету".
    @Bean
    public ServletRegistrationBean<ServletHttpHandlerAdapter> webFluxServletRegistration(ServletHttpHandlerAdapter adapter) {
        ServletRegistrationBean<ServletHttpHandlerAdapter> registration = new ServletRegistrationBean<>(adapter, "/reactive/*");
        registration.setName("webflux-servlet");
        registration.setLoadOnStartup(1);
        return registration;
    }


}