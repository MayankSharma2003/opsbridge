package io.opsbridge.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.opsbridge.domain.enums.Role;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class JwtTokenProviderTest {

    private static final String SECRET = "unit-test-signing-secret-at-least-32-bytes-long!!";

    @Test
    void issueToken_encodesSubjectRoleAndExpiryClaims() {
        Instant fixedNow = Instant.parse("2026-01-01T00:00:00Z");
        Clock clock = Clock.fixed(fixedNow, ZoneOffset.UTC);
        JwtTokenProvider provider = new JwtTokenProvider(new JwtProperties(SECRET, 60_000L), clock);
        UUID userId = UUID.randomUUID();

        JwtTokenProvider.IssuedToken issued =
                provider.issueToken(userId, "admin@opsbridge.local", "Dev Admin", Role.ADMIN);

        assertThat(issued.expiresAt()).isEqualTo(fixedNow.plusMillis(60_000));

        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        // jjwt checks `exp` against wall-clock time by default, so parse with the same fixed clock the token was issued with.
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .clock(() -> java.util.Date.from(fixedNow))
                .build()
                .parseSignedClaims(issued.token())
                .getPayload();

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
        assertThat(claims.get("email", String.class)).isEqualTo("admin@opsbridge.local");
        assertThat(claims.get("name", String.class)).isEqualTo("Dev Admin");
        assertThat(claims.getIssuedAt().toInstant()).isEqualTo(fixedNow);
        assertThat(claims.getExpiration().toInstant()).isEqualTo(fixedNow.plusMillis(60_000));
    }
}
