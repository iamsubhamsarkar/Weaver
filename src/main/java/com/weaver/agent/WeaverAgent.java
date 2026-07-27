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
                       CodebaseTools codebaseTools,
                       ShellTool shellTool,
                       WebSearchTool webSearchTool,
                       StackOverflowTool stackOverflowTool) {
        this.providerRegistry = providerRegistry;
        this.experienceLibrary = experienceLibrary;
        this.memoryStore = memoryStore;
        this.config = config;

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
        // 1. Check semantic cache
        Optional<String> cached = experienceLibrary.lookupCachedSolution(userPrompt);
        if (cached.isPresent()) {
            outputCallback.accept("\n🎯 Found cached solution from Experience Library!");
            return cached.get();
        }

        // 2. Build conversation
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemPrompt()));

        // Add existing memory
        List<ChatMessage> history = memoryStore.getMessages(sessionId);
        if (!history.isEmpty()) {
            messages.addAll(history);
        }
        messages.add(new UserMessage(userPrompt));

        // 3. ReAct Loop
        String currentProvider = providerRegistry.getPrimaryProviderName();
        ChatLanguageModel model = providerRegistry.getPrimaryModel();

        if (model == null) {
            return "ERROR: No AI providers configured. Add API keys to configs/application-local.yml";
        }

        String finalResponse = null;
        int steps = 0;
        int maxSteps = config.getMaxAgentSteps();

        while (steps < maxSteps) {
            steps++;
            outputCallback.accept(String.format("\n⚡ Step %d/%d [%s]", steps, maxSteps, currentProvider));

            try {
                ChatRequest request = ChatRequest.builder()
                        .messages(messages)
                        .toolSpecifications(toolSpecs)
                        .build();

                onThinkingStart.run();
                ChatResponse response = model.chat(request);
                onThinkingStop.run();

                AiMessage aiMessage = response.aiMessage();
                messages.add(aiMessage);

                if (aiMessage.hasToolExecutionRequests()) {
                    List<ToolExecutionRequest> toolCalls = aiMessage.toolExecutionRequests();

                    for (ToolExecutionRequest toolCall : toolCalls) {
                        outputCallback.accept(String.format("  🔧 %s(%s)",
                                toolCall.name(), truncate(toolCall.arguments(), 80)));

                        String toolResult = executeTool(toolCall);
                        outputCallback.accept(String.format("  ← %s", truncate(toolResult, 120)));

                        messages.add(new ToolExecutionResultMessage(
                                toolCall.id(), toolCall.name(), toolResult));
                    }

                    providerRegistry.recordSuccess(currentProvider);
                    continue;
                }

                finalResponse = aiMessage.text();

                // Stream the response for real-time UX if callback is set
                if (streamTokenCallback != null && finalResponse != null && !finalResponse.isEmpty()) {
                    // Stream word-by-word for natural appearance
                    String[] words = finalResponse.split("(?<=\\s)"); // split keeping whitespace
                    for (String word : words) {
                        streamTokenCallback.accept(word);
                        try { Thread.sleep(15); } catch (InterruptedException ignored) {}
                    }
                    streamTokenCallback.accept("\n");
                }

                providerRegistry.recordSuccess(currentProvider);
                break;

            } catch (Exception e) {
                onThinkingStop.run();
                log.warn("Provider {} failed: {}", currentProvider, e.getMessage());
                providerRegistry.recordFailure(currentProvider);
                outputCallback.accept(String.format("  ⚠️ %s rate-limited, switching provider...", currentProvider));

                // Wait before retry (parse delay from error or use default 10s)
                long delayMs = parseRetryDelay(e.getMessage());
                if (delayMs > 0 && delayMs <= 60000) {
                    try { Thread.sleep(Math.min(delayMs, 15000)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }

                // Get next provider (properly tracked by name)
                var nextProvider = providerRegistry.getNextProvider(currentProvider);
                if (nextProvider == null) {
                    finalResponse = "ERROR: All AI providers are rate-limited. Please wait a minute and try again.";
                    break;
                }
                model = nextProvider.model();
                currentProvider = nextProvider.name();
            }
        }

        if (finalResponse == null) {
            finalResponse = "Agent reached maximum steps (" + maxSteps + ") without completing.";
        }

        // Save memory
        memoryStore.updateMessages(sessionId, messages);

        // Cache successful solutions
        if (!finalResponse.startsWith("ERROR") && !finalResponse.startsWith("Agent reached")) {
            experienceLibrary.storeSolution(userPrompt, finalResponse);
        }

        return finalResponse;
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
        return """
            You are Weaver, an autonomous coding agent. You help developers by reading code, writing files, \
            running commands, searching the web, and finding solutions on Stack Overflow.

            BEHAVIOR:
            - When given a task, explore the codebase first to understand context.
            - Use tools to read files, understand structure, then make changes.
            - After making changes, verify them by running build/test commands if appropriate.
            - Search the web or Stack Overflow when you encounter unfamiliar APIs or errors.
            - Be thorough but efficient.

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

            RULES:
            - Always read a file before editing it.
            - When done, provide a clear summary of what you did.
            - Current working directory: """ + config.getWorkspaceRoot() + """
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
