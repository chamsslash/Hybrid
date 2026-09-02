package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Список получателей STOMP-уведомлений о новом чате (beads 1e2).
 *
 * Зачем эти тесты существуют: каждый элемент списка подставляется прямо в адрес
 * рассылки — /mutual/chatlist/list_update/{id} и /mutual/chatlist/change_chatpreview/{id}.
 * Пока формат строки ничем не утверждался, в коде жил String.valueOf(getAuthorId()),
 * который печатал не id, а текстовый формат вложенного protobuf-сообщения User:
 * адрес получался /mutual/chatlist/list_update/id: "6" вместо .../6.
 *
 * Симптом был предельно тихим: исключения нет, лог рапортует «stomp add new chat
 * success», участники чата уведомление получают — не получает его только автор, и
 * ровно тот человек, который прямо сейчас смотрит на свой список чатов. Обнаружено
 * лишь живым прогоном на стенде, потому что ни один тест адрес не разглядывал.
 */
class ReactiveGrpcClientStompRecipientsTest {

    private DataTransferService.User user(String id) {
        return DataTransferService.User.newBuilder().setId(id).build();
    }

    private DataTransferService.ChatData chatData(String authorId, String... memberIds) {
        DataTransferService.ChatData.Builder builder = DataTransferService.ChatData.newBuilder()
                .setAuthorId(user(authorId));
        for (String memberId : memberIds) {
            builder.addUser(user(memberId));
        }
        return builder.build();
    }

    @Test
    void authorIdIsPlainIdNotProtobufTextFormat() {
        // Главный тест группы: именно здесь пряталась ошибка. Ассерт на равенство "6"
        // ловит её, а заодно и на contains("id:") — чтобы при возврате String.valueOf
        // диагностика в отчёте сразу называла причину, а не просто «6 != id: "6"».
        ArrayList<String> recipients = ReactiveGrpcClient.stompRecipients(chatData("6", "5"));

        assertThat(recipients).containsExactly("5", "6");
        assertThat(recipients).noneMatch(id -> id.contains("id:") || id.contains("\""));
    }

    @Test
    void everyRecipientIsUsableAsStompDestinationSuffix() {
        // Адрес собирается конкатенацией, поэтому любой посторонний символ (кавычка,
        // пробел, перевод строки) уводит кадр на адрес, которого не существует ни у
        // одного подписчика, — и сообщение исчезает молча, без ошибки.
        ArrayList<String> recipients = ReactiveGrpcClient.stompRecipients(chatData("42", "7", "13"));

        assertThat(recipients).allMatch(id -> id.matches("\\d+"));
    }

    @Test
    void authorIsIncludedWhenChatHasNoOtherMembers() {
        // Чат с одним лишь автором: список получателей не должен схлопываться в пустой,
        // иначе создатель не увидит собственный чат в списке до перезагрузки страницы.
        ArrayList<String> recipients = ReactiveGrpcClient.stompRecipients(chatData("6"));

        assertThat(recipients).containsExactly("6");
    }
}
