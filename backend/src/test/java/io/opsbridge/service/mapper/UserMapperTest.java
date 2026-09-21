package io.opsbridge.service.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.Role;
import org.junit.jupiter.api.Test;

// Deliberately null-tolerant: an unassigned incident or ownerless service maps through without a null-check.
class UserMapperTest {

    private final UserMapper mapper = new UserMapper();

    @Test
    void toSummary_copiesIdentityAndRole() {
        User user = new User("R. Villanueva", "rv@opsbridge.local", "hash", Role.OPERATOR);

        var summary = mapper.toSummary(user);

        assertThat(summary.name()).isEqualTo("R. Villanueva");
        assertThat(summary.email()).isEqualTo("rv@opsbridge.local");
        assertThat(summary.role()).isEqualTo(Role.OPERATOR);
    }

    @Test
    void toSummary_neverLeaksThePasswordHash() {
        User user = new User("R. Villanueva", "rv@opsbridge.local", "$2a$10$supersecret", Role.OPERATOR);

        // Pins that the response record has no field for it.
        assertThat(mapper.toSummary(user).toString()).doesNotContain("supersecret");
    }

    @Test
    void toSummary_returnsNullForNullRatherThanThrowing() {
        assertThat(mapper.toSummary(null)).isNull();
    }
}
