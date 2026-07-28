package com.weaver.provider;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    // Minimum delay between calls to the same provider (prevents RPM limit)
    private static final long MIN_CALL_INTERVAL_MS = 2500;

    @Value("${weaver.providers.groq.api-key:}")
    private String groqApiKey;
    @Value("${weaver.providers.groq.model:llama-3-groq-70b-8192-tool-use-preview}")
    private String groqModel;
    @Value("${weaver.providers.groq.enabled:true}")
    private boolean groqEnabled;

    @Value("${weaver.providers.gemini.api-key:}")
    private String geminiApiKey;
    @Value("${weaver.providers.gemini.model:gemini-2.0-flash}")
    private String geminiModel;
    @Value("${weaver.providers.gemini.enabled:true}")
    private boolean geminiEnabled;

    @Value("${weaver.providers.cerebras.api-key:}")
    private String cerebrasApiKey;
    @Value("${weaver.providers.cerebras.model:gemma-4-31b}")
    private String cerebrasModel;
    @Value("${weaver.providers.cerebras.enabled:true}")
    private boolean cerebrasEnabled;

    @Value("${weaver.providers.mistral.api-key:}")
    private String mistralApiKey;
    @Value("${weaver.providers.mistral.model:mistral-small-latest}")
    private String mistralModel;
    @Value("${weaver.providers.mistral.enabled:true}")
    private boolean mistralEnabled;

    @Value("${weaver.providers.openrouter.api-key:}")
    private String openrouterApiKey;
    @Value("${weaver.providers.openrouter.model:nvidia/nemotron-3-super-120b-a12b:free}")
    private String openrouterModel;
    @Value("${weaver.providers.openrouter.enabled:true}")
    private boolean openrouterEnabled;

    private final List<ProviderEntry> providers = new ArrayList<>();
    private final Map<String, StreamingChatLanguageModel> streamingModels = new HashMap<>();

    // Per-provider tracking
    private final Map<String, Instant> lastCallTime = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> callsThisMinute = new ConcurrentHashMap<>();
    private final Map<String, Instant> minuteWindowStart = new ConcurrentHashMap<>();
    private final Set<String> permanentlyDisabled = ConcurrentHashMap.newKeySet(); // kept empty, never used

    // Round-robin index
    private int roundRobinIndex = 0;

    public record ProviderEntry(String name, ChatLanguageModel model, int priority, long contextWindow, int rpmLimit) {}

    @PostConstruct
    public void init() {
        if (groqEnabled && !groqApiKey.isBlank()) {
            providers.add(new ProviderEntry("groq",
                OpenAiChatModel.builder()
                    .baseUrl("https://api.groq.com/openai/v1")
                    .apiKey(groqApiKey)
                    .modelName(groqModel)
                    .maxTokens(4096)
                    .timeout(Duration.ofSeconds(60))
                    .maxRetries(1)
                    .build(),
                1, 131072, 30));
            streamingModels.put("groq", OpenAiStreamingChatModel.builder()
                    .baseUrl("https://api.groq.com/openai/v1")
                    .apiKey(groqApiKey)
                    .modelName(groqModel)
                    .maxTokens(4096)
                    .timeout(Duration.ofSeconds(60))
                    .build());
            log.info("✓ Groq registered (model: {}, RPM: 30)", groqModel);
        }

        if (geminiEnabled && !geminiApiKey.isBlank()) {
            providers.add(new ProviderEntry("gemini",
                GoogleAiGeminiChatModel.builder()
                    .apiKey(geminiApiKey)
                    .modelName(geminiModel)
                    .maxOutputTokens(8192)
                    .maxRetries(1)
                    .timeout(Duration.ofSeconds(90))
                    .build(),
                2, 1048576, 15));
            log.info("✓ Gemini registered (model: {}, RPM: 15)", geminiModel);
        }

        if (cerebrasEnabled && !cerebrasApiKey.isBlank()) {
            providers.add(new ProviderEntry("cerebras",
                OpenAiChatModel.builder()
                    .baseUrl("https://api.cerebras.ai/v1")
                    .apiKey(cerebrasApiKey)
                    .modelName(cerebrasModel)
                    .maxTokens(4096)
                    .maxRetries(1)
                    .timeout(Duration.ofSeconds(60))
                    .build(),
                3, 131072, 30));
            streamingModels.put("cerebras", OpenAiStreamingChatModel.builder()
                    .baseUrl("https://api.cerebras.ai/v1")
                    .apiKey(cerebrasApiKey)
                    .modelName(cerebrasModel)
                    .maxTokens(4096)
                    .timeout(Duration.ofSeconds(60))
                    .build());
            log.info("✓ Cerebras registered (model: {})", cerebrasModel);
        }

        if (mistralEnabled && !mistralApiKey.isBlank()) {
            providers.add(new ProviderEntry("mistral",
                OpenAiChatModel.builder()
                    .baseUrl("https://api.mistral.ai/v1")
                    .apiKey(mistralApiKey)
                    .modelName(mistralModel)
                    .maxTokens(4096)
                    .maxRetries(1)
                    .timeout(Duration.ofSeconds(60))
                    .build(),
                4, 32768, 30));
            streamingModels.put("mistral", OpenAiStreamingChatModel.builder()
                    .baseUrl("https://api.mistral.ai/v1")
                    .apiKey(mistralApiKey)
                    .modelName(mistralModel)
                    .maxTokens(4096)
                    .timeout(Duration.ofSeconds(60))
                    .build());
            log.info("✓ Mistral registered (model: {})", mistralModel);
        }

        if (openrouterEnabled && !openrouterApiKey.isBlank()) {
            providers.add(new ProviderEntry("openrouter",
                OpenAiChatModel.builder()
                    .baseUrl("https://openrouter.ai/api/v1")
                    .apiKey(openrouterApiKey)
                    .modelName(openrouterModel)
                    .maxTokens(4096)
                    .maxRetries(1)
                    .timeout(Duration.ofSeconds(60))
                    .build(),
                5, 131072, 20));
            streamingModels.put("openrouter", OpenAiStreamingChatModel.builder()
                    .baseUrl("https://openrouter.ai/api/v1")
                    .apiKey(openrouterApiKey)
                    .modelName(openrouterModel)
                    .maxTokens(4096)
                    .timeout(Duration.ofSeconds(60))
                    .build());
            log.info("✓ OpenRouter registered (model: {})", openrouterModel);
        }

        if (providers.isEmpty()) {
            log.error("⚠ No AI providers configured!");
        } else {
            log.info("Registered {} AI providers", providers.size());
        }
    }

    /**
     * Get the best available provider using round-robin with rate-limit awareness.
     * Skips providers in the failedThisRequest set and permanently disabled ones.
     * Enforces minimum delay between calls to the same provider.
     */
    public ProviderEntry getAvailableProvider(Set<String> failedThisRequest) {
        int attempts = providers.size();

        for (int i = 0; i < attempts; i++) {
            roundRobinIndex = (roundRobinIndex + 1) % providers.size();
            ProviderEntry candidate = providers.get(roundRobinIndex);
            String name = candidate.name();

            // Skip if failed in this request already
            if (failedThisRequest.contains(name)) continue;

            // Skip if permanently disabled (capability failure)
            if (permanentlyDisabled.contains(name)) continue;

            // Skip if approaching RPM limit (proactive switch at 80%)
            if (isApproachingRateLimit(name, candidate.rpmLimit())) {
                log.info("Proactively skipping {} (approaching RPM limit)", name);
                continue;
            }

            // Enforce minimum delay between calls to same provider
            enforceCallDelay(name);

            // Track this call
            recordCall(name);

            return candidate;
        }

        // All providers are either failed or rate-limited — wait and retry
        log.info("All providers busy. Waiting 30s for rate limits to reset...");
        try { Thread.sleep(30000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // Reset RPM counters and try first available
        callsThisMinute.clear();
        minuteWindowStart.clear();

        for (ProviderEntry entry : providers) {
            if (!failedThisRequest.contains(entry.name()) && !permanentlyDisabled.contains(entry.name())) {
                recordCall(entry.name());
                return entry;
            }
        }

        return null; // Truly no providers available
    }

    /**
     * Get the primary (first) provider for initial call.
     */
    public ProviderEntry getPrimaryProvider() {
        for (ProviderEntry entry : providers) {
            if (!permanentlyDisabled.contains(entry.name())) {
                enforceCallDelay(entry.name());
                recordCall(entry.name());
                return entry;
            }
        }
        return providers.isEmpty() ? null : providers.get(0);
    }

    public ChatLanguageModel getPrimaryModel() {
        ProviderEntry entry = getPrimaryProvider();
        return entry != null ? entry.model() : null;
    }

    public String getPrimaryProviderName() {
        return providers.isEmpty() ? "none" : providers.get(0).name();
    }

    /**
     * Classify an error. Never permanently disable a provider —
     * all failures are treated as temporary (rate limits reset, networks recover).
     */
    public ErrorType classifyError(String providerName, Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        // Rate limit errors
        if (msg.contains("429") || msg.contains("rate_limit") || msg.contains("rate limit")
                || msg.contains("quota") || msg.contains("resource_exhausted")
                || msg.contains("too many requests")) {
            return ErrorType.RATE_LIMITED;
        }

        // Timeout or network issues
        if (msg.contains("timeout") || msg.contains("timed out") || msg.contains("connection")) {
            return ErrorType.NETWORK_ERROR;
        }

        // Everything else — treat as temporary, never permanently disable
        return ErrorType.UNKNOWN;
    }

    public enum ErrorType {
        RATE_LIMITED,
        NETWORK_ERROR,
        UNKNOWN
    }

    public void recordSuccess(String providerName) {
        // Success resets nothing — just confirms provider works
        log.debug("Provider {} succeeded", providerName);
    }

    public StreamingChatLanguageModel getStreamingModel(String providerName) {
        return streamingModels.get(providerName);
    }

    public StreamingChatLanguageModel getPrimaryStreamingModel() {
        if (providers.isEmpty()) return null;
        return streamingModels.get(providers.get(0).name());
    }

    public List<ProviderEntry> getAllProviders() {
        return Collections.unmodifiableList(providers);
    }

    public Set<String> getPermanentlyDisabled() {
        return Collections.unmodifiableSet(permanentlyDisabled);
    }

    // ─── Internal rate limiting ──────────────────────────────

    private boolean isApproachingRateLimit(String name, int rpmLimit) {
        AtomicInteger calls = callsThisMinute.get(name);
        Instant windowStart = minuteWindowStart.get(name);

        if (calls == null || windowStart == null) return false;

        // Reset if window expired
        if (Instant.now().isAfter(windowStart.plusSeconds(60))) {
            calls.set(0);
            minuteWindowStart.put(name, Instant.now());
            return false;
        }

        // Proactive switch at 80% of RPM limit
        return calls.get() >= (int) (rpmLimit * 0.8);
    }

    private void enforceCallDelay(String name) {
        Instant last = lastCallTime.get(name);
        if (last != null) {
            long elapsed = Instant.now().toEpochMilli() - last.toEpochMilli();
            if (elapsed < MIN_CALL_INTERVAL_MS) {
                long sleepTime = MIN_CALL_INTERVAL_MS - elapsed;
                try { Thread.sleep(sleepTime); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private void recordCall(String name) {
        lastCallTime.put(name, Instant.now());

        callsThisMinute.computeIfAbsent(name, k -> new AtomicInteger(0));
        minuteWindowStart.computeIfAbsent(name, k -> Instant.now());

        // Reset if window expired
        Instant windowStart = minuteWindowStart.get(name);
        if (Instant.now().isAfter(windowStart.plusSeconds(60))) {
            callsThisMinute.get(name).set(0);
            minuteWindowStart.put(name, Instant.now());
        }

        callsThisMinute.get(name).incrementAndGet();
    }
}
