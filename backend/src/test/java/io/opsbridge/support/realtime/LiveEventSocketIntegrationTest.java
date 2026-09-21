package io.opsbridge.support.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.opsbridge.domain.enums.Role;
import io.opsbridge.security.jwt.JwtTokenProvider;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

// Exercises the real handshake against a real embedded server: LiveEventSocketHandlerTest drives the
// handler directly with a mocked session, so it can't catch a handshake Spring Security rejects -- a gap that shipped as a bug.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LiveEventSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private LiveEventBroadcaster broadcaster;

    @Test
    void aBrowserCanCompleteTheHandshakeAndAuthenticate() throws Exception {
        RecordingClient client = new RecordingClient();
        WebSocketSession session = client.connect(port);

        assertThat(session.isOpen()).isTrue();

        session.sendMessage(new TextMessage(
                "{\"type\":\"AUTH\",\"token\":\"" + validToken() + "\"}"));

        assertThat(client.awaitMessage()).isEqualTo("{\"type\":\"READY\"}");
        session.close();
    }

    @Test
    void anAuthenticatedClientReceivesABroadcast() throws Exception {
        RecordingClient client = new RecordingClient();
        WebSocketSession session = client.connect(port);
        session.sendMessage(new TextMessage(
                "{\"type\":\"AUTH\",\"token\":\"" + validToken() + "\"}"));
        assertThat(client.awaitMessage()).isEqualTo("{\"type\":\"READY\"}");

        // Wait for the server to register the auth, otherwise this races the handler and tests nothing.
        waitForAuthenticatedSession();
        broadcaster.broadcast(LiveEvent.incidentUpdated(null, java.time.Instant.parse("2026-03-01T09:00:00Z")));

        assertThat(client.awaitMessage()).contains("INCIDENT_UPDATED");
        session.close();
    }

    @Test
    void aBadTokenIsRejectedByTheServerRatherThanLeftHanging() throws Exception {
        RecordingClient client = new RecordingClient();
        WebSocketSession session = client.connect(port);

        session.sendMessage(new TextMessage("{\"type\":\"AUTH\",\"token\":\"forged\"}"));

        assertThat(client.awaitClose().getCode()).isEqualTo(4401);
    }

    private void waitForAuthenticatedSession() throws InterruptedException {
        for (int i = 0; i < 50 && broadcaster.authenticatedSessionCount() == 0; i += 1) {
            Thread.sleep(20);
        }
    }

    private String validToken() {
        return jwtTokenProvider
                .issueToken(UUID.randomUUID(), "operator@opsbridge.local", "Dev Operator", Role.OPERATOR)
                .token();
    }

    /** Collects frames and the close status so assertions don't depend on timing luck. */
    private static final class RecordingClient extends TextWebSocketHandler {

        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final CountDownLatch messageLatch = new CountDownLatch(1);
        private final CountDownLatch closeLatch = new CountDownLatch(1);
        private volatile CloseStatus closeStatus;
        private volatile CountDownLatch nextMessage = new CountDownLatch(1);

        WebSocketSession connect(int port) throws Exception {
            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            // Mimic the browser: the handshake carries the dev frontend's Origin.
            headers.setOrigin("http://localhost:5173");
            return new StandardWebSocketClient()
                    .execute(this, headers, URI.create("ws://localhost:" + port + "/ws/events"))
                    .get(5, TimeUnit.SECONDS);
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
            nextMessage.countDown();
            messageLatch.countDown();
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closeStatus = status;
            closeLatch.countDown();
        }

        String awaitMessage() throws InterruptedException {
            assertThat(nextMessage.await(5, TimeUnit.SECONDS)).as("expected a frame from the server").isTrue();
            String message = messages.get(messages.size() - 1);
            nextMessage = new CountDownLatch(1);
            return message;
        }

        CloseStatus awaitClose() throws InterruptedException {
            assertThat(closeLatch.await(5, TimeUnit.SECONDS)).as("expected the server to close").isTrue();
            return closeStatus;
        }
    }
}
