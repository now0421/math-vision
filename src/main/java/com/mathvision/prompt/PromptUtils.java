package com.mathvision.prompt;

import java.util.List;

/**
 * Shared prompt utility methods.
 */
public final class PromptUtils {

    private PromptUtils() {}

    /**
     * Appends fix-history entries to a prompt StringBuilder.
     * Shared between RenderFixPrompts and SceneEvaluationPrompts.
     */
    public static void appendFixHistory(StringBuilder sb, List<String> fixHistory) {
        if (fixHistory != null && !fixHistory.isEmpty()) {
            sb.append("\nPrevious fix attempts to avoid repeating:\n");
            for (int i = 0; i < fixHistory.size(); i++) {
                String item = fixHistory.get(i);
                if (item == null) {
                    continue;
                }
                sb.append("  Attempt ").append(i + 1).append(": ")
                        .append(item.length() > 100 ? item.substring(0, 100) + "..." : item)
                        .append("\n");
            }
        }
    }
}
