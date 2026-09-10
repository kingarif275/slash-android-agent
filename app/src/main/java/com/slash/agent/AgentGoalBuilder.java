package com.slash.agent;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Converts the authoritative user request into an executable, verifiable goal. */
public final class AgentGoalBuilder {
    public AgentGoal build(String request) {
        String goal = request == null ? "" : request.trim()
                .replaceAll("(?i)\\bgohome\\b", "go home")
                .replaceAll("(?i)\\breturnhome\\b", "return home")
                .replaceAll("(?i)\\blaunch\\b", "open")
                .replaceAll("(?i)\\bstart\\b", "open")
                .replaceAll("(?i)\\breturn\\s+home\\b", "go home");
        goal = goal.replaceAll("(?i)\\bhead\\s+home\\b", "go home");
        goal = goal.replaceAll("(?i)\\bexit\\s+to\\s+home\\b", "go home");
        goal = goal.replaceAll("(?i)\\bnavigate\\s+back\\b", "press back");
        String lower = goal.toLowerCase(Locale.US);
        AgentGoal.Intent intent = lower.contains("play ") && !hasPostPlaybackActions(lower)
                ? AgentGoal.Intent.PLAY_MEDIA
                : lower.contains("open ") && !hasActionAfterOpen(lower)
                    ? AgentGoal.Intent.OPEN_DESTINATION : AgentGoal.Intent.DEVICE_ACTION;
        JSONArray constraints = new JSONArray()
                .put("Keep the complete user request authoritative; do not drop later clauses.")
                .put("Use only grounded controls from the latest world-state observation.")
                .put("Do not report success until an independent verifier accepts fresh evidence.")
                .put("Do not perform actions outside the user's requested scope.");
        JSONArray success = new JSONArray();
        String subject = "";
        String provider = lower.contains("youtube music") ? "youtube music"
                : lower.contains("youtube") ? "youtube"
                : lower.contains("spotify") ? "spotify"
                : lower.contains("apple music") ? "apple music" : "";
        String artist = "";
        String song = "";
        if (lower.contains("play ")) {
            subject = requestedMediaSubject(goal);
            artist = subject;
            song = requestedSong(goal);
            // Requests such as "play a MrBeast video" do not use the
            // artist/song grammar ("by Artist"). Preserve the actual media
            // query so the verifier can require visible matching content
            // instead of treating any player surface as success.
            if (subject.isEmpty() && song.isEmpty()) {
                String query = requestedMediaQuery(goal);
                subject = query;
                artist = query;
            }
        }
        if (intent == AgentGoal.Intent.PLAY_MEDIA) {
            success.put("The active media app visibly shows playback active.");
            if (!artist.isEmpty()) success.put("The active media surface contains artist: " + artist);
        } else if (intent == AgentGoal.Intent.OPEN_DESTINATION) {
            success.put("The requested destination is the visible foreground Android app.");
        } else {
            success.put("Every action clause in the complete user request has been completed.");
            success.put("The final requested outcome is visible in a fresh Android world state.");
        }
        return new AgentGoal(goal, intent, goal, constraints, success, subject, provider, artist, song);
    }

    /** Builds a dependency-ordered mission graph without encoding any app layout. */
    public AgentObjectiveGraph buildGraph(AgentGoal goal) {
        AgentObjectiveGraph graph = new AgentObjectiveGraph();
        if (goal == null || goal.objective == null || goal.objective.trim().isEmpty()) return graph;
        String text = goal.objective.trim();
        String lower = text.toLowerCase(Locale.US);
        List<Clause> clauses = new ArrayList<>();
        int index = 1;
        String previous = null;
        java.util.regex.Matcher apps = java.util.regex.Pattern.compile(
                "(?i)\\b(?:open|launch|start)\\s+(.+?)(?=\\s+(?:and|then|before|after)\\b|[,.;]|$)").matcher(text);
        while (apps.find()) {
            String app = apps.group(1).trim();
            if (app.isEmpty()) continue;
            clauses.add(new Clause(apps.start(), AgentObjective.Type.OPEN_APP, "Open " + app));
        }
        java.util.regex.Matcher destinations = java.util.regex.Pattern.compile(
                "(?i)\\b(?:find|locate)\\s+(.+?)(?=\\s+(?:and|then|before|after)\\b|[,.;]|$)").matcher(text);
        while (destinations.find()) {
            String destination = destinations.group(1).trim();
            if (destination.isEmpty()) continue;
            clauses.add(new Clause(destinations.start(), AgentObjective.Type.FIND_DESTINATION,
                    "Find " + destination));
        }
        if (lower.contains("play ")) {
            clauses.add(new Clause(lower.indexOf("play "), AgentObjective.Type.PLAY_CONTENT,
                    "Play the requested content"));
        }
        if (lower.matches("(?s).*\\b(?:write|type|enter)\\b.*")) {
            int position = firstKeyword(lower, "write", "type", "enter");
            clauses.add(new Clause(position, AgentObjective.Type.TYPE_TEXT, "Enter the requested text"));
        }
        if (lower.matches("(?s).*\\b(?:calculate|compute|work out)\\b.*")) {
            int position = firstKeyword(lower, "calculate", "compute", "work out");
            clauses.add(new Clause(position, AgentObjective.Type.CALCULATE,
                    "Calculate the requested expression"));
        }
        if (lower.matches("(?s).*\\b(?:remember|copy|extract|read)\\b.*\\b(?:title|answer|result|text)\\b.*")) {
            int position = firstKeyword(lower, "remember", "copy", "extract", "read");
            clauses.add(new Clause(position, AgentObjective.Type.EXTRACT_INFORMATION,
                    "Extract the requested information for later objectives"));
        }
        if (lower.matches("(?s).*\\b(?:go\\s*home|return\\s*home|home)\\b.*")) {
            clauses.add(new Clause(firstKeyword(lower, "go home", "gohome", "return home", "home"),
                    AgentObjective.Type.HOME, "Go Home"));
        }
        if (lower.matches("(?s).*\\b(?:press\\s+back|navigate\\s+back)\\b.*")) {
            clauses.add(new Clause(firstKeyword(lower, "press back", "navigate back"),
                    AgentObjective.Type.BACK, "Press Back"));
        }
        clauses.sort((left, right) -> Integer.compare(left.position, right.position));
        for (Clause clause : clauses) {
            previous = addObjective(graph, "objective_" + String.format(Locale.US, "%03d", index++),
                    clause.type, clause.description, previous);
        }
        if (graph.all().isEmpty()) {
            graph.add(new AgentObjective("objective_001", AgentObjective.Type.GENERIC_ACTION,
                    text, Collections.emptyList()));
        }
        return graph;
    }

    private String addObjective(AgentObjectiveGraph graph, String id, AgentObjective.Type type,
            String description, String previous) {
        List<String> dependencies = previous == null ? Collections.emptyList()
                : Collections.singletonList(previous);
        graph.add(new AgentObjective(id, type, description, dependencies));
        return id;
    }

    private static int firstKeyword(String text, String... keywords) {
        int best = Integer.MAX_VALUE;
        for (String keyword : keywords) {
            int position = text.indexOf(keyword);
            if (position >= 0) best = Math.min(best, position);
        }
        return best == Integer.MAX_VALUE ? 0 : best;
    }

    private static final class Clause {
        final int position;
        final AgentObjective.Type type;
        final String description;
        Clause(int position, AgentObjective.Type type, String description) {
            this.position = position;
            this.type = type;
            this.description = description;
        }
    }

    private static boolean hasPostPlaybackActions(String lower) {
        int play = lower.indexOf("play ");
        if (play < 0) return false;
        String tail = lower.substring(play + 5);
        return tail.matches(".*\\b(like|subscribe|share|comment|save|download|add|send|turn|enable|disable)\\b.*");
    }

    private static boolean hasActionAfterOpen(String lower) {
        int open = lower.indexOf("open ");
        if (open < 0) return false;
        String tail = lower.substring(open + 5);
        return tail.matches(".*\\b(and|then)\\b.*")
                || tail.matches(".*\\b(turn|play|search|find|message|chat|send|type|call|tap|click|select|set|enable|disable)\\b.*");
    }

    private static String requestedSong(String goal) {
        String value = goal == null ? "" : goal.trim();
        String lower = value.toLowerCase(Locale.US);
        int play = lower.indexOf("play ");
        int by = lower.lastIndexOf(" by ");
        if (play < 0 || by <= play + 5) return "";
        return value.substring(play + 5, by).replaceAll("(?i)^any song\\s*", "").trim();
    }

    static String requestedMediaSubject(String goal) {
        String value = goal == null ? "" : goal.trim();
        int by = value.toLowerCase(Locale.US).lastIndexOf(" by ");
        if (by < 0) return "";
        String subject = value.substring(by + 4).replaceAll("[.!?]+$", "").trim();
        return subject.replaceFirst("(?i)\\s+on\\s+(spotify|youtube music|youtube|apple music)$", "").trim();
    }

    private static String requestedMediaQuery(String goal) {
        String value = goal == null ? "" : goal.trim();
        String lower = value.toLowerCase(Locale.US);
        int play = lower.indexOf("play ");
        if (play < 0) return "";
        String query = value.substring(play + 5).replaceAll("[.!?]+$", "").trim();
        query = query.replaceFirst("(?i)^(a|an|any|the|one)\\s+", "");
        query = query.replaceFirst("(?i)\\s+on\\s+(youtube music|youtube|spotify|apple music)$", "").trim();
        query = query.replaceFirst("(?i)\\s+video$", "").trim();
        return query;
    }
}
