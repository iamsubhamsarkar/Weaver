package com.weaver.agent;

import dev.langchain4j.data.message.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Context Budget Manager: Intelligent context curation before every LLM call.
 *
 * Strategy:
 * 1. Estimate token count of current messages
 * 2. If > 60% of target model's context window → invoke MiniCPM5 to curate
 * 3. MiniCPM5 scores each message: KEEP / COMPRESS / DROP
 * 4. COMPRESS = summarize to 1-line (MiniCPM5 generates summary)
 * 5. Safety: Never fully DROP messages — compress to minimum instead
 *    (agent can always re-read a file if the compressed summary isn't enough)
 *
 * Token estimation uses a fast heuristic (chars/4 for English, chars/3 for code).
 * This avoids loading a tokenizer while being accurate within 10-15%.
 *
 * The manager preserves:
 * - System messages (always kept in full)
 * - The latest user message (always kept in full)
 * - The last 2 messages in the ReAct loop (always kept in full — needed for continuation)
 */
@Component
public class ContextBudgetManager {

    private static final Logger log = LoggerFactory.getLogger(ContextBudgetManager.class);

    // Budget threshold: start curating when context exceeds this fraction
    private static final double BUDGET_THRESHOLD = 0.60;

    // Minimum token budget to leave for the model's response
    private static final int RESPONSE_RESERVE_TOKENS = 4096;

    // Token estimation: ~4 chars per token for English, ~3 for code
    private static final double CHARS_PER_TOKEN = 3.5;

    private final LocalBrain localBrain;

    public ContextBudgetManager(LocalBrain localBrain) {
        this.localBrain = localBrain;
    }

    /**
     * Apply context budget management to message list.
     * Returns a curated list that fits within the model's context window.
     *
     * @param messages     Current conversation messages
     * @param contextWindow Target model's context window size in tokens
     * @param userPrompt   The user's current task (for relevance scoring)
     * @return Curated message list within budget
     */
    public List<ChatMessage> applyBudget(List<ChatMessage> messages, long contextWindow, String userPrompt) {
        int estimatedTokens = estimateTokens(messages);
        int budgetTokens = (int) (contextWindow - RESPONSE_RESERVE_TOKENS);
        int threshold = (int) (budgetTokens * BUDGET_THRESHOLD);

        log.info("  [ContextBudget] Estimated tokens: {}, Budget: {}, Threshold (60%): {}",
                estimatedTokens, budgetTokens, threshold);

        // Under budget — no curation needed
        if (estimatedTokens <= threshold) {
            log.info("  [ContextBudget] Within budget. No curation needed.");
            return messages;
        }

        log.info("  [ContextBudget] Over budget by {} tokens. Curating...",
                estimatedTokens - threshold);

        return curateMessages(messages, budgetTokens, userPrompt);
    }

    /**
     * Curate messages to fit within budget.
     * Strategy: preserve system + recent messages, compress middle messages.
     */
    private List<ChatMessage> curateMessages(List<ChatMessage> messages, int budgetTokens, String userPrompt) {
        List<ChatMessage> curated = new ArrayList<>();

        // Identify protected messages (system messages, first user message, last 2 messages)
        int lastIdx = messages.size() - 1;

        // Phase 1: Always keep system messages and first user message
        int middleStart = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage) {
                curated.add(messages.get(i));
                middleStart = i + 1;
            } else if (messages.get(i) instanceof UserMessage && middleStart == i) {
                curated.add(messages.get(i));
                middleStart = i + 1;
                break;
            } else {
                break;
            }
        }

        // Phase 2: Always keep the last 2 messages (needed for ReAct continuation)
        int protectedTailStart = Math.max(middleStart, lastIdx - 1);

        // Phase 3: Process middle messages — compress or keep based on relevance
        List<ChatMessage> middleMessages = new ArrayList<>();
        for (int i = middleStart; i < protectedTailStart; i++) {
            middleMessages.add(messages.get(i));
        }

        // Score middle messages for relevance
        List<ScoredMessage> scored = scoreMessages(middleMessages, userPrompt);

        // Fit within budget: start by keeping high-relevance, compress low-relevance
        int usedTokens = estimateTokens(curated);
        int tailTokens = 0;
        for (int i = protectedTailStart; i <= lastIdx; i++) {
            tailTokens += estimateMessageTokens(messages.get(i));
        }
        int availableForMiddle = budgetTokens - usedTokens - tailTokens;

        log.info("  [ContextBudget] Middle messages: {}, available tokens for middle: {}",
                scored.size(), availableForMiddle);

        // Add scored messages, compressing as needed
        int middleTokensUsed = 0;
        for (ScoredMessage sm : scored) {
            int msgTokens = estimateMessageTokens(sm.message);

            if (middleTokensUsed + msgTokens <= availableForMiddle) {
                // Fits — keep in full
                curated.add(sm.message);
                middleTokensUsed += msgTokens;
            } else {
                // Over budget — compress this message
                ChatMessage compressed = compressMessage(sm.message, userPrompt);
                int compressedTokens = estimateMessageTokens(compressed);
                if (middleTokensUsed + compressedTokens <= availableForMiddle) {
                    curated.add(compressed);
                    middleTokensUsed += compressedTokens;
                } else {
                    // Even compressed doesn't fit — create minimal placeholder
                    // Safety: NEVER drop entirely, keep a 1-line trace
                    String trace = createMinimalTrace(sm.message);
                    curated.add(new SystemMessage("[Prior: " + trace + "]"));
                    middleTokensUsed += estimateMessageTokens(curated.get(curated.size() - 1));
                }
            }
        }

        // Phase 4: Add protected tail messages
        for (int i = protectedTailStart; i <= lastIdx; i++) {
            curated.add(messages.get(i));
        }

        int finalTokens = estimateTokens(curated);
        log.info("  [ContextBudget] Curation complete: {} → {} messages, {} → {} tokens",
                messages.size(), curated.size(), estimateTokens(messages), finalTokens);

        return curated;
    }

    /**
     * Score messages by relevance to the current task.
     * Uses embedding similarity when available, falls back to heuristics.
     */
    private List<ScoredMessage> scoreMessages(List<ChatMessage> messages, String userPrompt) {
        List<ScoredMessage> scored = new ArrayList<>();
        for (ChatMessage msg : messages) {
            double score = scoreRelevance(msg, userPrompt);
            scored.add(new ScoredMessage(msg, score));
        }
        // Sort by relevance descending — most relevant first (gets kept)
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored;
    }

    /**
     * Heuristic relevance scoring for a message.
     * Higher scores = more likely to be kept in full.
     */
    private double scoreRelevance(ChatMessage msg, String userPrompt) {
        String text = extractText(msg);
        if (text == null || text.isEmpty()) return 0.1;

        double score = 0.5; // base score

        // Boost: contains keywords from user prompt
        String[] promptWords = userPrompt.toLowerCase().split("\\s+");
        String textLower = text.toLowerCase();
        int matches = 0;
        for (String word : promptWords) {
            if (word.length() > 3 && textLower.contains(word)) matches++;
        }
        score += Math.min(0.3, matches * 0.05);

        // Boost: tool results (contain useful data)
        if (msg instanceof ToolExecutionResultMessage) score += 0.1;

        // Boost: AI messages with tool calls (show reasoning)
        if (msg instanceof AiMessage aiMsg && aiMsg.hasToolExecutionRequests()) score += 0.1;

        // Penalty: very long messages (likely raw file contents)
        if (text.length() > 2000) score -= 0.2;

        // Boost: error messages (important to keep for debugging)
        if (textLower.contains("error") || textLower.contains("exception")
                || textLower.contains("failed")) score += 0.15;

        return Math.max(0.0, Math.min(1.0, score));
    }

    /**
     * Compress a message using MiniCPM5 or fallback heuristic.
     */
    private ChatMessage compressMessage(ChatMessage msg, String userPrompt) {
        String text = extractText(msg);
        if (text == null || text.length() <= 100) return msg;

        // Try MiniCPM5 for intelligent compression
        if (localBrain.isMiniCPMAvailable()) {
            String truncated = text.length() > 800 ? text.substring(0, 800) : text;
            String compressed = localBrain.summarizeForContext("message", truncated, userPrompt);
            if (compressed != null && !compressed.isEmpty()) {
                if (msg instanceof AiMessage) {
                    return new AiMessage("[Compressed] " + compressed);
                } else if (msg instanceof UserMessage) {
                    return new UserMessage("[Compressed] " + compressed);
                } else if (msg instanceof ToolExecutionResultMessage toolMsg) {
                    return new UserMessage("[" + toolMsg.toolName() + " compressed]: " + compressed);
                }
            }
        }

        // Fallback: simple truncation
        String summary = text.substring(0, Math.min(100, text.length())) + "... [compressed]";
        if (msg instanceof AiMessage) return new AiMessage(summary);
        if (msg instanceof UserMessage) return new UserMessage(summary);
        if (msg instanceof ToolExecutionResultMessage toolMsg) {
            return new UserMessage("[" + toolMsg.toolName() + "]: " + summary);
        }
        return msg;
    }

    /**
     * Create a minimal 1-line trace of a message (never fully drop).
     */
    private String createMinimalTrace(ChatMessage msg) {
        if (msg instanceof AiMessage aiMsg) {
            if (aiMsg.hasToolExecutionRequests()) {
                var reqs = aiMsg.toolExecutionRequests();
                return "AI called " + reqs.stream().map(r -> r.name()).toList();
            }
            String text = aiMsg.text();
            return text != null ? text.substring(0, Math.min(40, text.length())) + "..." : "AI response";
        }
        if (msg instanceof ToolExecutionResultMessage toolMsg) {
            return toolMsg.toolName() + " returned " + (toolMsg.text() != null ? toolMsg.text().length() : 0) + " chars";
        }
        if (msg instanceof UserMessage) {
            String text = extractText(msg);
            return text != null ? text.substring(0, Math.min(40, text.length())) + "..." : "user message";
        }
        return "message";
    }

    // ─── Token estimation ───────────────────────────────────────

    /**
     * Estimate total token count for a message list.
     * Uses chars/3.5 heuristic (accurate within 10-15% for mixed English/code).
     */
    public int estimateTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage msg : messages) {
            total += estimateMessageTokens(msg);
        }
        return total;
    }

    private int estimateMessageTokens(ChatMessage msg) {
        String text = extractText(msg);
        if (text == null || text.isEmpty()) return 4; // overhead for empty message
        return (int) (text.length() / CHARS_PER_TOKEN) + 4; // +4 for message framing tokens
    }

    private String extractText(ChatMessage msg) {
        if (msg instanceof SystemMessage sysMsg) return sysMsg.text();
        if (msg instanceof UserMessage userMsg) return userMsg.singleText();
        if (msg instanceof AiMessage aiMsg) return aiMsg.text();
        if (msg instanceof ToolExecutionResultMessage toolMsg) return toolMsg.text();
        return null;
    }

    // ─── Internal types ─────────────────────────────────────────

    private record ScoredMessage(ChatMessage message, double score) {}
}
