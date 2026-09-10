package com.slash.agent;

import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable output of Agent Mode's intent-parser and goal-builder stages. */
public final class AgentGoal {
    public enum Intent { OPEN_DESTINATION, PLAY_MEDIA, DEVICE_ACTION }

    public final String userRequest;
    public final Intent intent;
    public final String objective;
    public final JSONArray constraints;
    public final JSONArray successConditions;
    public final String requestedSubject;
    public final String provider;
    public final String artist;
    public final String song;

    AgentGoal(String userRequest, Intent intent, String objective, JSONArray constraints,
            JSONArray successConditions, String requestedSubject, String provider, String artist,
            String song) {
        this.userRequest = userRequest;
        this.intent = intent;
        this.objective = objective;
        this.constraints = constraints;
        this.successConditions = successConditions;
        this.requestedSubject = requestedSubject;
        this.provider = provider;
        this.artist = artist;
        this.song = song;
    }

    public JSONObject toJson() {
        try {
            String intentName = intent == Intent.PLAY_MEDIA ? "play_music"
                    : intent == Intent.OPEN_DESTINATION ? "open_destination" : "device_action";
            JSONObject semanticConstraints = new JSONObject();
            JSONObject success = new JSONObject();
            if (!provider.isEmpty()) semanticConstraints.put("provider", provider);
            if (!artist.isEmpty()) semanticConstraints.put("artist", artist);
            if (!song.isEmpty()) semanticConstraints.put("song", song);
            if (intent == Intent.PLAY_MEDIA) {
                success.put("playback", "playing");
                if (!artist.isEmpty()) success.put("artist_contains", artist);
            } else success.put("world_state_matches_goal", true);
            return new JSONObject().put("userRequest", userRequest).put("intent", intentName)
                    .put("goal", intentName).put("objective", objective).put("provider", provider)
                    .put("constraints", semanticConstraints).put("success", success)
                    .put("policyConstraints", new JSONArray(constraints.toString()))
                    .put("successConditions", new JSONArray(successConditions.toString()));
        } catch (Exception ignored) { return new JSONObject(); }
    }
}
