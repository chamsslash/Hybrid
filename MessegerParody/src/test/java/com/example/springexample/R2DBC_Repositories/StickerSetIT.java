package com.example.springexample.R2DBC_Repositories;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers-интеграция личного набора стикеров (beads a22).
 *
 * Набор — не таблица, а производная от истории сообщений: GROUP BY по sticker_key с
 * ORDER BY MAX(time_stamp) DESC. Весь смысл фичи «набор» живёт в этом SQL, а не в Java,
 * поэтому мок репозитория здесь ничего бы не стерёг. Схема поднимается тем же
 * changelog'ом, что и в проде, так что тест заодно проверяет миграцию v4 (колонка
 * message.sticker_key вообще существует и nullable).
 *
 * ТРЕБУЕТ Docker. В обычный {@code mvn test} не попадает дважды: по имени ({@code *IT}
 * вне surefire-шаблонов {@code *Test}) и по тегу ({@code excludedGroups}). Запуск явно:
 * {@code mvn test -Dtest=StickerSetIT -DexcludedGroups=} — оба ключа обязательны.
 */
@Tag("integration")
@Testcontainers
class StickerSetIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    private static DatabaseClient databaseClient;

    private ReactiveRepository repository;

    @BeforeAll
    static void applyProductionChangelog() throws Exception {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.setResourceLoader(new DefaultResourceLoader());
        liquibase.afterPropertiesSet();

        ConnectionFactory connectionFactory = ConnectionFactories.get(String.format(
                "r2dbc:postgresql://%s:%s@%s:%d/%s",
                POSTGRES.getUsername(), POSTGRES.getPassword(),
                POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), POSTGRES.getDatabaseName()));
        databaseClient = DatabaseClient.create(connectionFactory);
    }

    @BeforeEach
    void resetData() {
        execute("TRUNCATE message, user_chat, chat, users RESTART IDENTITY CASCADE");
        // message ссылается внешними ключами на users и chat — без этих строк INSERT упал бы
        // на FK, а не на том, что тест проверяет. Двое пользователей: набор личный, и это
        // надо иметь чем проверить. Два чата: набор общий на все чаты, и это тоже.
        execute("INSERT INTO users (id, name) VALUES (1, 'Дима'), (2, 'Оля')");
        execute("INSERT INTO chat (id, title) VALUES (1, 'чат'), (2, 'другой чат')");
        repository = new ReactiveRepository(databaseClient);
    }

    /**
     * Порядок набора — по убыванию ПОСЛЕДНЕГО использования, а не первого. Пользователь
     * ждёт, что стикер, которым он только что пошутил, лежит первым, даже если впервые
     * отправил его год назад.
     */
    @Test
    void setIsOrderedByLastUseDescending() {
        insertSticker(1L, 1L, "sticker/1/old.png", "2026-09-01T10:00:00Z");
        insertSticker(1L, 1L, "sticker/1/fresh.png", "2026-09-20T10:00:00Z");
        insertSticker(1L, 1L, "sticker/1/middle.png", "2026-09-10T10:00:00Z");

        assertThat(stickersOf(1L))
                .containsExactly("sticker/1/fresh.png", "sticker/1/middle.png", "sticker/1/old.png");
    }

    /**
     * Дубли схлопываются: один и тот же стикер, отправленный десять раз, — это одна
     * позиция набора, а не десять. Без GROUP BY панель через неделю состояла бы из копий
     * одной картинки.
     */
    @Test
    void repeatedStickerCollapsesToSingleEntryAtItsLastUse() {
        insertSticker(1L, 1L, "sticker/1/cat.png", "2026-09-01T10:00:00Z");
        insertSticker(1L, 2L, "sticker/1/dog.png", "2026-09-05T10:00:00Z");
        // Тот же cat, но позже и в другом чате: набор общий на все чаты, и повторная
        // отправка обязана поднять позицию, а не завести вторую.
        insertSticker(1L, 2L, "sticker/1/cat.png", "2026-09-09T10:00:00Z");

        assertThat(stickersOf(1L)).containsExactly("sticker/1/cat.png", "sticker/1/dog.png");
    }

    /**
     * Набор личный. Чужие стикеры в него не попадают, даже если отправлены в чат, где
     * пользователь состоит: ACL на чтение пускает любого аутентифицированного к
     * {@code sticker/...}, так что протечка сюда сразу стала бы протечкой в UI.
     */
    @Test
    void setContainsOnlyOwnStickers() {
        insertSticker(1L, 1L, "sticker/1/mine.png", "2026-09-01T10:00:00Z");
        insertSticker(2L, 1L, "sticker/2/foreign.png", "2026-09-02T10:00:00Z");

        assertThat(stickersOf(1L)).containsExactly("sticker/1/mine.png");
        assertThat(stickersOf(2L)).containsExactly("sticker/2/foreign.png");
    }

    /** Обычные текстовые сообщения в набор не попадают — у них sticker_key равен NULL. */
    @Test
    void textMessagesAreNotPartOfTheSet() {
        repository.insertMessage(1L, 1L, "привет", Instant.parse("2026-09-01T10:00:00Z"),
                "55555555-5555-5555-5555-555555555555", null).block();

        assertThat(stickersOf(1L)).isEmpty();
    }

    private void insertSticker(long userId, long chatId, String stickerKey, String timestamp) {
        repository.insertMessage(chatId, userId, "", Instant.parse(timestamp),
                java.util.UUID.randomUUID().toString(), stickerKey).block();
    }

    private List<String> stickersOf(long userId) {
        return repository.findStickerKeysByUserId(userId).collectList().block();
    }

    private static void execute(String sql) {
        databaseClient.sql(sql).fetch().rowsUpdated().block();
    }
}
