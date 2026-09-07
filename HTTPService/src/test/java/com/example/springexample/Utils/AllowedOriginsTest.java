package com.example.springexample.Utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Список разрешённых Origin для handshake STOMP/SockJS (beads ybg).
 *
 * <p>Сторожит переход на публичный домен: раньше на эндпоинтах стояло
 * {@code setAllowedOriginPatterns("*")}, и никакой тест не заметил бы, если бы список
 * снова стал пустым, дублирующимся или потерял одну из схем — handshake ломается уже в
 * браузере и молча, кодом 403.
 */
class AllowedOriginsTest {

    @Test
    @DisplayName("Обе схемы хоста попадают в список, http-стенд первым")
    void httpStandListsBothSchemesPrimaryFirst() {
        List<String> origins = AllowedOrigins.forHost("http", "myapp.localtest.me", "");

        assertThat(origins).containsExactly(
                "http://myapp.localtest.me",
                "https://myapp.localtest.me");
    }

    @Test
    @DisplayName("На https первым идёт https, но http-вариант остаётся — миграция идёт не мгновенно")
    void httpsStandKeepsHttpVariantForMigrationWindow() {
        List<String> origins = AllowedOrigins.forHost("https", "gyattalert.duckdns.org", "");

        assertThat(origins).containsExactly(
                "https://gyattalert.duckdns.org",
                "http://gyattalert.duckdns.org");
    }

    @Test
    @DisplayName("Дополнительные origin'ы добавляются, пустые элементы отбрасываются")
    void extraOriginsAreAppendedAndBlanksDropped() {
        List<String> origins = AllowedOrigins.forHost("http", "myapp.localtest.me",
                " http://localhost , , https://front.example ");

        assertThat(origins).containsExactly(
                "http://myapp.localtest.me",
                "https://myapp.localtest.me",
                "http://localhost",
                "https://front.example");
    }

    @Test
    @DisplayName("Дубликат хоста в extras не удваивает элемент списка")
    void duplicateOriginIsNotRepeated() {
        List<String> origins = AllowedOrigins.forHost("http", "myapp.localtest.me",
                "http://myapp.localtest.me");

        assertThat(origins).containsExactly(
                "http://myapp.localtest.me",
                "https://myapp.localtest.me");
    }

    @Test
    @DisplayName("Пустой хост не превращается в origin вида \"http://\"")
    void blankHostProducesNoSchemeOnlyOrigin() {
        List<String> origins = AllowedOrigins.forHost("http", "  ", "http://localhost");

        assertThat(origins).containsExactly("http://localhost");
    }

    @Test
    @DisplayName("null в extras означает \"дополнительных нет\", а не падение")
    void nullExtrasAreTreatedAsEmpty() {
        List<String> origins = AllowedOrigins.forHost("https", "gyattalert.duckdns.org", null);

        assertThat(origins).containsExactly(
                "https://gyattalert.duckdns.org",
                "http://gyattalert.duckdns.org");
    }
}
