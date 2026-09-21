package io.opsbridge.support.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opsbridge.security.jwt.JwtTokenProvider;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

// /ws/events protocol. Token arrives in the first frame ({"type":"AUTH","token":"..."}), not a
// header or query param -- WebSocket/EventSource can't set Authorization headers, and
// ?access_token=... would leak the token into access logs and browser history.
// Push-only after AUTH succeeds -- any later frame is ignored, so the socket adds no mutation
// surface and every state change still goes through a REST endpoint.
@Component
public class LiveEventSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LiveEventSocketHandler.class);
    private static final String AUTH_TYPE = "AUTH";

    private final JwtTokenProvider jwtTokenProvider;
    private final LiveEventBroadcaster broadcaster;
    private final ObjectMapper objectMapper;
    private final Duration authDeadline;

    public LiveEventSocketHandler(
            JwtTokenProvider jwtTokenProvider,
            LiveEventBroadcaster broadcaster,
            ObjectMapper objectMapper,
            @Value("${opsbridge.realtime.auth-deadline-ms:5000}") long authDeadlineMs) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.broadcaster = broadcaster;
        this.objectMapper = objectMapper;
        this.authDeadline = Duration.ofMillis(authDeadlineMs);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        broadcaster.register(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Push-only channel: once authenticated there is nothing a client can usefully say.
        if (broadcaster.isAuthenticated(session.getId())) {
            return;
        }
        authenticate(session, message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        broadcaster.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Live socket transport error", exception);
        broadcaster.close(session, CloseStatus.SERVER_ERROR);
    }

    /** Reaps sockets that connected but never presented a token. */
    @Scheduled(fixedDelayString = "${opsbridge.realtime.auth-sweep-ms:5000}")
    public void closeStaleUnauthenticatedSessions() {
        broadcaster.closeStaleUnauthenticated(authDeadline);
    }

    // Deliberately no token, and no reason, in any log line or frame sent back -- a caller only
    // learns it was rejected.
    private void authenticate(WebSocketSession session, String payload) {
        readToken(payload)
                .flatMap(jwtTokenProvider::parse)
                .ifPresentOrElse(
                        principal -> {
                            broadcaster.authenticate(session.getId(), principal);
                            sendReady(session);
                        },
                        () -> broadcaster.close(session, LiveEventBroadcaster.UNAUTHENTICATED));
    }

    private Optional<String> readToken(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (!AUTH_TYPE.equals(node.path("type").asText())) {
                return Optional.empty();
            }
            String token = node.path("token").asText(null);
            return token == null || token.isBlank() ? Optional.empty() : Optional.of(token);
        } catch (Exception ex) {
            // Malformed frame is just a failed auth attempt, not a server fault.
            return Optional.empty();
        }
    }

    // Tells the client to stop treating the connection as pending and clear any backoff.
    private void sendReady(WebSocketSession session) {
        try {
            session.sendMessage(new TextMessage("{\"type\":\"READY\"}"));
        } catch (Exception ex) {
            log.debug("Failed sending READY", ex);
            broadcaster.close(session, CloseStatus.SERVER_ERROR);
        }
    }
}
