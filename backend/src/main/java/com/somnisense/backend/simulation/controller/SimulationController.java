package com.somnisense.backend.simulation.controller;

import com.somnisense.backend.security.AuthenticatedUser;
import com.somnisense.backend.simulation.dto.SimulationResponse;
import com.somnisense.backend.simulation.dto.StartSimulationRequest;
import com.somnisense.backend.simulation.service.SimulationLifecycleService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/** Simulator worker lifecycle control per active SIMULATED monitoring session. */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/simulation")
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
public class SimulationController {

    private final SimulationLifecycleService simulationLifecycleService;

    public SimulationController(SimulationLifecycleService simulationLifecycleService) {
        this.simulationLifecycleService = simulationLifecycleService;
    }

    @PostMapping("/start")
    public ResponseEntity<SimulationResponse> start(@PathVariable UUID sessionId,
                                                    @Valid @RequestBody(required = false) StartSimulationRequest request,
                                                    @AuthenticationPrincipal AuthenticatedUser actor) {
        SimulationLifecycleService.StartResult result = simulationLifecycleService.start(sessionId, request, actor.getId());
        if (!result.created()) {
            return ResponseEntity.ok(result.simulation());
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .replacePath("/api/v1/sessions/{sessionId}/simulation")
                .buildAndExpand(sessionId)
                .toUri();
        return ResponseEntity.created(location).body(result.simulation());
    }

    @PostMapping("/pause")
    public SimulationResponse pause(@PathVariable UUID sessionId,
                                    @AuthenticationPrincipal AuthenticatedUser actor) {
        return simulationLifecycleService.pause(sessionId, actor.getId());
    }

    @PostMapping("/resume")
    public SimulationResponse resume(@PathVariable UUID sessionId,
                                     @AuthenticationPrincipal AuthenticatedUser actor) {
        return simulationLifecycleService.resume(sessionId, actor.getId());
    }

    @PostMapping("/stop")
    public SimulationResponse stop(@PathVariable UUID sessionId,
                                   @AuthenticationPrincipal AuthenticatedUser actor) {
        return simulationLifecycleService.stop(sessionId, actor.getId());
    }

    @GetMapping
    public SimulationResponse get(@PathVariable UUID sessionId) {
        return simulationLifecycleService.get(sessionId);
    }
}
