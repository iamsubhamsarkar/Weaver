package com.weaver.agent;

import dev.langchain4j.data.message.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Compresses conversation context before handing off to a new model during failover.
 *
 * Strategy:
 * - System message: keep as-is
 * - User message: keep as-is
 * - AI messages with tool calls: keep (compact)
 * - Tool results: summarize using LocalBrain (if available) or truncate
 * - AI text responses: trim to max 200 chars
 */
public class ContextCompressor {

    private static final int MAX_TOOL_RESULT_CHARS = 200;
    private static final int MAX_AI_TEXT_CHARS = 300;

    /**
     * Compress conversation with LocalBrain intelligence.
     */
    public static List<ChatMessage> compress(List<ChatMessage> messages, LocalBrain localBrain, String userPrompt) {
        List<ChatMessage> compressed = new ArrayList<>();

        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage) {
                compressed.add(msg);
            } else if (msg instanceof UserMessage) {
                compressed.add(msg);
            } else if (msg instanceof AiMessage aiMsg) {
                if (aiMsg.hasToolExecutionRequests()) {
                    compressed.add(aiMsg);
                } else if (aiMsg.text() != null) {
                    String text = aiMsg.text();
                    if (text.length() > MAX_AI_TEXT_CHARS) {
                        text = text.substring(0, MAX_AI_TEXT_CHARS) + "... [trimmed]";
                    }
                    compressed.add(new AiMessage(text));
                }
            } else if (msg instanceof ToolExecutionResultMessage toolResult) {
                String result = toolResult.text();
                String compressedResult;

                // Use LocalBrain for smart summarization if result is large
                if (localBrain != null && result != null && result.length() > MAX_TOOL_RESULT_CHARS) {
                    compressedResult = localBrain.summarizeForContext(
                            toolResult.toolName(), result, userPrompt);
                } else {
                    compressedResult = simpleCompress(toolResult.toolName(), result);
                }

                compressed.add(new ToolExecutionResultMessage(
                        toolResult.id(), toolResult.toolName(), compressedResult));
            } else {
                compressed.add(msg);
            }
        }

        return compressed;
    }

    /**
     * Compress without LocalBrain (fallback).
     */
    public static List<ChatMessage> compress(List<ChatMessage> messages) {
        return compress(messages, null, null);
    }

    /**
     * Simple compression when LocalBrain is not available.
     */
    private static String simpleCompress(String toolName, String result) {
        if (result == null || result.length() <= MAX_TOOL_RESULT_CHARS) {
            return result;
        }

        if (toolName != null && (toolName.equals("readFile") || toolName.equals("readFileLines"))) {
            int lineCount = result.split("\n").length;
            String firstLines = getFirstLines(result, 5);
            return String.format("[File: %d lines, %d chars]\n%s\n... [truncated]",
                    lineCount, result.length(), firstLines);
        }

        if (toolName != null && toolName.equals("listDirectory")) {
            return getFirstLines(result, 10) + "\n... [truncated]";
        }

        if (toolName != null && (toolName.equals("run") || toolName.equals("runCommand"))) {
            String[] lines = result.split("\n");
            if (lines.length <= 6) return result;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 3; i++) sb.append(lines[i]).append("\n");
            sb.append("... [").append(lines.length - 6).append(" lines omitted]\n");
            for (int i = lines.length - 3; i < lines.length; i++) sb.append(lines[i]).append("\n");
            return sb.toString();
        }

        if (toolName != null && (toolName.equals("writeFile") || toolName.equals("editFile"))) {
            return result; // Already concise
        }

        return result.substring(0, MAX_TOOL_RESULT_CHARS) + "... [truncated]";
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
