package com.weaver.agent;

import com.weaver.config.WeaverConfigProperties;
import com.weaver.memory.WeaverMemoryStore;
import com.weaver.provider.ProviderRegistry;
import com.weaver.semantic.ExperienceLibraryService;
import com.weaver.tools.CodebaseTools;
import com.weaver.tools.ShellTool;
import com.weaver.tools.StackOverflowTool;
import com.weaver.tools.WebSearchTool;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;

@Service
public class WeaverAgent {

    private static final Logger log = LoggerFactory.getLogger(WeaverAgent.class);

    private final ProviderRegistry providerRegistry;
    private final ExperienceLibraryService experienceLibrary;
    private final WeaverMemoryStore memoryStore;
    private final WeaverConfigProperties config;
    private final LocalBrain localBrain;
    private final SkillLibrary skillLibrary;
    private final ContextBudgetManager contextBudgetManager;
    private final SemanticRouter semanticRouter;
    private final SelfImprovementSystem selfImprovement;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final List<ToolSpecification> toolSpecs;
    private final Map<String, ToolMethod> toolMethods;

    private Consumer<String> outputCallback = s -> {};
    private Consumer<String> streamTokenCallback = null; // if set, streams final response token-by-token
    private Runnable onThinkingStart = () -> {};
    private Runnable onThinkingStop = () -> {};

    private record ToolMethod(Object instance, Method method) {}

    public WeaverAgent(ProviderRegistry providerRegistry,
                       ExperienceLibraryService experienceLibrary,
                       WeaverMemoryStore memoryStore,
                       WeaverConfigProperties config,
                       LocalBrain localBrain,
                       SkillLibrary skillLibrary,
                       ContextBudgetManager contextBudgetManager,
                       SemanticRouter semanticRouter,
                       SelfImprovementSystem selfImprovement,
                       CodebaseTools codebaseTools,
                       ShellTool shellTool,
                       WebSearchTool webSearchTool,
                       StackOverflowTool stackOverflowTool) {
        this.providerRegistry = providerRegistry;
        this.experienceLibrary = experienceLibrary;
        this.memoryStore = memoryStore;
        this.config = config;
        this.localBrain = localBrain;
        this.skillLibrary = skillLibrary;
        this.contextBudgetManager = contextBudgetManager;
        this.semanticRouter = semanticRouter;
        this.selfImprovement = selfImprovement;

        this.toolSpecs = new ArrayList<>();
        this.toolMethods = new HashMap<>();

        registerTools(codebaseTools);
        registerTools(shellTool);
        registerTools(webSearchTool);
        registerTools(stackOverflowTool);

        log.info("WeaverAgent initialized with {} tools", toolSpecs.size());
    }

    private void registerTools(Object toolProvider) {
        List<ToolSpecification> specs = ToolSpecifications.toolSpecificationsFrom(toolProvider);
        for (ToolSpecification spec : specs) {
            toolSpecs.add(spec);
            // Find the matching method
            for (Method method : toolProvider.getClass().getMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    String methodName = method.getName();
                    if (methodName.equals(spec.name())) {
                        toolMethods.put(spec.name(), new ToolMethod(toolProvider, method));
                        break;
                    }
                }
            }
        }
    }

    public void setOutputCallback(Consumer<String> callback) {
        this.outputCallback = callback;
    }

    public void setOnThinkingStart(Runnable onStart) {
        this.onThinkingStart = onStart;
    }

    public void setOnThinkingStop(Runnable onStop) {
        this.onThinkingStop = onStop;
    }

    public void setStreamTokenCallback(Consumer<String> callback) {
        this.streamTokenCallback = callback;
    }

    public String execute(String userPrompt, String sessionId) {
        log.info("════════════════════════════════════════════════════════════");
        log.info("NEW TASK: '{}'", userPrompt);
        log.info("Session: {}, Workspace: {}", sessionId, config.getWorkspaceRoot());
        log.info("════════════════════════════════════════════════════════════");

        // ═══ OPTIMIZATION PIPELINE ═══
        // Cache → Skill → Plan-then-Execute → ReAct (fallback)

        // ─── Layer 1: Semantic Cache (0 API calls) ───
        log.info("Layer 1: Checking semantic cache...");
        Optional<String> cached = experienceLibrary.lookupCachedSolution(userPrompt);
        if (cached.isPresent()) {
            log.info("Cache HIT (found {} chars). Validating with LocalBrain...", cached.get().length());
            if (localBrain.validateCacheRelevance(userPrompt, cached.get())) {
                log.info("Cache ACCEPTED by LocalBrain. Returning cached response.");
                outputCallback.accept("\n🎯 Cache hit — instant response.");
                streamResponse(cached.get());
                return cached.get();
            } else {
                log.info("Cache REJECTED by LocalBrain. Continuing to next layer.");
                outputCallback.accept("\n  🧠 Cache hit rejected by validation — continuing...");
            }
        } else {
            log.info("Cache MISS. No matching solution found.");
        }

        // ─── Layer 2: Skill Library Replay (0 API calls) ───
        log.info("Layer 2: Checking skill library ({} skills loaded)...", skillLibrary.getSkillCount());
        String skillPlan = skillLibrary.findMatchingSkill(userPrompt);
        if (skillPlan != null) {
            log.info("Skill MATCH found. Validating with LocalBrain...");
            if (localBrain.validateSkillFit(userPrompt, skillPlan)) {
                log.info("Skill ACCEPTED. Replaying...");
                outputCallback.accept("\n📚 Skill match — replaying...");
                String skillResult = replaySkill(skillPlan);
                if (skillResult != null) {
                    log.info("Skill replay SUCCEEDED: {}", truncate(skillResult, 100));
                    return skillResult;
                }
                log.info("Skill replay FAILED (tool error). Falling through.");
            } else {
                log.info("Skill REJECTED by LocalBrain. Planning fresh.");
                outputCallback.accept("\n  🧠 Skill rejected by validation — planning fresh...");
            }
        } else {
            log.info("No skill match.");
        }

        // ─── Layer 3: Classify task + Select tools (Semantic Router) ───
        SemanticRouter.RouteResult routeResult = semanticRouter.classify(userPrompt);
        LocalBrain.TaskType taskType = semanticRouter.toTaskType(routeResult.category());
        List<ToolSpecification> selectedTools = ToolSelector.selectTools(taskType, toolSpecs, userPrompt);
        log.info("Layer 3: Route={} (confidence={}, complexity={}, tier={}), Tools selected={}/{}",
                routeResult.category(), String.format("%.3f", routeResult.confidence()),
                routeResult.complexity(), routeResult.recommendedTier(),
                selectedTools.size(), toolSpecs.size());
        log.info("  Selected tools: {}", selectedTools.stream().map(t -> t.name()).toList());

        // ─── Layer 4: Pre-search for code gen tasks ───
        log.info("Layer 4: Pre-search check (taskType={})", taskType);
        String preSearchContext = null;
        if (taskType == LocalBrain.TaskType.CODE_GENERATION) {
            preSearchContext = performPreSearch(userPrompt);
            if (preSearchContext != null) {
                log.info("Pre-search returned {} chars. Validating with LocalBrain...", preSearchContext.length());
                if (!localBrain.validateSearchRelevance(userPrompt, preSearchContext)) {
                    log.info("Pre-search REJECTED by LocalBrain. Discarding.");
                    preSearchContext = null;
                } else {
                    log.info("Pre-search ACCEPTED. Will inject as context.");
                }
            } else {
                log.info("Pre-search returned nothing or skipped.");
            }
        } else {
            log.info("Pre-search skipped (not CODE_GENERATION).");
        }

        // ─── Layer 5: Strategy Selection ───
        // CODE_GENERATION → straight to ReAct (needs full content generation, not JSON plans)
        // Other tasks → try Plan-then-Execute first (efficient for orchestration)
        Set<String> failedThisRequest = new HashSet<>();
        ProviderRegistry.ProviderEntry provider = providerRegistry.getPrimaryProvider();
        if (provider == null) {
            return "ERROR: No providers configured. Run /configure.";
        }

        boolean usePlan = (taskType == LocalBrain.TaskType.BUG_FIX
                || taskType == LocalBrain.TaskType.SHELL_COMMAND
                || taskType == LocalBrain.TaskType.FILE_READ);
        log.info("Layer 5: Strategy={}, Provider={}", usePlan ? "PLAN" : "REACT", provider.name());

        if (usePlan) {
            log.info("Attempting Plan-then-Execute...");
            outputCallback.accept(String.format("  🧠 %s | %d tools | Plan mode", taskType, selectedTools.size()));

            Map<String, PlanExecutor.ToolMethod> planTools = new HashMap<>();
            toolMethods.forEach((name, tm) ->
                planTools.put(name, new PlanExecutor.ToolMethod(tm.instance(), tm.method())));

            try {
                onThinkingStart.run();
                PlanExecutor.PlanResult planResult = PlanExecutor.planAndExecute(
                        provider.model(), userPrompt, config.getWorkspaceRoot(),
                        planTools, selectedTools, outputCallback);
                onThinkingStop.run();

                if (planResult != null && planResult.success()) {
                    log.info("Plan SUCCEEDED. Response: {}", truncate(planResult.response(), 100));
                    log.info("Validating plan with LocalBrain...");
                    if (localBrain.validatePlanJson(userPrompt, planResult.rawPlan())) {
                        log.info("Plan ACCEPTED. Storing skill + caching.");
                        providerRegistry.recordSuccess(provider.name());
                        skillLibrary.storeSkill(userPrompt, planResult.rawPlan());
                        experienceLibrary.storeSolution(userPrompt, planResult.response());
                        streamResponse(planResult.response());
                        return planResult.response();
                    } else {
                        log.info("Plan REJECTED by LocalBrain. Falling to ReAct.");
                    }
                } else {
                    log.info("Plan returned null or failed. Falling to ReAct.");
                }
            } catch (Exception e) {
                onThinkingStop.run();
                log.error("Plan EXCEPTION: {}", e.getMessage());
                failedThisRequest.add(provider.name());
                providerRegistry.classifyError(provider.name(), e);
            }
        } else {
            log.info("Skipping Plan (task type {} uses ReAct directly)", taskType);
            outputCallback.accept(String.format("  🧠 %s | %d tools | ReAct mode", taskType, selectedTools.size()));
        }

        // ─── Layer 6: ReAct Loop ───
        // Use the primary provider if it hasn't failed, otherwise get next available
        ProviderRegistry.ProviderEntry reactProvider;
        if (!failedThisRequest.contains(provider.name())) {
            reactProvider = provider; // Primary is still good, use it
        } else {
            reactProvider = providerRegistry.getAvailableProvider(failedThisRequest);
        }
        if (reactProvider == null) return "ERROR: All providers unavailable.";

        ChatLanguageModel model = reactProvider.model();
        String currentProvider = reactProvider.name();
        log.info("Layer 6: ReAct starting with provider={}", currentProvider);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemPrompt()));
        if (preSearchContext != null) {
            messages.add(new SystemMessage("REFERENCE:\n" + preSearchContext));
        }
        List<ChatMessage> history = memoryStore.getMessages(sessionId);
        if (!history.isEmpty()) messages.addAll(history);
        messages.add(new UserMessage(userPrompt));
        messages = SlidingContextWindow.apply(messages);

        String finalResponse = null;
        int steps = 0;
        int maxSteps = config.getMaxAgentSteps();

        while (steps < maxSteps) {
            steps++;
            log.info("ReAct Step {}/{} [provider={}]", steps, maxSteps, currentProvider);
            log.debug("  Messages in context: {} (types: {})", messages.size(),
                    messages.stream().map(m -> m.getClass().getSimpleName()).toList());
            outputCallback.accept(String.format("\n⚡ Step %d/%d [%s]", steps, maxSteps, currentProvider));

            try {
                // Apply context budget before sending to LLM
                messages = contextBudgetManager.applyBudget(messages, reactProvider.contextWindow(), userPrompt);

                ChatRequest request = ChatRequest.builder()
                        .messages(messages)
                        .toolSpecifications(selectedTools)
                        .build();

                onThinkingStart.run();
                ChatResponse response = model.chat(request);
                onThinkingStop.run();

                AiMessage aiMessage = response.aiMessage();
                messages.add(aiMessage);

                if (aiMessage.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest toolCall : aiMessage.toolExecutionRequests()) {
                        log.info("Tool call: {}({})", toolCall.name(), truncate(toolCall.arguments(), 200));
                        outputCallback.accept(String.format("  🔧 %s(%s)",
                                toolCall.name(), truncate(toolCall.arguments(), 80)));

                        // ─── Validation Gate: Pre-execution ───
                        if (!localBrain.validateToolCall(userPrompt, toolCall.name(), toolCall.arguments())) {
                            log.warn("  [Gate] Tool call REJECTED by MiniCPM5: {}({})",
                                    toolCall.name(), truncate(toolCall.arguments(), 80));
                            outputCallback.accept("  ⛔ Tool call rejected by validation — asking LLM to retry");
                            messages.add(new ToolExecutionResultMessage(
                                    toolCall.id(), toolCall.name(),
                                    "VALIDATION_REJECTED: This tool call was rejected by the safety validator. "
                                    + "Please try a different approach or verify your arguments."));
                            continue;
                        }

                        String toolResult = executeTool(toolCall);
                        log.info("Tool result: {} chars, starts with: {}", 
                                toolResult != null ? toolResult.length() : 0, truncate(toolResult, 100));

                        // ─── Validation Gate: Post-execution ───
                        if (toolResult != null && toolResult.startsWith("ERROR")) {
                            // Record error for self-improvement
                            selfImprovement.recordFailure(userPrompt, routeResult.category(),
                                    toolResult, toolCall.name());
                        }

                        outputCallback.accept(String.format("  ← %s", truncate(toolResult, 120)));
                        messages.add(new ToolExecutionResultMessage(
                                toolCall.id(), toolCall.name(), toolResult));
                    }
                    providerRegistry.recordSuccess(currentProvider);
                    messages = SlidingContextWindow.apply(messages);
                    continue;
                }

                finalResponse = aiMessage.text();
                log.info("Task COMPLETE. Final response ({} chars): {}", 
                        finalResponse != null ? finalResponse.length() : 0, truncate(finalResponse, 200));
                providerRegistry.recordSuccess(currentProvider);
                break;

            } catch (Exception e) {
                onThinkingStop.run();
                log.error("Provider {} FAILED: {}", currentProvider, e.getMessage());
                log.debug("Full error:", e);

                String errorMsg = e.getMessage() != null ? e.getMessage() : "";
                providerRegistry.classifyError(currentProvider, e);

                // ─── Smart Retry Strategy ─────────────────────────────────
                // Layer 1: If TPM (per-minute) rate limit with short wait → wait and retry SAME provider
                long retryMs = parseRetryDelay(errorMsg);
                int retrySeconds = (int) (retryMs / 1000);
                boolean isPerMinuteLimit = errorMsg.contains("tokens per minute") || errorMsg.contains("TPM");
                boolean isPerDayLimit = errorMsg.contains("tokens per day") || errorMsg.contains("TPD");

                if (isPerMinuteLimit && retrySeconds > 0 && retrySeconds <= 30) {
                    log.info("TPM limit hit. Waiting {}s and retrying same provider: {}", retrySeconds, currentProvider);
                    outputCallback.accept(String.format("  ⏳ Rate limited. Waiting %ds...", retrySeconds));
                    try { Thread.sleep(retryMs + 1000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    // Don't add to failedThisRequest — retry same provider
                    continue;
                }

                // Layer 2: If per-day limit → mark ALL models from same provider account as failed
                if (isPerDayLimit) {
                    String providerPrefix = currentProvider.contains("/")
                            ? currentProvider.substring(0, currentProvider.indexOf("/"))
                            : currentProvider;
                    log.info("Daily limit hit on {}. Marking all {} models as failed for this request.", currentProvider, providerPrefix);
                    for (ProviderRegistry.ProviderEntry p : providerRegistry.getAllProviders()) {
                        if (p.name().startsWith(providerPrefix + "/")) {
                            failedThisRequest.add(p.name());
                        }
                    }
                    outputCallback.accept(String.format("  ⚠️ %s daily limit hit. Skipping all %s models.", currentProvider, providerPrefix));
                } else {
                    failedThisRequest.add(currentProvider);
                    outputCallback.accept(String.format("  ⚠️ %s failed, switching...", currentProvider));
                }

                // Layer 3: Circuit breaker — if 2+ models from same provider failed, skip remaining
                String providerGroup = currentProvider.contains("/")
                        ? currentProvider.substring(0, currentProvider.indexOf("/"))
                        : currentProvider;
                long failedFromSameGroup = failedThisRequest.stream()
                        .filter(name -> name.startsWith(providerGroup + "/"))
                        .count();
                if (failedFromSameGroup >= 2) {
                    // Mark all remaining models from this provider as failed
                    for (ProviderRegistry.ProviderEntry p : providerRegistry.getAllProviders()) {
                        if (p.name().startsWith(providerGroup + "/") && !failedThisRequest.contains(p.name())) {
                            failedThisRequest.add(p.name());
                            log.info("Circuit breaker: skipping {} (2+ failures from {})", p.name(), providerGroup);
                        }
                    }
                }

                ProviderRegistry.ProviderEntry next = providerRegistry.getAvailableProvider(failedThisRequest);
                if (next == null) { finalResponse = "ERROR: All providers exhausted."; break; }
                model = next.model();
                currentProvider = next.name();
                messages = ContextCompressor.compress(messages, localBrain, userPrompt);
                messages = SlidingContextWindow.apply(messages);
            }
        }

        if (finalResponse == null) finalResponse = "Max steps reached. Task may be incomplete.";

        memoryStore.updateMessages(sessionId, messages);
        if (!finalResponse.startsWith("ERROR") && !finalResponse.startsWith("Max steps")) {
            // LocalBrain gate: validate output before caching
            if (localBrain.validateOutput(userPrompt, "response", finalResponse)) {
                experienceLibrary.storeSolution(userPrompt, finalResponse);
            }
            // Self-improvement: record success
            selfImprovement.recordSuccess(userPrompt, routeResult.category(),
                    selectedTools.stream().map(t -> t.name()).toList().toString(), finalResponse);
        } else {
            // Self-improvement: record failure
            selfImprovement.recordFailure(userPrompt, routeResult.category(),
                    finalResponse,
                    selectedTools.stream().map(t -> t.name()).toList().toString());
        }
        streamResponse(finalResponse);
        return finalResponse;
    }

    private String replaySkill(String planJson) {
        try {
            var node = objectMapper.readTree(planJson);
            var steps = node.get("steps");
            if (steps == null || !steps.isArray()) return null;

            outputCallback.accept("  📋 " + steps.size() + " steps");
            for (int i = 0; i < steps.size(); i++) {
                var step = steps.get(i);
                String toolName = step.get("tool").asText();
                outputCallback.accept(String.format("  🔧 [%d/%d] %s", i + 1, steps.size(), toolName));
                String result = executeToolFromArgs(toolName, step.get("args"));
                if (result != null && result.startsWith("ERROR")) return null;
                outputCallback.accept("  ← " + truncate(result, 100));
            }
            String msg = node.has("final_message") ? node.get("final_message").asText() : "Done.";
            streamResponse(msg);
            return msg;
        } catch (Exception e) { return null; }
    }

    private void streamResponse(String response) {
        if (streamTokenCallback != null && response != null && !response.isEmpty()) {
            for (String word : response.split("(?<=\\s)")) {
                streamTokenCallback.accept(word);
                try { Thread.sleep(15); } catch (InterruptedException ignored) {}
            }
            streamTokenCallback.accept("\n");
        }
    }

    private String executeToolFromArgs(String toolName, JsonNode args) {
        ToolMethod tm = toolMethods.get(toolName);
        if (tm == null) return "ERROR: Unknown tool: " + toolName;
        try {
            Object[] methodArgs = parseArguments(args.toString(), tm.method());
            Object result = tm.method().invoke(tm.instance(), methodArgs);
            return result != null ? result.toString() : "(no output)";
        } catch (Exception e) { return "ERROR: " + e.getMessage(); }
    }

    /**
     * Smart pre-search: uses LocalBrain to determine if search is needed and extract a good query.
     */
    private String performPreSearch(String userPrompt) {
        // Use LocalBrain to classify the task
        LocalBrain.TaskType taskType = localBrain.classifyTask(userPrompt);

        // Only pre-search for code generation tasks
        if (taskType != LocalBrain.TaskType.CODE_GENERATION && taskType != LocalBrain.TaskType.UNKNOWN) {
            return null;
        }

        // Use LocalBrain to extract a smart search query
        String searchQuery = localBrain.extractSearchQuery(userPrompt);
        if (searchQuery == null || searchQuery.isBlank()) {
            return null;
        }

        try {
            outputCallback.accept("  🌐 Pre-searching: " + truncate(searchQuery, 60));

            // Use the WebSearchTool directly
            ToolMethod webSearch = toolMethods.get("webSearch");
            if (webSearch != null) {
                Object result = webSearch.method().invoke(webSearch.instance(), searchQuery);
                String searchResult = result != null ? result.toString() : null;

                if (searchResult != null && !searchResult.startsWith("ERROR") && !searchResult.startsWith("No results")) {
                    // Truncate to save tokens
                    if (searchResult.length() > 500) {
                        searchResult = searchResult.substring(0, 500) + "...";
                    }
                    outputCallback.accept("  ← Found references");
                    return searchResult;
                }
            }
        } catch (Exception e) {
            log.debug("Pre-search failed: {}", e.getMessage());
        }
        return null;
    }

    private String executeTool(ToolExecutionRequest toolCall) {
        ToolMethod tm = toolMethods.get(toolCall.name());
        if (tm == null) {
            return "ERROR: Unknown tool: " + toolCall.name();
        }

        try {
            Method method = tm.method();
            Object[] args = parseArguments(toolCall.arguments(), method);
            Object result = method.invoke(tm.instance(), args);
            return result != null ? result.toString() : "(no output)";
        } catch (Exception e) {
            return "ERROR executing tool " + toolCall.name() + ": " + e.getMessage();
        }
    }

    private Object[] parseArguments(String jsonArgs, Method method) {
        try {
            JsonNode node = objectMapper.readTree(jsonArgs);
            Class<?>[] paramTypes = method.getParameterTypes();
            java.lang.reflect.Parameter[] params = method.getParameters();
            Object[] args = new Object[paramTypes.length];

            for (int i = 0; i < params.length; i++) {
                String paramName = params[i].getName();
                // Try common parameter name patterns
                JsonNode value = node.get(paramName);
                if (value == null) {
                    // Try by position if field names don't match
                    Iterator<JsonNode> iter = node.elements();
                    int idx = 0;
                    while (iter.hasNext() && idx <= i) {
                        if (idx == i) {
                            value = iter.next();
                            break;
                        }
                        iter.next();
                        idx++;
                    }
                }

                if (value == null || value.isNull()) {
                    args[i] = getDefault(paramTypes[i]);
                } else if (paramTypes[i] == String.class) {
                    args[i] = value.asText();
                } else if (paramTypes[i] == int.class || paramTypes[i] == Integer.class) {
                    args[i] = value.asInt();
                } else if (paramTypes[i] == long.class || paramTypes[i] == Long.class) {
                    args[i] = value.asLong();
                } else if (paramTypes[i] == boolean.class || paramTypes[i] == Boolean.class) {
                    args[i] = value.asBoolean();
                } else {
                    args[i] = value.asText();
                }
            }
            return args;
        } catch (Exception e) {
            // Fallback: try to pass the whole JSON as a single string arg
            Class<?>[] paramTypes = method.getParameterTypes();
            if (paramTypes.length == 1 && paramTypes[0] == String.class) {
                return new Object[]{jsonArgs};
            }
            return new Object[paramTypes.length];
        }
    }

    private Object getDefault(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == boolean.class) return false;
        return null;
    }

    private String buildSystemPrompt() {
        String learnedRules = selfImprovement.getTopRulesForPrompt();
        return """
            You are Weaver, an autonomous coding agent. You help developers by reading code, writing files, \
            running commands, searching the web, and finding solutions on Stack Overflow.

            BEHAVIOR:
            - When given a task, explore the codebase first to understand context.
            - Use tools to read files, understand structure, then make changes.
            - After making changes, verify them by running build/test commands if appropriate.
            - Search the web or Stack Overflow when you encounter unfamiliar APIs or errors.
            - Be thorough but efficient.
            - If a tool result shows a prior step already completed part of the task, CONTINUE from where it left off. Do NOT redo work.

            TOOL USAGE:
            - readFile(path): Read a file's contents.
            - readFileLines(path, startLine, endLine): Read specific lines.
            - writeFile(path, content): Create or overwrite a file.
            - editFile(path, oldString, newString): Surgical text replacement in a file.
            - listDirectory(path): Tree view of a directory.
            - searchFiles(directory, pattern): Find text patterns in files.
            - run(command): Execute a shell command.
            - runCommand(command, workingDirectory): Execute in specific directory.
            - webSearch(query): Search DuckDuckGo for docs/solutions.
            - fetchWebPage(url): Extract text from a URL.
            - searchStackOverflow(query): Find code solutions on Stack Overflow.

            RESPONSE RULES (CRITICAL):
            - When your task is done, reply with a ONE-LINE summary. Example: "Done. Created login.html with form and gradient background."
            - Do NOT explain the code you wrote. The user can read it themselves.
            - Do NOT restate what the tools already did. The user saw the tool output.
            - Do NOT provide usage instructions unless explicitly asked.
            - NEVER say things like "This code will create..." or "You can replace the console.log line with..."
            - Maximum final response: 2-3 short sentences. Save tokens.
            """ + learnedRules + """

            WORKSPACE:
            - Current working directory: """ + config.getWorkspaceRoot() + "\n" + """
            - All file paths should be relative to this directory unless absolute paths are given.
            """;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        text = text.replace("\n", " ").trim();
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    /**
     * Parse "retry in X.XXs" or "retryDelay: Xs" from error messages to determine wait time.
     */
    private long parseRetryDelay(String errorMessage) {
        if (errorMessage == null) return 10000; // default 10s
        try {
            // Match "try again in X.Xs" or "try again in Xms"
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("try again in ([\\d.]+)(s|ms)")
                    .matcher(errorMessage);
            if (m.find()) {
                double value = Double.parseDouble(m.group(1));
                String unit = m.group(2);
                return (long) (unit.equals("ms") ? value : value * 1000);
            }
            // Match "retryDelay": "Xs"
            m = java.util.regex.Pattern.compile("retryDelay.*?(\\d+)s").matcher(errorMessage);
            if (m.find()) {
                return Long.parseLong(m.group(1)) * 1000;
            }
        } catch (Exception ignored) {}
        return 10000; // default 10s
    }
}
