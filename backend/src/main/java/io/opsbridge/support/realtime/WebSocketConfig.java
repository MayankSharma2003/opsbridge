package io.opsbridge.support.realtime;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

// Plain WebSocket, not STOMP/SockJS -- one push-only broadcast channel doesn't need a broker
// abstraction and client library.
// Origins reuse the same allow-list as CORS, explicit and never a wildcard -- setAllowedOrigins("*")
// would let any page on the internet open an authenticated feed.
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final LiveEventSocketHandler liveEventSocketHandler;
    private final List<String> allowedOrigins;

    public WebSocketConfig(
            LiveEventSocketHandler liveEventSocketHandler,
            @Value("${opsbridge.cors.allowed-origins}") List<String> allowedOrigins) {
        this.liveEventSocketHandler = liveEventSocketHandler;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(liveEventSocketHandler, "/ws/events")
                .setAllowedOrigins(allowedOrigins.toArray(String[]::new));
    }
}
