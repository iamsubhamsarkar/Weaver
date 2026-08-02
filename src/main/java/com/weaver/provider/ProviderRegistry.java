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

    // ─── TIER 1: NVIDIA NIM (Priority 0 — frontier models, free, GPU-hosted) ───
    @Value("${weaver.providers.nvidia.api-key:}")
    private String nvidiaApiKey;
    @Value("${weaver.providers.nvidia.enabled:true}")
    private boolean nvidiaEnabled;

    // ─── TIER 2: Free cloud APIs ───
    @Value("${weaver.providers.groq.api-key:}")
    private String groqApiKey;
    @Value("${weaver.providers.groq.enabled:true}")
    private boolean groqEnabled;

    @Value("${weaver.providers.gemini.api-key:}")
    private String geminiApiKey;
    @Value("${weaver.providers.gemini.enabled:true}")
    private boolean geminiEnabled;

    @Value("${weaver.providers.cerebras.api-key:}")
    private String cerebrasApiKey;
    @Value("${weaver.providers.cerebras.enabled:true}")
    private boolean cerebrasEnabled;

    @Value("${weaver.providers.mistral.api-key:}")
    private String mistralApiKey;
    @Value("${weaver.providers.mistral.enabled:true}")
    private boolean mistralEnabled;

    @Value("${weaver.providers.openrouter.api-key:}")
    private String openrouterApiKey;
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
        // ─── TIER 1: NVIDIA NIM — Frontier models, free, OpenAI-compatible ───
        // Endpoint: https://integrate.api.nvidia.com/v1 (confirmed Aug 2026)
        // Models: Nemotron Ultra 253B, Nemotron Super 49B v1.5, Nemotron Super 120B, DeepSeek V4
        // Rate: ~40 RPM free tier, GPU-hosted
        if (nvidiaEnabled && !nvidiaApiKey.isBlank()) {
            registerNvidiaModel("nim/nemotron-ultra-253b", "nvidia/llama-3.1-nemotron-ultra-253b-v1",
                    0, 131072, 40);
            registerNvidiaModel("nim/nemotron-super-49b", "nvidia/llama-3.3-nemotron-super-49b-v1.5",
                    0, 131072, 40);
            registerNvidiaModel("nim/nemotron-super-120b", "nvidia/nemotron-3-super-120b-a12b",
                    0, 131072, 40);
            registerNvidiaModel("nim/deepseek-v4-flash", "deepseek-ai/deepseek-v4-flash",
                    0, 131072, 40);
            registerNvidiaModel("nim/qwen3-coder-480b", "qwen/qwen3-coder-480b-a35b-instruct",
                    0, 131072, 40);
        }

        // ─── TIER 2: Free cloud APIs ────────────────────────────────

        // ─── Groq: 3 models, same API key ───────────────────────────
        if (groqEnabled && !groqApiKey.isBlank()) {
            registerGroqModel("groq/gpt-oss-20b", "openai/gpt-oss-20b", 1, 131072, 30);
            registerGroqModel("groq/llama-3.1-8b", "llama-3.1-8b-instant", 2, 131072, 30);
            registerGroqModel("groq/llama-3.3-70b", "llama-3.3-70b-versatile", 3, 131072, 30);
        }

        // ─── Mistral: 7 models, same API key ────────────────────────
        // API IDs verified from docs.mistral.ai/models/overview (July 2026)
        if (mistralEnabled && !mistralApiKey.isBlank()) {
            registerMistralModel("mistral/medium-3.5", "mistral-medium-latest", 4, 256000, 30);
            registerMistralModel("mistral/devstral", "devstral-2512", 5, 131072, 30);
            registerMistralModel("mistral/small-4", "mistral-small-latest", 6, 256000, 30);
            registerMistralModel("mistral/large-3", "mistral-large-latest", 7, 131072, 30);
            registerMistralModel("mistral/ministral-14b", "ministral-14b-2512", 8, 256000, 30);
            registerMistralModel("mistral/ministral-8b", "ministral-8b-2512", 9, 256000, 30);
            registerMistralModel("mistral/ministral-3b", "ministral-3b-2512", 10, 256000, 30);
        }

        // ─── Gemini: 1 model ────────────────────────────────────────
        if (geminiEnabled && !geminiApiKey.isBlank()) {
            providers.add(new ProviderEntry("gemini/flash",
                GoogleAiGeminiChatModel.builder()
                    .apiKey(geminiApiKey)
                    .modelName("gemini-2.0-flash")
                    .maxOutputTokens(8192)
                    .maxRetries(1)
                    .timeout(Duration.ofSeconds(90))
                    .build(),
                11, 1048576, 15));
            log.info("✓ gemini/flash registered (gemini-2.0-flash, RPM: 15)");
        }

        // ─── Cerebras: 1 model ──────────────────────────────────────
        if (cerebrasEnabled && !cerebrasApiKey.isBlank()) {
            registerOpenAiCompatible("cerebras/gemma-4-31b", "https://api.cerebras.ai/v1",
                cerebrasApiKey, "gemma-4-31b", 12, 131072, 30);
        }

        // ─── OpenRouter: 1 model ────────────────────────────────────
        if (openrouterEnabled && !openrouterApiKey.isBlank()) {
            registerOpenAiCompatible("openrouter/nemotron-120b", "https://openrouter.ai/api/v1",
                openrouterApiKey, "nvidia/nemotron-3-super-120b-a12b:free", 13, 131072, 20);
        }

        if (providers.isEmpty()) {
            log.error("⚠ No AI providers configured!");
        } else {
            log.info("Registered {} AI model entries across providers", providers.size());
        }
    }

    // ─── Registration helpers ─────────────────────────────────────────

    private void registerGroqModel(String name, String modelName, int priority, long contextWindow, int rpmLimit) {
        providers.add(new ProviderEntry(name,
            OpenAiChatModel.builder()
                .baseUrl("https://api.groq.com/openai/v1")
                .apiKey(groqApiKey)
                .modelName(modelName)
                .maxTokens(4096)
                .timeout(Duration.ofSeconds(60))
                .maxRetries(1)
                .build(),
            priority, contextWindow, rpmLimit));
        streamingModels.put(name, OpenAiStreamingChatModel.builder()
                .baseUrl("https://api.groq.com/openai/v1")
                .apiKey(groqApiKey)
                .modelName(modelName)
                .maxTokens(4096)
                .timeout(Duration.ofSeconds(60))
                .build());
        log.info("✓ {} registered (model: {}, RPM: {})", name, modelName, rpmLimit);
    }

    private void registerMistralModel(String name, String modelName, int priority, long contextWindow, int rpmLimit) {
        providers.add(new ProviderEntry(name,
            OpenAiChatModel.builder()
                .baseUrl("https://api.mistral.ai/v1")
                .apiKey(mistralApiKey)
                .modelName(modelName)
                .maxTokens(4096)
                .timeout(Duration.ofSeconds(60))
                .maxRetries(1)
                .build(),
            priority, contextWindow, rpmLimit));
        streamingModels.put(name, OpenAiStreamingChatModel.builder()
                .baseUrl("https://api.mistral.ai/v1")
                .apiKey(mistralApiKey)
                .modelName(modelName)
                .maxTokens(4096)
                .timeout(Duration.ofSeconds(60))
                .build());
        log.info("✓ {} registered (model: {}, RPM: {})", name, modelName, rpmLimit);
    }

    private void registerNvidiaModel(String name, String modelName, int priority, long contextWindow, int rpmLimit) {
        providers.add(new ProviderEntry(name,
            OpenAiChatModel.builder()
                .baseUrl("https://integrate.api.nvidia.com/v1")
                .apiKey(nvidiaApiKey)
                .modelName(modelName)
                .maxTokens(4096)
                .timeout(Duration.ofSeconds(90))
                .maxRetries(1)
                .build(),
            priority, contextWindow, rpmLimit));
        streamingModels.put(name, OpenAiStreamingChatModel.builder()
                .baseUrl("https://integrate.api.nvidia.com/v1")
                .apiKey(nvidiaApiKey)
                .modelName(modelName)
                .maxTokens(4096)
                .timeout(Duration.ofSeconds(90))
                .build());
        log.info("✓ {} registered [TIER 1 NIM] (model: {}, RPM: {})", name, modelName, rpmLimit);
    }

    private void registerOpenAiCompatible(String name, String baseUrl, String apiKey,
                                           String modelName, int priority, long contextWindow, int rpmLimit) {
        providers.add(new ProviderEntry(name,
            OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(4096)
                .timeout(Duration.ofSeconds(60))
                .maxRetries(1)
                .build(),
            priority, contextWindow, rpmLimit));
        streamingModels.put(name, OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(4096)
                .timeout(Duration.ofSeconds(60))
                .build());
        log.info("✓ {} registered (model: {}, RPM: {})", name, modelName, rpmLimit);
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
