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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers-интеграция идемпотентности вставки сообщения (beads myl).
 *
 * Юнит-теста с моком репозитория здесь принципиально недостаточно: уникальность
 * обеспечивает уникальный индекс Postgres, а не Java-код. Поэтому схема поднимается
 * тем же самым Liquibase-changelog'ом, что и в проде (`db.changelog-master.yaml`) —
 * тест заодно стережёт и саму миграцию v2, а не только SQL репозитория.
 *
 * ТРЕБУЕТ Docker. В обычный `mvn test` не попадает дважды: по имени (`*IT` вне
 * surefire-шаблонов `*Test`) и по тегу (`excludedGroups`). Запуск явно:
 * `mvn test -Dtest=MessageIdempotencyIT -DexcludedGroups=` — оба ключа обязательны,
 * `-Dtest` пробивает шаблон имён, `-DexcludedGroups=` снимает фильтр по тегу.
 */
@Tag("integration")
@Testcontainers
class MessageIdempotencyIT {

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
        // на FK, а не на том, что тест проверяет.
        execute("INSERT INTO users (id, name) VALUES (1, 'Дима')");
        execute("INSERT INTO chat (id, title) VALUES (1, 'чат')");
        repository = new ReactiveRepository(databaseClient);
    }

    /**
     * Ровно тот сценарий, ради которого заведён message_id: Kafka даёт at-least-once,
     * и переигранная запись приходит в консьюмер вторым разом с ТЕМ ЖЕ телом.
     */
    @Test
    void replayedMessageWithSameIdLeavesSingleRow() {
        String messageId = "11111111-1111-1111-1111-111111111111";

        insert("привет", messageId);
        insert("привет", messageId);

        assertThat(countMessages()).isEqualTo(1);
    }

    /**
     * Обратная сторона того же контракта: два одинаковых сообщения подряд от одного
     * человека — нормальный сценарий, а не дубль. Дедупликация обязана идти по id,
     * а не по содержимому, иначе второе «ага» пользователя тихо пропадало бы.
     */
    @Test
    void identicalTextWithDifferentIdsInsertsBothRows() {
        insert("ага", "22222222-2222-2222-2222-222222222222");
        insert("ага", "33333333-3333-3333-3333-333333333333");

        assertThat(countMessages()).isEqualTo(2);
    }

    /**
     * Легаси-путь: в бэклоге топика лежат записи, сделанные до появления message_id.
     * У них id нет и не будет, защиты от дублей для них тоже нет — но консьюмер обязан
     * их вставлять, а не падать. В Postgres уникальный индекс допускает сколько угодно
     * NULL, поэтому колонка нарочно nullable и ON CONFLICT по ней просто не срабатывает.
     */
    @Test
    void legacyMessageWithoutIdIsStillInserted() {
        insert("старое сообщение", null);
        insert("старое сообщение", null);

        assertThat(countMessages()).isEqualTo(2);
    }

    private void insert(String text, String messageId) {
        repository.insertMessage(1L, 1L, text, Instant.parse("2026-08-21T10:00:00Z"), messageId).block();
    }

    private long countMessages() {
        Long count = databaseClient.sql("SELECT count(*) FROM message")
                .map((row, meta) -> row.get(0, Long.class))
                .one()
                .block();
        return count == null ? 0L : count;
    }

    private static void execute(String sql) {
        databaseClient.sql(sql).fetch().rowsUpdated().block();
    }
}
