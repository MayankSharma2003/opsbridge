package io.opsbridge.controller;

import io.opsbridge.api.request.CreateServiceRequest;
import io.opsbridge.api.request.UpdateServiceRequest;
import io.opsbridge.api.response.ServiceResponse;
import io.opsbridge.domain.enums.ServiceStatus;
import io.opsbridge.security.OpsBridgePrincipal;
import io.opsbridge.security.policy.Policies;
import io.opsbridge.service.ServiceCatalogService;
import io.opsbridge.service.command.ArchiveServiceCommand;
import io.opsbridge.service.command.CreateServiceCommand;
import io.opsbridge.service.command.ServiceSearchCommand;
import io.opsbridge.service.command.UpdateServiceCommand;
import io.opsbridge.service.mapper.ServiceMapper;
import io.opsbridge.support.pagination.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Reads are open to any role; every mutation is Admin-only -- Operators are refused here unlike incidents (spec 3.2 case 3).
// Actor comes from the token principal, not the body, so edits can't be attributed to someone else.
@RestController
@RequestMapping("/api/v1/services")
public class ServiceController {

    private final ServiceCatalogService serviceCatalogService;
    private final ServiceMapper serviceMapper;

    public ServiceController(ServiceCatalogService serviceCatalogService, ServiceMapper serviceMapper) {
        this.serviceCatalogService = serviceCatalogService;
        this.serviceMapper = serviceMapper;
    }

    @GetMapping
    @PreAuthorize(Policies.AUTHENTICATED)
    public PageResponse<ServiceResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) ServiceStatus status,
            @RequestParam(required = false) String team,
            @PageableDefault(size = 25, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        ServiceSearchCommand search = new ServiceSearchCommand(q, status, team);
        return PageResponse.from(serviceCatalogService.list(search, pageable), serviceMapper::toResponse);
    }

    @GetMapping("/{id}")
    @PreAuthorize(Policies.AUTHENTICATED)
    public ServiceResponse get(@PathVariable UUID id) {
        return serviceMapper.toResponse(serviceCatalogService.findByIdWithOpenCount(id));
    }

    @PostMapping
    @PreAuthorize(Policies.ADMIN)
    public ResponseEntity<ServiceResponse> create(
            @Valid @RequestBody CreateServiceRequest request,
            @AuthenticationPrincipal OpsBridgePrincipal principal) {
        CreateServiceCommand command = new CreateServiceCommand(
                principal.userId(),
                request.name(),
                request.description(),
                request.ownerId(),
                request.team(),
                request.escalationContact(),
                request.availabilityTarget());
        ServiceResponse created = serviceMapper.toResponse(serviceCatalogService.create(command));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{id}")
    @PreAuthorize(Policies.ADMIN)
    public ServiceResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateServiceRequest request,
            @AuthenticationPrincipal OpsBridgePrincipal principal) {
        UpdateServiceCommand command = new UpdateServiceCommand(
                id,
                principal.userId(),
                request.name(),
                request.description(),
                request.ownerId(),
                request.team(),
                request.escalationContact(),
                request.availabilityTarget());
        return serviceMapper.toResponse(serviceCatalogService.update(command));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize(Policies.ADMIN)
    public ServiceResponse archive(
            @PathVariable UUID id, @AuthenticationPrincipal OpsBridgePrincipal principal) {
        return serviceMapper.toResponse(
                serviceCatalogService.archive(new ArchiveServiceCommand(id, principal.userId())));
    }
}
