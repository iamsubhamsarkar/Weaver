package com.weaver.agent;

import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.*;

/**
 * Selects only the tools relevant to the current task type.
 * Reduces input tokens by ~1500 per API call by not sending all 11 tool definitions.
 */
public class ToolSelector {

    /**
     * Select relevant tools based on task classification.
     * Returns a subset of tools that are likely needed for this task type.
     */
    public static List<ToolSpecification> selectTools(
            LocalBrain.TaskType taskType, List<ToolSpecification> allTools) {

        Set<String> relevantNames = getRelevantToolNames(taskType);

        // If classification is UNKNOWN or returned null, use all tools
        if (relevantNames == null) return allTools;

        List<ToolSpecification> selected = new ArrayList<>();
        for (ToolSpecification tool : allTools) {
            if (relevantNames.contains(tool.name())) {
                selected.add(tool);
            }
        }

        // Always return at least the core tools if classification is uncertain
        if (selected.isEmpty()) return allTools;
        return selected;
    }

    private static Set<String> getRelevantToolNames(LocalBrain.TaskType taskType) {
        return switch (taskType) {
            case CODE_GENERATION -> Set.of(
                "writeFile", "editFile", "readFile", "listDirectory",
                "webSearch", "run"
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
