package io.opsbridge.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.opsbridge.api.request.TransitionIncidentRequest;
import io.opsbridge.domain.entity.Incident;
import io.opsbridge.domain.entity.IncidentActivity;
import io.opsbridge.domain.entity.Service;
import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.ActivityEventType;
import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Role;
import io.opsbridge.domain.enums.Severity;
import io.opsbridge.repository.IncidentActivityRepository;
import io.opsbridge.repository.IncidentRepository;
import io.opsbridge.repository.ServiceRepository;
import io.opsbridge.repository.UserRepository;
import io.opsbridge.security.jwt.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opsbridge.support.error.ErrorResponse;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;

// Two operators race to update the same incident; runs against the full stack (real HTTP, real
// transactions) because a mocked repository can't lose a version race. Whichever way the race
// falls the loser gets 409 STALE_VERSION; H2 here proves the locking, not the Postgres migration.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IncidentConcurrencyTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private IncidentActivityRepository incidentActivityRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private Clock clock;

    // Uses the JDK HttpClient factory: the default HttpURLConnection-based client rejects PATCH outright.
    private final TestRestTemplate restTemplate = new TestRestTemplate(
            new org.springframework.boot.web.client.RestTemplateBuilder()
                    .requestFactory((java.util.function.Supplier<
                                    org.springframework.http.client.ClientHttpRequestFactory>)
                            JdkClientHttpRequestFactory::new));

    private Incident incident;
    private String firstOperatorToken;
    private String secondOperatorToken;

    @BeforeEach
    void setUp() {
        incidentActivityRepository.deleteAll();
        incidentRepository.deleteAll();
        serviceRepository.deleteAll();
        userRepository.deleteAll();

        User first = userRepository.save(new User("First Operator", "first@opsbridge.local", "hash", Role.OPERATOR));
        User second = userRepository.save(new User("Second Operator", "second@opsbridge.local", "hash", Role.OPERATOR));
        Service service = serviceRepository.save(new Service("Checkout API", "Handles checkout", first));

        // Persisted directly, not via the create endpoint: the readable key comes from a Flyway sequence H2 lacks.
        Incident seeded = new Incident(
                "INC-9000", "Checkout is down", "p99 above 3s", service, first, Priority.P1, Severity.SEV1);
        seeded.setSlaStartedAt(clock.instant());
        incident = incidentRepository.save(seeded);

        firstOperatorToken = bearer(first);
        secondOperatorToken = bearer(second);

        assertThat(incident.getVersion()).isZero();
    }

    @Test
    void twoConcurrentStatusChangesLeaveOneWinnerAndOneConflict() throws Exception {
        // Both operators start from the same version-0 view of the incident.
        TransitionIncidentRequest request =
                new TransitionIncidentRequest(0, IncidentStatus.TRIAGED, "picking this up", null);

        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> firstCall =
                    pool.submit(transitionCall(startTogether, firstOperatorToken, request));
            Future<ResponseEntity<String>> secondCall =
                    pool.submit(transitionCall(startTogether, secondOperatorToken, request));

            ResponseEntity<String> a = firstCall.get(30, TimeUnit.SECONDS);
            ResponseEntity<String> b = secondCall.get(30, TimeUnit.SECONDS);

            assertThat(List.of(a.getStatusCode(), b.getStatusCode()))
                    .as("exactly one write wins, the other is told to reload")
                    .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);

            ResponseEntity<String> conflict = a.getStatusCode() == HttpStatus.CONFLICT ? a : b;
            ErrorResponse error = parse(conflict.getBody());
            assertThat(error.status()).isEqualTo(409);
            assertThat(error.code()).isEqualTo("STALE_VERSION");
            assertThat(error.path()).isEqualTo("/api/v1/incidents/" + incident.getId() + "/status");
        } finally {
            pool.shutdownNow();
        }

        // The loser left no trace: one change, one version bump, one activity row.
        Incident stored = incidentRepository.findById(incident.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(IncidentStatus.TRIAGED);
        assertThat(stored.getVersion()).isEqualTo(1);

        List<IncidentActivity> statusRows =
                incidentActivityRepository.findByIncidentIdOrderByCreatedAtAsc(incident.getId()).stream()
                        .filter(row -> row.getEventType() == ActivityEventType.STATUS_CHANGED)
                        .toList();
        assertThat(statusRows).hasSize(1);
        assertThat(statusRows.getFirst().getFromValue()).isEqualTo("OPEN");
        assertThat(statusRows.getFirst().getToValue()).isEqualTo("TRIAGED");
    }

    @Test
    void aSequentialSecondWriteWithTheSameStaleVersionIsAlsoRejected() {
        // Uncontended counterpart: no race, just a client that never reloaded.
        TransitionIncidentRequest request = new TransitionIncidentRequest(0, IncidentStatus.TRIAGED, null, null);

        ResponseEntity<String> winner = transition(firstOperatorToken, request);
        ResponseEntity<String> loser = transition(secondOperatorToken, request);

        assertThat(winner.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loser.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parse(loser.getBody()).code()).isEqualTo("STALE_VERSION");
    }

    private Callable<ResponseEntity<String>> transitionCall(
            CyclicBarrier startTogether, String token, TransitionIncidentRequest request) {
        return () -> {
            // Released together so both requests are in flight before either can commit.
            startTogether.await(30, TimeUnit.SECONDS);
            return transition(token, request);
        };
    }

    private ResponseEntity<String> transition(String token, TransitionIncidentRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/incidents/" + incident.getId() + "/status",
                HttpMethod.PATCH,
                new HttpEntity<>(request, headers),
                String.class);
    }

    private ErrorResponse parse(String body) {
        try {
            return objectMapper.readValue(body, ErrorResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Unparseable error body: " + body, e);
        }
    }

    private String bearer(User user) {
        return jwtTokenProvider
                .issueToken(user.getId(), user.getEmail(), user.getName(), user.getRole())
                .token();
    }

}
