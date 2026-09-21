package io.opsbridge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opsbridge.api.request.CreateServiceRequest;
import io.opsbridge.api.request.UpdateServiceRequest;
import io.opsbridge.domain.entity.Service;
import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.Role;
import io.opsbridge.domain.enums.ServiceStatus;
import io.opsbridge.security.RestSecurityResponses;
import io.opsbridge.security.SecurityConfig;
import io.opsbridge.security.jwt.JwtAuthFilter;
import io.opsbridge.security.jwt.JwtProperties;
import io.opsbridge.security.jwt.JwtTokenProvider;
import io.opsbridge.service.ServiceCatalogService;
import io.opsbridge.service.command.ArchiveServiceCommand;
import io.opsbridge.service.command.CreateServiceCommand;
import io.opsbridge.service.command.ServiceSearchCommand;
import io.opsbridge.service.command.UpdateServiceCommand;
import io.opsbridge.service.mapper.ServiceMapper;
import io.opsbridge.service.mapper.UserMapper;
import io.opsbridge.support.clock.ClockConfig;
import io.opsbridge.support.error.ResourceNotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import io.opsbridge.service.ServiceWithOpenIncidents;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

// The service catalog is the one place an Operator (who freely mutates incidents) must still be refused.
@WebMvcTest(ServiceController.class)
@Import({
    SecurityConfig.class,
    RestSecurityResponses.class,
    ClockConfig.class,
    JwtAuthFilter.class,
    JwtTokenProvider.class,
    ServiceMapper.class,
    UserMapper.class
})
@EnableConfigurationProperties(JwtProperties.class)
@ActiveProfiles("test")
class ServiceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private ServiceCatalogService serviceCatalogService;

    private Service service;
    private UUID serviceId;

    @BeforeEach
    void setUp() {
        service = service();
        serviceId = service.getId();
        when(serviceCatalogService.findById(serviceId)).thenReturn(service);
        when(serviceCatalogService.findByIdWithOpenCount(serviceId))
                .thenReturn(new ServiceWithOpenIncidents(service, 3L, 1L));
        when(serviceCatalogService.list(any(), any()))
                .thenReturn(new PageImpl<>(
                        List.of(new ServiceWithOpenIncidents(service, 3L, 1L)), PageRequest.of(0, 25), 1));
        // Mutations return live counts, so a response never claims zero open incidents for a service that has some.
        when(serviceCatalogService.create(any())).thenReturn(new ServiceWithOpenIncidents(service, 0L, 0L));
        when(serviceCatalogService.update(any())).thenReturn(new ServiceWithOpenIncidents(service, 3L, 1L));
        when(serviceCatalogService.archive(any())).thenReturn(new ServiceWithOpenIncidents(service, 3L, 1L));
    }

    @Test
    void list_returns401WithoutAToken() throws Exception {
        mockMvc.perform(get("/api/v1/services"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")));
        verifyNoInteractions(serviceCatalogService);
    }

    @Test
    void create_returns401WithoutAToken() throws Exception {
        mockMvc.perform(post("/api/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(serviceCatalogService);
    }

    // Spec 3.2, case 3: an Operator is refused this Admin-only action.
    @Test
    void create_returns403ForAnOperator() throws Exception {
        mockMvc.perform(post("/api/v1/services")
                        .header("Authorization", bearer(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("FORBIDDEN")));
        verifyNoInteractions(serviceCatalogService);
    }

    @Test
    void update_returns403ForAnOperator() throws Exception {
        mockMvc.perform(patch("/api/v1/services/" + serviceId)
                        .header("Authorization", bearer(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(serviceCatalogService);
    }

    @Test
    void archive_returns403ForAnOperator() throws Exception {
        mockMvc.perform(post("/api/v1/services/" + serviceId + "/archive").header("Authorization", bearer(Role.OPERATOR)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(serviceCatalogService);
    }

    @Test
    void create_returns403ForAViewer() throws Exception {
        mockMvc.perform(post("/api/v1/services")
                        .header("Authorization", bearer(Role.VIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(serviceCatalogService);
    }

    @Test
    void archive_returns403ForAViewer() throws Exception {
        mockMvc.perform(post("/api/v1/services/" + serviceId + "/archive").header("Authorization", bearer(Role.VIEWER)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(serviceCatalogService);
    }

    @Test
    void list_returns200ForAViewer() throws Exception {
        mockMvc.perform(get("/api/v1/services").header("Authorization", bearer(Role.VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name", is("Checkout API")))
                .andExpect(jsonPath("$.content[0].status", is("ACTIVE")))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(25)))
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.totalPages", is(1)))
                .andExpect(jsonPath("$.first", is(true)))
                .andExpect(jsonPath("$.last", is(true)));
    }

    @Test
    void get_returns200ForAnOperator() throws Exception {
        mockMvc.perform(get("/api/v1/services/" + serviceId).header("Authorization", bearer(Role.OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Checkout API")))
                .andExpect(jsonPath("$.owner.email", is("admin@opsbridge.local")));
    }

    @Test
    void list_passesQueryParamsIntoTheSearchCommandAndPageable() throws Exception {
        mockMvc.perform(get("/api/v1/services")
                        .header("Authorization", bearer(Role.VIEWER))
                        .param("q", "checkout")
                        .param("status", "ACTIVE")
                        .param("team", "Payments")
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk());

        ArgumentCaptor<ServiceSearchCommand> search = ArgumentCaptor.forClass(ServiceSearchCommand.class);
        ArgumentCaptor<Pageable> pageable =
                ArgumentCaptor.forClass(Pageable.class);
        verify(serviceCatalogService).list(search.capture(), pageable.capture());
        assertThat(search.getValue().q()).isEqualTo("checkout");
        assertThat(search.getValue().status()).isEqualTo(ServiceStatus.ACTIVE);
        assertThat(search.getValue().team()).isEqualTo("Payments");
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    void list_sendsANullFilteredSearchCommandWhenNoParamsAreGiven() throws Exception {
        mockMvc.perform(get("/api/v1/services").header("Authorization", bearer(Role.VIEWER)))
                .andExpect(status().isOk());

        ArgumentCaptor<ServiceSearchCommand> search = ArgumentCaptor.forClass(ServiceSearchCommand.class);
        verify(serviceCatalogService).list(search.capture(), any());
        assertThat(search.getValue()).isEqualTo(new ServiceSearchCommand(null, null, null));
    }

    @Test
    void list_returns400ForAnInvalidStatusValue() throws Exception {
        mockMvc.perform(get("/api/v1/services").header("Authorization", bearer(Role.VIEWER)).param("status", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        verifyNoInteractions(serviceCatalogService);
    }

    @Test
    void create_returns201ForAnAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/services")
                        .header("Authorization", bearer(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is("Checkout API")));
    }

    @Test
    void update_returns200ForAnAdmin() throws Exception {
        mockMvc.perform(patch("/api/v1/services/" + serviceId)
                        .header("Authorization", bearer(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isOk());
    }

    @Test
    void archive_returns200ForAnAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/services/" + serviceId + "/archive").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void create_attributesTheChangeToTheAuthenticatedAdmin() throws Exception {
        UUID adminId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/services")
                        .header("Authorization", bearer(adminId, Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateServiceCommand> command = ArgumentCaptor.forClass(CreateServiceCommand.class);
        verify(serviceCatalogService).create(command.capture());
        // Actor comes from the verified token, not the body, so a caller can't pin the edit on someone else.
        assertThat(command.getValue().actorId()).isEqualTo(adminId);
    }

    @Test
    void update_attributesTheChangeToTheAuthenticatedAdmin() throws Exception {
        UUID adminId = UUID.randomUUID();

        mockMvc.perform(patch("/api/v1/services/" + serviceId)
                        .header("Authorization", bearer(adminId, Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isOk());

        ArgumentCaptor<UpdateServiceCommand> command = ArgumentCaptor.forClass(UpdateServiceCommand.class);
        verify(serviceCatalogService).update(command.capture());
        assertThat(command.getValue().actorId()).isEqualTo(adminId);
        assertThat(command.getValue().serviceId()).isEqualTo(serviceId);
    }

    @Test
    void archive_attributesTheChangeToTheAuthenticatedAdmin() throws Exception {
        UUID adminId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/services/" + serviceId + "/archive")
                        .header("Authorization", bearer(adminId, Role.ADMIN)))
                .andExpect(status().isOk());

        ArgumentCaptor<ArchiveServiceCommand> command = ArgumentCaptor.forClass(ArchiveServiceCommand.class);
        verify(serviceCatalogService).archive(command.capture());
        assertThat(command.getValue().actorId()).isEqualTo(adminId);
        assertThat(command.getValue().serviceId()).isEqualTo(serviceId);
    }

    @Test
    void create_returns400WhenNameIsBlank() throws Exception {
        mockMvc.perform(post("/api/v1/services")
                        .header("Authorization", bearer(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateServiceRequest("  ", null, UUID.randomUUID(), null, null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
    }

    @Test
    void create_returns400WhenOwnerIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/services")
                        .header("Authorization", bearer(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateServiceRequest("Payments API", null, null, null, null, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_returns400WhenAvailabilityTargetIsOutOfRange() throws Exception {
        mockMvc.perform(patch("/api/v1/services/" + serviceId)
                        .header("Authorization", bearer(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateServiceRequest(null, null, null, null, null, new BigDecimal("150.00")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        verifyNoInteractions(serviceCatalogService);
    }

    @Test
    void archive_returns404ForAnUnknownService() throws Exception {
        UUID missing = UUID.randomUUID();
        when(serviceCatalogService.archive(any())).thenThrow(new ResourceNotFoundException("Service", missing));

        mockMvc.perform(post("/api/v1/services/" + missing + "/archive")
                        .header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("RESOURCE_NOT_FOUND")));
    }

    @Test
    void update_reportsTheServicesRealOpenIncidentCountNotZero() throws Exception {
        // Regression: this used to map through a Service-only overload defaulting the count to 0.
        mockMvc.perform(patch("/api/v1/services/" + serviceId)
                        .header("Authorization", bearer(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"team\":\"Storefront\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openIncidentCount", is(3)))
                .andExpect(jsonPath("$.urgentOpenIncidentCount", is(1)));
    }

    @Test
    void archive_reportsTheServicesRealOpenIncidentCountNotZero() throws Exception {
        mockMvc.perform(post("/api/v1/services/" + serviceId + "/archive")
                        .header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openIncidentCount", is(3)));
    }

    @Test
    void get_returns404ForAnUnknownService() throws Exception {
        UUID missing = UUID.randomUUID();
        when(serviceCatalogService.findByIdWithOpenCount(missing))
                .thenThrow(new ResourceNotFoundException("Service", missing));

        mockMvc.perform(get("/api/v1/services/" + missing).header("Authorization", bearer(Role.VIEWER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("RESOURCE_NOT_FOUND")));
    }

    private CreateServiceRequest validCreateRequest() {
        return new CreateServiceRequest(
                "Checkout API", "Handles checkout", UUID.randomUUID(), "Payments", "oncall@opsbridge.local",
                new BigDecimal("99.90"));
    }

    private UpdateServiceRequest validUpdateRequest() {
        return new UpdateServiceRequest("Checkout API v2", null, null, null, null, null);
    }

    private String bearer(Role role) {
        return bearer(UUID.randomUUID(), role);
    }

    private String bearer(UUID userId, Role role) {
        return "Bearer "
                + jwtTokenProvider
                        .issueToken(userId, role.name().toLowerCase() + "@opsbridge.local", "Dev", role)
                        .token();
    }

    private static Service service() {
        User owner = new User("Dev Admin", "admin@opsbridge.local", "hash", Role.ADMIN);
        ReflectionTestUtils.setField(owner, "id", UUID.randomUUID());

        Service service = new Service("Checkout API", "Handles checkout", owner);
        ReflectionTestUtils.setField(service, "id", UUID.randomUUID());
        service.setTeam("Payments");
        service.setAvailabilityTarget(new BigDecimal("99.90"));
        return service;
    }
}
