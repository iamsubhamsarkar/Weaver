package com.weaver.agent;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;
import java.util.regex.*;

/**
 * LocalBrain: Lightweight local intelligence for Weaver.
 *
 * Uses two strategies:
 * 1. Embedding-based semantic similarity for task classification
 *    (uses the AllMiniLmL6V2 model already loaded for ChromaDB)
 * 2. NLP-based keyword extraction for search query generation
 * 3. If Gemma 270M ONNX is available locally (~/.weaver/models/), uses it
 *    via subprocess for higher quality extraction/summarization.
 *
 * All runs locally. Zero API calls. ~300MB additional memory for Gemma.
 */
@Component
public class LocalBrain {

    private static final Logger log = LoggerFactory.getLogger(LocalBrain.class);

    private final EmbeddingModel embeddingModel;
    private boolean gemmaAvailable = false;

    // Pre-computed embeddings for task classification
    private Embedding codeGenEmbedding;
    private Embedding fileReadEmbedding;
    private Embedding bugFixEmbedding;
    private Embedding explainEmbedding;
    private Embedding shellCmdEmbedding;
    private Embedding searchEmbedding;

    public LocalBrain(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        initClassificationEmbeddings();
        checkGemmaAvailability();
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
     * Compares the prompt's embedding against pre-computed category embeddings.
     */
    public TaskType classifyTask(String userPrompt) {
        try {
            Embedding promptEmbedding = embeddingModel.embed(userPrompt).content();

            Map<TaskType, Double> scores = new LinkedHashMap<>();
            scores.put(TaskType.CODE_GENERATION, cosineSimilarity(promptEmbedding, codeGenEmbedding));
            scores.put(TaskType.FILE_READ, cosineSimilarity(promptEmbedding, fileReadEmbedding));
            scores.put(TaskType.BUG_FIX, cosineSimilarity(promptEmbedding, bugFixEmbedding));
            scores.put(TaskType.EXPLAIN, cosineSimilarity(promptEmbedding, explainEmbedding));
            scores.put(TaskType.SHELL_COMMAND, cosineSimilarity(promptEmbedding, shellCmdEmbedding));
            scores.put(TaskType.SEARCH, cosineSimilarity(promptEmbedding, searchEmbedding));

            TaskType best = TaskType.UNKNOWN;
            double bestScore = 0.0;
            for (Map.Entry<TaskType, Double> entry : scores.entrySet()) {
                if (entry.getValue() > bestScore) {
                    bestScore = entry.getValue();
                    best = entry.getKey();
                }
            }

            log.debug("Task classified as {} (score: {:.3f})", best, bestScore);
            return bestScore > 0.3 ? best : TaskType.UNKNOWN;
        } catch (Exception e) {
            log.debug("Classification failed: {}", e.getMessage());
            return TaskType.UNKNOWN;
        }
    }

    // ─── Search Query Extraction ─────────────────────────────────

    /**
     * Extract a smart web search query from the user's prompt.
     * Uses NLP techniques: remove filler words, extract tech terms, focus on intent.
     * If Gemma is available, uses it for higher quality extraction.
     */
    public String extractSearchQuery(String userPrompt) {
        // Try Gemma first if available
        if (gemmaAvailable) {
            String gemmaResult = runGemmaExtraction(userPrompt);
            if (gemmaResult != null) return gemmaResult;
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
     * Uses sentence-level importance scoring via embeddings.
     */
    public String summarizeForContext(String toolName, String content, String userPrompt) {
        if (content == null || content.length() <= 300) return content;

        // Try Gemma if available
        if (gemmaAvailable) {
            String gemmaResult = runGemmaSummarize(toolName, content);
            if (gemmaResult != null) return gemmaResult;
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

    // ─── Gemma via Ollama Integration ─────────────────────────

    private void checkGemmaAvailability() {
        try {
            // Check if Ollama is installed and the model is available
            ProcessBuilder pb = new ProcessBuilder("ollama", "list");
            pb.redirectErrorStream(true);
            Process p = pb.start();

            String output = new String(p.getInputStream().readAllBytes());
            boolean completed = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);

            if (completed && p.exitValue() == 0 && output.contains("gemma")) {
                gemmaAvailable = true;
                log.info("✓ Local brain available (Ollama + Gemma)");
            } else {
                gemmaAvailable = false;
                log.info("Local brain not available. Install with: curl -fsSL https://ollama.com/install.sh | sh && ollama pull gemma3:1b");
            }
        } catch (Exception e) {
            gemmaAvailable = false;
            log.info("Ollama not found. Using embedding-based pre-processing only.");
        }
    }

    private String runGemmaExtraction(String userPrompt) {
        return runGemma("Extract a concise web search query from this request. "
                + "Output ONLY the search query, nothing else:\n" + userPrompt);
    }

    private String runGemmaSummarize(String toolName, String content) {
        String truncated = content.length() > 1000 ? content.substring(0, 1000) : content;
        return runGemma("Summarize this " + toolName + " output in 2-3 lines. Be concise:\n" + truncated);
    }

    /**
     * Run a prompt through Ollama's Gemma model.
     * Uses: ollama run gemma3:1b "prompt"
     * Returns null if unavailable or fails.
     */
    private String runGemma(String prompt) {
        if (!gemmaAvailable) return null;

        try {
            ProcessBuilder pb = new ProcessBuilder("ollama", "run", "gemma3:1b", prompt);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean completed = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return null;
            }

            if (process.exitValue() != 0) return null;

            String result = output.toString().trim();
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            log.debug("Ollama inference failed: {}", e.getMessage());
            return null;
        }
    }

    // ─── Validation Gates (Gemma-powered when available) ────────

    /**
     * Validate whether a cached solution is relevant to the current prompt.
     * Returns true if the cache should be used, false if it should be skipped.
     * If Gemma is unavailable, always returns true (don't block).
     */
    public boolean validateCacheRelevance(String userPrompt, String cachedSolution) {
        if (!gemmaAvailable) return true; // No Gemma = don't block, use cache as-is

        String truncatedSolution = cachedSolution.length() > 300
                ? cachedSolution.substring(0, 300) : cachedSolution;

        String result = runGemma(
            "Does this cached solution match the user's request? "
            + "Answer ONLY 'YES' or 'NO'.\n"
            + "User request: " + userPrompt + "\n"
            + "Cached solution (preview): " + truncatedSolution);

        if (result == null) return true; // Gemma failed = don't block
        return !result.trim().toUpperCase().startsWith("NO");
    }

    /**
     * Validate whether a skill plan fits the current task.
     * Returns true if the skill should be replayed, false if it should be skipped.
     */
    public boolean validateSkillFit(String userPrompt, String skillDescription) {
        if (!gemmaAvailable) return true;

        String result = runGemma(
            "Can this stored skill be reused for the new task? "
            + "Answer ONLY 'YES' or 'NO'.\n"
            + "Stored skill was for: " + skillDescription + "\n"
            + "New task: " + userPrompt);

        if (result == null) return true;
        return !result.trim().toUpperCase().startsWith("NO");
    }

    /**
     * Validate whether web search results are relevant to the task.
     * Returns true if results should be injected as context, false if they should be discarded.
     */
    public boolean validateSearchRelevance(String userPrompt, String searchResults) {
        if (!gemmaAvailable) return true;

        String truncatedResults = searchResults.length() > 300
                ? searchResults.substring(0, 300) : searchResults;

        String result = runGemma(
            "Are these search results relevant to the user's coding task? "
            + "Answer ONLY 'YES' or 'NO'.\n"
            + "Task: " + userPrompt + "\n"
            + "Search results (preview): " + truncatedResults);

        if (result == null) return true;
        return !result.trim().toUpperCase().startsWith("NO");
    }

    /**
     * Validate whether a plan JSON is structurally valid and makes sense.
     * Returns true if the plan should be executed, false if it should be rejected.
     */
    public boolean validatePlanJson(String userPrompt, String planJson) {
        if (!gemmaAvailable) {
            // Without Gemma, at least check basic JSON structure
            return planJson != null && planJson.contains("\"steps\"") && planJson.contains("\"tool\"");
        }

        String truncatedPlan = planJson.length() > 500
                ? planJson.substring(0, 500) : planJson;

        String result = runGemma(
            "Is this execution plan reasonable for the task? "
            + "Check: does it use the right tools? Are the steps logical? "
            + "Answer ONLY 'YES' or 'NO'.\n"
            + "Task: " + userPrompt + "\n"
            + "Plan: " + truncatedPlan);

        if (result == null) {
            // Fallback: basic structure check
            return planJson.contains("\"steps\"") && planJson.contains("\"tool\"");
        }
        return !result.trim().toUpperCase().startsWith("NO");
    }

    /**
     * Validate whether the output of a task makes sense (post-execution check).
     * Returns true if the output should be cached/stored, false if it seems wrong.
     */
    public boolean validateOutput(String userPrompt, String toolName, String result) {
        if (!gemmaAvailable) return true;

        // Only validate significant outputs (writeFile, run)
        if (toolName == null) return true;
        if (!toolName.equals("writeFile") && !toolName.equals("run")) return true;

        String truncatedResult = result.length() > 200
                ? result.substring(0, 200) : result;

        String gemmaResult = runGemma(
            "Did this tool execution succeed for the task? "
            + "Answer ONLY 'YES' or 'NO'.\n"
            + "Task: " + userPrompt + "\n"
            + "Tool: " + toolName + "\n"
            + "Result: " + truncatedResult);

        if (gemmaResult == null) return true;
        return !gemmaResult.trim().toUpperCase().startsWith("NO");
    }

    // ─── Utility ─────────────────────────────────────────────────

    private void initClassificationEmbeddings() {
        try {
            // Pre-compute embeddings for each task category
            codeGenEmbedding = embeddingModel.embed(
                "create build write implement make generate new application page component function class").content();
            fileReadEmbedding = embeddingModel.embed(
                "read show display list open view contents file directory what is in").content();
            bugFixEmbedding = embeddingModel.embed(
                "fix bug error debug repair broken not working failing crash exception").content();
            explainEmbedding = embeddingModel.embed(
                "explain describe what does how does why understand meaning purpose").content();
            shellCmdEmbedding = embeddingModel.embed(
                "run execute build compile test deploy start stop install command terminal").content();
            searchEmbedding = embeddingModel.embed(
                "search find look up how to documentation reference tutorial guide").content();

            log.info("✓ Task classification embeddings initialized");
        } catch (Exception e) {
            log.warn("Failed to initialize classification embeddings: {}", e.getMessage());
        }
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

    public boolean isGemmaAvailable() {
        return gemmaAvailable;
    }
}
