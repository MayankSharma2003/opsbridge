package io.opsbridge.controller;

import io.opsbridge.api.request.AssignIncidentRequest;
import io.opsbridge.api.request.CreateIncidentRequest;
import io.opsbridge.api.request.EscalateIncidentRequest;
import io.opsbridge.api.request.TransitionIncidentRequest;
import io.opsbridge.api.request.UpdateIncidentRequest;
import io.opsbridge.api.response.IncidentActivityResponse;
import io.opsbridge.api.response.IncidentResponse;
import io.opsbridge.api.response.IncidentSummaryResponse;
import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Severity;
import io.opsbridge.security.OpsBridgePrincipal;
import io.opsbridge.security.policy.Policies;
import io.opsbridge.service.IncidentService;
import io.opsbridge.service.command.AssignIncidentCommand;
import io.opsbridge.service.command.CreateIncidentCommand;
import io.opsbridge.service.command.EscalateIncidentCommand;
import io.opsbridge.service.command.IncidentSearchCommand;
import io.opsbridge.service.command.TransitionIncidentCommand;
import io.opsbridge.service.command.UpdateIncidentCommand;
import io.opsbridge.service.mapper.IncidentActivityMapper;
import io.opsbridge.service.mapper.IncidentMapper;
import io.opsbridge.support.pagination.PageResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
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

// @PreAuthorize here is on top of the SecurityConfig matchers, not instead of them.
@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    private final IncidentService incidentService;
    private final IncidentMapper incidentMapper;
    private final IncidentActivityMapper incidentActivityMapper;

    public IncidentController(
            IncidentService incidentService,
            IncidentMapper incidentMapper,
            IncidentActivityMapper incidentActivityMapper) {
        this.incidentService = incidentService;
        this.incidentMapper = incidentMapper;
        this.incidentActivityMapper = incidentActivityMapper;
    }

    @GetMapping
    @PreAuthorize(Policies.AUTHENTICATED)
    public PageResponse<IncidentSummaryResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(required = false) Priority priority,
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) UUID serviceId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @PageableDefault(size = 25, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        IncidentSearchCommand search = new IncidentSearchCommand(q, status, priority, severity, serviceId, from, to);
        return PageResponse.from(incidentService.list(search, pageable), incidentMapper::toSummary);
    }

    @GetMapping("/{id}")
    @PreAuthorize(Policies.AUTHENTICATED)
    public IncidentResponse get(@PathVariable UUID id) {
        return incidentMapper.toResponse(incidentService.findById(id));
    }

    @GetMapping("/{id}/activity")
    @PreAuthorize(Policies.AUTHENTICATED)
    public PageResponse<IncidentActivityResponse> activity(
            @PathVariable UUID id,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        return PageResponse.from(incidentService.listActivity(id, pageable), incidentActivityMapper::toResponse);
    }

    @PostMapping
    @PreAuthorize(Policies.OPERATOR_OR_ADMIN)
    public ResponseEntity<IncidentResponse> create(
            @Valid @RequestBody CreateIncidentRequest request,
            @AuthenticationPrincipal OpsBridgePrincipal principal) {
        // Reporter is always the caller -- can't be spoofed via the body.
        CreateIncidentCommand command = new CreateIncidentCommand(
                request.title(),
                request.description(),
                request.serviceId(),
                principal.userId(),
                request.priority(),
                request.severity());
        IncidentResponse created = incidentMapper.toResponse(incidentService.create(command));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{id}")
    @PreAuthorize(Policies.OPERATOR_OR_ADMIN)
    public IncidentResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateIncidentRequest request,
            @AuthenticationPrincipal OpsBridgePrincipal principal) {
        UpdateIncidentCommand command = new UpdateIncidentCommand(
                id,
                request.expectedVersion(),
                principal.userId(),
                request.title(),
                request.description(),
                request.serviceId(),
                request.resolutionNotes());
        return incidentMapper.toResponse(incidentService.update(command));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize(Policies.OPERATOR_OR_ADMIN)
    public IncidentResponse transition(
            @PathVariable UUID id,
            @Valid @RequestBody TransitionIncidentRequest request,
            @AuthenticationPrincipal OpsBridgePrincipal principal) {
        TransitionIncidentCommand command = new TransitionIncidentCommand(
                id,
                request.expectedVersion(),
                principal.userId(),
                request.targetStatus(),
                request.comment(),
                request.resolutionNotes());
        return incidentMapper.toResponse(incidentService.transition(command));
    }

    @PatchMapping("/{id}/assign")
    @PreAuthorize(Policies.OPERATOR_OR_ADMIN)
    public IncidentResponse assign(
            @PathVariable UUID id,
            @Valid @RequestBody AssignIncidentRequest request,
            @AuthenticationPrincipal OpsBridgePrincipal principal) {
        AssignIncidentCommand command =
                new AssignIncidentCommand(id, request.expectedVersion(), principal.userId(), request.assigneeId());
        return incidentMapper.toResponse(incidentService.assign(command));
    }

    @PatchMapping("/{id}/escalate")
    @PreAuthorize(Policies.OPERATOR_OR_ADMIN)
    public IncidentResponse escalate(
            @PathVariable UUID id,
            @Valid @RequestBody EscalateIncidentRequest request,
            @AuthenticationPrincipal OpsBridgePrincipal principal) {
        EscalateIncidentCommand command = new EscalateIncidentCommand(
                id,
                request.expectedVersion(),
                principal.userId(),
                request.priority(),
                request.severity(),
                request.reason());
        return incidentMapper.toResponse(incidentService.escalate(command));
    }
}
