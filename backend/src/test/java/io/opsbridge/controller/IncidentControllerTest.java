package io.opsbridge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opsbridge.api.request.AssignIncidentRequest;
import io.opsbridge.api.request.CreateIncidentRequest;
import io.opsbridge.api.request.EscalateIncidentRequest;
import io.opsbridge.api.request.TransitionIncidentRequest;
import io.opsbridge.api.request.UpdateIncidentRequest;
import io.opsbridge.domain.entity.Incident;
import io.opsbridge.domain.entity.Service;
import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Role;
import io.opsbridge.domain.enums.Severity;
import io.opsbridge.security.RestSecurityResponses;
import io.opsbridge.security.SecurityConfig;
import io.opsbridge.security.jwt.JwtAuthFilter;
import io.opsbridge.security.jwt.JwtProperties;
import io.opsbridge.security.jwt.JwtTokenProvider;
import io.opsbridge.service.IncidentService;
import io.opsbridge.service.command.CreateIncidentCommand;
import io.opsbridge.service.command.IncidentSearchCommand;
import io.opsbridge.service.mapper.IncidentActivityMapper;
import io.opsbridge.service.mapper.IncidentMapper;
import io.opsbridge.service.mapper.ServiceMapper;
import io.opsbridge.service.mapper.UserMapper;
import io.opsbridge.support.clock.ClockConfig;
import io.opsbridge.support.error.IllegalStatusTransitionException;
import io.opsbridge.support.error.ResourceNotFoundException;
import io.opsbridge.support.error.StaleVersionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

// Uses real signed tokens instead of @WithMockUser, so a broken filter or missing @PreAuthorize actually fails these tests.
@WebMvcTest(IncidentController.class)
@Import({
    SecurityConfig.class,
    RestSecurityResponses.class,
    ClockConfig.class,
    JwtAuthFilter.class,
    JwtTokenProvider.class,
    IncidentMapper.class,
    IncidentActivityMapper.class,
    ServiceMapper.class,
    UserMapper.class
})
@EnableConfigurationProperties(JwtProperties.class)
@ActiveProfiles("test")
class IncidentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JwtProperties jwtProperties;

    @MockitoBean
    private IncidentService incidentService;

    private Incident incident;
    private UUID incidentId;

    @BeforeEach
    void setUp() {
        incident = incident();
        incidentId = incident.getId();
        when(incidentService.findById(incidentId)).thenReturn(incident);
        when(incidentService.list(any(), any()))
                .thenReturn(new PageImpl<>(List.of(incident), PageRequest.of(0, 25), 1));
        when(incidentService.listActivity(any(), any())).thenReturn(new PageImpl<>(List.of()));
        when(incidentService.create(any())).thenReturn(incident);
        when(incidentService.update(any())).thenReturn(incident);
        when(incidentService.transition(any())).thenReturn(incident);
        when(incidentService.assign(any())).thenReturn(incident);
        when(incidentService.escalate(any())).thenReturn(incident);
    }

    @Test
    void list_returns401WithoutAToken() throws Exception {
        mockMvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")));
        verifyNoInteractions(incidentService);
    }

    @Test
    void create_returns401WithoutAToken() throws Exception {
        mockMvc.perform(post("/api/v1/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(incidentService);
    }

    @Test
    void get_returns401ForAnExpiredToken() throws Exception {
        mockMvc.perform(get("/api/v1/incidents/" + incidentId).header("Authorization", expiredBearer()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")));
        verifyNoInteractions(incidentService);
    }

    @Test
    void get_returns401ForATamperedSignature() throws Exception {
        mockMvc.perform(get("/api/v1/incidents/" + incidentId).header("Authorization", tamperedBearer(Role.ADMIN)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(incidentService);
    }

    @Test
    void get_returns401ForAGarbageToken() throws Exception {
        mockMvc.perform(get("/api/v1/incidents/" + incidentId).header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(incidentService);
    }

    @Test
    void create_returns403ForAViewer() throws Exception {
        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", bearer(Role.VIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("FORBIDDEN")));
        verifyNoInteractions(incidentService);
    }

    @Test
    void transition_returns403ForAViewer() throws Exception {
        mockMvc.perform(patch("/api/v1/incidents/" + incidentId + "/status")
                        .header("Authorization", bearer(Role.VIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TransitionIncidentRequest(0, IncidentStatus.TRIAGED, null, null))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(incidentService);
    }

    @Test
    void assign_returns403ForAViewer() throws Exception {
        mockMvc.perform(patch("/api/v1/incidents/" + incidentId + "/assign")
                        .header("Authorization", bearer(Role.VIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignIncidentRequest(0, UUID.randomUUID()))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(incidentService);
    }

    @Test
    void escalate_returns403ForAViewer() throws Exception {
        mockMvc.perform(patch("/api/v1/incidents/" + incidentId + "/escalate")
                        .header("Authorization", bearer(Role.VIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new EscalateIncidentRequest(0, Priority.P1, null, "impact"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(incidentService);
    }

    @Test
    void update_returns403ForAViewer() throws Exception {
        mockMvc.perform(patch("/api/v1/incidents/" + incidentId)
                        .header("Authorization", bearer(Role.VIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateIncidentRequest(0, "New title", null, null, null))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(incidentService);
    }

    @Test
    void list_returns200ForAViewer() throws Exception {
        mockMvc.perform(get("/api/v1/incidents").header("Authorization", bearer(Role.VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].publicKey", is("INC-1000")))
                .andExpect(jsonPath("$.content[0].status", is("OPEN")))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    void list_passesQueryParamsIntoTheSearchCommandAndPageable() throws Exception {
        UUID serviceId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/incidents")
                        .header("Authorization", bearer(Role.VIEWER))
                        .param("q", "checkout")
                        .param("status", "INVESTIGATING")
                        .param("priority", "P1")
                        .param("severity", "SEV1")
                        .param("serviceId", serviceId.toString())
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk());

        ArgumentCaptor<IncidentSearchCommand> search = ArgumentCaptor.forClass(IncidentSearchCommand.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(incidentService).list(search.capture(), pageable.capture());
        assertThat(search.getValue().q()).isEqualTo("checkout");
        assertThat(search.getValue().status()).isEqualTo(IncidentStatus.INVESTIGATING);
        assertThat(search.getValue().priority()).isEqualTo(Priority.P1);
        assertThat(search.getValue().severity()).isEqualTo(Severity.SEV1);
        assertThat(search.getValue().serviceId()).isEqualTo(serviceId);
        assertThat(search.getValue().from()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(search.getValue().to()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    void list_sendsANullFilteredSearchCommandWhenNoParamsAreGiven() throws Exception {
        mockMvc.perform(get("/api/v1/incidents").header("Authorization", bearer(Role.VIEWER)))
                .andExpect(status().isOk());

        ArgumentCaptor<IncidentSearchCommand> search = ArgumentCaptor.forClass(IncidentSearchCommand.class);
        verify(incidentService).list(search.capture(), any());
        assertThat(search.getValue()).isEqualTo(new IncidentSearchCommand(null, null, null, null, null, null, null));
    }

    @Test
    void list_returns400ForAnInvalidStatusValue() throws Exception {
        mockMvc.perform(get("/api/v1/incidents").header("Authorization", bearer(Role.VIEWER)).param("status", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        verifyNoInteractions(incidentService);
    }

    @Test
    void activity_returns401WithoutAToken() throws Exception {
        mockMvc.perform(get("/api/v1/incidents/" + incidentId + "/activity"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(incidentService);
    }

    @Test
    void activity_returns200ForAViewer() throws Exception {
        mockMvc.perform(get("/api/v1/incidents/" + incidentId + "/activity")
                        .header("Authorization", bearer(Role.VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void activity_returns404ForAnUnknownIncident() throws Exception {
        UUID missing = UUID.randomUUID();
        when(incidentService.listActivity(eq(missing), any()))
                .thenThrow(new ResourceNotFoundException("Incident", missing));

        mockMvc.perform(get("/api/v1/incidents/" + missing + "/activity").header("Authorization", bearer(Role.VIEWER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("RESOURCE_NOT_FOUND")));
    }

    @Test
    void get_returns200ForAViewerWithRawEnumsAndVersion() throws Exception {
        mockMvc.perform(get("/api/v1/incidents/" + incidentId).header("Authorization", bearer(Role.VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey", is("INC-1000")))
                // Raw enum values, for the frontend to localize.
                .andExpect(jsonPath("$.priority", is("P2")))
                .andExpect(jsonPath("$.status", is("OPEN")))
                .andExpect(jsonPath("$.sla.state", is("ON_TRACK")))
                // Version comes back so the client can send it as expectedVersion next time.
                .andExpect(jsonPath("$.version", is(3)))
                .andExpect(jsonPath("$.service.name", is("Checkout API")));
    }

    @Test
    void create_returns201ForAnOperator() throws Exception {
        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", bearer(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.publicKey", is("INC-1000")));
    }

    @Test
    void create_returns201ForAnAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", bearer(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isCreated());
    }

    @Test
    void create_takesTheReporterFromTheTokenNotTheRequestBody() throws Exception {
        UUID callerId = UUID.randomUUID();
        String token = "Bearer "
                + jwtTokenProvider
                        .issueToken(callerId, "operator@opsbridge.local", "Dev Operator", Role.OPERATOR)
                        .token();

        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateIncidentCommand> captor = ArgumentCaptor.forClass(CreateIncidentCommand.class);
        verify(incidentService).create(captor.capture());
        assertThat(captor.getValue().reporterId()).isEqualTo(callerId);
    }

    @Test
    void transition_returns200ForAnOperator() throws Exception {
        mockMvc.perform(patch("/api/v1/incidents/" + incidentId + "/status")
                        .header("Authorization", bearer(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TransitionIncidentRequest(3, IncidentStatus.TRIAGED, "picked up", null))))
                .andExpect(status().isOk());
    }

    @Test
    void assign_returns200ForAnOperator() throws Exception {
        mockMvc.perform(patch("/api/v1/incidents/" + incidentId + "/assign")
                        .header("Authorization", bearer(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignIncidentRequest(3, UUID.randomUUID()))))
                .andExpect(status().isOk());
    }

    @Test
    void escalate_returns200ForAnOperator() throws Exception {
        mockMvc.perform(patch("/api/v1/incidents/" + incidentId + "/escalate")
                        .header("Authorization", bearer(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new EscalateIncidentRequest(3, Priority.P1, null, "customer impact"))))
                .andExpect(status().isOk());
    }

    @Test
    void update_returns200ForAnOperator() throws Exception {
        mockMvc.perform(patch("/api/v1/incidents/" + incidentId)
                        .header("Authorization", bearer(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateIncidentRequest(3, "New title", null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey", is("INC-1000")));
    }

    @Test
    void create_returns400WhenTitleIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", bearer(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateIncidentRequest(
                                "  ", null, UUID.randomUUID(), Priority.P1, Severity.SEV1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
    }

    @Test
    void transition_returns400WhenExpectedVersionIsOmitted() throws Exception {
        mockMvc.perform(patch("/api/v1/incidents/" + incidentId + "/status")
                        .header("Authorization", bearer(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TransitionIncidentRequest(null, IncidentStatus.TRIAGED, null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
    }

    @Test
    void assign_returns400WhenExpectedVersionIsOmitted() throws Exception {
        mockMvc.perform(patch("/api/v1/incidents/" + incidentId + "/assign")
                        .header("Authorization", bearer(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignIncidentRequest(null, UUID.randomUUID()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        verifyNoInteractions(incidentService);
    }

    @Test
    void escalate_returns400WhenExpectedVersionIsOmitted() throws Exception {
        mockMvc.perform(patch("/api/v1/incidents/" + incidentId + "/escalate")
                        .header("Authorization", bearer(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new EscalateIncidentRequest(null, Priority.P1, null, "customer impact"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        verifyNoInteractions(incidentService);
    }

    @Test
    void update_returns400WhenExpectedVersionIsOmitted() throws Exception {
        mockMvc.perform(patch("/api/v1/incidents/" + incidentId)
                        .header("Authorization", bearer(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateIncidentRequest(null, "New title", null, null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        verifyNoInteractions(incidentService);
    }

    @Test
    void get_returns404ForAnUnknownIncident() throws Exception {
        UUID missing = UUID.randomUUID();
        when(incidentService.findById(missing)).thenThrow(new ResourceNotFoundException("Incident", missing));

        mockMvc.perform(get("/api/v1/incidents/" + missing).header("Authorization", bearer(Role.VIEWER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("RESOURCE_NOT_FOUND")));
    }

    @Test
    void transition_returns409OnAStaleVersion() throws Exception {
        when(incidentService.transition(any())).thenThrow(new StaleVersionException(1, 4));

        mockMvc.perform(patch("/api/v1/incidents/" + incidentId + "/status")
                        .header("Authorization", bearer(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TransitionIncidentRequest(1, IncidentStatus.TRIAGED, null, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("STALE_VERSION")));
    }

    @Test
    void transition_returns422OnAnIllegalTransition() throws Exception {
        when(incidentService.transition(any()))
                .thenThrow(new IllegalStatusTransitionException(IncidentStatus.OPEN, IncidentStatus.RESOLVED));

        mockMvc.perform(patch("/api/v1/incidents/" + incidentId + "/status")
                        .header("Authorization", bearer(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TransitionIncidentRequest(3, IncidentStatus.RESOLVED, null, null))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("ILLEGAL_STATUS_TRANSITION")));
    }

    private CreateIncidentRequest validCreateRequest() {
        return new CreateIncidentRequest(
                "Checkout latency spike", "p99 above 3s", UUID.randomUUID(), Priority.P2, Severity.SEV3);
    }

    private String bearer(Role role) {
        return "Bearer "
                + jwtTokenProvider
                        .issueToken(UUID.randomUUID(), role.name().toLowerCase() + "@opsbridge.local", "Dev", role)
                        .token();
    }

    // Right key, but already expired by the time the clock reads it.
    private String expiredBearer() {
        JwtTokenProvider staleProvider = new JwtTokenProvider(
                new JwtProperties(jwtProperties.secret(), 1_000L),
                Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC));
        return "Bearer "
                + staleProvider
                        .issueToken(UUID.randomUUID(), "admin@opsbridge.local", "Dev Admin", Role.ADMIN)
                        .token();
    }

    /** Valid header and payload, signature bytes altered. */
    private String tamperedBearer(Role role) {
        String token = jwtTokenProvider
                .issueToken(UUID.randomUUID(), "admin@opsbridge.local", "Dev Admin", role)
                .token();
        return "Bearer " + token.substring(0, token.length() - 4) + "AAAA";
    }

    private static Incident incident() {
        User reporter = user("reporter@opsbridge.local", Role.OPERATOR);
        Service service = new Service("Checkout API", "Checkout", reporter);
        ReflectionTestUtils.setField(service, "id", UUID.randomUUID());

        Incident incident = new Incident(
                "INC-1000", "Checkout latency spike", "p99 above 3s", service, reporter, Priority.P2, Severity.SEV3);
        ReflectionTestUtils.setField(incident, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(incident, "version", 3);
        incident.setSlaStartedAt(Instant.parse("2026-03-01T09:00:00Z"));
        return incident;
    }

    private static User user(String email, Role role) {
        User user = new User("Dev " + role.name(), email, "hash", role);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
