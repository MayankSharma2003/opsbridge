package io.opsbridge.controller;

import io.opsbridge.api.response.UserSummaryResponse;
import io.opsbridge.domain.enums.Role;
import io.opsbridge.security.policy.Policies;
import io.opsbridge.service.UserService;
import io.opsbridge.service.mapper.UserMapper;
import io.opsbridge.support.pagination.PageResponse;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Read-only user directory, open to any authenticated role.
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;

    public UserController(UserService userService, UserMapper userMapper) {
        this.userService = userService;
        this.userMapper = userMapper;
    }

    @GetMapping
    @PreAuthorize(Policies.AUTHENTICATED)
    public PageResponse<UserSummaryResponse> list(
            @RequestParam(required = false) Set<Role> role,
            @PageableDefault(size = 25, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        return PageResponse.from(userService.list(role, pageable), userMapper::toSummary);
    }
}
