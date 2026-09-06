package com.example.springexample;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Граница цепочки картинок (beads cbq).
 *
 * <p>Отдельная {@code SecurityFilterChain} существует ради одной вещи — снятого
 * {@code cacheControl}, чтобы 30-дневная директива контроллера перестала склеиваться
 * с {@code no-store} от Spring Security. Ровно поэтому ширина её {@code securityMatcher}
 * — это решение о БЕЗОПАСНОСТИ, а не о маршрутизации: всё, что попадает под матчер,
 * теряет {@code no-store} и становится кешируемым в браузере.
 *
 * <p>Тест пришивает границу к конкретным путям. Расширь кто-нибудь шаблон до
 * {@code /api/**} — и кешироваться начнут {@code /api/me} и {@code /api/chat}, то есть
 * содержимое переписки осядет на диске у пользователя. Отсюда и обратные проверки:
 * они дороже прямой, потому что сторожат то, чего быть НЕ должно.
 *
 * <p>Проверяется именно матчер, а не заголовки ответа: заголовки — это поведение всей
 * цепочки фильтров, и его проверяет живой замер из приёмки тикета
 * ({@code curl} по {@code /api/images/...} против {@code /api/me}). Здесь пришит тот
 * единственный вход, от которого зависит, к какому ответу это поведение применится.
 */
class MvcSecurityImagesChainBoundaryTest {

    private boolean matches(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        // AntPathRequestMatcher складывает путь из getServletPath() + getPathInfo(), а
        // конструктор MockHttpServletRequest заполняет только requestURI. Без этой
        // строки путь получается пустым: положительная проверка краснеет, а все
        // отрицательные проходят ВХОЛОСТУЮ — тест выглядел бы зелёным, ничего не сторожа.
        request.setServletPath(uri);
        return MvcSecurityConfig.IMAGES_MATCHER.matches(request);
    }

    /**
     * Что делает: подаёт реальный путь отдачи картинки, включая вложенный ключ MinIO.
     * Что проверяет: матчер срабатывает.
     * Зачем: если бы не срабатывал, фикс просто не применялся бы — 30-дневная директива
     * так и осталась бы склеена с no-store, и тикет считался бы закрытым зря.
     */
    @Test
    void matchesImageObjectPaths() {
        assertThat(matches("/api/images/userimage/7/53ae5022-d70f-4c59-984a-b6281f3a2329.png")).isTrue();
        assertThat(matches("/api/images/chatimage/42/0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0.jpg")).isTrue();
    }

    /**
     * Что делает: подаёт остальные эндпоинты SPA-API.
     * Что проверяет: матчер НЕ срабатывает.
     * Зачем: главная проверка файла. Эти ответы обязаны сохранить no-store — /api/chat
     * и /api/chatlist несут содержимое переписки, /api/me — данные пользователя.
     * Расширение шаблона до /api/** уронит именно этот тест.
     */
    @Test
    void doesNotMatchOtherApiEndpoints() {
        assertThat(matches("/api/me")).isFalse();
        assertThat(matches("/api/chat")).isFalse();
        assertThat(matches("/api/chatlist")).isFalse();
        assertThat(matches("/api/imagesomething")).isFalse();
    }

    /**
     * Что делает: подаёт путь аутентификации и статику.
     * Что проверяет: матчер НЕ срабатывает.
     * Зачем: /exchangeTokens обслуживается общей цепочкой со своим CSRF и делегирующим
     * entry point; утечь под цепочку картинок он не должен ни при каком расширении
     * шаблона.
     */
    @Test
    void doesNotMatchAuthOrStaticPaths() {
        assertThat(matches("/exchangeTokens")).isFalse();
        assertThat(matches("/images/logo.png")).isFalse();
        assertThat(matches("/welcome")).isFalse();
    }
}
