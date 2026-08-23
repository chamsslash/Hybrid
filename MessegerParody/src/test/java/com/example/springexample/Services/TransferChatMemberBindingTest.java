package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import com.example.springexample.ConvertToProto;
import com.example.springexample.JPA_Entities.r2dbc_chat;
import com.example.springexample.JPA_Entities.r2dbc_user;
import com.example.springexample.JPA_Entities.r2dbc_user_Chat;
import com.example.springexample.R2DBC_Repositories.ReactiveChatRepository;
import com.example.springexample.R2DBC_Repositories.ReactiveRepository;
import com.example.springexample.R2DBC_Repositories.ReactiveUserChatRepository;
import com.example.springexample.R2DBC_Repositories.ReactiveUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Состав участников при создании чата (ReactiveImpl.transferchat).
 *
 * Стережёт баг: связки user_chat писались по списку users, который дубликаты сохраняет,
 * тогда как проверка «такой чат уже есть» шла по allIds с .distinct(). Один и тот же
 * участник, названный дважды (или названный явно автор, который добавляется отдельно),
 * давал два INSERT и падение на pk_user_chat — причём чат к этому моменту был уже создан,
 * так что пользователь видел ошибку при фактически созданном чате, а повтор плодил дубли.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferChatMemberBindingTest {

    @Mock
    private ConvertToProto toProto;
    @Mock
    private ReactiveRepository customReactiveRepository;
    @Mock
    private ReactiveUserChatRepository reactiveUserChatRepository;
    @Mock
    private ReactiveUserRepository reactiveUserRepository;
    @Mock
    private ReactiveChatRepository reactiveChatRepository;

    private r2dbc_user user(long id, String name) {
        r2dbc_user u = new r2dbc_user();
        u.setId(id);
        u.setName(name);
        return u;
    }

    private DataTransferService.ChatData chatData(String title, long authorId, long... memberIds) {
        DataTransferService.ChatData.Builder b = DataTransferService.ChatData.newBuilder()
                .setTitle(title)
                .setAuthorId(DataTransferService.User.newBuilder()
                        .setId(String.valueOf(authorId)).build());
        for (long id : memberIds) {
            b.addUser(DataTransferService.User.newBuilder().setId(String.valueOf(id)).build());
        }
        return b.build();
    }

    private ReactiveImpl serviceWith(r2dbc_user... users) {
        for (r2dbc_user u : users) {
            when(reactiveUserRepository.findById(u.getId())).thenReturn(Mono.just(u));
        }
        // Существующего чата нет — идём в ветку создания.
        when(customReactiveRepository.findChatByTitleAndExactUsers(anyString(), anyList()))
                .thenReturn(Mono.empty());
        when(reactiveChatRepository.save(any(r2dbc_chat.class))).thenAnswer(inv -> {
            r2dbc_chat saved = inv.getArgument(0);
            saved.setId(42L);
            return Mono.just(saved);
        });
        when(reactiveUserChatRepository.save(any(r2dbc_user_Chat.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        return new ReactiveImpl(toProto, customReactiveRepository,
                reactiveUserChatRepository, reactiveUserRepository, reactiveChatRepository);
    }

    private List<r2dbc_user_Chat> capturedBindings(int expectedSaves) {
        ArgumentCaptor<r2dbc_user_Chat> captor = ArgumentCaptor.forClass(r2dbc_user_Chat.class);
        verify(reactiveUserChatRepository, times(expectedSaves)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void duplicateMemberIsBoundOnce() {
        r2dbc_user author = user(6, "induk1");
        r2dbc_user member = user(5, "dmitriy");
        ReactiveImpl service = serviceWith(author, member);

        DataTransferService.ChatResponse resp =
                service.transferchat(chatData("Дубликат участника", 6, 5, 5)).block();

        assertThat(resp).isNotNull();
        assertThat(resp.getStatus()).isEqualTo("200");
        // Автор + участник, ровно по одной связке, несмотря на два вхождения участника.
        assertThat(capturedBindings(2))
                .extracting(r2dbc_user_Chat::getUserId)
                .containsExactlyInAnyOrder(5L, 6L);
    }

    /**
     * Ровно тот ввод, на котором баг ловился вживую: автор назвал участником себя, причём
     * дважды. Автор уже добавляется в состав отдельно (noneMatch выше), так что по старому
     * коду выходило два INSERT одной и той же пары — чат создавался, а пользователь получал
     * «duplicate key value violates unique constraint pk_user_chat».
     */
    @Test
    void authorNamedTwiceAsMemberIsBoundOnce() {
        r2dbc_user author = user(6, "induk1");
        ReactiveImpl service = serviceWith(author);

        DataTransferService.ChatResponse resp =
                service.transferchat(chatData("Автор в списке", 6, 6, 6)).block();

        assertThat(resp).isNotNull();
        assertThat(resp.getStatus()).isEqualTo("200");
        assertThat(capturedBindings(1))
                .extracting(r2dbc_user_Chat::getUserId)
                .containsExactly(6L);
    }
}
