package com.buildgraph.prototype.ticket;

import com.buildgraph.prototype.common.BuildGraphCorsProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class SupportChatWebSocketConfig implements WebSocketConfigurer {
    private final SupportChatWebSocketHandler supportChatWebSocketHandler;
    private final String[] allowedOriginPatterns;

    public SupportChatWebSocketConfig(
            SupportChatWebSocketHandler supportChatWebSocketHandler,
            BuildGraphCorsProperties corsProperties
    ) {
        this.supportChatWebSocketHandler = supportChatWebSocketHandler;
        this.allowedOriginPatterns = corsProperties.allowedOrigins();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(supportChatWebSocketHandler, "/ws/support-chat")
                .setAllowedOrigins(allowedOriginPatterns);
    }
}
