package com.slash.agent;

import java.util.Locale;

/** Independent success verifier; planner claims are never accepted as evidence. */
public final class AgentVerifier {
    public static final class Result {
        public final boolean success;
        public final String reason;
        Result(boolean success, String reason) { this.success = success; this.reason = reason; }
    }

    public Result verify(AgentGoal goal, AgentWorldState world, int screenChangingActions) {
        if (!world.accessibilityAvailable) return failure("Current screen is unavailable");
        if (world.keyguardLocked) return failure("Device is locked");
        // Accessibility dumps from media apps can be unexpectedly large. Cap the
        // verifier input so a fresh-screen check never monopolizes the planner
        // scheduler while normalizing a pathological third-party tree.
        String observation = world.accessibilityObservation == null ? "" : world.accessibilityObservation;
        if (observation.length() > 120_000) observation = observation.substring(0, 120_000);
        String lower = observation.toLowerCase(Locale.US);
        // Intent is session state and can survive a conversational retry. The
        // original objective remains authoritative; never apply media evidence
        // to a non-media request such as Settings navigation.
        boolean objectiveRequestsMedia = goal.objective != null
                && goal.objective.toLowerCase(Locale.US).contains("play ");
        if (goal.intent == AgentGoal.Intent.PLAY_MEDIA && objectiveRequestsMedia
                && !goal.objective.toLowerCase(Locale.US).contains("find ")
                && !goal.objective.toLowerCase(Locale.US).contains("locate ")) {
            // The visible active-app surface is authoritative. MediaSession may describe remote
            // playback from a PC, cast target, or another device and must not influence completion.
            // A minimised/remote player can exist before this task and is not
            // proof that the requested content started. Require active-player
            // evidence from the foreground surface itself.
            // A player may hide transport controls until it is tapped, while the
            // foreground accessibility tree still exposes its live timeline (for
            // example, "13 minutes ... of 20 minutes ..."). That timeline is
            // local foreground evidence, unlike MediaSession state which can
            // describe playback on another device.
            boolean progressVisible = lower.matches("(?s).*\\b\\d+\\s+(?:seconds?|minutes?|hours?).*\\bof\\b.*\\b\\d+\\s+(?:seconds?|minutes?|hours?).*");
            boolean pausedControlVisible = lower.contains("pause") || lower.contains("playing")
                    || lower.contains("now playing") || progressVisible;
            boolean artistVisible = !goal.artist.isEmpty() && containsNormalized(lower, goal.artist);
            boolean songVisible = !goal.song.isEmpty() && containsNormalized(lower, goal.song);
            if (pausedControlVisible && (artistVisible || songVisible))
                return success("Visible player surface verifies playback and requested media metadata");
            return failure("Visible playback evidence in the active app is incomplete");
        }
        if (goal.intent == AgentGoal.Intent.OPEN_DESTINATION) {
            String packageName = world.foregroundPackage();
            if (packageName.isEmpty() || packageName.startsWith("com.slash.agent")) {
                return failure("Requested destination is not the foreground app");
            }
            return success("Requested destination is visibly foreground");
        }
        // Calculation outcomes require an actual visible result, not merely a
        // calculator launch or a click count. This remains app-independent.
        if (goal.objective != null && goal.objective.toLowerCase(Locale.US)
                .matches(".*\\b(?:calculate|compute|work out)\\b.*")) {
            boolean hasNumericDisplay = lower.matches("(?s).*\\b(?:result|equals|answer)\\b.*\\d+.*")
                    || lower.matches("(?s).*=[ ]*\\d+[.,]?\\d*\\b.*");
            if (!hasNumericDisplay) return failure("The requested calculation result is not visible");
        }
        // Semantic navigation goals are verified from the fresh visible screen,
        // not from the planner's claim or a click count. A destination such as
        // "Display settings" may be rendered as the page title "Display".
        if (goal.objective != null) {
            // When a task explicitly names an app, visible text alone is not
            // sufficient: a search result or stale accessibility tree from a
            // different app must not satisfy the destination.  Require the
            // fresh foreground package to agree with the requested subject
            // when Android exposes a meaningful package name.
            String foreground = world.foregroundPackage().toLowerCase(Locale.US);
            String subject = goal.requestedSubject == null ? ""
                    : goal.requestedSubject.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
            if (subject.isEmpty()) {
                java.util.regex.Matcher opened = java.util.regex.Pattern.compile(
                        "(?i)\\bopen\\s+([a-z][a-z0-9 ]*?)(?=\\s+(?:and|then|find|locate)\\b|$)")
                        .matcher(goal.objective);
                if (opened.find()) subject = opened.group(1).trim().replaceAll("[^a-z0-9]", "");
            }
            if (goal.intent != AgentGoal.Intent.PLAY_MEDIA
                    && !subject.isEmpty() && !foreground.isEmpty()
                    && !foreground.startsWith("com.slash.agent")
                    && !foreground.replaceAll("[^a-z0-9]", "").contains(subject)) {
                return failure("Fresh foreground app does not match the requested destination");
            }
            java.util.regex.Matcher destination = java.util.regex.Pattern.compile(
                    "(?i)\\b(?:find|locate)\\s+(.+?)(?:\\s+and\\s+.*)?$")
                    .matcher(goal.objective.trim());
            if (destination.find()) {
                String wanted = destination.group(1).replaceAll("(?i)\\b(settings?|page|section)\\b", "")
                        .replaceAll("[^a-z0-9 ]", " ").trim();
                if (!wanted.isEmpty() && containsNormalized(lower, wanted))
                    return success("Requested semantic destination is visible on the fresh screen");
            }
        }
        boolean compound = goal.objective.toLowerCase(Locale.US).matches(".*\\b(and|then)\\b.*");
        String objectiveLower = goal.objective.toLowerCase(Locale.US).replaceAll("[.!?]+$", "").trim();
        boolean homeIsFinal = objectiveLower.matches(".*\\b(go\\s*home|return\\s*home|home)\\b$");
        if (homeIsFinal) {
            String foreground = world.foregroundPackage().toLowerCase(Locale.US);
            if (!(foreground.contains("launcher") || foreground.contains("home")))
                return failure("Home was requested but the launcher is not foreground");
        }
        int minimumActions = compound ? requiredActionClauses(objectiveLower) : 1;
        if (screenChangingActions < minimumActions) {
            return failure("Only " + screenChangingActions + " verified screen-changing action(s) occurred; the complete request is not finished");
        }
        return success("Requested outcome has fresh visible evidence");
    }

    private int requiredActionClauses(String objective) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)\\b(open|go\\s*home|return\\s*home|home|press\\s*back|back)\\b")
                .matcher(objective);
        int count = 0;
        while (matcher.find()) count++;
        // An explicitly invalid app is a recoverable failed clause; do not require a
        // screen-changing action for the failed launch itself.
        int invalids = 0;
        java.util.regex.Matcher invalidMatcher = java.util.regex.Pattern.compile("(?i)nosuchapp").matcher(objective);
        while (invalidMatcher.find()) invalids++;
        count = Math.max(1, count - invalids);
        return Math.max(2, count);
    }

    private Result success(String reason) { return new Result(true, reason); }
    private Result failure(String reason) { return new Result(false, reason); }

    private boolean containsNormalized(String actual, String expected) {
        String a = actual == null ? "" : actual.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
        String e = expected == null ? "" : expected.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
        return !e.isEmpty() && a.contains(e);
    }
}
