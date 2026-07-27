package com.weaver.agent;

import dev.langchain4j.data.message.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Compresses conversation context before handing off to a new model during failover.
 *
 * Strategy:
 * - System message: keep as-is (it's short and essential)
 * - User message: keep as-is (it's the task)
 * - AI messages with tool calls: keep the tool call names/args (compact)
 * - Tool results: TRUNCATE large results to a short summary
 * - AI text responses: keep but trim to max 200 chars
 *
 * This ensures the new model knows WHAT was already done without
 * consuming thousands of tokens on raw file contents.
 */
public class ContextCompressor {

    private static final int MAX_TOOL_RESULT_CHARS = 200;
    private static final int MAX_AI_TEXT_CHARS = 300;

    /**
     * Compress a conversation history for handoff to a new model.
     * Returns a new list with summarized tool results.
     */
    public static List<ChatMessage> compress(List<ChatMessage> messages) {
        List<ChatMessage> compressed = new ArrayList<>();

        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage) {
                // Keep system prompt as-is
                compressed.add(msg);
            } else if (msg instanceof UserMessage) {
                // Keep user message as-is
                compressed.add(msg);
            } else if (msg instanceof AiMessage aiMsg) {
                if (aiMsg.hasToolExecutionRequests()) {
                    // Keep tool call requests as-is (they're small)
                    compressed.add(aiMsg);
                } else if (aiMsg.text() != null) {
                    // Trim verbose AI text responses
                    String text = aiMsg.text();
                    if (text.length() > MAX_AI_TEXT_CHARS) {
                        text = text.substring(0, MAX_AI_TEXT_CHARS) + "... [trimmed]";
                    }
                    compressed.add(new AiMessage(text));
                }
            } else if (msg instanceof ToolExecutionResultMessage toolResult) {
                // This is where we save the most tokens — truncate large tool outputs
                String result = toolResult.text();
                String compressedResult = compressToolResult(toolResult.toolName(), result);
                compressed.add(new ToolExecutionResultMessage(
                        toolResult.id(), toolResult.toolName(), compressedResult));
            } else {
                // Keep any other message types
                compressed.add(msg);
            }
        }

        return compressed;
    }

    /**
     * Compress a single tool result based on what kind of tool produced it.
     */
    private static String compressToolResult(String toolName, String result) {
        if (result == null || result.length() <= MAX_TOOL_RESULT_CHARS) {
            return result; // Already short enough
        }

        // For file reads — summarize instead of passing full content
        if (toolName != null && (toolName.equals("readFile") || toolName.equals("readFileLines"))) {
            int lineCount = result.split("\n").length;
            String firstLines = getFirstLines(result, 5);
            return String.format("[File content: %d lines, %d chars. Preview:]\n%s\n... [truncated for context transfer]",
                    lineCount, result.length(), firstLines);
        }

        // For directory listings — keep first few entries
        if (toolName != null && toolName.equals("listDirectory")) {
            String firstLines = getFirstLines(result, 10);
            return firstLines + "\n... [truncated]";
        }

        // For shell command output — keep first and last few lines
        if (toolName != null && (toolName.equals("run") || toolName.equals("runCommand"))) {
            String[] lines = result.split("\n");
            if (lines.length <= 6) return result;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 3; i++) sb.append(lines[i]).append("\n");
            sb.append("... [").append(lines.length - 6).append(" lines omitted]\n");
            for (int i = lines.length - 3; i < lines.length; i++) sb.append(lines[i]).append("\n");
            return sb.toString();
        }

        // For web/SO search — keep first 200 chars
        if (toolName != null && (toolName.equals("webSearch") || toolName.equals("searchStackOverflow"))) {
            return result.substring(0, MAX_TOOL_RESULT_CHARS) + "... [truncated]";
        }

        // For write/edit results — these are already short ("Successfully wrote...")
        if (toolName != null && (toolName.equals("writeFile") || toolName.equals("editFile"))) {
            return result; // Already concise
        }

        // Generic fallback: truncate
        return result.substring(0, MAX_TOOL_RESULT_CHARS) + "... [truncated for context transfer]";
    }

    private static String getFirstLines(String text, int n) {
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, lines.length); i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString().trim();
    }
}
