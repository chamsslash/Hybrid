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
 * trusted-types default — имя политики из static/trusted_policy.js
 * (trustedTypes.createPolicy('default', ...)). Имена обязаны совпадать: политика с
 * незаявленным именем не создастся, и первый же policy.createHTML упадёт.
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
                + "trusted-types default; object-src 'none'; base-uri 'none';";
    }

    public static final String HEADER = "Content-Security-Policy";
}
