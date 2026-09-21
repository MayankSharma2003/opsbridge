package io.opsbridge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opsbridge.api.request.LoginRequest;
import io.opsbridge.api.response.LoginResponse;
import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.Role;
import io.opsbridge.repository.UserRepository;
import io.opsbridge.security.jwt.JwtTokenProvider;
import io.opsbridge.support.error.InvalidCredentialsException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class AuthServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtTokenProvider jwtTokenProvider;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        authService = new AuthService(userRepository, passwordEncoder, jwtTokenProvider);
    }

    @Test
    void login_succeedsForActiveUserWithCorrectPassword() {
        User user = activeUser("admin@opsbridge.local", "hashed", Role.ADMIN);
        when(userRepository.findByEmail("admin@opsbridge.local")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Admin#Dev2026", "hashed")).thenReturn(true);
        Instant expiresAt = Instant.parse("2026-01-01T01:00:00Z");
        when(jwtTokenProvider.issueToken(user.getId(), user.getEmail(), user.getName(), user.getRole()))
                .thenReturn(new JwtTokenProvider.IssuedToken("signed-token", expiresAt));

        LoginResponse response = authService.login(new LoginRequest("admin@opsbridge.local", "Admin#Dev2026"));

        assertThat(response.token()).isEqualTo("signed-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresAt()).isEqualTo(expiresAt);
        assertThat(response.userId()).isEqualTo(user.getId());
        assertThat(response.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void login_rejectsUnknownEmail() {
        when(userRepository.findByEmail("nobody@opsbridge.local")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@opsbridge.local", "whatever")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_rejectsWrongPassword() {
        User user = activeUser("operator@opsbridge.local", "hashed", Role.OPERATOR);
        when(userRepository.findByEmail("operator@opsbridge.local")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("operator@opsbridge.local", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_rejectsInactiveUser() {
        User user = activeUser("viewer@opsbridge.local", "hashed", Role.VIEWER);
        ReflectionTestUtils.setField(user, "active", false);
        when(userRepository.findByEmail("viewer@opsbridge.local")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("viewer@opsbridge.local", "whatever")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    private User activeUser(String email, String passwordHash, Role role) {
        User user = new User("Test User", email, passwordHash, role);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
