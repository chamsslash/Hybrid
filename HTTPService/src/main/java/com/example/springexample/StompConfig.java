package com.example.springexample;

import com.example.springexample.Utils.AllowedOrigins;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

@Configuration
@RequiredArgsConstructor
@EnableWebSocketMessageBroker
public class StompConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final StompFrameTimestampInterceptor stompFrameTimestampInterceptor;

    /** Внешний хост приложения. Дефолт совпадает с Helm ingress.host. */
    @Value("${INGRESS_HOST:myapp.localtest.me}")
    private String ingressHost;

    /** Внешняя схема. См. {@code global.publicScheme} в Helm/values.yaml. */
    @Value("${PUBLIC_SCHEME:http}")
    private String publicScheme;

    /**
     * Origin'ы сверх выводимых из хоста, через запятую. Дефолт {@code http://localhost}
     * повторяет список CORS в AuthService, чтобы обе проверки видели одно и то же.
     */
    @Value("${EXTRA_ALLOWED_ORIGINS:http://localhost}")
    private String extraAllowedOrigins;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        /// Куда подписываются клиенты
        registry.enableSimpleBroker("/mutual", "/private");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Порядок важен: время фрейма снимается ПЕРВЫМ, ещё в потоке сессии, до любых
        // проверок и до раздачи фрейма в пул обработчиков (beads 525). Аутентификация
        // access-токеном на CONNECT + контроль подписок — следом (beads 58, g9x).
        registration.interceptors(stompFrameTimestampInterceptor, stompAuthChannelInterceptor);
    }

    /**
     * Один и тот же список на все шесть эндпоинтов: они обслуживают одно приложение с
     * одного хоста, и разойтись им незачем.
     *
     * <p>Раньше здесь стояло {@code "*"} — handshake принимался с любого сайта. Пока стенд
     * слушал loopback, это ничего не стоило; на публичном хосте это открытая дверь, потому
     * что нативный WebSocket не подчиняется CORS и уходит с куками. Подробности —
     * {@link AllowedOrigins}. Вернуть прежнее поведение можно, задав
     * {@code EXTRA_ALLOWED_ORIGINS=*}.
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        List<String> origins = AllowedOrigins.forHost(publicScheme, ingressHost, extraAllowedOrigins);
        String[] patterns = origins.toArray(new String[0]);

        //Для WS соеденений
        for (String endpoint : List.of(
                "/ChatMessagesConn",
                "/MutualChatNotificationConn",
                "/GeneralChatDataUpdateConn",
                "/ChatChangesHandleConn",
                "/MutualImagesConn",
                "/StatusUserConn")) {
            registry.addEndpoint(endpoint).setAllowedOriginPatterns(patterns).withSockJS();
        }
    }
}
