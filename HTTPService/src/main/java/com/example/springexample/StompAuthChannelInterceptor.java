package com.example.springexample;

import com.example.springexample.Services.ChatMembershipService;
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
 * Подписки на /private/** разрешены только на собственный userId.
 * Подписка на адреса конкретного чата разрешена только его участникам (beads g9x);
 * членство проверяется через ChatMembershipService, при недоступности проверки — отказ.
 * На SEND проверка членства сюда намеренно НЕ вынесена: {@code ChatBoxStompController}
 * и так вызывает {@code ChatMembershipService.members(chatId)} за списком получателей
 * веерной рассылки, так что интерцептор дублировал бы тот же gRPC-вызов вторым разом
 * на каждый фрейм (особенно чувствительно на typing-статусах — они летят вдвое чаще).
 * Кроме того, {@code isMember} внутри интерцептора блокирует ({@code .block()}) поток
 * общего пула {@code clientInboundChannel}, которым обслуживаются вообще все STOMP-команды
 * всех сессий, включая CONNECT — заминка MessegerParody без единой ошибки в логах вешает
 * весь WebSocket-ярус. Проверка перенесена в контроллер, где список участников уже под рукой.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final AccessTokenVerifier accessTokenVerifier;
    private final ChatMembershipService chatMembershipService;

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
                if (isPerUserDestination(destination)) {
                    if (!destination.endsWith("/" + userId)) {
                        log.warn("SUBSCRIBE to foreign per-user destination {} by user {}", destination, userId);
                        throw new org.springframework.security.access.AccessDeniedException(
                                "Cannot subscribe to another user's destination");
                    }
                }
                Long chatId = subscriptionChatId(destination);
                if (chatId != null && !chatMembershipService.isMember(chatId, userId)) {
                    log.warn("SUBSCRIBE на чат {} отклонён: пользователь {} не участник", chatId, userId);
                    throw new org.springframework.security.access.AccessDeniedException(
                            "Not a member of chat " + chatId);
                }
            }
            case SEND -> {
                if (accessor.getUser() == null) {
                    throw new org.springframework.security.access.AccessDeniedException(
                            "SEND requires authenticated session");
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
     * Пер-юзерные STOMP-назначения оканчиваются на "/{userId}" и должны совпадать
     * с аутентифицированным пользователем. Кроме всего /private/** сюда входят
     * chatlist-каналы на /mutual, адресованные конкретному userId, — иначе любой
     * аутентифицированный клиент мог бы подписаться на чужой userId.
     * Общие каналы (/mutual/.../typing_statuses_channel, image-каналы, чат-скоуп
     * по chat_id) под эти префиксы не попадают и остаются широковещательными.
     */
    private static final String[] PER_USER_PREFIXES = {
            "/private/",
            "/mutual/chatlist/change_chatpreview/",
            "/mutual/chatlist/list_update/",
            "/mutual/chatlist/notify/",
            "/mutual/chatlist/typing/"
    };

    private static boolean isPerUserDestination(String destination) {
        if (!StringUtils.hasText(destination)) {
            return false;
        }
        for (String prefix : PER_USER_PREFIXES) {
            if (destination.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static final String SUB_CHAT_PREFIX = "/mutual/chat/";
    private static final String SUB_TYPING_PREFIX = "/mutual/typing/";

    /**
     * Глобальные каналы, живущие под префиксом /mutual/chat/ и не относящиеся к чатам.
     * Список поимённый намеренно: всё остальное под этим префиксом обязано быть числовым
     * chatId, иначе отказ. Иначе следующий добавленный глобальный канал молча оказался бы
     * без проверки членства.
     */
    private static final java.util.Set<String> NON_CHAT_MUTUAL_CHAT_SUFFIXES =
            java.util.Set.of("image_chat_channel", "image_message_channel");

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

    /** chatId подписки, требующей проверки членства, либо null если адрес к чатам не относится. */
    private static Long subscriptionChatId(String destination) {
        if (!StringUtils.hasText(destination)) {
            return null;
        }
        if (destination.startsWith(SUB_TYPING_PREFIX)) {
            return parseChatIdOrDeny(destination, SUB_TYPING_PREFIX);
        }
        if (destination.startsWith(SUB_CHAT_PREFIX)) {
            String tail = destination.substring(SUB_CHAT_PREFIX.length());
            if (NON_CHAT_MUTUAL_CHAT_SUFFIXES.contains(tail)) {
                return null;
            }
            return parseChatIdOrDeny(destination, SUB_CHAT_PREFIX);
        }
        return null;
    }
}
