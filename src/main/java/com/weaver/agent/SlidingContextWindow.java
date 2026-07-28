package com.weaver.agent;

import dev.langchain4j.data.message.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Sliding Context Window: keeps only recent messages + a compact summary.
 * Prevents quadratic token growth as conversation continues.
 *
 * Strategy:
 * - Always keep: system prompt + user's current task
 * - Keep last N messages in full (the "window")
 * - Summarize everything older than the window into a 1-2 line summary
 *
 * This ensures the LLM always has enough context to continue
 * but never receives the full 10,000+ token history from long sessions.
 */
public class SlidingContextWindow {

    private static final int WINDOW_SIZE = 4; // Keep last 4 messages in full

    /**
     * Apply sliding window to message list.
     * Keeps system message + user message + last N messages.
     * Summarizes older messages into a compact system note.
     */
    public static List<ChatMessage> apply(List<ChatMessage> messages) {
        if (messages.size() <= WINDOW_SIZE + 2) {
            return messages; // Small enough, no windowing needed
        }

        List<ChatMessage> windowed = new ArrayList<>();

        // 1. Always keep the system message(s) at the start
        int firstNonSystem = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage) {
                windowed.add(messages.get(i));
                firstNonSystem = i + 1;
            } else {
                break;
            }
        }

        // 2. Always keep the first user message (the task)
        for (int i = firstNonSystem; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage) {
                windowed.add(messages.get(i));
                firstNonSystem = i + 1;
                break;
            }
        }

        // 3. Identify the window boundary
        int totalRemaining = messages.size() - firstNonSystem;
        if (totalRemaining <= WINDOW_SIZE) {
            // Everything fits in the window
            for (int i = firstNonSystem; i < messages.size(); i++) {
                windowed.add(messages.get(i));
            }
            return windowed;
        }

        // 4. Summarize older messages (before the window)
        int windowStart = messages.size() - WINDOW_SIZE;
        String summary = summarizeOlderMessages(messages, firstNonSystem, windowStart);
        if (!summary.isEmpty()) {
            windowed.add(new SystemMessage("[Prior context summary: " + summary + "]"));
        }

        // 5. Add the last N messages in full (the window)
        for (int i = windowStart; i < messages.size(); i++) {
            windowed.add(messages.get(i));
        }

        return windowed;
    }

    /**
     * Create a compact summary of older messages.
     * Extracts: what tools were called and their outcomes.
     */
    private static String summarizeOlderMessages(List<ChatMessage> messages, int from, int to) {
        List<String> actions = new ArrayList<>();

        for (int i = from; i < to; i++) {
            ChatMessage msg = messages.get(i);

            if (msg instanceof AiMessage aiMsg && aiMsg.hasToolExecutionRequests()) {
                for (var req : aiMsg.toolExecutionRequests()) {
                    actions.add(req.name() + "() called");
                }
            } else if (msg instanceof ToolExecutionResultMessage toolMsg) {
                String result = toolMsg.text();
                if (result != null && result.startsWith("Successfully")) {
                    actions.add(result.length() > 60 ? result.substring(0, 60) : result);
                } else if (result != null && result.startsWith("ERROR")) {
                    actions.add("FAILED: " + toolMsg.toolName());
                }
            }
        }

        if (actions.isEmpty()) return "";
        // Keep it very compact
        if (actions.size() > 5) {
            return String.join("; ", actions.subList(0, 5)) + " + " + (actions.size() - 5) + " more steps";
        }
        return String.join("; ", actions);
    }
}
