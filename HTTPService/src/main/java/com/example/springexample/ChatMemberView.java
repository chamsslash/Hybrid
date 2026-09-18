package com.example.springexample;

/**
 * Участник чата в том виде, в каком его показывает экран состава чата.
 *
 * <p>Отдельный тип, а не сырой {@code DataTransferService.UserDataRequest} из gRPC:
 * тот несёт поле {@code password}, и отдавать его наружу в JSON нельзя ни при каких
 * условиях. Сейчас MessegerParody его не заполняет, но «сейчас не заполняет» — это
 * свойство одной строки в {@code ConvertToProto}, а не гарантия контракта. Явный DTO
 * с тремя полями делает утечку невозможной по построению, а не по договорённости.
 *
 * <p>{@code imageUrl} — ключ объекта в MinIO, а не готовый URL: байты браузер тянет
 * отдельным авторизованным запросом через {@code /api/images/{key}} (см. image_loader.js),
 * потому что тег {@code <img>} не умеет послать Authorization. Пустая строка означает,
 * что аватарки у участника нет и рисуется заглушка.
 */
public record ChatMemberView(long userId, String username, String imageUrl) {
}
