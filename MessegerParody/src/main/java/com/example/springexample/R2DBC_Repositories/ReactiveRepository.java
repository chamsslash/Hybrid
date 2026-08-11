package com.example.springexample.R2DBC_Repositories;

import com.example.springexample.JPA_Entities.r2dbc_chat;
import com.example.springexample.JPA_Entities.r2dbc_message;
import com.example.springexample.JPA_Entities.RowsMappers.ChatMapper;
import com.example.springexample.JPA_Entities.RowsMappers.MessageMapper;
import com.example.springexample.JPA_Entities.RowsMappers.UserMapper;
import com.example.springexample.JPA_Entities.r2dbc_user;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository; // @Repository включает в себя @Component, поэтому @Component излишен
import reactor.core.publisher.Flux;
import java.util.List;
import reactor.core.publisher.Mono;

@SuppressWarnings({"checkstyle:EmptyLineSeparator", "checkstyle:MissingJavadocType"})
// @Component // Удалено, так как @Repository уже является @Component
@Repository
@RequiredArgsConstructor
public class ReactiveRepository {
    private final DatabaseClient reactiveDb;

    // ИСПОЛЬЗУЕМ ЕДИНОЕ ИМЯ ТАБЛИЦЫ: 'user_chat'
    // Изначальный код использовал и 'user_chat', и 'user_chats'. Это необходимо исправить.
    // Здесь и далее используется 'user_chat'. Убедитесь, что имя таблицы в вашей БД верное.

    public Flux<r2dbc_chat> findAllOrderedChatsByUserId(Long userId) {
        String sql = """
        SELECT c.*
        FROM chat c
        JOIN (
          SELECT uc.chat_id,
                 COUNT(m.id) AS msg_cnt,
                 MAX(m.time_stamp) AS latest_msg
          FROM user_chat uc -- ИСПРАВЛЕНО: имя таблицы
          LEFT JOIN message m ON m.chat_id = uc.chat_id
          WHERE uc.user_id = $1
          GROUP BY uc.chat_id
        ) agg ON agg.chat_id = c.id
        ORDER BY agg.msg_cnt DESC, agg.latest_msg DESC, c.id DESC
    """;

        return reactiveDb.sql(sql)
                .bind(0, userId)
                .map((row, rowMetadata) -> ChatMapper.map(row))
                .all();
    }

    /**
     * Находит чат по названию и точному списку участников.
     * Этот метод был переписан для большей читаемости, надежности и эффективности.
     * Используются именованные параметры (:title, :userIds, :userCount), так как они
     * лучше подходят для работы со списками значений (IN clause) в Spring R2DBC.
     */
    public Mono<r2dbc_chat> findChatByTitleAndExactUsers(String title, List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Mono.empty();
        }

        // Этот SQL-запрос находит чат, который соответствует названию и имеет
        // точное количество указанных пользователей, и никаких других.
        String sql = """
            SELECT c.*
            FROM chat c
            JOIN user_chat uc ON c.id = uc.chat_id
            WHERE c.title = :title
            GROUP BY c.id, c.title, c.image_url -- Группировка по всем полям chats
            HAVING
                -- Условие 1: количество уникальных пользователей в чате равно размеру списка
                COUNT(DISTINCT uc.user_id) = :userCount
                AND
                -- Условие 2: все пользователи из списка присутствуют в чате
                COUNT(DISTINCT CASE WHEN uc.user_id IN (:userIds) THEN uc.user_id ELSE NULL END) = :userCount
            LIMIT 1
        """;

        return reactiveDb.sql(sql)
                .bind("title", title)
                .bind("userCount", userIds.size())
                .bind("userIds", userIds) // Spring R2DBC автоматически раскроет список для 'IN'
                .map((row, meta) -> ChatMapper.map(row))
                .one();
    }

    public Flux<r2dbc_user> findAllUsersByChatId(Long chatId) {
        String sql = """
            SELECT u.*
            FROM users u
            JOIN user_chat uc ON u.id = uc.user_id -- ИСПРАВЛЕНО: имя таблицы
            WHERE uc.chat_id = $1
        """;

        return reactiveDb.sql(sql)
                .bind(0, chatId)
                .map((row, meta) -> UserMapper.map(row))
                .all();
    }

    // В оригинальном коде был findChatsById, возвращающий Flux.
    // Если id - это первичный ключ, то более корректно ожидать один результат (или ни одного).
    // Метод переименован в findChatById и возвращает Mono для ясности намерений.
    public Mono<r2dbc_chat> findChatById(Long chatId) {
        // Была опечатка "chats" (множественное число) — реальная таблица называется
        // "chat". Запрос всегда падал в switchIfEmpty/onErrorResume вызывающего кода
        // (ReactiveImpl.transferchat), из-за чего просмотр УЖЕ СУЩЕСТВУЮЩЕГО чата
        // (не создание нового) всегда получал status=500 -> ApiController.chat()
        // пропускал загрузку истории сообщений по короткому замыканию на этом статусе.
        // Обнаружено при живой верификации 5l4 (Task 6) — история сообщений не
        // грузилась даже при корректно сохранённых в БД сообщениях.
        String sql = "SELECT * FROM chat WHERE id = $1 LIMIT 1";
        return reactiveDb.sql(sql)
                .bind(0, chatId)
                .map((row, meta) -> ChatMapper.map(row))
                .one();
    }

    public Mono<r2dbc_message> findTopByChatIdOrderByTimestampDesc(Long chatId) {
        // NULLS LAST обязателен: в Postgres DESC по умолчанию ставит NULL первыми,
        // поэтому строка без времени всегда выигрывала бы "самое новое" и превью
        // чата показывало бы не то сообщение. NULL здесь означает "время неизвестно"
        // (легаси-строки), такие сообщения не должны считаться самыми свежими.
        // Тай-брейк по id: при равных time_stamp (в частности у всех легаси-строк
        // с NULL) порядок иначе недетерминирован между запросами.
        String sql = """
            SELECT * FROM message
            WHERE chat_id = $1
            ORDER BY time_stamp DESC NULLS LAST, id DESC
            LIMIT 1
        """;

        return reactiveDb.sql(sql)
                .bind(0, chatId)
                .map((row, meta) -> MessageMapper.map(row))
                .one();
    }

    public Flux<r2dbc_message> getMessagesByChatId(Long chatId) {
        // Симметрично findTopByChatIdOrderByTimestampDesc: NULL = "время неизвестно",
        // такие строки самые старые, поэтому в начало истории (ASC по умолчанию в
        // Postgres ставит NULL последними, т.е. выдавал бы их за самые свежие).
        // Тай-брейк по id: при равных time_stamp (в частности у всех легаси-строк
        // с NULL) порядок иначе недетерминирован между запросами.
        String sql = "SELECT * FROM message WHERE chat_id = $1 ORDER BY time_stamp ASC NULLS FIRST, id ASC";
        return reactiveDb.sql(sql)
                .bind(0, chatId)
                .map((row, meta) -> MessageMapper.map(row))
                .all();
    }

    public Mono<String> getUsernameById(Long userId) {
        String sql = "SELECT name FROM users WHERE id = $1";
        return reactiveDb.sql(sql)
                .bind(0, userId)
                .map((row, meta) -> row.get("name", String.class))
                .one();
    }

    public Mono<String> getUserImageUrl(Long userId) {
        String sql = "SELECT image_url FROM users WHERE id = $1";
        return reactiveDb.sql(sql)
                .bind(0, userId)
                .map((row, meta) -> row.get("image_url", String.class))
                .one();
    }

    public Mono<Void> insertMessage(Long chatId, Long userId, String text, java.time.Instant timestamp) {
        String sql = "INSERT INTO message (chat_id, user_id_id, text, time_stamp) VALUES ($1, $2, $3, $4)";
        return reactiveDb.sql(sql)
                .bind(0, chatId)
                .bind(1, userId)
                .bind(2, text)
                .bind(3, timestamp)
                .then();
    }
}