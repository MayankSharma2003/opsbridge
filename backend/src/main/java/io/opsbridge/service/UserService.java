package io.opsbridge.service;

import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.Role;
import io.opsbridge.repository.UserRepository;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only user directory -- currently exists to back the incident assignee picker. */
@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public Page<User> list(Set<Role> roles, Pageable pageable) {
        if (roles == null || roles.isEmpty()) {
            return userRepository.findAll(pageable);
        }
        return userRepository.findByRoleIn(roles, pageable);
    }
}
