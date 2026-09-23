package com.example.springexample;

/**
 * Одна реплика диалога для промпта AI-ассистента: кто написал и что.
 *
 * Поля названы как в JSON, которым контекст лежит в Redis (`{"user":...,"message":...}`),
 * чтобы разбор `ChatContextService.getFullContext()` читался один-в-один.
 *
 * Появился вместо `Map<ник, список реплик>` (beads j97): мапа теряла порядок разговора —
 * реплики схлопывались по авторам, а порядок ключей `HashMap` вообще произволен, и из
 * промпта было не понять, кто кому отвечал.
 */
public record ChatContextMessage(String user, String message) {
}
