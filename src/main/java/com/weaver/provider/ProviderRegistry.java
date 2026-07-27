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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    @Value("${weaver.providers.groq.api-key:}")
    private String groqApiKey;
    @Value("${weaver.providers.groq.model:llama-3.3-70b-versatile}")
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
    @Value("${weaver.providers.cerebras.model:llama-3.3-70b}")
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
    @Value("${weaver.providers.openrouter.model:meta-llama/llama-3.3-70b-instruct:free}")
    private String openrouterModel;
    @Value("${weaver.providers.openrouter.enabled:true}")
    private boolean openrouterEnabled;

    private final List<ProviderEntry> providers = new ArrayList<>();
    private final Map<String, AtomicInteger> failureCounts = new ConcurrentHashMap<>();
    private final Map<String, StreamingChatLanguageModel> streamingModels = new HashMap<>();

    public record ProviderEntry(String name, ChatLanguageModel model, int priority, long contextWindow) {}

    @PostConstruct
    public void init() {
        // Priority: Groq (1) -> Gemini (2) -> Cerebras (3) -> Mistral (4) -> OpenRouter (5)

        if (groqEnabled && !groqApiKey.isBlank()) {
            providers.add(new ProviderEntry("groq",
                OpenAiChatModel.builder()
                    .baseUrl("https://api.groq.com/openai/v1")
                    .apiKey(groqApiKey)
                    .modelName(groqModel)
                    .maxTokens(4096)
                    .timeout(Duration.ofSeconds(60))
                    .build(),
                1, 131072));
            streamingModels.put("groq", OpenAiStreamingChatModel.builder()
                    .baseUrl("https://api.groq.com/openai/v1")
                    .apiKey(groqApiKey)
                    .modelName(groqModel)
                    .maxTokens(4096)
                    .timeout(Duration.ofSeconds(60))
                    .build());
            log.info("✓ Groq provider registered (model: {})", groqModel);
        }

        if (geminiEnabled && !geminiApiKey.isBlank()) {
            providers.add(new ProviderEntry("gemini",
                GoogleAiGeminiChatModel.builder()
                    .apiKey(geminiApiKey)
                    .modelName(geminiModel)
                    .maxOutputTokens(8192)
                    .timeout(Duration.ofSeconds(90))
                    .build(),
                2, 1048576));
            log.info("✓ Gemini provider registered (model: {})", geminiModel);
        }

        if (cerebrasEnabled && !cerebrasApiKey.isBlank()) {
            providers.add(new ProviderEntry("cerebras",
                OpenAiChatModel.builder()
                    .baseUrl("https://api.cerebras.ai/v1")
                    .apiKey(cerebrasApiKey)
                    .modelName(cerebrasModel)
                    .maxTokens(4096)
                    .timeout(Duration.ofSeconds(60))
                    .build(),
                3, 131072));
            streamingModels.put("cerebras", OpenAiStreamingChatModel.builder()
                    .baseUrl("https://api.cerebras.ai/v1")
                    .apiKey(cerebrasApiKey)
                    .modelName(cerebrasModel)
                    .maxTokens(4096)
                    .timeout(Duration.ofSeconds(60))
                    .build());
            log.info("✓ Cerebras provider registered (model: {})", cerebrasModel);
        }

        if (mistralEnabled && !mistralApiKey.isBlank()) {
            providers.add(new ProviderEntry("mistral",
                OpenAiChatModel.builder()
                    .baseUrl("https://api.mistral.ai/v1")
                    .apiKey(mistralApiKey)
                    .modelName(mistralModel)
                    .maxTokens(4096)
                    .timeout(Duration.ofSeconds(60))
                    .build(),
                4, 32768));
            streamingModels.put("mistral", OpenAiStreamingChatModel.builder()
                    .baseUrl("https://api.mistral.ai/v1")
                    .apiKey(mistralApiKey)
                    .modelName(mistralModel)
                    .maxTokens(4096)
                    .timeout(Duration.ofSeconds(60))
                    .build());
            log.info("✓ Mistral provider registered (model: {})", mistralModel);
        }

        if (openrouterEnabled && !openrouterApiKey.isBlank()) {
            providers.add(new ProviderEntry("openrouter",
                OpenAiChatModel.builder()
                    .baseUrl("https://openrouter.ai/api/v1")
                    .apiKey(openrouterApiKey)
                    .modelName(openrouterModel)
                    .maxTokens(4096)
                    .timeout(Duration.ofSeconds(60))
                    .build(),
                5, 131072));
            streamingModels.put("openrouter", OpenAiStreamingChatModel.builder()
                    .baseUrl("https://openrouter.ai/api/v1")
                    .apiKey(openrouterApiKey)
                    .modelName(openrouterModel)
                    .maxTokens(4096)
                    .timeout(Duration.ofSeconds(60))
                    .build());
            log.info("✓ OpenRouter provider registered (model: {})", openrouterModel);
        }

        providers.sort(Comparator.comparingInt(ProviderEntry::priority));

        if (providers.isEmpty()) {
            log.error("⚠ No AI providers configured! Add API keys to application.yml or ai-apis/*.properties");
        } else {
            log.info("Registered {} AI providers", providers.size());
        }
    }

    public ChatLanguageModel getPrimaryModel() {
        return providers.isEmpty() ? null : providers.get(0).model();
    }

    public String getPrimaryProviderName() {
        return providers.isEmpty() ? "none" : providers.get(0).name();
    }

    public ChatLanguageModel getNextModel(String failedProvider) {
        failureCounts.computeIfAbsent(failedProvider, k -> new AtomicInteger(0)).incrementAndGet();
        for (ProviderEntry entry : providers) {
            if (!entry.name().equals(failedProvider)) {
                int failures = failureCounts.getOrDefault(entry.name(), new AtomicInteger(0)).get();
                if (failures < 5) {
                    log.info("Falling back from {} to {}", failedProvider, entry.name());
                    return entry.model();
                }
            }
        }
        // Reset failures and try first available
        failureCounts.clear();
        return providers.isEmpty() ? null : providers.get(0).model();
    }

    public List<ProviderEntry> getAllProviders() {
        return Collections.unmodifiableList(providers);
    }

    public StreamingChatLanguageModel getStreamingModel(String providerName) {
        return streamingModels.get(providerName);
    }

    public StreamingChatLanguageModel getPrimaryStreamingModel() {
        if (providers.isEmpty()) return null;
        return streamingModels.get(providers.get(0).name());
    }

    public void recordSuccess(String providerName) {
        failureCounts.computeIfAbsent(providerName, k -> new AtomicInteger(0)).set(0);
    }

    public void recordFailure(String providerName) {
        failureCounts.computeIfAbsent(providerName, k -> new AtomicInteger(0)).incrementAndGet();
    }
}
