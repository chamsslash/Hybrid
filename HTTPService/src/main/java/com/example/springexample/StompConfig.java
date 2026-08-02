package com.example.springexample;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@RequiredArgsConstructor
@EnableWebSocketMessageBroker
public class StompConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        /// Куда подписываются клиенты
        registry.enableSimpleBroker("/mutual", "/private");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Аутентификация access-токеном на CONNECT + контроль подписок (beads 58)
        registration.interceptors(stompAuthChannelInterceptor);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        //Для WS соеденений
       registry.addEndpoint("/ChatMessagesConn").setAllowedOriginPatterns("*").withSockJS();
       registry.addEndpoint("/MutualChatNotificationConn").setAllowedOriginPatterns("*").withSockJS();
        registry.addEndpoint("/GeneralChatDataUpdateConn").setAllowedOriginPatterns("*").withSockJS();
        registry.addEndpoint("/ChatChangesHandleConn").setAllowedOriginPatterns("*").withSockJS();
        registry.addEndpoint("/MutualImagesConn").setAllowedOriginPatterns("*").withSockJS();
        registry.addEndpoint("/StatusUserConn").setAllowedOriginPatterns("*").withSockJS();

    }
}
