package com.weaver.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Self-Improvement System: Learns from task successes and failures.
 *
 * Persists learning to:
 * - ~/.weaver/brain-memory/rules.jsonl — learned patterns (injected into system prompt)
 * - ~/.weaver/brain-memory/routes.jsonl — new route examples from successful tasks
 * - ~/.weaver/brain-memory/failures.jsonl — failed attempts for analysis
 *
 * Strategy:
 * 1. After task SUCCESS → extract route example + any correction rules
 * 2. After task FAILURE → log the failure context, extract correction rule
 * 3. On startup → load top rules into MiniCPM5 system prompt
 * 4. Decay: examples older than 30 days without a match get removed
 * 5. Cap: max 30 examples per route category
 *
 * Rules are structured as:
 * {"rule": "Always check file exists before writing", "context": "writeFile", "score": 5, "created": "..."}
 *
 * Route examples:
 * {"prompt": "...", "category": "BUG_FIX", "score": 1, "lastMatched": "...", "created": "..."}
 */
@Component
public class SelfImprovementSystem {

    private static final Logger log = LoggerFactory.getLogger(SelfImprovementSystem.class);

    private static final int MAX_RULES = 50;
    private static final int MAX_ROUTES_PER_CATEGORY = 30;
    private static final int DECAY_DAYS = 30;
    private static final int TOP_RULES_FOR_PROMPT = 10;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path memoryDir;
    private final Path rulesFile;
    private final Path routesFile;
    private final Path failuresFile;

    // In-memory caches
    private final List<Rule> rules = new ArrayList<>();
    private final List<RouteExample> routeExamples = new ArrayList<>();

    public SelfImprovementSystem() {
        this.memoryDir = Path.of(System.getProperty("user.home"), ".weaver", "brain-memory");
        this.rulesFile = memoryDir.resolve("rules.jsonl");
        this.routesFile = memoryDir.resolve("routes.jsonl");
        this.failuresFile = memoryDir.resolve("failures.jsonl");

        initializeStorage();
        loadFromDisk();
        decayOldEntries();
    }

    // ─── Public API ──────────────────────────────────────────────

    /**
     * Record a successful task completion.
     * Extracts a route example and potentially a rule.
     */
    public void recordSuccess(String userPrompt, SemanticRouter.RouteCategory category,
                              String toolsUsed, String outcome) {
        log.info("  [SelfImprovement] Recording success: category={}, prompt='{}'",
                category, truncate(userPrompt, 60));

        // Add route example
        RouteExample example = new RouteExample(
                userPrompt, category.name(), 1, Instant.now().toString(), Instant.now().toString());
        addRouteExample(example);

        // Persist
        appendToFile(routesFile, objectMapper.valueToTree(example));
    }

    /**
     * Record a task failure for later analysis.
     * Extracts a correction rule if possible.
     */
    public void recordFailure(String userPrompt, SemanticRouter.RouteCategory category,
                              String errorMessage, String toolsUsed) {
        log.info("  [SelfImprovement] Recording failure: category={}, error='{}'",
                category, truncate(errorMessage, 80));

        // Log the failure
        ObjectNode failure = objectMapper.createObjectNode();
        failure.put("prompt", userPrompt);
        failure.put("category", category.name());
        failure.put("error", errorMessage);
        failure.put("tools", toolsUsed);
        failure.put("timestamp", Instant.now().toString());
        appendToFile(failuresFile, failure);

        // Extract a correction rule if the error is clear
        String rule = extractRuleFromFailure(errorMessage, toolsUsed);
        if (rule != null) {
            addRule(new Rule(rule, category.name(), 1, Instant.now().toString()));
        }
    }

    /**
     * Get the top learned rules formatted for injection into system prompt.
     * Returns a compact string with the most valuable rules.
     */
    public String getTopRulesForPrompt() {
        if (rules.isEmpty()) return "";

        // Sort by score descending, take top N
        List<Rule> topRules = rules.stream()
                .sorted((a, b) -> Integer.compare(b.score, a.score))
                .limit(TOP_RULES_FOR_PROMPT)
                .toList();

        StringBuilder sb = new StringBuilder("\nLEARNED RULES (from past experience):\n");
        for (Rule rule : topRules) {
            sb.append("- ").append(rule.rule).append("\n");
        }
        return sb.toString();
    }

    /**
     * Get the count of learned rules.
     */
    public int getRuleCount() {
        return rules.size();
    }

    /**
     * Get the count of route examples.
     */
    public int getRouteExampleCount() {
        return routeExamples.size();
    }

    /**
     * Boost a rule's score when it proves useful again.
     */
    public void reinforceRule(String ruleText) {
        for (Rule rule : rules) {
            if (rule.rule.equals(ruleText)) {
                rule.score++;
                log.info("  [SelfImprovement] Reinforced rule (score={}): '{}'",
                        rule.score, truncate(ruleText, 60));
                persistRules();
                return;
            }
        }
    }

    // ─── Internal Methods ────────────────────────────────────────

    /**
     * Extract a correction rule from a failure pattern.
     */
    private String extractRuleFromFailure(String errorMessage, String toolsUsed) {
        if (errorMessage == null) return null;
        String lower = errorMessage.toLowerCase();

        // Common patterns → rules
        if (lower.contains("file not found") || lower.contains("no such file")) {
            return "Always verify file exists (readFile/listDirectory) before writing or editing";
        }
        if (lower.contains("permission denied")) {
            return "Check file permissions before write operations";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "Add timeout handling for long-running shell commands";
        }
        if (lower.contains("syntax error") || lower.contains("parse error")) {
            return "Validate generated code syntax before writing to file";
        }
        if (lower.contains("already exists")) {
            return "Check if file/resource already exists before creating";
        }
        if (lower.contains("connection refused")) {
            return "Verify service is running before attempting connections";
        }
        if (lower.contains("null") || lower.contains("undefined")) {
            return "Always null-check tool results before using them";
        }

        return null;
    }

    private void addRule(Rule newRule) {
        // Check for duplicates
        for (Rule existing : rules) {
            if (existing.rule.equalsIgnoreCase(newRule.rule)) {
                existing.score++;
                persistRules();
                return;
            }
        }

        rules.add(newRule);

        // Cap total rules
        if (rules.size() > MAX_RULES) {
            // Remove lowest-scored rules
            rules.sort((a, b) -> Integer.compare(a.score, b.score));
            rules.subList(0, rules.size() - MAX_RULES).clear();
        }

        persistRules();
        log.info("  [SelfImprovement] Added rule (total={}): '{}'",
                rules.size(), truncate(newRule.rule, 60));
    }

    private void addRouteExample(RouteExample example) {
        routeExamples.add(example);

        // Cap per category
        Map<String, List<RouteExample>> byCategory = routeExamples.stream()
                .collect(Collectors.groupingBy(r -> r.category));

        for (Map.Entry<String, List<RouteExample>> entry : byCategory.entrySet()) {
            if (entry.getValue().size() > MAX_ROUTES_PER_CATEGORY) {
                // Sort by score ascending, remove oldest/lowest
                entry.getValue().sort((a, b) -> Integer.compare(a.score, b.score));
                int excess = entry.getValue().size() - MAX_ROUTES_PER_CATEGORY;
                List<RouteExample> toRemove = entry.getValue().subList(0, excess);
                routeExamples.removeAll(toRemove);
            }
        }
    }

    /**
     * Decay old entries: remove route examples that haven't matched in DECAY_DAYS.
     */
    private void decayOldEntries() {
        Instant cutoff = Instant.now().minus(DECAY_DAYS, ChronoUnit.DAYS);
        int beforeSize = routeExamples.size();

        routeExamples.removeIf(example -> {
            try {
                Instant lastMatched = Instant.parse(example.lastMatched);
                return lastMatched.isBefore(cutoff);
            } catch (Exception e) {
                return false;
            }
        });

        int removed = beforeSize - routeExamples.size();
        if (removed > 0) {
            log.info("  [SelfImprovement] Decayed {} old route examples (>{} days)",
                    removed, DECAY_DAYS);
            persistRoutes();
        }
    }

    // ─── Persistence ─────────────────────────────────────────────

    private void initializeStorage() {
        try {
            Files.createDirectories(memoryDir);
        } catch (IOException e) {
            log.warn("  [SelfImprovement] Cannot create memory dir: {}", e.getMessage());
        }
    }

    private void loadFromDisk() {
        // Load rules
        if (Files.exists(rulesFile)) {
            try {
                List<String> lines = Files.readAllLines(rulesFile);
                for (String line : lines) {
                    if (line.isBlank()) continue;
                    try {
                        JsonNode node = objectMapper.readTree(line);
                        rules.add(new Rule(
                                node.get("rule").asText(),
                                node.has("context") ? node.get("context").asText() : "",
                                node.has("score") ? node.get("score").asInt() : 1,
                                node.has("created") ? node.get("created").asText() : Instant.now().toString()
                        ));
                    } catch (Exception ignored) {}
                }
                log.info("  [SelfImprovement] Loaded {} rules from disk", rules.size());
            } catch (IOException e) {
                log.warn("  [SelfImprovement] Cannot read rules: {}", e.getMessage());
            }
        }

        // Load routes
        if (Files.exists(routesFile)) {
            try {
                List<String> lines = Files.readAllLines(routesFile);
                for (String line : lines) {
                    if (line.isBlank()) continue;
                    try {
                        JsonNode node = objectMapper.readTree(line);
                        routeExamples.add(new RouteExample(
                                node.get("prompt").asText(),
                                node.get("category").asText(),
                                node.has("score") ? node.get("score").asInt() : 1,
                                node.has("lastMatched") ? node.get("lastMatched").asText() : Instant.now().toString(),
                                node.has("created") ? node.get("created").asText() : Instant.now().toString()
                        ));
                    } catch (Exception ignored) {}
                }
                log.info("  [SelfImprovement] Loaded {} route examples from disk", routeExamples.size());
            } catch (IOException e) {
                log.warn("  [SelfImprovement] Cannot read routes: {}", e.getMessage());
            }
        }
    }

    private void persistRules() {
        try {
            List<String> lines = new ArrayList<>();
            for (Rule rule : rules) {
                lines.add(objectMapper.writeValueAsString(rule));
            }
            Files.write(rulesFile, lines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("  [SelfImprovement] Cannot persist rules: {}", e.getMessage());
        }
    }

    private void persistRoutes() {
        try {
            List<String> lines = new ArrayList<>();
            for (RouteExample example : routeExamples) {
                lines.add(objectMapper.writeValueAsString(example));
            }
            Files.write(routesFile, lines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("  [SelfImprovement] Cannot persist routes: {}", e.getMessage());
        }
    }

    private void appendToFile(Path file, JsonNode node) {
        try {
            String line = objectMapper.writeValueAsString(node) + "\n";
            Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("  [SelfImprovement] Cannot append to {}: {}", file.getFileName(), e.getMessage());
        }
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }

    // ─── Internal Types ──────────────────────────────────────────

    public static class Rule {
        public String rule;
        public String context;
        public int score;
        public String created;

        public Rule() {}
        public Rule(String rule, String context, int score, String created) {
            this.rule = rule;
            this.context = context;
            this.score = score;
            this.created = created;
        }

        // Getters for Jackson
        public String getRule() { return rule; }
        public String getContext() { return context; }
        public int getScore() { return score; }
        public String getCreated() { return created; }
    }

    public static class RouteExample {
        public String prompt;
        public String category;
        public int score;
        public String lastMatched;
        public String created;

        public RouteExample() {}
        public RouteExample(String prompt, String category, int score, String lastMatched, String created) {
            this.prompt = prompt;
            this.category = category;
            this.score = score;
            this.lastMatched = lastMatched;
            this.created = created;
        }

        // Getters for Jackson
        public String getPrompt() { return prompt; }
        public String getCategory() { return category; }
        public int getScore() { return score; }
        public String getLastMatched() { return lastMatched; }
        public String getCreated() { return created; }
    }
}
