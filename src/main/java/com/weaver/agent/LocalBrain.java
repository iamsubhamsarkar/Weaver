package com.weaver.agent;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.*;

/**
 * LocalBrain: Lightweight local intelligence for Weaver.
 *
 * Uses MiniCPM5-1B Q8 via Ollama for:
 * 1. Context management — scoring messages KEEP/COMPRESS/DROP
 * 2. Validation gates — verifying tool calls, outputs, cache relevance
 * 3. Summarization — compressing messages to 1-line summaries
 * 4. Search query extraction — generating focused web queries
 *
 * Also uses AllMiniLmL6V2 embeddings for:
 * - Semantic task classification (36+ prototype sentences)
 * - Embedding-based extractive summarization fallback
 *
 * MiniCPM5-1B Q8 was chosen because:
 * - 1.1 GB RAM, runs on any machine with Ollama
 * - Strong instruction following for its size
 * - Q8 quantization (NOT Q4 — Q4 degrades into repetition)
 * - Replaces Gemma 270M which had 50% instruction failure rate
 *
 * All runs locally. Zero API calls. ~1.1 GB RAM for MiniCPM5.
 */
@Component
public class LocalBrain {

    private static final Logger log = LoggerFactory.getLogger(LocalBrain.class);

    private final EmbeddingModel embeddingModel;
    private boolean minicpmAvailable = false;

    // Model name for Ollama — Qwen2 1.5B Instruct (best instruction-following at small size)
    // Alternative: llama3.2:1b or qwen2:0.5b-instruct for even smaller footprint
    private static final String LOCAL_MODEL = "qwen2:1.5b-instruct";

    // Pre-computed embeddings for task classification (multiple prototypes per category)
    private List<Embedding> codeGenEmbeddings;
    private List<Embedding> fileReadEmbeddings;
    private List<Embedding> bugFixEmbeddings;
    private List<Embedding> explainEmbeddings;
    private List<Embedding> shellCmdEmbeddings;
    private List<Embedding> searchEmbeddings;

    public LocalBrain(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        initClassificationEmbeddings();
        checkMiniCPMAvailability();
    }

    // ─── Task Classification ─────────────────────────────────────

    public enum TaskType {
        CODE_GENERATION,  // "create a login page", "build an API"
        FILE_READ,        // "read main.py", "show me the config"
        BUG_FIX,          // "fix the error", "debug this"
        EXPLAIN,          // "explain this code", "what does this do"
        SHELL_COMMAND,    // "run tests", "build the project"
        SEARCH,           // "find how to", "search for"
        UNKNOWN
    }

    /**
     * Classify the user's task using semantic similarity.
     * Compares the prompt's embedding against multiple prototype embeddings per category.
     * Uses the best match across all prototypes and a low threshold (0.15) since
     * AllMiniLmL6V2 produces scores in a narrow band for sentence-to-sentence comparisons.
     */
    public TaskType classifyTask(String userPrompt) {
        try {
            Embedding promptEmbedding = embeddingModel.embed(userPrompt).content();

            // Score against all prototypes per category, take the max per category
            Map<TaskType, Double> scores = new LinkedHashMap<>();
            scores.put(TaskType.CODE_GENERATION, maxSimilarity(promptEmbedding, codeGenEmbeddings));
            scores.put(TaskType.FILE_READ, maxSimilarity(promptEmbedding, fileReadEmbeddings));
            scores.put(TaskType.BUG_FIX, maxSimilarity(promptEmbedding, bugFixEmbeddings));
            scores.put(TaskType.EXPLAIN, maxSimilarity(promptEmbedding, explainEmbeddings));
            scores.put(TaskType.SHELL_COMMAND, maxSimilarity(promptEmbedding, shellCmdEmbeddings));
            scores.put(TaskType.SEARCH, maxSimilarity(promptEmbedding, searchEmbeddings));

            TaskType best = TaskType.UNKNOWN;
            double bestScore = 0.0;
            for (Map.Entry<TaskType, Double> entry : scores.entrySet()) {
                if (entry.getValue() > bestScore) {
                    bestScore = entry.getValue();
                    best = entry.getKey();
                }
            }

            // Minimum threshold of 0.15 — below that, it's genuinely unclassifiable
            log.info("  [LocalBrain] Classification scores: {}", scores);
            log.info("  [LocalBrain] Best={} (score={}), threshold=0.15 → result={}",
                    best, String.format("%.3f", bestScore), bestScore > 0.15 ? best : "UNKNOWN");
            return bestScore > 0.15 ? best : TaskType.UNKNOWN;
        } catch (Exception e) {
            log.warn("  [LocalBrain] Classification FAILED: {}", e.getMessage());
            return TaskType.UNKNOWN;
        }
    }

    private double maxSimilarity(Embedding prompt, List<Embedding> prototypes) {
        double max = 0.0;
        for (Embedding proto : prototypes) {
            double sim = cosineSimilarity(prompt, proto);
            if (sim > max) max = sim;
        }
        return max;
    }

    // ─── Search Query Extraction ─────────────────────────────────

    /**
     * Extract a smart web search query from the user's prompt.
     * Uses few-shot pattern completion for Qwen2 1.5B.
     */
    public String extractSearchQuery(String userPrompt) {
        // Try local model first if available
        if (minicpmAvailable) {
            // Few-shot pattern completion — no instructions, just examples
            String prompt = "IN: Create a REST API with authentication in Spring Boot\n"
                    + "OUT: REST API authentication Spring Boot tutorial\n\n"
                    + "IN: Fix the CORS error in my React app calling Express backend\n"
                    + "OUT: CORS error React Express fix\n\n"
                    + "IN: Build a login page with gradient background and form validation\n"
                    + "OUT: login page HTML CSS gradient form validation\n\n"
                    + "IN: " + (userPrompt.length() > 100 ? userPrompt.substring(0, 100) : userPrompt) + "\n"
                    + "OUT:";
            String result = runLocalModel(prompt);
            if (result != null && !result.isBlank() && !result.contains("search for")) return result.trim();
        }

        // Fallback: NLP-based extraction
        return nlpExtractQuery(userPrompt);
    }

    /**
     * NLP-based search query extraction (no model needed).
     * Extracts technology keywords, removes filler words, builds a focused query.
     */
    private String nlpExtractQuery(String userPrompt) {
        String input = userPrompt.toLowerCase().trim();

        // Remove common filler phrases
        String[] fillers = {
            "can you ", "could you ", "please ", "i want to ", "i need to ",
            "help me ", "make me ", "build me ", "create me ", "write me ",
            "i want ", "i need ", "let's ", "we need to ", "we should ",
            "follow this ", "follow ", "and build the required thing",
            "the required thing", "as per ", "according to "
        };
        for (String filler : fillers) {
            input = input.replace(filler, " ");
        }

        // Extract technology-related keywords
        List<String> techKeywords = extractTechKeywords(input);

        // Extract action keywords
        List<String> actionKeywords = extractActionKeywords(input);

        // Build search query
        StringBuilder query = new StringBuilder();
        if (!actionKeywords.isEmpty()) {
            query.append(String.join(" ", actionKeywords)).append(" ");
        }
        if (!techKeywords.isEmpty()) {
            query.append(String.join(" ", techKeywords));
        } else {
            // If no tech keywords found, use cleaned input (first 80 chars)
            String cleaned = input.replaceAll("\\s+", " ").trim();
            if (cleaned.length() > 80) cleaned = cleaned.substring(0, 80);
            query.append(cleaned);
        }

        String result = query.toString().trim();
        if (!result.isEmpty()) {
            result += " example code 2024";
        }
        return result.isEmpty() ? null : result;
    }

    private List<String> extractTechKeywords(String input) {
        List<String> keywords = new ArrayList<>();
        // Technology patterns
        String[] techPatterns = {
            "html", "css", "javascript", "typescript", "react", "vue", "angular", "svelte",
            "python", "java", "spring boot", "spring", "node\\.?js", "express",
            "rest api", "api", "graphql", "websocket",
            "docker", "kubernetes", "aws", "gcp", "azure",
            "mongodb", "postgresql", "mysql", "redis", "sqlite",
            "login", "authentication", "jwt", "oauth",
            "tailwind", "bootstrap", "gradient", "responsive",
            "crud", "pagination", "form", "validation",
            "git", "ci/cd", "pipeline", "deploy",
            "test", "jest", "pytest", "junit",
            "flutter", "swift", "kotlin", "go", "rust",
            "machine learning", "neural network", "tensorflow", "pytorch"
        };

        for (String pattern : techPatterns) {
            if (Pattern.compile("\\b" + pattern + "\\b").matcher(input).find()) {
                keywords.add(pattern.replace("\\.", "").replace("\\b", ""));
            }
        }
        return keywords;
    }

    private List<String> extractActionKeywords(String input) {
        List<String> actions = new ArrayList<>();
        String[] actionPatterns = {"login page", "signup", "dashboard", "form", "navigation",
            "landing page", "portfolio", "todo app", "chat app", "e-commerce",
            "rate limiting", "error handling", "middleware", "routing"};

        for (String pattern : actionPatterns) {
            if (input.contains(pattern)) {
                actions.add(pattern);
            }
        }
        return actions;
    }

    // ─── Context Summarization ───────────────────────────────────

    /**
     * Summarize a tool result intelligently.
     * Uses few-shot pattern completion for Qwen2 1.5B.
     */
    public String summarizeForContext(String toolName, String content, String userPrompt) {
        if (content == null || content.length() <= 300) return content;

        // Try local model with few-shot pattern
        if (minicpmAvailable) {
            String truncated = content.length() > 800 ? content.substring(0, 800) : content;
            // Few-shot: show the exact output format expected
            String prompt = "IN: [File about React todo app with useState, useEffect, localStorage]\n"
                    + "SUM: React todo app using hooks and localStorage for persistence\n\n"
                    + "IN: [Directory listing: src/, package.json, README.md, tests/, .gitignore]\n"
                    + "SUM: Node.js project with src, tests, and standard config files\n\n"
                    + "IN: [" + truncated.substring(0, Math.min(200, truncated.length())) + "...]\n"
                    + "SUM:";
            String result = runLocalModel(prompt);
            if (result != null && !result.isBlank() && result.length() < 200) return result.trim();
        }

        // Fallback: embedding-based extractive summarization
        return embeddingSummarize(content, userPrompt);
    }

    /**
     * Embedding-based extractive summarization.
     * Picks the lines most semantically relevant to the user's prompt.
     */
    private String embeddingSummarize(String content, String userPrompt) {
        try {
            String[] lines = content.split("\n");
            if (lines.length <= 8) return content;

            Embedding promptEmb = embeddingModel.embed(userPrompt).content();

            // Score each line by relevance to the user's prompt
            List<Map.Entry<Integer, Double>> scored = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty() || line.length() < 5) continue;

                Embedding lineEmb = embeddingModel.embed(line).content();
                double score = cosineSimilarity(promptEmb, lineEmb);
                scored.add(Map.entry(i, score));
            }

            // Sort by score, take top 8 lines
            scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            List<Integer> topIndices = new ArrayList<>();
            for (int i = 0; i < Math.min(8, scored.size()); i++) {
                topIndices.add(scored.get(i).getKey());
            }
            Collections.sort(topIndices); // Preserve original order

            StringBuilder summary = new StringBuilder();
            summary.append("[Summary: ").append(lines.length).append(" lines → top 8 relevant]\n");
            for (int idx : topIndices) {
                summary.append(lines[idx]).append("\n");
            }
            return summary.toString();
        } catch (Exception e) {
            // Fallback to simple truncation
            return content.substring(0, Math.min(300, content.length())) + "... [truncated]";
        }
    }

    // ─── MiniCPM5-1B Q8 via Ollama HTTP API ─────────────────────

    private static final String OLLAMA_API_URL = "http://localhost:11434/api/generate";

    private void checkMiniCPMAvailability() {
        try {
            // Check if Ollama API is reachable and minicpm5 model is available
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(3)).build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:11434/api/tags"))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .GET().build();

            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String body = response.body();
                // Check for our preferred model (qwen2 1.5b)
                if (body.contains("qwen2")) {
                    minicpmAvailable = true;
                    log.info("✓ Local brain available (Ollama API + Qwen2 1.5B)");
                    preWarmOllama();
                } else if (body.contains("llama3.2") || body.contains("llama3:")) {
                    // Fallback: Llama 3.2 1B works too
                    minicpmAvailable = true;
                    log.info("✓ Local brain available (Ollama API + Llama). Recommended: ollama pull qwen2:1.5b-instruct");
                    preWarmOllama();
                } else if (body.contains("gemma")) {
                    // Fallback: old Gemma model still works (just less reliable)
                    minicpmAvailable = true;
                    log.info("⚠ Using Gemma fallback. Recommended: ollama pull qwen2:1.5b-instruct");
                    preWarmOllama();
                } else {
                    minicpmAvailable = false;
                    log.info("No local model found in Ollama. Run: ollama pull qwen2:1.5b-instruct");
                }
            } else {
                minicpmAvailable = false;
                log.info("Ollama API returned status {}. Using embedding-only mode.", response.statusCode());
            }
        } catch (Exception e) {
            minicpmAvailable = false;
            log.info("Ollama API not reachable at localhost:11434. Using embedding-only mode.");
        }
    }

    /**
     * Pre-warm: send a tiny request to load the model into RAM via API.
     * Non-blocking (async), and sets keep_alive to 60 minutes.
     */
    private void preWarmOllama() {
        Thread warmupThread = new Thread(() -> {
            try {
                log.info("  Pre-warming Ollama via API (loading Qwen2 into RAM)...");
                String result = callOllamaApi("hi", 5, 20);
                if (result != null) {
                    log.info("  ✓ Qwen2 pre-warmed. Model in RAM for 60 min.");
                } else {
                    log.warn("  Pre-warm returned null. First call may be slow.");
                }
            } catch (Exception e) {
                log.warn("  Pre-warm failed: {}", e.getMessage());
            }
        });
        warmupThread.setDaemon(true);
        warmupThread.start();
    }

    /**
     * Run a prompt through MiniCPM5 via Ollama's HTTP API.
     * Uses a Weaver-specific system prompt for consistent behavior.
     * Timeout: 8s strict. Returns null if unavailable or slow.
     */
    private String runLocalModel(String prompt) {
        if (!minicpmAvailable) {
            log.info("  [LocalBrain] MiniCPM5 NOT available — skipping (pass-through)");
            return null;
        }

        long startMs = System.currentTimeMillis();
        log.info("  [LocalBrain] Calling MiniCPM5 via Ollama API...");
        log.debug("  [LocalBrain] Prompt: {}", prompt.length() > 150 ? prompt.substring(0, 150) + "..." : prompt);

        String result = callOllamaApi(prompt, 30, 8);
        long elapsed = System.currentTimeMillis() - startMs;

        if (result != null) {
            log.info("  [LocalBrain] MiniCPM5 responded in {}ms: '{}'", elapsed,
                    result.length() > 100 ? result.substring(0, 100) + "..." : result);
        } else {
            log.warn("  [LocalBrain] MiniCPM5 returned null after {}ms", elapsed);
        }
        return result;
    }

    /**
     * Call Ollama HTTP API directly (no process spawning).
     * Model stays loaded in memory for 60 minutes (keep_alive).
     * Uses the best available model: minicpm5 → gemma fallback.
     */
    private String callOllamaApi(String prompt, int maxTokens, int timeoutSeconds) {
        try {
            // Ultra-short system prompt — research shows <50 words is optimal for 1.5B models
            String systemPrompt = "Complete the pattern. Output only what comes after the arrow (→) or after OUT:";

            String jsonBody = String.format(
                    "{\"model\":\"%s\",\"system\":\"%s\",\"prompt\":\"%s\",\"stream\":false,"
                    + "\"keep_alive\":\"60m\",\"options\":{\"num_predict\":%d,\"temperature\":0.1}}",
                    LOCAL_MODEL, escapeJson(systemPrompt), escapeJson(prompt), maxTokens);

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(2)).build();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(OLLAMA_API_URL))
                    .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                // Parse response JSON to get "response" field
                String body = response.body();
                // Use Jackson for reliable JSON parsing
                try {
                    com.fasterxml.jackson.databind.JsonNode node =
                            new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                    if (node.has("response")) {
                        return node.get("response").asText().trim();
                    }
                } catch (Exception ignored) {}
                // Fallback: manual extraction
                int start = body.indexOf("\"response\":\"");
                if (start >= 0) {
                    start += 12;
                    int end = body.indexOf("\"", start);
                    while (end > 0 && body.charAt(end - 1) == '\\') {
                        end = body.indexOf("\"", end + 1);
                    }
                    if (end > start) {
                        return body.substring(start, end)
                                .replace("\\n", "\n")
                                .replace("\\\"", "\"")
                                .trim();
                    }
                }
            }
            return null;
        } catch (java.net.http.HttpTimeoutException e) {
            log.warn("  [LocalBrain] Ollama API timeout ({}s limit)", timeoutSeconds);
            return null;
        } catch (Exception e) {
            log.warn("  [LocalBrain] Ollama API error: {}", e.getMessage());
            return null;
        }
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ─── Validation Gates (MiniCPM5-powered when available) ────

    /**
     * Validate whether a cached solution is relevant to the current prompt.
     * Returns true if the cache should be used, false if it should be skipped.
     * If MiniCPM5 is unavailable, always returns true (don't block).
     */
    public boolean validateCacheRelevance(String userPrompt, String cachedSolution) {
        log.info("  [Gate] validateCacheRelevance: minicpmAvailable={}", minicpmAvailable);
        if (!minicpmAvailable) { log.info("  [Gate] → PASS (no local model)"); return true; }

        String truncatedSolution = cachedSolution.length() > 200
                ? cachedSolution.substring(0, 200) : cachedSolution;

        // Few-shot YES/NO pattern — no abstract instructions
        String prompt = "Q: Does cached answer match the request?\n"
                + "Request: Build a login page | Cache: HTML login form with CSS → YES\n"
                + "Request: Fix database error | Cache: HTML login form with CSS → NO\n"
                + "Request: " + (userPrompt.length() > 60 ? userPrompt.substring(0, 60) : userPrompt)
                + " | Cache: " + truncatedSolution.substring(0, Math.min(60, truncatedSolution.length())) + " →";

        String result = runLocalModel(prompt);
        if (result == null) { log.info("  [Gate] → PASS (returned null)"); return true; }
        boolean pass = !result.trim().toUpperCase().startsWith("NO");
        log.info("  [Gate] → {} (said: '{}')", pass ? "PASS" : "REJECT", result.trim());
        return pass;
    }

    /**
     * Validate whether a skill plan fits the current task.
     * Returns true if the skill should be replayed, false if it should be skipped.
     */
    public boolean validateSkillFit(String userPrompt, String skillDescription) {
        log.info("  [Gate] validateSkillFit: minicpmAvailable={}", minicpmAvailable);
        if (!minicpmAvailable) { log.info("  [Gate] → PASS (no local model)"); return true; }

        // Few-shot pattern
        String prompt = "Q: Can old skill be reused for new task?\n"
                + "Old: create login page | New: build signup form → YES\n"
                + "Old: create login page | New: fix database connection → NO\n"
                + "Old: " + (skillDescription.length() > 50 ? skillDescription.substring(0, 50) : skillDescription)
                + " | New: " + (userPrompt.length() > 50 ? userPrompt.substring(0, 50) : userPrompt) + " →";

        String result = runLocalModel(prompt);
        if (result == null) { log.info("  [Gate] → PASS (returned null)"); return true; }
        boolean pass = !result.trim().toUpperCase().startsWith("NO");
        log.info("  [Gate] → {} (said: '{}')", pass ? "PASS" : "REJECT", result.trim());
        return pass;
    }

    public boolean validateSearchRelevance(String userPrompt, String searchResults) {
        log.info("  [Gate] validateSearchRelevance: minicpmAvailable={}", minicpmAvailable);
        if (!minicpmAvailable) { log.info("  [Gate] → PASS (no local model)"); return true; }

        String truncatedResults = searchResults.length() > 200
                ? searchResults.substring(0, 200) : searchResults;

        // Few-shot pattern
        String prompt = "Q: Are search results useful for the task?\n"
                + "Task: build login page | Results: HTML form tutorial, CSS styling → YES\n"
                + "Task: build login page | Results: cooking recipes, weather API → NO\n"
                + "Task: " + (userPrompt.length() > 50 ? userPrompt.substring(0, 50) : userPrompt)
                + " | Results: " + truncatedResults.substring(0, Math.min(80, truncatedResults.length())) + " →";

        String result = runLocalModel(prompt);
        if (result == null) { log.info("  [Gate] → PASS (returned null)"); return true; }
        boolean pass = !result.trim().toUpperCase().startsWith("NO");
        log.info("  [Gate] → {} (said: '{}')", pass ? "PASS" : "REJECT", result.trim());
        return pass;
    }

    public boolean validatePlanJson(String userPrompt, String planJson) {
        log.info("  [Gate] validatePlanJson: minicpmAvailable={}", minicpmAvailable);
        if (!minicpmAvailable) {
            boolean structureOk = planJson != null && planJson.contains("\"steps\"") && planJson.contains("\"tool\"");
            log.info("  [Gate] → {} (no local model, basic JSON check)", structureOk ? "PASS" : "REJECT");
            return structureOk;
        }

        String truncatedPlan = planJson.length() > 300
                ? planJson.substring(0, 300) : planJson;

        // Few-shot pattern
        String prompt = "Q: Is this plan reasonable for the task?\n"
                + "Task: create file | Plan: steps:[readFile, writeFile] → YES\n"
                + "Task: create file | Plan: steps:[deleteDatabase] → NO\n"
                + "Task: " + (userPrompt.length() > 40 ? userPrompt.substring(0, 40) : userPrompt)
                + " | Plan: " + truncatedPlan.substring(0, Math.min(80, truncatedPlan.length())) + " →";

        String result = runLocalModel(prompt);
        if (result == null) {
            boolean structureOk = planJson.contains("\"steps\"") && planJson.contains("\"tool\"");
            log.info("  [Gate] → {} (null, fallback check)", structureOk ? "PASS" : "REJECT");
            return structureOk;
        }
        boolean pass = !result.trim().toUpperCase().startsWith("NO");
        log.info("  [Gate] → {} (said: '{}')", pass ? "PASS" : "REJECT", result.trim());
        return pass;
    }

    public boolean validateOutput(String userPrompt, String toolName, String result) {
        log.info("  [Gate] validateOutput: tool={}, minicpmAvailable={}", toolName, minicpmAvailable);
        if (!minicpmAvailable) { log.info("  [Gate] → PASS (no local model)"); return true; }

        // Only validate final responses
        if (toolName == null || (!toolName.equals("writeFile") && !toolName.equals("run")
                && !toolName.equals("response"))) {
            log.info("  [Gate] → PASS (tool '{}' not validated)", toolName);
            return true;
        }

        String truncatedResult = result.length() > 150 ? result.substring(0, 150) : result;

        // Few-shot pattern
        String prompt = "Q: Did the task complete successfully?\n"
                + "Task: create login page | Result: Successfully wrote index.html → YES\n"
                + "Task: run tests | Result: ERROR: compilation failed → NO\n"
                + "Task: " + (userPrompt.length() > 40 ? userPrompt.substring(0, 40) : userPrompt)
                + " | Result: " + truncatedResult.substring(0, Math.min(60, truncatedResult.length())) + " →";

        String modelResult = runLocalModel(prompt);
        if (modelResult == null) { log.info("  [Gate] → PASS (returned null)"); return true; }
        boolean pass = !modelResult.trim().toUpperCase().startsWith("NO");
        log.info("  [Gate] → {} (said: '{}')", pass ? "PASS" : "REJECT", modelResult.trim());
        return pass;
    }

    /**
     * Validate a tool call before execution (pre-validation gate).
     * DISABLED: Small local models (1.5B) lack the reasoning capacity to correctly
     * determine if a tool call is appropriate. They default to "NO" for safety,
     * which blocks all productive work. This gate should only be enabled when
     * using a model >= 7B that can actually reason about tool appropriateness.
     *
     * Always returns true (allow all tool calls).
     */
    public boolean validateToolCall(String userPrompt, String toolName, String arguments) {
        // Pre-execution validation disabled — small models reject valid tool calls
        return true;
    }

    // ─── Utility ─────────────────────────────────────────────────

    private void initClassificationEmbeddings() {
        try {
            // Pre-compute multiple prototype embeddings per category using realistic prompts.
            // Multiple examples per category improves coverage of how users phrase requests.

            codeGenEmbeddings = embedAll(
                "Create a login page with HTML and CSS",
                "Build a REST API with Spring Boot",
                "Write a Python script that reads a CSV file",
                "Make me a todo app in React",
                "Generate a function that sorts a list",
                "Implement user authentication with JWT"
            );

            fileReadEmbeddings = embedAll(
                "Read the file main.py",
                "Show me the contents of package.json",
                "What is in the config directory",
                "Open and display the README",
                "List all files in the src folder",
                "Read lines 10 to 50 of server.js"
            );

            bugFixEmbeddings = embedAll(
                "Fix the NullPointerException in UserService",
                "Debug why the tests are failing",
                "There is an error when I click submit",
                "The application crashes on startup",
                "This function returns wrong results",
                "Fix the broken CSS layout on mobile"
            );

            explainEmbeddings = embedAll(
                "Explain this code to me",
                "What does this function do",
                "How does the authentication flow work",
                "Why is this using a HashMap instead of a TreeMap",
                "Can you explain what async await means",
                "What is the purpose of this middleware"
            );

            shellCmdEmbeddings = embedAll(
                "Run the tests",
                "Build the project",
                "Start the development server",
                "Install the dependencies",
                "Deploy to production",
                "Run npm install and then npm start"
            );

            searchEmbeddings = embedAll(
                "How do I implement rate limiting in Express",
                "Search for how to connect to PostgreSQL in Java",
                "Find a tutorial on Docker compose",
                "What is the best way to handle file uploads",
                "Look up how to use websockets in Python",
                "Find documentation for Spring Security"
            );

            log.info("✓ Task classification embeddings initialized (6 categories × 6 prototypes)");
        } catch (Exception e) {
            log.warn("Failed to initialize classification embeddings: {}", e.getMessage());
        }
    }

    private List<Embedding> embedAll(String... sentences) {
        List<Embedding> embeddings = new ArrayList<>();
        for (String sentence : sentences) {
            embeddings.add(embeddingModel.embed(sentence).content());
        }
        return embeddings;
    }

    private double cosineSimilarity(Embedding a, Embedding b) {
        float[] vecA = a.vector();
        float[] vecB = b.vector();
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vecA.length; i++) {
            dotProduct += vecA[i] * vecB[i];
            normA += vecA[i] * vecA[i];
            normB += vecB[i] * vecB[i];
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public boolean isMiniCPMAvailable() {
        return minicpmAvailable;
    }
}
