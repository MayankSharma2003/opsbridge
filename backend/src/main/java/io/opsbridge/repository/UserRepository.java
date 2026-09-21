package io.opsbridge.repository;

import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.Role;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    /** Backs the assignee picker (GET /users?role=) -- e.g. narrowing candidates to Operator/Admin. */
    Page<User> findByRoleIn(Collection<Role> roles, Pageable pageable);
}
