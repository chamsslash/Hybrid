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
 *
 * <p><b>Порт в собранные origin'ы НЕ подставляется, и это ограничение.</b> Браузер
 * добавляет порт в заголовок {@code Origin} тогда и только тогда, когда он не дефолтный
 * для схемы — то есть не 443 для {@code https} и не 80 для {@code http}. Оба штатных
 * режима проекта под это подходят: публикация идёт по 443 (на другом порту не работает ни
 * HTTP-01, ни TLS-ALPN-01, сертификата бы не было вовсе), локальный стенд — по 80.
 *
 * <p>А вот стенд, поднятый на нестандартном порту ({@code KIND_HTTP_HOST_PORT=8081} и
 * обращение прямо к нему, минуя прокси), пришлёт {@code Origin:
 * http://myapp.localtest.me:8081}, и он в списке не найдётся. Сверка точная, по строке.
 *
 * <p>Ляжет при этом не только STOMP. Тот же {@code INGRESS_HOST} без порта питает список
 * CORS в AuthService, а браузер шлёт {@code Origin} и на <b>same-origin POST</b> — такой
 * запрос уходит в {@code auth_request}-сабреквест к {@code /jwtcheck}, и {@code CorsFilter}
 * отбивает незнакомый Origin 403 ДО контроллера (beads 2q5). То есть встанут и
 * {@code /startauth}, и {@code /api/createchat}, и {@code /AiAssist}.
 *
 * <p>Страница при этом откроется: на same-origin GET браузер {@code Origin} не шлёт
 * вовсе, сверять нечего. Отсюда обманчивость симптома — сайт выглядит живым, а действия
 * не работают.
 *
 * <p>Лечится без правки кода: такой origin вписывается в {@code EXTRA_ALLOWED_ORIGINS}
 * целиком, вместе с портом. Отдельной переменной под порт заведено намеренно не было —
 * это ещё одно значение, которое обязано совпадать с тремя другими, а расходятся такие
 * значения ровно тогда, когда про них забываешь. Действующий список печатается в лог при
 * старте ({@code StompConfig}), так что сверить его с тем, что шлёт браузер, — одна
 * команда, а не расследование.
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
