package com.example.springexample.Services;

import com.example.springexample.JPA_Entities.User;
import com.example.springexample.Repositories.Auth_rep;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Поиск пользователей по началу ника — вся политика подсказок в одном месте.
 *
 * Вынесен из {@code Auth_impl} не ради красоты: правила (регистронезависимость,
 * экранирование шаблона, отсечение пустого ввода, лимит) — это ровно то, что проверяется
 * юнит-тестом, а gRPC-обёртка с {@code StreamObserver} тестируется отдельно и о них
 * ничего знать не должна.
 */
@Service
@RequiredArgsConstructor
public class UsernameSearchService {

    /**
     * Escape-символ шаблона LIKE. Не обратный слэш: тот пришлось бы экранировать и в
     * Java-строке, и в JPQL, превращая простое правило в загадку. Значение обязано
     * совпадать с {@code ESCAPE '!'} в {@link Auth_rep#searchByNamePrefix}.
     */
    static final char ESCAPE_CHAR = '!';

    private final Auth_rep auth_rep;

    /**
     * Возвращает до {@code limit} пользователей, чей ник начинается с {@code prefix},
     * исключая самого запрашивающего.
     *
     * Пустой (в том числе состоящий из пробелов) префикс и неположительный лимит дают
     * пустой список БЕЗ обращения к БД: подсказки на пустом поле никому не нужны, а
     * запрос за ними стоил бы полного сканирования по каждому нажатию Backspace.
     */
    @Transactional(readOnly = true)
    public List<User> searchByPrefix(String prefix, long requesterId, int limit) {
        if (prefix == null || prefix.isBlank() || limit <= 0) {
            return List.of();
        }
        // Locale.ROOT, а не toLowerCase() без аргумента: в турецкой локали "I" превращается
        // в "ı", и совпадение с индексом lower(name), который считает Postgres, потерялось бы
        // в зависимости от локали JVM.
        String pattern = escapeLikePattern(prefix.toLowerCase(Locale.ROOT)) + "%";
        return auth_rep.searchByNamePrefix(pattern, requesterId, PageRequest.of(0, limit));
    }

    /**
     * Экранирует служебные символы LIKE. Без этого ввод {@code %} вернул бы всех
     * пользователей системы одним запросом, а {@code _} совпал бы с любым символом.
     * Сам escape-символ экранируется тоже — иначе ник, начинающийся с {@code !},
     * ломал бы шаблон.
     */
    static String escapeLikePattern(String raw) {
        StringBuilder escaped = new StringBuilder(raw.length() + 4);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ESCAPE_CHAR || c == '%' || c == '_') {
                escaped.append(ESCAPE_CHAR);
            }
            escaped.append(c);
        }
        return escaped.toString();
    }
}
