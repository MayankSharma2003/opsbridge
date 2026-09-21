package io.opsbridge.security.jwt;

import io.opsbridge.security.OpsBridgePrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// Authenticates bearer tokens; a bad/missing token isn't an error here -- the chain continues unauthenticated and authorization rules decide (401 downstream).
// Never logs the token or the Authorization header.
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        bearerToken(request)
                .flatMap(jwtTokenProvider::parse)
                .ifPresent(principal -> SecurityContextHolder.getContext().setAuthentication(authenticationFor(principal)));
        filterChain.doFilter(request, response);
    }

    private Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    /** Roles are exposed as ROLE_-prefixed authorities so hasRole/hasAnyRole expressions match. */
    private UsernamePasswordAuthenticationToken authenticationFor(OpsBridgePrincipal principal) {
        List<SimpleGrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }
}
