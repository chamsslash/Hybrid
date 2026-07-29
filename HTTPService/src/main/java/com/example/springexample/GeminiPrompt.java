package com.example.springexample;

import com.google.gson.JsonArray;

/**
 * Пара (системная инструкция, содержимое диалога) для Gemini generateContent —
 * у Gemini system-инструкция отдельное top-level поле запроса, а не элемент
 * общего списка сообщений (как было у Yandex).
 */
public record GeminiPrompt(String systemInstruction, JsonArray contents) {
}
