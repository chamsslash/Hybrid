package com.example.springexample;

import com.example.springexample.Services.ChatMembershipService;
import com.example.springexample.Services.MembershipDecision;
import com.example.springexample.Utils.AccessTokenVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;

/**
 * Аутентификация WebSocket на уровне STOMP (beads 58).
 * SockJS-handshake не несёт Authorization-заголовок, поэтому ingress его не проверяет —
 * клиент обязан передать access-токен в заголовках STOMP CONNECT.
 * SUBSCRIBE устроен deny-by-default (beads bwh): адрес обязан подойти под одно из
 * известных семейств — пер-юзерное (хвост равен своему userId) или чат-скоуп (хвост
 * равен chatId, требуется членство), — иначе отказ. Белый список и аудит подписок
 * фронта, на котором он основан, — ниже, у PER_USER_PREFIXES/PER_CHAT_PREFIXES.
 * Членство проверяется через ChatMembershipService, при недоступности проверки — отказ.
 * На SEND проверка членства сюда намеренно НЕ вынесена: {@code ChatBoxStompController}
 * и так вызывает {@code ChatMembershipService.members(chatId)} за списком получателей
 * веерной рассылки, так что интерцептор дублировал бы тот же gRPC-вызов вторым разом
 * на каждый фрейм (особенно чувствительно на typing-статусах — они летят вдвое чаще).
 * Кроме того, {@code decideBlocking} внутри интерцептора блокирует ({@code .block()}) поток
 * общего пула {@code clientInboundChannel}, которым обслуживаются вообще все STOMP-команды
 * всех сессий, включая CONNECT — заминка MessegerParody без единой ошибки в логах вешает
 * весь WebSocket-ярус. Проверка перенесена в контроллер, где список участников уже под рукой.
 * Цену того переноса — отказ перестал рвать сессию, и серия отказов стала бесплатной для
 * клиента — добирает стоп-кран на SEND (beads isf): контроллер отмечает отказ в
 * {@code StompDenialCounter}, интерцептор после порога отклоняет фрейм ДО разбора, без
 * дублирующего gRPC-вызова.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final AccessTokenVerifier accessTokenVerifier;
    private final ChatMembershipService chatMembershipService;
    private final StompDenialCounter denialCounter;

    /**
     * Тот же матчер, что использует {@code DefaultSubscriptionRegistry} простого брокера
     * для сопоставления подписки с исходящими адресами (Ant-путь). isPattern() отвечает
     * ровно на нужный вопрос — «является ли эта строка шаблоном для брокера» — а не на
     * приблизительный вопрос «есть ли в строке спецсимволы», поэтому предпочтён явной
     * проверке символов.
     */
    private static final AntPathMatcher ANT_PATH_MATCHER = new AntPathMatcher();

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        switch (accessor.getCommand()) {
            case CONNECT -> {
                String authHeader = accessor.getFirstNativeHeader("Authorization");
                Authentication auth = accessTokenVerifier.verify(authHeader);
                if (auth == null) {
                    log.warn("STOMP CONNECT rejected: missing/invalid access token");
                    throw new org.springframework.security.access.AccessDeniedException(
                            "STOMP CONNECT requires valid Authorization header");
                }
                accessor.setUser(auth);
                // Запись счётчика отказов заводится здесь и только здесь (beads isf):
                // инкремент из контроллера прилетает с потока пула обработчиков и может
                // опоздать за разрывом сессии — создавать запись ему нельзя, иначе она
                // останется в карте навсегда, убирать её уже некому.
                denialCounter.register(accessor.getSessionId());
            }
            case SUBSCRIBE -> {
                if (accessor.getUser() == null) {
                    throw new org.springframework.security.access.AccessDeniedException(
                            "SUBSCRIBE requires authenticated session");
                }
                String destination = accessor.getDestination();
                String userId = accessor.getUser().getName();
                if (destination != null && ANT_PATH_MATCHER.isPattern(destination)) {
                    log.warn("SUBSCRIBE на шаблонный адрес {} отклонён: пользователь {}", destination, userId);
                    throw new org.springframework.security.access.AccessDeniedException(
                            "Wildcard subscriptions are not allowed");
                }
                authorizeSubscription(destination, userId);
            }
            case SEND -> {
                if (accessor.getUser() == null) {
                    throw new org.springframework.security.access.AccessDeniedException(
                            "SEND requires authenticated session");
                }
                // Стоп-кран на серию отказов (beads isf). Отказ по членству живёт в
                // контроллере и сессию не рвёт, поэтому серия ничем не ограничена: каждый
                // фрейм стоит одного getAllUsersByChatId в MessegerParody. После порога
                // отклоняем ДО пропуска фрейма — то есть без единого лишнего gRPC-вызова.
                // Отказ здесь = ERROR-фрейм + разрыв сессии, ровно то поведение, которое
                // было до переноса проверки членства в контроллер (beads g9x).
                // Разрыв не бан: переподключение даёт новую сессию с чистым счётчиком, см.
                // javadoc StompDenialCounter — это осознанно, а не дырявый бан.
                if (denialCounter.isOverLimit(accessor.getSessionId())) {
                    log.warn("SEND отклонён: сессия {} набрала {} отказов подряд, разрываем",
                            accessor.getSessionId(), StompDenialCounter.MAX_DENIALS_PER_SESSION);
                    throw new org.springframework.security.access.AccessDeniedException(
                            "Too many denied SEND frames in this session");
                }
                String destination = accessor.getDestination();
                if (!StringUtils.hasText(destination) || !destination.startsWith("/app/")) {
                    log.warn("SEND на адрес {} отклонён: адреса вне /app/ доставляются брокером напрямую, минуя @MessageMapping",
                            destination);
                    throw new org.springframework.security.access.AccessDeniedException(
                            "SEND is only allowed to /app/ destinations");
                }
                // Проверка членства в чате здесь намеренно отсутствует (beads g9x) —
                // см. javadoc класса: перенесена в ChatBoxStompController, где список
                // участников уже запрашивается для веерной рассылки, чтобы не дублировать
                // gRPC-вызов и не держать поток clientInboundChannel на .block().
            }
            default -> { /* остальные команды не требуют проверок */ }
        }
        return message;
    }

    /**
     * Белый список семейств адресов SUBSCRIBE (beads bwh). Всё, что не подошло ни под
     * PER_USER_PREFIXES, ни под PER_CHAT_PREFIXES, — отказ.
     *
     * До bwh логика была обратной: отклонялись только адреса четырёх известных семейств,
     * а любой другой адрес проходил. Живьём на стенде (2026-08-21, аккаунт sunny id=4)
     * подписка на выдуманный на месте /mutual/chat_list/anything была ALLOWED — за ним нет
     * никакого обработчика, и он всё равно проходил. Под тем же allow-by-default жили три
     * глобальных канала картинок, по которым летел ImageUploadDTO{targetType,targetId,
     * objectKey} по ВСЕМ чатам системы: подписчик собирал id чужих чатов и ключи объектов
     * MinIO, а дальше скачивал их через /api/images (beads e1o). Цена ошибки несимметрична:
     * лишний отказ виден сразу и чинится одной строкой, лишний доступ не виден никак.
     *
     * АУДИТ ПОДПИСОК ФРОНТА (grep '.subscribe(' по static/views/*.js, 2026-08-21) — это
     * и есть исчерпывающее основание белого списка, других подписчиков у брокера нет:
     *   chatlist.view.js:129  /mutual/chatlist/change_chatpreview/{userId}  превью чата
     *   chatlist.view.js:150  /mutual/chatlist/list_update/{userId}         новый чат в списке
     *   chatlist.view.js:165  /mutual/chatlist/image/{userId}               аватарка чата в списке
     *                         (было /mutual/chat_list/image_chat_channel, глобальный)
     *   chatlist.view.js:179  /mutual/chatlist/typing/{userId}              «печатает» в списке
     *   chat.view.js:246      /mutual/chat/{chatId}                         сообщения чата
     *   chat.view.js:253      /mutual/typing/{chatId}                       «печатает» в чате
     *   chat.view.js:266      /mutual/chat_image/{chatId}                   аватарка открытого чата
     *                         (было /mutual/chat/image_chat_channel, глобальный)
     *   chat.view.js          /private/{userId}                             отбивка отказа на SEND
     *                         (beads isf; семейство /private/ уже было в PER_USER_PREFIXES)
     * Подписка chat.view.js:267 на /mutual/chat/image_message_channel удалена: у события
     * userimage targetId — это userId, а не chatId (WEBFLUX_Service.Upload_image при
     * регистрации), привязать такой адрес к чату невозможно. Событие уехало на пер-юзерный
     * /mutual/user_image/{userId}; фронт его не слушает — обработчик и так был мёртвым,
     * см. отдельную заметку в chat.view.js.
     *
     * Регрессию аудита стережёт everyFrontendSubscriptionFromAuditPasses: новая подписка
     * во фронте обязана появиться и здесь, и в том тесте, иначе deny-by-default отрежет её
     * молча — единственный реальный риск этой схемы.
     */

    /**
     * Семейства, где хвост адреса — userId: подписаться можно только на собственный.
     * Хвост сверяется ЦЕЛИКОМ, а не endsWith("/" + userId), как было до bwh: тот вариант
     * пропускал /mutual/chatlist/typing/42/9 — знакомый префикс, чужой userId в середине
     * и свой в конце. Брокер такой адрес никогда не наполнит, но deny-by-default обязан
     * отвечать «нет» на всё, чего система не рассылает, а не только на незнакомые префиксы.
     */
    private static final String[] PER_USER_PREFIXES = {
            "/private/",
            "/mutual/chatlist/change_chatpreview/",
            "/mutual/chatlist/list_update/",
            "/mutual/chatlist/notify/",
            "/mutual/chatlist/typing/",
            "/mutual/chatlist/image/",
            "/mutual/user_image/"
    };

    /**
     * Семейства, где хвост адреса — chatId: подписаться может только участник чата.
     * /mutual/chat_image/ отделён от /mutual/chat/ подчёркиванием намеренно. Раньше канал
     * аватарки жил как /mutual/chat/image_chat_channel и был синтаксически неотличим от
     * /mutual/chat/{chatId} — ровно из-за этой коллизии и завёлся поимённый список
     * NON_CHAT_MUTUAL_CHAT_SUFFIXES, дыра в проверке членства. Список удалён вместе с
     * коллизией: теперь под /mutual/chat/ не бывает ничего, кроме числового chatId.
     */
    private static final String[] PER_CHAT_PREFIXES = {
            "/mutual/chat/",
            "/mutual/typing/",
            "/mutual/chat_image/"
    };

    /** Префикс из списка, под который подошёл адрес, либо null. */
    private static String matchingPrefix(String[] prefixes, String destination) {
        for (String prefix : prefixes) {
            if (destination.startsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    /**
     * Единственная точка решения «пускать ли SUBSCRIBE»: сначала пер-юзерные семейства,
     * затем чат-скоуп, затем безусловный отказ. Порядок между двумя списками произволен —
     * префиксы не пересекаются (/mutual/chatlist/ и /mutual/chat_image/ расходятся с
     * /mutual/chat/ уже на 13-м символе), — но зафиксирован, чтобы будущий префикс,
     * случайно попавший в оба списка, разрешался предсказуемо.
     */
    private void authorizeSubscription(String destination, String userId) {
        if (!StringUtils.hasText(destination)) {
            log.warn("SUBSCRIBE без адреса отклонён: пользователь {}", userId);
            throw new org.springframework.security.access.AccessDeniedException(
                    "SUBSCRIBE requires a destination");
        }

        String perUserPrefix = matchingPrefix(PER_USER_PREFIXES, destination);
        if (perUserPrefix != null) {
            if (!destination.substring(perUserPrefix.length()).equals(userId)) {
                log.warn("SUBSCRIBE to foreign per-user destination {} by user {}", destination, userId);
                throw new org.springframework.security.access.AccessDeniedException(
                        "Cannot subscribe to another user's destination");
            }
            return;
        }

        String perChatPrefix = matchingPrefix(PER_CHAT_PREFIXES, destination);
        if (perChatPrefix != null) {
            long chatId = parseChatIdOrDeny(destination, perChatPrefix);
            if (chatMembershipService.decideBlocking(chatId, userId) != MembershipDecision.MEMBER) {
                log.warn("SUBSCRIBE на чат {} отклонён: пользователь {} не участник", chatId, userId);
                throw new org.springframework.security.access.AccessDeniedException(
                        "Not a member of chat " + chatId);
            }
            return;
        }

        log.warn("SUBSCRIBE на неизвестный адрес {} отклонён: пользователь {} (deny-by-default, beads bwh)",
                destination, userId);
        throw new org.springframework.security.access.AccessDeniedException(
                "Unknown subscription destination: " + destination);
    }

    /**
     * Разбирает chatId из хвоста адреса. Пустой хвост, нечисловой или не влезающий
     * в long — не валидный адрес чата, доступ отклоняется (fail-closed).
     */
    private static long parseChatIdOrDeny(String destination, String prefix) {
        String tail = destination.substring(prefix.length());
        if (!tail.matches("\\d+")) {
            log.warn("Отказ: адрес {} не содержит числового chatId", destination);
            throw new org.springframework.security.access.AccessDeniedException(
                    "Malformed chat destination: " + destination);
        }
        try {
            return Long.parseLong(tail);
        } catch (NumberFormatException e) {
            log.warn("Отказ: chatId в адресе {} не влезает в long", destination);
            throw new org.springframework.security.access.AccessDeniedException(
                    "Malformed chat destination: " + destination);
        }
    }

}
