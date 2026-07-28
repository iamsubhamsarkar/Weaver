package com.weaver.agent;

import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.*;

/**
 * Selects only the tools relevant to the current task type.
 * Reduces input tokens by not sending all 11 tool definitions.
 *
 * If the prompt contains multiple intents (e.g., "read X and build Y"),
 * returns all tools to avoid blocking needed actions.
 */
public class ToolSelector {

    // Action words that indicate the user wants to CREATE something
    private static final Set<String> ACTION_WORDS = Set.of(
        "build", "create", "make", "write", "implement", "generate",
        "add", "develop", "construct", "design", "setup", "set up",
        "perform", "do", "execute", "follow"
    );

    /**
     * Select relevant tools based on task classification.
     * Detects multi-intent prompts and returns all tools when needed.
     */
    public static List<ToolSpecification> selectTools(
            LocalBrain.TaskType taskType, List<ToolSpecification> allTools, String userPrompt) {

        // Multi-intent detection: if prompt has action words regardless of classification,
        // give the LLM all tools so it can both read AND create
        if (taskType == LocalBrain.TaskType.FILE_READ || taskType == LocalBrain.TaskType.EXPLAIN) {
            if (containsActionIntent(userPrompt)) {
                return allTools; // User wants to read AND do something — give all tools
            }
        }

        Set<String> relevantNames = getRelevantToolNames(taskType);
        if (relevantNames == null) return allTools;

        List<ToolSpecification> selected = new ArrayList<>();
        for (ToolSpecification tool : allTools) {
            if (relevantNames.contains(tool.name())) {
                selected.add(tool);
            }
        }

        if (selected.isEmpty()) return allTools;
        return selected;
    }

    /**
     * Overload for backward compatibility (without prompt).
     */
    public static List<ToolSpecification> selectTools(
            LocalBrain.TaskType taskType, List<ToolSpecification> allTools) {
        return selectTools(taskType, allTools, "");
    }

    private static boolean containsActionIntent(String prompt) {
        if (prompt == null) return false;
        String lower = prompt.toLowerCase();
        for (String word : ACTION_WORDS) {
            if (lower.contains(word)) return true;
        }
        return false;
    }

    private static Set<String> getRelevantToolNames(LocalBrain.TaskType taskType) {
        return switch (taskType) {
            case CODE_GENERATION -> Set.of(
                "writeFile", "editFile", "readFile", "readFileLines",
                "listDirectory", "webSearch", "run", "searchFiles"
            );
            case FILE_READ -> Set.of(
                "readFile", "readFileLines", "listDirectory", "searchFiles"
            );
            case BUG_FIX -> Set.of(
                "readFile", "readFileLines", "editFile", "searchFiles",
                "run", "webSearch", "searchStackOverflow"
            );
            case EXPLAIN -> Set.of(
                "readFile", "readFileLines", "listDirectory", "searchFiles"
            );
            case SHELL_COMMAND -> Set.of(
                "run", "runCommand", "readFile", "listDirectory"
            );
            case SEARCH -> Set.of(
                "webSearch", "fetchWebPage", "searchStackOverflow", "searchFiles"
            );
            case UNKNOWN -> null; // null = use all tools
        };
    }
}
