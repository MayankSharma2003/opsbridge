package io.opsbridge.controller;

import io.opsbridge.api.response.MeResponse;
import io.opsbridge.security.OpsBridgePrincipal;
import io.opsbridge.security.policy.Policies;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Lets the frontend recover user/role from a stored token on reload.
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    @GetMapping
    @PreAuthorize(Policies.AUTHENTICATED)
    public MeResponse me(@AuthenticationPrincipal OpsBridgePrincipal principal) {
        return new MeResponse(principal.userId(), principal.name(), principal.email(), principal.role());
    }
}
