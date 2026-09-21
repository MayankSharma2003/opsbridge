package io.opsbridge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.Role;
import io.opsbridge.security.RestSecurityResponses;
import io.opsbridge.security.SecurityConfig;
import io.opsbridge.security.jwt.JwtAuthFilter;
import io.opsbridge.security.jwt.JwtProperties;
import io.opsbridge.security.jwt.JwtTokenProvider;
import io.opsbridge.service.UserService;
import io.opsbridge.service.mapper.UserMapper;
import io.opsbridge.support.clock.ClockConfig;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

// Read-only directory, open to every authenticated role.
@WebMvcTest(UserController.class)
@Import({
    SecurityConfig.class,
    RestSecurityResponses.class,
    ClockConfig.class,
    JwtAuthFilter.class,
    JwtTokenProvider.class,
    UserMapper.class
})
@EnableConfigurationProperties(JwtProperties.class)
@ActiveProfiles("test")
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserService userService;

    private User operator;

    @BeforeEach
    void setUp() {
        operator = user("operator@opsbridge.local", Role.OPERATOR);
        when(userService.list(any(), any())).thenReturn(new PageImpl<>(List.of(operator), PageRequest.of(0, 25), 1));
    }

    @Test
    void list_returns401WithoutAToken() throws Exception {
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")));
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void list_returns200ForEveryRole(Role role) throws Exception {
        mockMvc.perform(get("/api/v1/users").header("Authorization", bearer(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email", is("operator@opsbridge.local")))
                .andExpect(jsonPath("$.content[0].role", is("OPERATOR")));
    }

    @Test
    void list_returns400ForAnInvalidRoleValue() throws Exception {
        mockMvc.perform(get("/api/v1/users?role=SUPERUSER").header("Authorization", bearer(Role.VIEWER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
    }

    @Test
    void list_passesTheRoleFilterThrough() throws Exception {
        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", bearer(Role.VIEWER))
                        .param("role", "OPERATOR", "ADMIN"))
                .andExpect(status().isOk());

        ArgumentCaptor<Set<Role>> roles = ArgumentCaptor.forClass(Set.class);
        verify(userService).list(roles.capture(), any());
        assertThat(roles.getValue()).containsExactlyInAnyOrder(Role.OPERATOR, Role.ADMIN);
    }

    @Test
    void list_sendsANullRoleFilterWhenNoneIsGiven() throws Exception {
        mockMvc.perform(get("/api/v1/users").header("Authorization", bearer(Role.VIEWER)))
                .andExpect(status().isOk());

        ArgumentCaptor<Set<Role>> roles = ArgumentCaptor.forClass(Set.class);
        verify(userService).list(roles.capture(), any());
        assertThat(roles.getValue()).isNull();
    }

    private String bearer(Role role) {
        return "Bearer "
                + jwtTokenProvider
                        .issueToken(UUID.randomUUID(), role.name().toLowerCase() + "@opsbridge.local", "Dev", role)
                        .token();
    }

    private static User user(String email, Role role) {
        User user = new User("Dev " + role.name(), email, "hash", role);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
