package com.example.springexample.Services;

import com.example.grpc.DataTransferService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сверка запрошенных имён участников с теми, кого разрезолвил AuthService
 * (WEBFLUX_Service.findMissingUsernames).
 *
 * Резолв имён лоссовый: Auth_impl.getUserByUsername фильтрует ненайденных через
 * Optional::isPresent и отдаёт только тех, кто есть. Без этой сверки опечатка в имени
 * проходила молча — чат создавался без выпавшего участника, а пользователь видел успех.
 * Тестируем точечно, без multipart и ReactiveSecurityContextHolder, как и
 * CreateChatJsonResponseTest.
 */
class CreateChatUsernameValidationTest {

    private DataTransferService.UserDataRequest user(long id, String name) {
        return DataTransferService.UserDataRequest.newBuilder()
                .setId(id).setUsername(name).build();
    }

    @Test
    void allNamesResolvedYieldsNoMissing() {
        List<String> missing = WEBFLUX_Service.findMissingUsernames(
                List.of("dmitriy", "induk1"),
                List.of(user(5, "dmitriy"), user(6, "induk1")));

        assertThat(missing).isEmpty();
    }

    @Test
    void unresolvedNameIsReported() {
        List<String> missing = WEBFLUX_Service.findMissingUsernames(
                List.of("dmitriy", "nosuchuser"),
                List.of(user(5, "dmitriy")));

        assertThat(missing).containsExactly("nosuchuser");
    }

    /**
     * Ключевой случай: раньше выпадение участника ловилось бы сравнением размеров списков,
     * но при повторе имени в форме размеры расходятся на полностью корректном вводе —
     * AuthService вернёт одного пользователя на два запрошенных имени. Сверять нужно
     * по именам, иначе валидация отбивает валидный ввод.
     */
    @Test
    void duplicateRequestedNameIsNotMissingWhenResolved() {
        List<String> missing = WEBFLUX_Service.findMissingUsernames(
                List.of("dmitriy", "dmitriy"),
                List.of(user(5, "dmitriy")));

        assertThat(missing).isEmpty();
    }

    /**
     * Не нашли никого: наружу должны уехать все имена, и каждое по одному разу — список
     * попадает прямо в текст ошибки пользователю.
     */
    @Test
    void nothingResolvedReportsEveryNameOnce() {
        List<String> missing = WEBFLUX_Service.findMissingUsernames(
                List.of("ghost", "ghost", "phantom"),
                List.of());

        assertThat(missing).containsExactly("ghost", "phantom");
    }
}
