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
                if (StringUtils.hasText(destination) && destination.startsWith("/private/")) {
                    String userId = accessor.getUser().getName();
                    if (!destination.endsWith("/" + userId)) {
                        log.warn("SUBSCRIBE to foreign private destination {} by user {}", destination, userId);
                        throw new org.springframework.security.access.AccessDeniedException(
                                "Cannot subscribe to another user's private destination");
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
}
