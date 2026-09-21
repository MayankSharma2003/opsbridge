package io.opsbridge.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import io.opsbridge.domain.enums.Role;
import io.opsbridge.security.OpsBridgePrincipal;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

// The filter never rejects a request itself -- it authenticates or leaves the context empty for
// the authorization rules to answer, since throwing here would turn a routine 401 into a 500.
class JwtAuthFilterTest {

    private static final String SECRET = "unit-test-signing-secret-at-least-32-bytes-long!!";

    private final JwtTokenProvider tokenProvider =
            new JwtTokenProvider(new JwtProperties(SECRET, 3_600_000L), Clock.systemUTC());
    private final JwtAuthFilter filter = new JwtAuthFilter(tokenProvider);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesAValidTokenWithARolePrefixedAuthority() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = tokenProvider
                .issueToken(userId, "operator@opsbridge.local", "Dev Operator", Role.OPERATOR)
                .token();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isInstanceOf(OpsBridgePrincipal.class);
        OpsBridgePrincipal principal = (OpsBridgePrincipal) authentication.getPrincipal();
        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.role()).isEqualTo(Role.OPERATOR);
        assertThat(authentication.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_OPERATOR");
        verify(chain).doFilter(request, response);
    }

    @Test
    void leavesTheContextEmptyWhenNoHeaderIsPresent() throws Exception {
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer not-a-jwt", "Bearer ", "Basic abc123", "Bearer a.b.c"})
    void leavesTheContextEmptyForUnusableHeaders(String header) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", header);
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void leavesTheContextEmptyForAnExpiredToken() throws Exception {
        JwtTokenProvider staleProvider = new JwtTokenProvider(
                new JwtProperties(SECRET, 1_000L),
                Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC));
        String expired = staleProvider
                .issueToken(UUID.randomUUID(), "admin@opsbridge.local", "Dev Admin", Role.ADMIN)
                .token();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + expired);

        filter.doFilter(request, new MockHttpServletResponse(), Mockito.mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void leavesTheContextEmptyForATokenSignedWithAnotherKey() throws Exception {
        JwtTokenProvider foreignProvider = new JwtTokenProvider(
                new JwtProperties("a-completely-different-signing-secret-32b!!", 3_600_000L), Clock.systemUTC());
        String foreign = foreignProvider
                .issueToken(UUID.randomUUID(), "attacker@example.com", "Attacker", Role.ADMIN)
                .token();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + foreign);

        filter.doFilter(request, new MockHttpServletResponse(), Mockito.mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void alwaysContinuesTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer garbage");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
