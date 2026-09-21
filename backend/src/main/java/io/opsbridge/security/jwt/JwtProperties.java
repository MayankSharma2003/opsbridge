package io.opsbridge.security.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opsbridge.jwt")
public record JwtProperties(String secret, long expirationMs) {
}
