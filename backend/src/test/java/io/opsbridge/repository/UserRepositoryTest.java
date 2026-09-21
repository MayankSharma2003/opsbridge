package io.opsbridge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.Role;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/** Backs the assignee picker's role filter (GET /users?role=). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.save(new User("Dev Viewer", "viewer@opsbridge.local", "hash", Role.VIEWER));
        userRepository.save(new User("Dev Operator", "operator@opsbridge.local", "hash", Role.OPERATOR));
        userRepository.save(new User("Dev Admin", "admin@opsbridge.local", "hash", Role.ADMIN));
    }

    @Test
    void findByRoleIn_returnsOnlyMatchingRoles() {
        Page<User> result = userRepository.findByRoleIn(Set.of(Role.OPERATOR, Role.ADMIN), PageRequest.of(0, 25));

        assertThat(result.getContent()).extracting(User::getRole).containsExactlyInAnyOrder(Role.OPERATOR, Role.ADMIN);
    }

    @Test
    void findByRoleIn_returnsEmptyForARoleWithNoUsers() {
        // An empty role set matches nobody, even though fixtures exist.
        Page<User> result = userRepository.findByRoleIn(Set.of(), PageRequest.of(0, 25));

        assertThat(result.getContent()).isEmpty();
    }
}
