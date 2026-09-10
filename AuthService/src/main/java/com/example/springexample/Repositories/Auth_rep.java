package com.example.springexample.Repositories;

import com.example.springexample.JPA_Entities.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface Auth_rep extends JpaRepository<User,Long> {
    @Query("SELECT u FROM User u WHERE u.name = :username")
    Optional<User> findFirstByName(@Param("username") String username);
    @Query("""
    SELECT u FROM User u
    JOIN u.chats c
    WHERE c.id = :chatId
""")
    List<User> findAllByChatId(@Param("chatId") Long chatId);
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findFirstById(@Param("id") Long id);
    @Query("SELECT u FROM User u WHERE u.google_sub =:googlesub")
    Optional<User> findByGoogleSub(@Param("googlesub") String googleSub);

    /**
     * Пользователи, чей ник начинается с префикса, кроме самого запрашивающего.
     *
     * Выражение в WHERE — ровно {@code lower(u.name)}, потому что индекс
     * {@code ux_users_lower_name} построен на {@code lower(name) text_pattern_ops}.
     * Вариант {@code u.name ILIKE :prefix} дал бы тот же результат, но по {@code Seq Scan}:
     * функциональный индекс применяется только при БУКВАЛЬНОМ совпадении выражения.
     * Проверено планом запроса на живой базе.
     *
     * Escape-символ — {@code '!'}, а не {@code '\'}: обратный слэш пришлось бы экранировать
     * ещё и в Java-строке, и в JPQL, что превращает правило в загадку. Экранирование входа
     * делает {@link com.example.springexample.Services.UsernameSearchService}, сюда приходит
     * уже готовый шаблон.
     *
     * Строки с {@code name IS NULL} выпадают сами: {@code lower(NULL) LIKE ...} даёт NULL.
     */
    @Query("""
    SELECT u FROM User u
    WHERE lower(u.name) LIKE :prefix ESCAPE '!'
      AND u.id <> :requesterId
    ORDER BY lower(u.name)
""")
    List<User> searchByNamePrefix(@Param("prefix") String prefix,
                                  @Param("requesterId") Long requesterId,
                                  Pageable pageable);
}
