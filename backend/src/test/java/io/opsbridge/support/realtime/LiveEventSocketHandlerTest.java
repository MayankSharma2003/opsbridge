package io.opsbridge.support.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.opsbridge.api.response.IncidentSummaryResponse;
import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Role;
import io.opsbridge.domain.enums.Severity;
import io.opsbridge.domain.enums.SlaState;
import io.opsbridge.security.jwt.JwtProperties;
import io.opsbridge.security.jwt.JwtTokenProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

// A socket must be worthless until it proves who it is, never a way to read what the REST API would refuse.
class LiveEventSocketHandlerTest {

    private static final Instant NOW = Instant.parse("2026-03-01T09:00:00Z");
    private static final String SECRET = "test-secret-value-long-enough-for-hmac-sha256-signing";

    private JwtTokenProvider jwtTokenProvider;
    private LiveEventBroadcaster broadcaster;
    private LiveEventSocketHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        objectMapper = appObjectMapper();
        jwtTokenProvider = new JwtTokenProvider(new JwtProperties(SECRET, 3_600_000L), clock);
        broadcaster = new LiveEventBroadcaster(objectMapper, clock);
        handler = new LiveEventSocketHandler(jwtTokenProvider, broadcaster, objectMapper, 5_000L);
    }

    @Test
    void aValidTokenOnTheFirstFrameAuthenticatesTheSession() throws Exception {
        WebSocketSession session = openSession("s1");

        handler.handleTextMessage(session, authFrame(validToken()));

        assertThat(broadcaster.isAuthenticated("s1")).isTrue();
        assertThat(broadcaster.authenticatedSessionCount()).isEqualTo(1);
        verify(session).sendMessage(new TextMessage("{\"type\":\"READY\"}"));
    }

    @Test
    void anInvalidTokenClosesTheSocketAsUnauthenticated() throws Exception {
        WebSocketSession session = openSession("s1");

        handler.handleTextMessage(session, authFrame("not-a-real-token"));

        assertThat(broadcaster.isAuthenticated("s1")).isFalse();
        verify(session).close(LiveEventBroadcaster.UNAUTHENTICATED);
    }

    @Test
    void anExpiredTokenIsRejectedJustLikeAForgedOne() throws Exception {
        // Issued against a clock an hour past this token's lifetime.
        JwtTokenProvider issuer = new JwtTokenProvider(
                new JwtProperties(SECRET, 1_000L), Clock.fixed(NOW.minus(Duration.ofHours(2)), ZoneOffset.UTC));
        String expired = issuer.issueToken(UUID.randomUUID(), "op@opsbridge.local", "Op", Role.OPERATOR)
                .token();
        WebSocketSession session = openSession("s1");

        handler.handleTextMessage(session, authFrame(expired));

        assertThat(broadcaster.isAuthenticated("s1")).isFalse();
        verify(session).close(LiveEventBroadcaster.UNAUTHENTICATED);
    }

    @Test
    void aMalformedFrameIsAFailedAuthNotAServerError() throws Exception {
        WebSocketSession session = openSession("s1");

        handler.handleTextMessage(session, new TextMessage("this is not json"));

        assertThat(broadcaster.isAuthenticated("s1")).isFalse();
        verify(session).close(LiveEventBroadcaster.UNAUTHENTICATED);
    }

    @Test
    void aFrameOfTheWrongTypeDoesNotAuthenticate() throws Exception {
        WebSocketSession session = openSession("s1");

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"SUBSCRIBE\",\"token\":\"" + validToken() + "\"}"));

        assertThat(broadcaster.isAuthenticated("s1")).isFalse();
        verify(session).close(LiveEventBroadcaster.UNAUTHENTICATED);
    }

    @Test
    void anUnauthenticatedSessionReceivesNothingWhenAnEventIsBroadcast() throws Exception {
        WebSocketSession session = openSession("s1");

        broadcaster.broadcast(LiveEvent.incidentUpdated(incidentSummary(), NOW));

        verify(session, never()).sendMessage(any());
    }

    @Test
    void anAuthenticatedSessionReceivesBroadcastEvents() throws Exception {
        WebSocketSession session = openSession("s1");
        handler.handleTextMessage(session, authFrame(validToken()));

        broadcaster.broadcast(LiveEvent.incidentUpdated(incidentSummary(), NOW));

        verify(session).sendMessage(new TextMessage("{\"type\":\"READY\"}"));
        verify(session).sendMessage(new TextMessage(
                objectMapper.writeValueAsString(LiveEvent.incidentUpdated(incidentSummary(), NOW))));
    }

    @Test
    void aSocketThatNeverAuthenticatesIsClosedOnceItsDeadlinePasses() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        LiveEventBroadcaster timed = new LiveEventBroadcaster(objectMapper, clock);
        WebSocketSession session = session("s1");
        timed.register(session);

        // Still inside the grace period.
        clock.advance(Duration.ofSeconds(4));
        timed.closeStaleUnauthenticated(Duration.ofSeconds(5));
        verify(session, never()).close(any());

        // Past it.
        clock.advance(Duration.ofSeconds(2));
        timed.closeStaleUnauthenticated(Duration.ofSeconds(5));
        verify(session).close(LiveEventBroadcaster.UNAUTHENTICATED);
    }

    @Test
    void anAuthenticatedSessionIsNeverReapedNoMatterHowLongItStaysConnected() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        LiveEventBroadcaster timed = new LiveEventBroadcaster(objectMapper, clock);
        LiveEventSocketHandler timedHandler =
                new LiveEventSocketHandler(jwtTokenProvider, timed, objectMapper, 5_000L);
        WebSocketSession session = session("s1");
        timedHandler.afterConnectionEstablished(session);
        timedHandler.handleTextMessage(session, authFrame(validToken()));

        clock.advance(Duration.ofHours(9));
        timed.closeStaleUnauthenticated(Duration.ofSeconds(5));

        verify(session, never()).close(any());
        assertThat(timed.isAuthenticated("s1")).isTrue();
    }

    @Test
    void framesSentAfterAuthenticationAreIgnoredSoTheChannelStaysPushOnly() throws Exception {
        WebSocketSession session = openSession("s1");
        handler.handleTextMessage(session, authFrame(validToken()));

        // A bad token on a later frame must not downgrade or close an already-authenticated session.
        handler.handleTextMessage(session, authFrame("garbage"));

        assertThat(broadcaster.isAuthenticated("s1")).isTrue();
        verify(session, never()).close(any());
    }

    @Test
    void aClosedConnectionIsForgotten() throws Exception {
        WebSocketSession session = openSession("s1");
        handler.handleTextMessage(session, authFrame(validToken()));

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(broadcaster.isAuthenticated("s1")).isFalse();
        assertThat(broadcaster.authenticatedSessionCount()).isZero();
    }

    private WebSocketSession openSession(String id) {
        WebSocketSession session = session(id);
        handler.afterConnectionEstablished(session);
        return session;
    }

    // Mirrors the mapper Spring Boot auto-configures, so frames here match what the app actually sends.
    private static ObjectMapper appObjectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private TextMessage authFrame(String token) {
        return new TextMessage("{\"type\":\"AUTH\",\"token\":\"" + token + "\"}");
    }

    private String validToken() {
        return jwtTokenProvider
                .issueToken(UUID.randomUUID(), "operator@opsbridge.local", "Dev Operator", Role.OPERATOR)
                .token();
    }

    private IncidentSummaryResponse incidentSummary() {
        return new IncidentSummaryResponse(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "INC-1000",
                "Checkout latency",
                null,
                null,
                Priority.P1,
                Severity.SEV1,
                IncidentStatus.INVESTIGATING,
                SlaState.BREACHED,
                NOW,
                3,
                NOW,
                NOW);
    }

    /** A clock the test can move forward, so connection-age behaviour is deterministic. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
