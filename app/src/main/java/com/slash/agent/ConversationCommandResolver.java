package com.slash.agent;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves conversational follow-ups before a new AgentGoal can be constructed. */
public final class ConversationCommandResolver {
    public enum Command { NEW_TASK, RETRY_PREVIOUS, CONTINUE_PREVIOUS, MODIFY_PREVIOUS,
        CORRECT_PREVIOUS, CANCEL_PREVIOUS }

    public static final class Resolution {
        public final Command command;
        public final AgentGoal goal;
        public final String explanation;
        public final int attempt;
        Resolution(Command command, AgentGoal goal, String explanation) {
            this.command = command; this.goal = goal; this.explanation = explanation;
            this.attempt = goal == null ? 0 : 1;
        }
        Resolution(Command command, AgentGoal goal, String explanation, int attempt) {
            this.command = command; this.goal = goal; this.explanation = explanation;
            this.attempt = attempt;
        }
        public boolean requiresPrevious() { return command != Command.NEW_TASK; }
    }

    private static final Pattern RETRY = Pattern.compile("(?i)^(please\\s+)?(try|do)\\s+(it|that)?\\s*again$|^(retry|again|one more time)$");
    private static final Pattern PROVIDER = Pattern.compile("(?i)\\b(?:use|on|with)\\s+(youtube music|youtube|spotify|apple music)\\b");
    private static final Pattern CORRECTION = Pattern.compile("(?i)^(?:no[, ]+)?(?:i meant|make it|with)\\s+(.+?)[.!]?$|^do the same thing with\\s+(.+?)[.!]?$");

    public Resolution resolve(String instruction, AgentTaskSession previous) {
        String clean = instruction == null ? "" : instruction.replaceAll("\\s+", " ").trim();
        String lower = clean.toLowerCase(Locale.US).replaceAll("[.!]+$", "");
        if (lower.matches("^(stop|cancel|never mind|nevermind)$"))
            return prior(Command.CANCEL_PREVIOUS, previous, "Cancel previous goal");
        if (lower.matches("^(continue|keep going|carry on)$"))
            return prior(Command.CONTINUE_PREVIOUS, previous, "Continue previous goal");
        if (RETRY.matcher(lower).matches())
            return prior(Command.RETRY_PREVIOUS, previous, "Retry previous goal from fresh state");
        if (lower.contains("again") && (lower.contains("but ") || PROVIDER.matcher(clean).find())) {
            return new Resolution(Command.MODIFY_PREVIOUS, modify(clean, previous), "Retry with validated modifications", nextAttempt(previous));
        }
        Matcher correction = CORRECTION.matcher(clean);
        if (correction.matches()) {
            String replacement = correction.group(1) != null ? correction.group(1) : correction.group(2);
            return new Resolution(Command.CORRECT_PREVIOUS, modifyArtist(replacement, previous), "Correct previous goal", nextAttempt(previous));
        }
        return new Resolution(Command.NEW_TASK, null, "Create a new goal");
    }

    private Resolution prior(Command command, AgentTaskSession previous, String explanation) {
        return new Resolution(command, previousGoal(previous), explanation, nextAttempt(previous));
    }

    private int nextAttempt(AgentTaskSession previous) { return previous == null ? 0 : previous.attempt + 1; }

    private AgentGoal previousGoal(AgentTaskSession previous) {
        return previous != null && previous.retryable() ? previous.activeGoal : null;
    }

    private AgentGoal modify(String instruction, AgentTaskSession previous) {
        AgentGoal prior = previousGoal(previous);
        if (prior == null) return null;
        Matcher provider = PROVIDER.matcher(instruction);
        if (!provider.find()) return prior;
        String chosen = provider.group(1);
        String base = prior.objective.replaceAll("(?i)\\s+on\\s+(spotify|youtube music|youtube|apple music)[.!]?$", "");
        return new AgentGoalBuilder().build(base + " on " + chosen + ".");
    }

    private AgentGoal modifyArtist(String replacement, AgentTaskSession previous) {
        AgentGoal prior = previousGoal(previous);
        if (prior == null || prior.intent != AgentGoal.Intent.PLAY_MEDIA) return null;
        String provider = prior.provider.isEmpty() ? "" : " on " + prior.provider;
        return new AgentGoalBuilder().build("Play any song by " + replacement.trim() + provider + ".");
    }
}
