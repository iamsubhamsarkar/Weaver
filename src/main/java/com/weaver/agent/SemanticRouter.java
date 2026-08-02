package com.weaver.agent;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Semantic Router: Advanced intent classification using embedding similarity.
 *
 * Features:
 * - 150+ route examples across 10 categories (vs. old 36 across 6)
 * - Complexity routing: SIMPLE vs COMPLEX tasks → different model selection
 * - Confidence threshold: score < 0.4 → defer to MiniCPM5 for classification
 * - Ambiguity detection: top-2 scores within 0.1 → defer to MiniCPM5
 * - Model recommendation: which provider tier suits each task type
 *
 * Uses AllMiniLmL6V2 embeddings (same model used for semantic cache).
 * Classification takes <50ms (embedding comparison, no API call).
 */
@Component
public class SemanticRouter {

    private static final Logger log = LoggerFactory.getLogger(SemanticRouter.class);

    private final EmbeddingModel embeddingModel;
    private final LocalBrain localBrain;

    // Confidence thresholds
    private static final double MIN_CONFIDENCE = 0.15;     // Below this → UNKNOWN
    private static final double DEFER_THRESHOLD = 0.40;    // Below this → ask MiniCPM5
    private static final double AMBIGUITY_GAP = 0.10;      // Top-2 within this → ambiguous

    // Route definitions: category → list of prototype embeddings
    private final Map<RouteCategory, List<Embedding>> routes = new LinkedHashMap<>();

    public SemanticRouter(EmbeddingModel embeddingModel, LocalBrain localBrain) {
        this.embeddingModel = embeddingModel;
        this.localBrain = localBrain;
        initRoutes();
    }

    // ─── Route Categories ────────────────────────────────────────

    public enum RouteCategory {
        // Code generation
        SIMPLE_CODE,       // single file, straightforward
        COMPLEX_CODE,      // multi-file, architecture, integration

        // Code modification
        BUG_FIX,           // fix errors, debug
        REFACTOR,          // improve code without changing behavior

        // File operations
        FILE_READ,         // read, show, display files
        FILE_WRITE,        // create, write new files (not code gen)

        // Explanation & analysis
        EXPLAIN,           // explain code, architecture
        REVIEW,            // review code quality, suggest improvements

        // Shell & commands
        SHELL_COMMAND,     // run tests, build, deploy

        // Research & search
        SEARCH,            // find docs, search web, how-to

        UNKNOWN
    }

    /**
     * Complexity level for model routing decisions.
     */
    public enum Complexity {
        SIMPLE,    // Small models can handle (8B-20B)
        MODERATE,  // Medium models (49B-70B)
        COMPLEX    // Frontier models only (120B+)
    }

    /**
     * Route result with confidence scoring and model recommendation.
     */
    public record RouteResult(
        RouteCategory category,
        Complexity complexity,
        double confidence,
        boolean ambiguous,
        String recommendedTier    // "tier1", "tier2", "any"
    ) {}

    // ─── Public API ──────────────────────────────────────────────

    /**
     * Classify a user prompt into a route with confidence and complexity.
     */
    public RouteResult classify(String userPrompt) {
        long startMs = System.currentTimeMillis();

        try {
            Embedding promptEmb = embeddingModel.embed(userPrompt).content();

            // Score against all categories
            Map<RouteCategory, Double> scores = new LinkedHashMap<>();
            for (Map.Entry<RouteCategory, List<Embedding>> entry : routes.entrySet()) {
                scores.put(entry.getKey(), maxSimilarity(promptEmb, entry.getValue()));
            }

            // Find top-2
            List<Map.Entry<RouteCategory, Double>> sorted = new ArrayList<>(scores.entrySet());
            sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

            RouteCategory bestCategory = sorted.get(0).getKey();
            double bestScore = sorted.get(0).getValue();
            double secondScore = sorted.size() > 1 ? sorted.get(1).getValue() : 0.0;

            long elapsed = System.currentTimeMillis() - startMs;
            log.info("  [SemanticRouter] Classification in {}ms: best={} ({}), second={} ({})",
                    elapsed, bestCategory, String.format("%.3f", bestScore),
                    sorted.size() > 1 ? sorted.get(1).getKey() : "N/A",
                    String.format("%.3f", secondScore));

            // Check ambiguity: top-2 within AMBIGUITY_GAP
            boolean ambiguous = (bestScore - secondScore) < AMBIGUITY_GAP && bestScore > MIN_CONFIDENCE;

            // Check confidence threshold
            if (bestScore < MIN_CONFIDENCE) {
                return new RouteResult(RouteCategory.UNKNOWN, Complexity.MODERATE, bestScore, false, "tier1");
            }

            // If low confidence or ambiguous, defer to MiniCPM5
            if ((bestScore < DEFER_THRESHOLD || ambiguous) && localBrain.isMiniCPMAvailable()) {
                log.info("  [SemanticRouter] Low confidence ({}) or ambiguous. Deferring to MiniCPM5.", bestScore);
                RouteCategory deferred = deferToMiniCPM(userPrompt, bestCategory, sorted);
                if (deferred != null) bestCategory = deferred;
                ambiguous = false; // MiniCPM5 resolved it
            }

            // Determine complexity
            Complexity complexity = assessComplexity(userPrompt, bestCategory);

            // Recommend tier
            String tier = recommendTier(bestCategory, complexity);

            return new RouteResult(bestCategory, complexity, bestScore, ambiguous, tier);

        } catch (Exception e) {
            log.warn("  [SemanticRouter] Classification failed: {}", e.getMessage());
            return new RouteResult(RouteCategory.UNKNOWN, Complexity.MODERATE, 0.0, false, "tier1");
        }
    }

    /**
     * Map RouteCategory to the LocalBrain.TaskType for backward compatibility.
     */
    public LocalBrain.TaskType toTaskType(RouteCategory category) {
        return switch (category) {
            case SIMPLE_CODE, COMPLEX_CODE -> LocalBrain.TaskType.CODE_GENERATION;
            case BUG_FIX -> LocalBrain.TaskType.BUG_FIX;
            case REFACTOR -> LocalBrain.TaskType.CODE_GENERATION;
            case FILE_READ -> LocalBrain.TaskType.FILE_READ;
            case FILE_WRITE -> LocalBrain.TaskType.CODE_GENERATION;
            case EXPLAIN, REVIEW -> LocalBrain.TaskType.EXPLAIN;
            case SHELL_COMMAND -> LocalBrain.TaskType.SHELL_COMMAND;
            case SEARCH -> LocalBrain.TaskType.SEARCH;
            case UNKNOWN -> LocalBrain.TaskType.UNKNOWN;
        };
    }

    // ─── Internal Methods ────────────────────────────────────────

    /**
     * Defer classification to MiniCPM5 when embedding scores are inconclusive.
     */
    private RouteCategory deferToMiniCPM(String prompt, RouteCategory topEmbedding,
                                          List<Map.Entry<RouteCategory, Double>> sorted) {
        // Present top-3 options to MiniCPM5
        StringBuilder options = new StringBuilder();
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            options.append(sorted.get(i).getKey()).append(", ");
        }

        String result = localBrain.summarizeForContext("classify",
                "Classify this user request into ONE of: " + options
                + "UNKNOWN.\nRequest: " + prompt + "\nAnswer with just the category name.", prompt);

        if (result == null) return topEmbedding;

        // Parse MiniCPM5 response
        String upper = result.trim().toUpperCase().replace(" ", "_");
        for (RouteCategory cat : RouteCategory.values()) {
            if (upper.contains(cat.name())) return cat;
        }
        return topEmbedding;
    }

    /**
     * Assess task complexity from prompt characteristics.
     */
    private Complexity assessComplexity(String prompt, RouteCategory category) {
        String lower = prompt.toLowerCase();
        int length = prompt.length();

        // Simple indicators
        if (length < 50) return Complexity.SIMPLE;
        if (category == RouteCategory.FILE_READ || category == RouteCategory.SHELL_COMMAND)
            return Complexity.SIMPLE;

        // Complex indicators
        if (lower.contains("architecture") || lower.contains("design pattern")
                || lower.contains("microservice") || lower.contains("full stack")
                || lower.contains("multi-file") || lower.contains("refactor entire")
                || lower.contains("integrate") || lower.contains("migration"))
            return Complexity.COMPLEX;

        if (category == RouteCategory.COMPLEX_CODE || category == RouteCategory.REVIEW)
            return Complexity.COMPLEX;

        // Multiple requirements suggest complexity
        long sentenceCount = prompt.chars().filter(c -> c == '.' || c == ';' || c == '\n').count();
        if (sentenceCount > 3) return Complexity.COMPLEX;

        return Complexity.MODERATE;
    }

    /**
     * Recommend which provider tier to use based on task type and complexity.
     */
    private String recommendTier(RouteCategory category, Complexity complexity) {
        return switch (complexity) {
            case SIMPLE -> "any";          // Even small Tier 2 models handle this
            case MODERATE -> "tier2";       // 70B+ models preferred
            case COMPLEX -> "tier1";        // Frontier models (NIM 253B/120B/480B)
        };
    }

    private double maxSimilarity(Embedding prompt, List<Embedding> prototypes) {
        double max = 0.0;
        for (Embedding proto : prototypes) {
            double sim = cosineSimilarity(prompt, proto);
            if (sim > max) max = sim;
        }
        return max;
    }

    private double cosineSimilarity(Embedding a, Embedding b) {
        float[] vecA = a.vector();
        float[] vecB = b.vector();
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < vecA.length; i++) {
            dot += vecA[i] * vecB[i];
            normA += vecA[i] * vecA[i];
            normB += vecB[i] * vecB[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private List<Embedding> embedAll(String... sentences) {
        List<Embedding> embeddings = new ArrayList<>();
        for (String s : sentences) {
            embeddings.add(embeddingModel.embed(s).content());
        }
        return embeddings;
    }

    // ─── Route Initialization (150+ examples) ────────────────────

    private void initRoutes() {
        long startMs = System.currentTimeMillis();

        routes.put(RouteCategory.SIMPLE_CODE, embedAll(
            "Create a hello world in Python",
            "Write a function that reverses a string",
            "Make a simple login page with HTML and CSS",
            "Generate a function that sorts a list",
            "Create a basic REST endpoint",
            "Write a bash script to rename files",
            "Make a simple calculator in JavaScript",
            "Create a Python script to read a CSV",
            "Write a unit test for the add function",
            "Generate a Dockerfile for a Node.js app",
            "Create a .gitignore for Java projects",
            "Write a SQL query to find duplicates",
            "Make a simple todo list component in React",
            "Create a class that implements Comparable",
            "Write a regex to validate email addresses"
        ));

        routes.put(RouteCategory.COMPLEX_CODE, embedAll(
            "Build a full REST API with authentication and database",
            "Create a microservice architecture with event sourcing",
            "Implement a complete chat application with WebSocket",
            "Design and implement a rate limiter with Redis",
            "Build an e-commerce checkout flow with payments",
            "Create a CI/CD pipeline with Docker and Kubernetes",
            "Implement OAuth2 with refresh tokens and RBAC",
            "Build a real-time dashboard with SSE and React",
            "Create a multi-tenant SaaS application structure",
            "Implement a distributed job queue with retries",
            "Build a full stack app with Next.js and Prisma",
            "Design a plugin architecture with dynamic loading",
            "Create an API gateway with rate limiting and caching",
            "Implement CQRS pattern with event store",
            "Build a file upload service with chunked uploads and resume"
        ));

        routes.put(RouteCategory.BUG_FIX, embedAll(
            "Fix the NullPointerException in UserService",
            "Debug why the tests are failing",
            "There is an error when I click submit",
            "The application crashes on startup",
            "This function returns wrong results",
            "Fix the broken CSS layout on mobile",
            "The API returns 500 error on large payloads",
            "Fix the race condition in the cache",
            "Debug memory leak in the WebSocket handler",
            "The login redirect loop won't stop",
            "Fix the off-by-one error in pagination",
            "The date parsing fails for some timezones",
            "TypeError undefined is not a function on line 42",
            "Fix the CORS error when calling the API",
            "The query times out with more than 1000 records"
        ));

        routes.put(RouteCategory.REFACTOR, embedAll(
            "Refactor this class to use dependency injection",
            "Clean up the duplicated code in these files",
            "Extract the validation logic into a separate service",
            "Convert these callbacks to async/await",
            "Reorganize the project folder structure",
            "Replace the magic numbers with named constants",
            "Split this 500-line function into smaller methods",
            "Apply the strategy pattern to this switch statement",
            "Make this code more testable by extracting interfaces",
            "Simplify the nested if-else into a cleaner pattern",
            "Convert this class component to a functional component",
            "Remove the circular dependency between modules",
            "Replace inheritance with composition",
            "Make this API backward compatible while adding new fields",
            "Improve the error handling in this module"
        ));

        routes.put(RouteCategory.FILE_READ, embedAll(
            "Read the file main.py",
            "Show me the contents of package.json",
            "What is in the config directory",
            "Open and display the README",
            "List all files in the src folder",
            "Read lines 10 to 50 of server.js",
            "Show me the test files",
            "What does the .env.example look like",
            "Display the router configuration",
            "List the project structure",
            "Show me what imports this module uses",
            "Read the database migration files",
            "What is the current version in pom.xml",
            "Show the API route definitions",
            "Display the error log file"
        ));

        routes.put(RouteCategory.FILE_WRITE, embedAll(
            "Create a new configuration file",
            "Add a .env file with the required variables",
            "Create a README for this project",
            "Write a docker-compose.yml for the services",
            "Create a Makefile for common commands",
            "Add a CHANGELOG entry for version 2.0",
            "Create a new migration file for adding indexes",
            "Write a setup script for new developers",
            "Create a GitHub Actions workflow file",
            "Add TypeScript type definitions for this module",
            "Create an nginx config for the proxy",
            "Write a Terraform module for the S3 bucket",
            "Create seed data for the test database",
            "Add a pre-commit hook configuration",
            "Write an API documentation in OpenAPI format"
        ));

        routes.put(RouteCategory.EXPLAIN, embedAll(
            "Explain this code to me",
            "What does this function do",
            "How does the authentication flow work",
            "Why is this using a HashMap instead of TreeMap",
            "Can you explain what async await means",
            "What is the purpose of this middleware",
            "How does this recursive algorithm work",
            "Explain the difference between these two approaches",
            "What design pattern is being used here",
            "Why would you choose this database over that one",
            "How does garbage collection work in Java",
            "What is the time complexity of this solution",
            "Explain how the event loop processes callbacks",
            "What are the trade-offs of this architecture",
            "How does this caching strategy work"
        ));

        routes.put(RouteCategory.REVIEW, embedAll(
            "Review this code for potential issues",
            "Are there any security vulnerabilities here",
            "What could go wrong with this implementation",
            "Is this the best approach for handling errors",
            "Check if there are any performance bottlenecks",
            "Review the API design for RESTful best practices",
            "Are there any edge cases I'm missing",
            "Is this database schema well normalized",
            "Check the error handling in this service",
            "Review the test coverage for this module",
            "Is this code following SOLID principles",
            "Check for potential memory leaks",
            "Review the logging strategy",
            "Are the environment variables handled securely",
            "Check if the input validation is sufficient"
        ));

        routes.put(RouteCategory.SHELL_COMMAND, embedAll(
            "Run the tests",
            "Build the project",
            "Start the development server",
            "Install the dependencies",
            "Deploy to production",
            "Run npm install and then npm start",
            "Execute the database migration",
            "Run the linter and fix issues",
            "Start Docker containers",
            "Kill the process on port 3000",
            "Clear the build cache",
            "Run the benchmark suite",
            "Check the system disk usage",
            "Restart the application",
            "Run git status and show recent commits"
        ));

        routes.put(RouteCategory.SEARCH, embedAll(
            "How do I implement rate limiting in Express",
            "Search for how to connect to PostgreSQL in Java",
            "Find a tutorial on Docker compose",
            "What is the best way to handle file uploads",
            "Look up how to use WebSockets in Python",
            "Find documentation for Spring Security",
            "How to implement pagination with cursor",
            "Search for JWT token refresh best practices",
            "Find examples of circuit breaker pattern",
            "How to set up GitHub Actions for Python",
            "Search for React state management comparison",
            "Find how to implement SSO with SAML",
            "How to configure nginx reverse proxy",
            "Search for Kubernetes deployment strategies",
            "Find best practices for API versioning"
        ));

        long elapsed = System.currentTimeMillis() - startMs;
        int totalExamples = routes.values().stream().mapToInt(List::size).sum();
        log.info("✓ SemanticRouter initialized: {} categories, {} total examples, {}ms",
                routes.size(), totalExamples, elapsed);
    }
}
