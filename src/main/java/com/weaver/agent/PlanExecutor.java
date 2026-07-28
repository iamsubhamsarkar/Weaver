package com.weaver.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;

/**
 * Plan-then-Execute: asks the LLM for a complete plan in ONE call,
 * then executes all tool calls locally without further LLM calls.
 *
 * This reduces a typical 3-6 API call task to 1-2 API calls.
 *
 * Flow:
 * 1. Send task + planning prompt to LLM → get JSON plan
 * 2. Execute each step locally (readFile, writeFile, run, etc.)
 * 3. If any step fails → fall back to normal ReAct loop
 * 4. If all steps succeed → return final message (1 API call total)
 */
public class PlanExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutor.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Result of plan execution.
     */
    public record PlanResult(
        boolean success,
        String response,       // Final message to user
        List<String> stepLogs, // What was executed
        String rawPlan         // The JSON plan for skill library storage
    ) {}

    /**
     * Ask the LLM for a plan and execute it locally.
     * Returns null if planning fails (caller should fall back to ReAct).
     */
    public static PlanResult planAndExecute(
            ChatLanguageModel model,
            String userPrompt,
            String workspaceRoot,
            Map<String, ToolMethod> toolMethods,
            List<ToolSpecification> availableTools,
            Consumer<String> outputCallback) {

        try {
            // 1. Ask LLM for a plan (ONE API call)
            String planningPrompt = buildPlanningPrompt(userPrompt, workspaceRoot, availableTools);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage(planningPrompt));
            messages.add(new UserMessage(userPrompt));

            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .build(); // No tools — we want a text JSON response, not tool calls

            ChatResponse response = model.chat(request);
            String planJson = response.aiMessage().text();

            if (planJson == null || planJson.isBlank()) return null;

            // 2. Parse the plan
            JsonNode plan = parsePlan(planJson);
            if (plan == null) return null;

            JsonNode steps = plan.get("steps");
            String finalMessage = plan.has("final_message")
                    ? plan.get("final_message").asText() : "Done.";

            if (steps == null || !steps.isArray() || steps.isEmpty()) return null;

            // 3. Execute each step locally (ZERO additional API calls)
            List<String> stepLogs = new ArrayList<>();
            outputCallback.accept("  📋 Plan: " + steps.size() + " steps");

            for (int i = 0; i < steps.size(); i++) {
                JsonNode step = steps.get(i);
                String toolName = step.has("tool") ? step.get("tool").asText() : null;
                JsonNode args = step.get("args");

                if (toolName == null || args == null) continue;

                outputCallback.accept(String.format("  🔧 [%d/%d] %s",
                        i + 1, steps.size(), toolName));

                // Execute the tool
                String result = executeToolFromPlan(toolName, args, toolMethods);

                if (result != null && result.startsWith("ERROR")) {
                    // Step failed — plan execution failed, fall back to ReAct
                    outputCallback.accept("  ⚠️ Step failed: " + truncate(result, 80));
                    return null; // Signals caller to use normal ReAct
                }

                stepLogs.add(toolName + " → " + truncate(result, 60));
                outputCallback.accept("  ← " + truncate(result, 100));
            }

            // 4. All steps succeeded — return the plan's final message
            return new PlanResult(true, finalMessage, stepLogs, planJson);

        } catch (Exception e) {
            log.debug("Plan-then-execute failed, falling back to ReAct: {}", e.getMessage());
            return null; // Fall back to ReAct
        }
    }

    private static String buildPlanningPrompt(String userPrompt,
                                               String workspaceRoot,
                                               List<ToolSpecification> tools) {
        StringBuilder toolList = new StringBuilder();
        for (ToolSpecification tool : tools) {
            toolList.append("- ").append(tool.name()).append("(");
            if (tool.parameters() != null) {
                tool.parameters().properties().forEach((name, prop) ->
                    toolList.append(name).append(", "));
            }
            toolList.append(")\n");
        }

        return """
            You are a planning agent. Given a task, output a JSON plan that can be executed step by step.
            You MUST respond with ONLY valid JSON (no markdown, no explanation, no ```json blocks).

            Available tools:
            """ + toolList + """

            Working directory: """ + workspaceRoot + """

            RULES:
            - Output ONLY a JSON object with "steps" array and "final_message" string.
            - Each step has "tool" (tool name) and "args" (object with the tool's parameters).
            - For writeFile: "args" must have "path" and "content" (write the FULL file content).
            - For editFile: "args" must have "path", "oldString", and "newString".
            - For readFile: "args" must have "path".
            - For run: "args" must have "command".
            - For webSearch: "args" must have "query".
            - Make file paths relative to the working directory unless absolute paths are given.
            - "final_message" should be a 1-line summary of what was accomplished.
            - You can request multiple tools to call in parallel by using the same step number.
            - If the task is complex and you're unsure of exact content, still attempt a plan.

            RESPONSE FORMAT (strict JSON only):
            {"steps": [{"tool": "toolName", "args": {...}}, ...], "final_message": "Done. Created X."}
            """;
    }

    private static JsonNode parsePlan(String planJson) {
        try {
            // Strip markdown code blocks if model added them
            planJson = planJson.trim();
            if (planJson.startsWith("```")) {
                planJson = planJson.replaceAll("```json\\s*", "").replaceAll("```\\s*", "");
            }

            // Try to find JSON object in the response
            int start = planJson.indexOf("{");
            int end = planJson.lastIndexOf("}");
            if (start >= 0 && end > start) {
                planJson = planJson.substring(start, end + 1);
            }

            JsonNode node = mapper.readTree(planJson);
            if (node.has("steps") && node.get("steps").isArray()) {
                return node;
            }
            return null;
        } catch (Exception e) {
            log.debug("Failed to parse plan JSON: {}", e.getMessage());
            return null;
        }
    }

    private static String executeToolFromPlan(String toolName, JsonNode args,
                                               Map<String, ToolMethod> toolMethods) {
        ToolMethod tm = toolMethods.get(toolName);
        if (tm == null) return "ERROR: Unknown tool: " + toolName;

        try {
            Method method = tm.method();
            Object[] methodArgs = parseArgsForMethod(args, method);
            Object result = method.invoke(tm.instance(), methodArgs);
            return result != null ? result.toString() : "(no output)";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private static Object[] parseArgsForMethod(JsonNode args, Method method) {
        java.lang.reflect.Parameter[] params = method.getParameters();
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] result = new Object[params.length];

        for (int i = 0; i < params.length; i++) {
            String paramName = params[i].getName();
            JsonNode value = args.get(paramName);

            // Try common alternative names
            if (value == null) {
                // Try matching by common patterns
                if (paramName.equals("arg0") || paramName.equals("p0")) {
                    // Positional — get first field
                    Iterator<JsonNode> iter = args.elements();
                    if (iter.hasNext()) value = iter.next();
                }
            }

            if (value == null || value.isNull()) {
                result[i] = getDefault(paramTypes[i]);
            } else if (paramTypes[i] == String.class) {
                result[i] = value.asText();
            } else if (paramTypes[i] == int.class || paramTypes[i] == Integer.class) {
                result[i] = value.asInt();
            } else if (paramTypes[i] == long.class || paramTypes[i] == Long.class) {
                result[i] = value.asLong();
            } else if (paramTypes[i] == boolean.class || paramTypes[i] == Boolean.class) {
                result[i] = value.asBoolean();
            } else {
                result[i] = value.asText();
            }
        }
        return result;
    }

    private static Object getDefault(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == boolean.class) return false;
        return null;
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        text = text.replace("\n", " ").trim();
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }

    // Re-use the ToolMethod record from WeaverAgent
    public record ToolMethod(Object instance, Method method) {}
}
