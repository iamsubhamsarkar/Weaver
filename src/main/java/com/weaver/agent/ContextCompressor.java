package com.weaver.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
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
     * Also sanitizes tool call/result pairs into plain messages for cross-provider compatibility.
     * This prevents "Unexpected role 'tool' after role 'user'" errors when switching between providers.
     */
    public static List<ChatMessage> compress(List<ChatMessage> messages, LocalBrain localBrain, String userPrompt) {
        // First: sanitize tool call/result pairs into plain text messages
        List<ChatMessage> sanitized = sanitizeForCrossProvider(messages, localBrain, userPrompt);

        // Then: apply size compression
        List<ChatMessage> compressed = new ArrayList<>();
        for (ChatMessage msg : sanitized) {
            if (msg instanceof SystemMessage) {
                compressed.add(msg);
            } else if (msg instanceof UserMessage) {
                compressed.add(msg);
            } else if (msg instanceof AiMessage aiMsg) {
                // After sanitization, no AiMessages should have tool calls
                if (aiMsg.text() != null) {
                    String text = aiMsg.text();
                    if (text.length() > MAX_AI_TEXT_CHARS) {
                        text = text.substring(0, MAX_AI_TEXT_CHARS) + "... [trimmed]";
                    }
                    compressed.add(new AiMessage(text));
                }
            } else {
                compressed.add(msg);
            }
        }

        return compressed;
    }

    /**
     * Convert AiMessage(tool_calls) + ToolExecutionResultMessage sequences into
     * plain AiMessage + UserMessage pairs that any provider can understand.
     *
     * This is critical for multi-provider failover: when provider A made a tool call
     * and got results, but then provider B takes over, provider B rejects the
     * orphaned tool messages because it didn't generate the tool_calls.
     *
     * Transformation:
     *   AiMessage(tool_calls=[{name:readFile, args:{path:x}}])
     *   ToolExecutionResultMessage(id, readFile, "file contents...")
     * Becomes:
     *   AiMessage("I'll read the file x")
     *   UserMessage("[Tool result: readFile] file contents...")
     */
    private static List<ChatMessage> sanitizeForCrossProvider(List<ChatMessage> messages,
                                                              LocalBrain localBrain, String userPrompt) {
        List<ChatMessage> result = new ArrayList<>();

        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);

            if (msg instanceof AiMessage aiMsg && aiMsg.hasToolExecutionRequests()) {
                // Convert tool call AiMessage into a plain text AiMessage
                StringBuilder aiText = new StringBuilder("I called: ");
                for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                    aiText.append(req.name()).append("(").append(truncateArgs(req.arguments())).append(") ");
                }
                result.add(new AiMessage(aiText.toString().trim()));

                // Collect all following ToolExecutionResultMessages and convert to a UserMessage
                StringBuilder toolResults = new StringBuilder();
                while (i + 1 < messages.size() && messages.get(i + 1) instanceof ToolExecutionResultMessage) {
                    i++;
                    ToolExecutionResultMessage toolResult = (ToolExecutionResultMessage) messages.get(i);
                    String content = toolResult.text();

                    // Summarize large results
                    if (localBrain != null && content != null && content.length() > MAX_TOOL_RESULT_CHARS) {
                        content = localBrain.summarizeForContext(toolResult.toolName(), content, userPrompt);
                    } else {
                        content = simpleCompress(toolResult.toolName(), content);
                    }

                    toolResults.append("[").append(toolResult.toolName()).append(" result]: ")
                            .append(content).append("\n");
                }

                if (toolResults.length() > 0) {
                    result.add(new UserMessage(toolResults.toString().trim()));
                }

            } else if (msg instanceof ToolExecutionResultMessage toolResult) {
                // Orphaned tool result without preceding AiMessage — convert to UserMessage
                String content = toolResult.text();
                if (localBrain != null && content != null && content.length() > MAX_TOOL_RESULT_CHARS) {
                    content = localBrain.summarizeForContext(toolResult.toolName(), content, userPrompt);
                } else {
                    content = simpleCompress(toolResult.toolName(), content);
                }
                result.add(new UserMessage("[" + toolResult.toolName() + " result]: " + content));

            } else {
                result.add(msg);
            }
        }

        return result;
    }

    private static String truncateArgs(String args) {
        if (args == null) return "";
        return args.length() > 80 ? args.substring(0, 80) + "..." : args;
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
