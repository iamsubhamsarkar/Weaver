package com.weaver.api.controller;

import com.weaver.agent.WeaverAgent;
import com.weaver.api.dto.AgentResponse;
import com.weaver.api.dto.PromptRequest;
import com.weaver.provider.ProviderRegistry;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final WeaverAgent agent;
    private final ProviderRegistry providerRegistry;

    public AgentController(WeaverAgent agent, ProviderRegistry providerRegistry) {
        this.agent = agent;
        this.providerRegistry = providerRegistry;
    }

    @PostMapping("/execute")
    public ResponseEntity<AgentResponse> execute(@Valid @RequestBody PromptRequest request) {
        String sessionId = request.getSessionId() != null
                ? request.getSessionId()
                : UUID.randomUUID().toString().substring(0, 8);

        long start = System.currentTimeMillis();
        String result = agent.execute(request.getPrompt(), sessionId);
        long elapsed = System.currentTimeMillis() - start;

        return ResponseEntity.ok(AgentResponse.builder()
                .solution(result)
                .cacheHit(false) // simplified for now
                .providerUsed(providerRegistry.getPrimaryProviderName())
                .sessionId(sessionId)
                .durationMs(elapsed)
                .build());
    }

    @GetMapping("/providers")
    public ResponseEntity<?> getProviders() {
        var providers = providerRegistry.getAllProviders().stream()
                .map(p -> Map.of(
                        "name", p.name(),
                        "priority", p.priority(),
                        "contextWindow", p.contextWindow()
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("providers", providers, "count", providers.size()));
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        int count = providerRegistry.getAllProviders().size();
        return ResponseEntity.ok(Map.of(
                "status", count > 0 ? "healthy" : "degraded",
                "providers", count,
                "primary", providerRegistry.getPrimaryProviderName()
        ));
    }
}
