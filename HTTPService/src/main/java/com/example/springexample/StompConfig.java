package com.example.springexample;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class StompConfig implements WebSocketMessageBrokerConfigurer {
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        /// Куда подписываются клиенты
        registry.enableSimpleBroker("/mutual", "/private");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        //Для WS соеденений
       registry.addEndpoint("/ChatMessagesConn").setAllowedOriginPatterns("*").withSockJS();
       registry.addEndpoint("/MutualChatNotificationConn").setAllowedOriginPatterns("*").withSockJS();
        registry.addEndpoint("/GeneralChatDataUpdateConn").setAllowedOriginPatterns("*").withSockJS();
        registry.addEndpoint("/MutualChatListNotificationConn").setAllowedOriginPatterns("*").withSockJS();
        registry.addEndpoint("/ChatChangesHandleConn").setAllowedOriginPatterns("*").withSockJS();
        registry.addEndpoint("/MutualImagesConn").setAllowedOriginPatterns("*").withSockJS();
        registry.addEndpoint("/StatusUserConn").setAllowedOriginPatterns("*").withSockJS();

    }
}
