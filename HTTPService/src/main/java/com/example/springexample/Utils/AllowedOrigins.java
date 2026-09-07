package com.example.springexample.Utils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Сборка списка разрешённых Origin из внешнего хоста приложения (beads ybg).
 *
 * <p>Заводится ради STOMP-эндпоинтов: там стояло
 * {@code setAllowedOriginPatterns("*")}, то есть handshake принимался с ЛЮБОГО сайта.
 * Пока стенд слушал только loopback, это ничего не стоило. При публикации наружу цена
 * появляется: нативный WebSocket не подчиняется CORS вообще — браузер откроет соединение
 * с чужой страницы и приложит куки, а SockJS-фолбэк на XHR отобьётся уже самим
 * {@code allowedOrigins}. То есть {@code "*"} на публичном хосте — это ровно одна
 * незакрытая дверь, а не «нестрогая настройка».
 *
 * <p>Оба варианта схемы для {@code host} включаются намеренно. Во время миграции на HTTPS
 * приложение какое-то время достижимо по обеим, а рассинхрон «схема в списке одна, а
 * браузер пришёл по другой» даёт молчаливый 403 на handshake — симптом, неотличимый от
 * поломки аутентификации.
 *
 * <p>{@code extraCsv} нужен для origin'ов, которые из хоста не выводятся: локальный
 * {@code http://localhost} на стенде, отдельный домен фронта и т.п. Пустые элементы
 * отбрасываются, чтобы {@code EXTRA_ALLOWED_ORIGINS=""} означало «дополнительных нет», а
 * не «разрешить пустой Origin».
 */
public final class AllowedOrigins {

    private AllowedOrigins() {
    }

    /**
     * @param scheme   внешняя схема ({@code http}/{@code https}); участвует только в
     *                 порядке элементов — обе схемы хоста попадают в список в любом случае
     * @param host     внешний хост приложения (INGRESS_HOST)
     * @param extraCsv дополнительные origin'ы через запятую; {@code null} или пустая
     *                 строка означают «дополнительных нет»
     */
    public static List<String> forHost(String scheme, String host, String extraCsv) {
        LinkedHashSet<String> origins = new LinkedHashSet<>();

        if (host != null && !host.isBlank()) {
            String primary = ("https".equalsIgnoreCase(scheme) ? "https" : "http") + "://" + host.trim();
            String secondary = ("https".equalsIgnoreCase(scheme) ? "http" : "https") + "://" + host.trim();
            origins.add(primary);
            origins.add(secondary);
        }

        if (extraCsv != null) {
            for (String extra : extraCsv.split(",")) {
                String trimmed = extra.trim();
                if (!trimmed.isEmpty()) {
                    origins.add(trimmed);
                }
            }
        }

        return new ArrayList<>(origins);
    }
}
