package com.weaver.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates colored line-level diffs for file edits.
 * Shows what was removed (red) and what was added (green).
 */
public class DiffDisplay {

    private static final String RED = "\033[31m";
    private static final String GREEN = "\033[32m";
    private static final String DIM = "\033[2m";
    private static final String CYAN = "\033[36m";
    private static final String RESET = "\033[0m";

    /**
     * Generate and print a colored diff between old and new content.
     * Only shows the changed region with a few lines of context.
     */
    public static String generateDiff(String filePath, String oldContent, String newContent) {
        String[] oldLines = oldContent.split("\n", -1);
        String[] newLines = newContent.split("\n", -1);

        // Find the first and last differing lines
        int firstDiff = 0;
        while (firstDiff < oldLines.length && firstDiff < newLines.length
                && oldLines[firstDiff].equals(newLines[firstDiff])) {
            firstDiff++;
        }

        int oldEnd = oldLines.length - 1;
        int newEnd = newLines.length - 1;
        while (oldEnd > firstDiff && newEnd > firstDiff
                && oldLines[oldEnd].equals(newLines[newEnd])) {
            oldEnd--;
            newEnd--;
        }

        // Build the diff output
        StringBuilder sb = new StringBuilder();
        sb.append(DIM).append("  ─── ").append(filePath).append(" ───").append(RESET).append("\n");

        // Context lines before
        int contextStart = Math.max(0, firstDiff - 2);
        for (int i = contextStart; i < firstDiff; i++) {
            sb.append(DIM).append(String.format("  %4d │ ", i + 1)).append(oldLines[i]).append(RESET).append("\n");
        }

        // Removed lines (red)
        for (int i = firstDiff; i <= oldEnd && i < oldLines.length; i++) {
            sb.append(RED).append(String.format("  %4d │- %s", i + 1, oldLines[i])).append(RESET).append("\n");
        }

        // Added lines (green)
        for (int i = firstDiff; i <= newEnd && i < newLines.length; i++) {
            sb.append(GREEN).append(String.format("  %4d │+ %s", i + 1, newLines[i])).append(RESET).append("\n");
        }

        // Context lines after
        int contextEnd = Math.min(oldLines.length - 1, oldEnd + 3);
        for (int i = oldEnd + 1; i <= contextEnd; i++) {
            sb.append(DIM).append(String.format("  %4d │ ", i + 1)).append(oldLines[i]).append(RESET).append("\n");
        }

        return sb.toString();
    }

    /**
     * Simpler diff for string replacements - shows just the old and new strings.
     */
    public static String generateReplacementDiff(String filePath, String oldStr, String newStr) {
        String[] oldLines = oldStr.split("\n", -1);
        String[] newLines = newStr.split("\n", -1);

        StringBuilder sb = new StringBuilder();
        sb.append(DIM).append("  ─── ").append(filePath).append(" ───").append(RESET).append("\n");

        for (String line : oldLines) {
            sb.append(RED).append("  │- ").append(line).append(RESET).append("\n");
        }
        for (String line : newLines) {
            sb.append(GREEN).append("  │+ ").append(line).append(RESET).append("\n");
        }

        return sb.toString();
    }
}
