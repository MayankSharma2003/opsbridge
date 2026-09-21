package io.opsbridge.controller;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opsbridge.domain.enums.Role;
import io.opsbridge.security.RestSecurityResponses;
import io.opsbridge.security.SecurityConfig;
import io.opsbridge.security.jwt.JwtAuthFilter;
import io.opsbridge.security.jwt.JwtProperties;
import io.opsbridge.security.jwt.JwtTokenProvider;
import io.opsbridge.support.clock.ClockConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MeController.class)
@Import({SecurityConfig.class, RestSecurityResponses.class, ClockConfig.class, JwtAuthFilter.class, JwtTokenProvider.class})
@EnableConfigurationProperties(JwtProperties.class)
@ActiveProfiles("test")
class MeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void me_returns401WithoutAToken() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")));
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void me_returnsTheCallerEncodedInTheToken(Role role) throws Exception {
        UUID userId = UUID.randomUUID();
        String token = jwtTokenProvider
                .issueToken(userId, "user@opsbridge.local", "Dev User", role)
                .token();

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(userId.toString())))
                .andExpect(jsonPath("$.email", is("user@opsbridge.local")))
                .andExpect(jsonPath("$.name", is("Dev User")))
                // Raw role value; the frontend localizes the label.
                .andExpect(jsonPath("$.role", is(role.name())));
    }
}
