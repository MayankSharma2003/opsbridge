package io.opsbridge.support.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opsbridge.security.OpsBridgePrincipal;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

// Owns the session registry; split from LiveEventSocketHandler which only owns the connection protocol.
// Sessions register unauthenticated and stay that way until a valid token arrives -- broadcast
// skips them, so the socket can't be used to bypass REST authorization.
// Single-instance only -- lives in one JVM's heap. Multiple replicas would need shared pub/sub
// (Redis or a real broker); out of scope, called out rather than hidden.
@Component
public class LiveEventBroadcaster {

    // App-range close code for "never authenticated" -- 1000-2999 are reserved by the protocol.
    static final CloseStatus UNAUTHENTICATED = new CloseStatus(4401, "Unauthenticated");

    private static final Logger log = LoggerFactory.getLogger(LiveEventBroadcaster.class);

    private final Map<String, LiveSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LiveEventBroadcaster(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void register(WebSocketSession session) {
        sessions.put(session.getId(), new LiveSession(session, clock.instant()));
    }

    // Promotes a registered session to authenticated; no-op if it already closed.
    public void authenticate(String sessionId, OpsBridgePrincipal principal) {
        LiveSession live = sessions.get(sessionId);
        if (live != null) {
            live.principal = principal;
        }
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    public boolean isAuthenticated(String sessionId) {
        LiveSession live = sessions.get(sessionId);
        return live != null && live.principal != null;
    }

    /** Authenticated connections only. Visible for tests. */
    public int authenticatedSessionCount() {
        return (int) sessions.values().stream().filter(live -> live.principal != null).count();
    }

    public void broadcast(LiveEvent event) {
        if (sessions.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (IOException ex) {
            log.warn("Could not serialize live event {}", event.type(), ex);
            return;
        }
        TextMessage message = new TextMessage(payload);
        for (LiveSession live : sessions.values()) {
            if (live.principal != null) {
                send(live, message);
            }
        }
    }

    // Drops sockets that opened but never authenticated -- otherwise one could sit open forever
    // holding a connection slot.
    public void closeStaleUnauthenticated(Duration deadline) {
        Instant cutoff = clock.instant().minus(deadline);
        for (LiveSession live : sessions.values()) {
            if (live.principal == null && live.connectedAt.isBefore(cutoff)) {
                close(live.session, UNAUTHENTICATED);
            }
        }
    }

    void close(WebSocketSession session, CloseStatus status) {
        remove(session.getId());
        try {
            session.close(status);
        } catch (IOException ex) {
            log.debug("Failed closing websocket session", ex);
        }
    }

    // Serialized per session, not per broadcaster, since Spring rejects concurrent sends on one
    // session and a broadcast can race a per-session write; slow clients don't block each other.
    private void send(LiveSession live, TextMessage message) {
        try {
            synchronized (live.sendLock) {
                if (live.session.isOpen()) {
                    live.session.sendMessage(message);
                }
            }
        } catch (IOException | IllegalStateException ex) {
            // A client that vanished mid-send is routine, not an error worth alarming on.
            log.debug("Dropping live session after failed send", ex);
            close(live.session, CloseStatus.SERVER_ERROR);
        }
    }

    private static final class LiveSession {
        private final WebSocketSession session;
        private final Instant connectedAt;
        private final Object sendLock = new Object();
        private volatile OpsBridgePrincipal principal;

        private LiveSession(WebSocketSession session, Instant connectedAt) {
            this.session = session;
            this.connectedAt = connectedAt;
        }
    }
}
