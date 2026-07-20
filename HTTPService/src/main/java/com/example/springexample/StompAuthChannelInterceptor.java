package com.example.springexample;

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
import org.springframework.util.StringUtils;

/**
 * Аутентификация WebSocket на уровне STOMP (beads 58).
 * SockJS-handshake не несёт Authorization-заголовок, поэтому ingress его не проверяет —
 * клиент обязан передать access-токен в заголовках STOMP CONNECT.
 * Подписки на /private/** разрешены только на собственный userId.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final AccessTokenVerifier accessTokenVerifier;

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
                if (isPerUserDestination(destination)) {
                    String userId = accessor.getUser().getName();
                    if (!destination.endsWith("/" + userId)) {
                        log.warn("SUBSCRIBE to foreign per-user destination {} by user {}", destination, userId);
                        throw new org.springframework.security.access.AccessDeniedException(
                                "Cannot subscribe to another user's destination");
                    }
                }
            }
            case SEND -> {
                if (accessor.getUser() == null) {
                    throw new org.springframework.security.access.AccessDeniedException(
                            "SEND requires authenticated session");
                }
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
            "/mutual/chatlist/notify/"
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
}
