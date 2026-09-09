package com.example.springexample.Services;

import lombok.extern.slf4j.Slf4j;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Единственный источник CSP-nonce и самого заголовка Content-Security-Policy.
 *
 * Заводится потому, что генерация была продублирована в двух местах и они разошлись:
 * MVC_Service.generateandputNonce клал nonce в модель И ставил заголовок, а
 * WEBFLUX_Service.ParseWithThymeLeaf — только клал nonce в модель. Шаблон-то у обеих
 * половин один и тот же (app.html), поэтому получалось, что один и тот же документ
 * отдаётся под двумя разными режимами безопасности: /welcome, /registerpage и
 * /authcallback — под строгим CSP, а /reactive/chatlist, /reactive/chat и
 * /reactive/createchat — вообще без него, и атрибуты nonce в script там были
 * декорацией. Заголовка не было и на ingress: во всём репозитории строка
 * Content-Security-Policy встречалась ровно один раз.
 *
 * 'strict-dynamic' обязателен: app.js — модуль, который догружает вью динамическим
 * import(), и без него каждая вью отваливалась бы от политики.
 *
 * ДВЕ директивы Trusted Types делают разное, и путать их дорого:
 *
 *   require-trusted-types-for 'script' — ПРИНУЖДЕНИЕ. Без неё строку можно присвоить
 *       в innerHTML в обход политики, и она пройдёт без санитизации. Именно так тут и
 *       было: атрибуты nonce стояли, политика создавалась, а защиты не было ни на одном
 *       маршруте. Проверялось живьём — '<img src=x onerror=alert(1)>' доезжал до DOM
 *       целиком, вместе с onerror.
 *
 *   trusted-types <имена> — ALLOWLIST ИМЁН политик, сам по себе не принуждает ничего.
 *
 * В allowlist ДВА имени, и второе обязательно:
 *
 *   default   — наша политика из static/trusted_policy.js, санитизирует через DOMPurify.
 *               Названа 'default' намеренно: браузер зовёт дефолтную политику неявно на
 *               любом сыром присваивании в sink, поэтому забытое место санитизируется,
 *               а не роняет страницу.
 *
 *   dompurify — СОБСТВЕННАЯ политика DOMPurify. Он санитизирует, записывая вход в
 *               innerHTML временного документа, и под принуждением эта внутренняя запись
 *               тоже становится sink'ом. Своей политикой (createHTML: e=>e, сквозная) он
 *               эту запись и оборачивает — но только если имя разрешено. Если не
 *               разрешено, createPolicy бросает, DOMPurify тихо остаётся без неё, и его
 *               внутренняя запись уходит в ДЕФОЛТНУЮ политику, то есть в него же:
 *               DOMPurify -> default -> DOMPurify. Рекурсия глушится, наружу выходит
 *               ПУСТАЯ СТРОКА на любой, даже безобидной разметке.
 *
 * Последнее — не теория: ровно так стенд и лёг при первой попытке включить принуждение
 * с allowlist из одного 'default'. Список чатов отрисовался пустым, в консоли
 * "chatlist bootstrap failed: Cannot set properties of null (setting 'textContent')" —
 * querySelector('.chat-title') не находил разметки, которой DOMPurify не вернул.
 *
 * Имя 'dompurify' захардкожено в самом DOMPurify (purify.min.js: "dompurify" + суффикс
 * из атрибута data-tt-policy-suffix, которого у нашего script-тега нет). Появится
 * суффикс — allowlist надо будет править синхронно.
 */
@Slf4j
public final class CspNonce {

    private CspNonce() {
    }

    /**
     * Свежий nonce на каждый ответ. null — если SecureRandom недоступен; вызывающий
     * тогда не ставит ни заголовок, ни атрибут (страница работает без CSP, но работает).
     */
    public static String generate() {
        try {
            return Base64.getEncoder().encodeToString(
                    SecureRandom.getInstanceStrong().generateSeed(16));
        } catch (NoSuchAlgorithmException e) {
            log.warn("no such alg for nonce");
            return null;
        }
    }

    /** Заголовок под конкретный nonce. Значение обязано совпасть с атрибутом в HTML. */
    public static String headerValue(String nonce) {
        return "script-src 'nonce-" + nonce + "' 'strict-dynamic'; "
                + "require-trusted-types-for 'script'; trusted-types default dompurify; "
                + "object-src 'none'; base-uri 'none';";
    }

    public static final String HEADER = "Content-Security-Policy";
}
