package io.opsbridge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.Role;
import io.opsbridge.repository.UserRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class UserServiceTest {

    private UserRepository userRepository;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userService = new UserService(userRepository);
    }

    @Test
    void list_delegatesToFindAllWhenNoRolesAreRequested() {
        Pageable pageable = PageRequest.of(0, 25);
        Page<User> expected = new PageImpl<>(List.of(user(Role.VIEWER)));
        when(userRepository.findAll(pageable)).thenReturn(expected);

        Page<User> result = userService.list(null, pageable);

        assertThat(result).isSameAs(expected);
        verify(userRepository, never()).findByRoleIn(any(), any());
    }

    @Test
    void list_delegatesToFindAllWhenTheRoleSetIsEmpty() {
        Pageable pageable = PageRequest.of(0, 25);
        Page<User> expected = new PageImpl<>(List.of(user(Role.ADMIN)));
        when(userRepository.findAll(pageable)).thenReturn(expected);

        Page<User> result = userService.list(Set.of(), pageable);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void list_delegatesToFindByRoleInWhenRolesAreRequested() {
        Pageable pageable = PageRequest.of(0, 25);
        Set<Role> roles = Set.of(Role.OPERATOR, Role.ADMIN);
        Page<User> expected = new PageImpl<>(List.of(user(Role.OPERATOR)));
        when(userRepository.findByRoleIn(roles, pageable)).thenReturn(expected);

        Page<User> result = userService.list(roles, pageable);

        assertThat(result).isSameAs(expected);
        verify(userRepository, never()).findAll(pageable);
    }

    private static User user(Role role) {
        User user = new User("Test User", "user@opsbridge.local", "hash", role);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
