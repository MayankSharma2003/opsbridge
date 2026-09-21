package io.opsbridge.service;

import io.opsbridge.api.request.LoginRequest;
import io.opsbridge.api.response.LoginResponse;
import io.opsbridge.domain.entity.User;
import io.opsbridge.repository.UserRepository;
import io.opsbridge.security.jwt.JwtTokenProvider;
import io.opsbridge.security.jwt.JwtTokenProvider.IssuedToken;
import io.opsbridge.support.error.InvalidCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthService(
            UserRepository userRepository, PasswordEncoder passwordEncoder, JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = userRepository
                .findByEmail(request.email())
                .filter(User::isActive)
                .orElseThrow(InvalidCredentialsException::new);

        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        IssuedToken issued =
                jwtTokenProvider.issueToken(user.getId(), user.getEmail(), user.getName(), user.getRole());

        return new LoginResponse(
                issued.token(),
                "Bearer",
                issued.expiresAt(),
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole());
    }
}
