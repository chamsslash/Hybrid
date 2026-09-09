package com.example.springexample; // Убедитесь, что пакет правильный

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.http.HttpStatus;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.support.RequestDataValueProcessor;

import java.util.LinkedHashMap;
import java.util.List;


@RequiredArgsConstructor
@Configuration
@EnableWebSecurity // Аннотация ТОЛЬКО для MVC
@Order(2) // Низкий приоритет
public class MvcSecurityConfig  {

    private final MvcJwtAuthFilter mvcJwtAuthFilter;

    @Bean(name = "requestDataValueProcessor")
    @Primary
    public RequestDataValueProcessor requestDataValueProcessor() {
        return new CsrfRequestDataValueProcessor();
    }

    /**
     * Отдельная цепочка для эндпоинтов актуатора (beads c2k).
     *
     * Заменяет прежний webSecurityCustomizer, который выводил /actuator/prometheus
     * из-под Security целиком через web.ignoring(). Тот подход был вынужденным:
     * readinessProbe висела на этом эндпоинте, потому что /actuator/health закрыт
     * Security и отвечает 302. Побочным эффектом весь дамп метрик отдавался наружу
     * через ingress без аутентификации.
     *
     * Теперь актуатор живёт на management-порту 8090, который в ingress не смотрит
     * и в Service не публикуется. Но цепочка всё равно нужна: management.server.port
     * поднимает ДОЧЕРНИЙ контекст, а бины родительского он наследует — включая
     * mvcFilterChain ниже, у которой нет securityMatcher и стоит
     * .anyRequest().authenticated(). Без отдельной цепочки запрос к актуатору
     * уходил бы в delegatingEntryPoint и получал редирект на /welcome; в AuthService
     * ровно этот сценарий воспроизвёлся живьём и вернул страницу входа Google.
     *
     * permitAll безопасен именно из-за разделения портов, а не сам по себе.
     * HIGHEST_PRECEDENCE обязателен: цепочки проверяются по порядку, иначе первой
     * сматчилась бы catch-all цепочка ниже.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * Путь отдачи объектов из MinIO. Вынесен в константу, потому что его же проверяет
     * тест: от ширины этого шаблона зависит, какие ответы теряют {@code no-store}.
     */
    public static final String IMAGES_PATH = "/api/images/**";

    /** Матчер цепочки картинок. Публичный ради теста границы (beads cbq). */
    public static final RequestMatcher IMAGES_MATCHER = new AntPathRequestMatcher(IMAGES_PATH);

    /**
     * Отдельная цепочка для картинок — ровно ради одной строки: снятого
     * {@code cacheControl} (beads cbq).
     *
     * <p><b>Что было сломано.</b> {@code ApiController.image} выставляет
     * {@code CacheControl.maxAge(30 дней).cachePrivate()}, но это не работало. Замер на
     * живом стенде:
     * <pre>
     * Cache-Control: no-cache, no-store, max-age=0, must-revalidate, max-age=2592000, private
     * Pragma: no-cache
     * Expires: 0
     * </pre>
     * Директивы пишет {@code CacheControlHeadersWriter} из {@code HeaderWriterFilter} —
     * он включён по умолчанию, а {@code headers()} в этом классе не настраивался вовсе.
     * Заголовок не заменяется, а склеивается, и {@code no-store} выигрывает: браузер не
     * имеет права сохранить ответ ни на 30 суток, ни на секунду. Аватарка на 7 МБ
     * перекачивалась при каждой перерисовке списка чатов.
     *
     * <p><b>Почему отдельная цепочка, а не глобальное отключение.</b>
     * {@code headers().cacheControl().disable()} на общей цепочке открыло бы кеширование
     * ВСЕМ ответам, включая {@code /api/me} и {@code /api/chat} с содержимым переписки.
     * Здесь же снятие ограничено {@code securityMatcher}, а всё остальное — включая
     * {@code /api/me}, {@code /api/chat}, {@code /api/chatlist} — по-прежнему обслуживает
     * цепочка ниже и по-прежнему получает {@code no-store}.
     *
     * <p><b>Почему @Order(1).</b> Цепочки проверяются по порядку. Цепочка актуатора выше
     * стоит на {@code HIGHEST_PRECEDENCE}, а {@code mvcFilterChain} ниже — catch-all без
     * {@code securityMatcher}: без явного порядка первой сматчилась бы она, и эта
     * цепочка не отработала бы никогда.
     *
     * <p>Аутентификация здесь настоящая, а не ослабленная: путь закрыт
     * {@code authenticated()}, тот же {@code mvcJwtAuthFilter}, тот же
     * stateless-режим — всё это ставит {@link #applyStatelessJwtAuth}, общий с
     * основной цепочкой, чтобы две цепочки не разъехались при будущих правках.
     * Проверка владения объектом MinIO живёт в контроллере и этой правкой не затронута.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain imagesFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(IMAGES_MATCHER)
                .headers(headers -> headers.cacheControl(cache -> cache.disable()))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                // Bearer-API не подвержен CSRF (нет cookie-аутентификации) — та же
                // причина, по которой /api/** исключён из CSRF в цепочке ниже.
                .csrf(AbstractHttpConfigurer::disable);
        applyStatelessJwtAuth(http, new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED));
        return http.build();
    }

    /**
     * Настройки, обязательные для любой цепочки, обслуживающей аутентифицированные
     * запросы SPA: без сессий, JWT-фильтр до формы логина, свой entry point.
     *
     * <p>Вынесено в общий метод не ради краткости, а ради синхронности: цепочек стало
     * две, и разъехавшийся stateless-режим или потерянный {@code mvcJwtAuthFilter} в
     * одной из них — это тихая дыра, а не заметная поломка.
     */
    private void applyStatelessJwtAuth(HttpSecurity http, AuthenticationEntryPoint entryPoint) throws Exception {
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(RequestCacheConfigurer::disable)
                .addFilterBefore(mvcJwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint));
    }

    @Bean
    public SecurityFilterChain mvcFilterChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setSecure(false);
        // Официальный SPA-паттерн CSRF (Spring Security): SpaCsrfTokenRequestHandler
        // резолвит токен из X-XSRF-TOKEN, а CsrfCookieFilter (ниже) материализует
        // deferred-токен в cookie на каждом ответе — иначе первый POST после
        // загрузки страницы (/exchangeTokens) отбивался 403 из-за рассинхрона (6i5).
        SpaCsrfTokenRequestHandler spaCsrfHandler = new SpaCsrfTokenRequestHandler();

        // SPA-API и AI-assist отдают голый 401 — клиент сам делает refresh+retry (beads 59).
        // Одиночный .authenticationEntryPoint(...) молча перекрывает
        // .defaultAuthenticationEntryPointFor(...), поэтому строим делегирующий
        // entry point вручную: /api/** и /AiAssist -> 401, всё остальное -> /welcome.
        AuthenticationEntryPoint unauthorizedEntryPoint = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
        LinkedHashMap<RequestMatcher, AuthenticationEntryPoint> entryPoints = new LinkedHashMap<>();
        entryPoints.put(new AntPathRequestMatcher("/api/**"), unauthorizedEntryPoint);
        entryPoints.put(new AntPathRequestMatcher("/AiAssist"), unauthorizedEntryPoint);
        DelegatingAuthenticationEntryPoint delegatingEntryPoint = new DelegatingAuthenticationEntryPoint(entryPoints);
        delegatingEntryPoint.setDefaultEntryPoint(new LoginUrlAuthenticationEntryPoint("/welcome"));

        // Stateless + JWT-фильтр + entry point ставит общий метод: те же настройки
        // обязана иметь цепочка картинок выше, и разъехаться они не должны.
        applyStatelessJwtAuth(http, delegatingEntryPoint);

        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                new AntPathRequestMatcher("/js/**"),
                                new AntPathRequestMatcher("/css/**"),
                                new AntPathRequestMatcher("/images/**"),
                                new AntPathRequestMatcher("/*.js"),
                                new AntPathRequestMatcher("/views/**"),
                                new AntPathRequestMatcher("/*.css"),
                                new AntPathRequestMatcher("/error"),
                                new AntPathRequestMatcher("/static/**"),
                                new AntPathRequestMatcher("/verifylogin"),
                                new AntPathRequestMatcher("/welcome"),
                                new AntPathRequestMatcher("/registerpage"),
                                new AntPathRequestMatcher("/createchatpage"),
                                new AntPathRequestMatcher("/"),
                                new AntPathRequestMatcher("/reactive/**"),
                                new AntPathRequestMatcher("/authcallback"),                                new AntPathRequestMatcher("/exchangeTokens"),
                                // SockJS/STOMP-хендшейк публичен: браузерный хендшейк не несёт
                                // Authorization, аутентификация — на STOMP CONNECT
                                // (StompAuthChannelInterceptor). Без этого GET-транспорты
                                // (/info,/websocket,/iframe.html) ловили 302, а POST-xhr — 403,
                                // и соединение падало до CONNECT (beads 58/59).
                                new AntPathRequestMatcher("/*Conn/**"),
                                new AntPathRequestMatcher("/*Conn")
                        ).permitAll()

                        .anyRequest().authenticated()
                )
                .csrf(csrf -> csrf
                        .csrfTokenRepository(repo)
                        .csrfTokenRequestHandler(spaCsrfHandler)
                        // Bearer-API не подвержен CSRF (нет cookie-аутентификации).
                        // SockJS POST-транспорты (/*Conn/.../xhr, xhr_streaming) — тоже:
                        // это часть публичного хендшейка, auth на STOMP CONNECT.
                        .ignoringRequestMatchers(
                                new AntPathRequestMatcher("/api/**"),
                                new AntPathRequestMatcher("/*Conn/**"))
                )
                // Материализует XSRF-TOKEN cookie на каждом ответе (после CsrfFilter,
                // который кладёт deferred-токен в атрибут запроса).
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);

        return http.build();
    }



}
