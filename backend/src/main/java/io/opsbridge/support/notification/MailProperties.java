package io.opsbridge.support.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpsBridge's own mail config, distinct from {@code spring.mail.*} (the SMTP transport itself).
 *
 * @param enabled  false in tests, so no suite can ever reach SMTP.
 * @param notifyTo comma-separated; no assignee exists yet at declaration time, so this is the
 *                 team's intake address.
 */
@ConfigurationProperties(prefix = "opsbridge.mail")
public record MailProperties(boolean enabled, String from, String notifyTo) {

    /** Recipients as a trimmed array, empty if none are configured. */
    public String[] recipients() {
        if (notifyTo == null || notifyTo.isBlank()) {
            return new String[0];
        }
        return notifyTo.split("\\s*,\\s*");
    }
}
