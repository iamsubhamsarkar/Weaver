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
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
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

                ChatResponse response = model.chat(request);
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
                providerRegistry.recordSuccess(currentProvider);
                break;

            } catch (Exception e) {
                log.warn("Provider {} failed: {}", currentProvider, e.getMessage());
                providerRegistry.recordFailure(currentProvider);
                outputCallback.accept(String.format("  ⚠️ %s failed, trying fallback...", currentProvider));

                model = providerRegistry.getNextModel(currentProvider);
                if (model == null) {
                    finalResponse = "ERROR: All AI providers failed. Last error: " + e.getMessage();
                    break;
                }
                currentProvider = "fallback";
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
}
