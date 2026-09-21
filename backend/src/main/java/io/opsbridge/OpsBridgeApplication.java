package io.opsbridge;

import io.opsbridge.security.jwt.JwtProperties;
import io.opsbridge.support.notification.MailProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * UserDetailsServiceAutoConfiguration is excluded: auth is handled entirely by AuthService (JWT,
 * no AuthenticationManager), so Spring Security's fallback would otherwise log a generated
 * in-memory user password on every startup for a user nothing ever uses.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties({JwtProperties.class, MailProperties.class})
@EnableScheduling
public class OpsBridgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsBridgeApplication.class, args);
    }
}
