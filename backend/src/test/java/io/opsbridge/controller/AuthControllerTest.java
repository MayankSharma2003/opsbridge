package io.opsbridge.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opsbridge.api.request.LoginRequest;
import io.opsbridge.api.response.LoginResponse;
import io.opsbridge.domain.enums.Role;
import io.opsbridge.security.RestSecurityResponses;
import io.opsbridge.security.SecurityConfig;
import io.opsbridge.security.jwt.JwtAuthFilter;
import io.opsbridge.security.jwt.JwtProperties;
import io.opsbridge.security.jwt.JwtTokenProvider;
import io.opsbridge.service.AuthService;
import io.opsbridge.support.clock.ClockConfig;
import io.opsbridge.support.error.InvalidCredentialsException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import({
    SecurityConfig.class,
    ClockConfig.class,
    RestSecurityResponses.class,
    JwtAuthFilter.class,
    JwtTokenProvider.class
})
@EnableConfigurationProperties(JwtProperties.class)
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @Test
    void login_returns200WithTokenOnSuccess() throws Exception {
        LoginResponse response = new LoginResponse(
                "signed-token",
                "Bearer",
                Instant.parse("2026-01-01T01:00:00Z"),
                UUID.randomUUID(),
                "Dev Admin",
                "admin@opsbridge.local",
                Role.ADMIN);
        when(authService.login(any(LoginRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("admin@opsbridge.local", "Admin#Dev2026"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", is("signed-token")))
                .andExpect(jsonPath("$.role", is("ADMIN")));
    }

    @Test
    void login_returns400OnValidationFailure() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("not-an-email", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
    }

    @Test
    void login_returns401OnInvalidCredentials() throws Exception {
        when(authService.login(any(LoginRequest.class))).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("admin@opsbridge.local", "wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("INVALID_CREDENTIALS")));
    }

    @Test
    void unauthenticatedRequestToNonPermittedPath_returns401NotDefault403() throws Exception {
        mockMvc.perform(get("/api/v1/anything"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")));
    }
}
