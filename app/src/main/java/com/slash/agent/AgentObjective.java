package com.slash.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A semantic mission objective with explicit lifecycle and evidence. */
public final class AgentObjective {
    public enum Type {
        OPEN_APP, LOCATE_CONTENT, PLAY_CONTENT, FIND_DESTINATION, TYPE_TEXT,
        HOME, BACK, EXTRACT_INFORMATION, CALCULATE, VERIFY_RESULT, GENERIC_ACTION
    }

    public enum Status { PENDING, READY, ACTIVE, WAITING, COMPLETED, BLOCKED, FAILED, CANCELLED, INVALIDATED }

    public final String id;
    public final Type type;
    public final String description;
    public final List<String> dependsOn;
    public Status status = Status.PENDING;
    public int attempts;
    public String evidence = "";
    public String result = "";
    public String failureReason = "";
    public int generation;

    public AgentObjective(String id, Type type, String description, List<String> dependsOn) {
        this.id = id;
        this.type = type;
        this.description = description == null ? "" : description;
        this.dependsOn = Collections.unmodifiableList(new ArrayList<>(dependsOn == null
                ? Collections.emptyList() : dependsOn));
    }

    public boolean dependenciesComplete(AgentObjectiveGraph graph) {
        for (String dependency : dependsOn) {
            AgentObjective item = graph.byId(dependency);
            if (item == null || item.status != Status.COMPLETED) return false;
        }
        return true;
    }

    public JSONObject toJson() {
        try {
            return new JSONObject().put("objective_id", id).put("type", type.name())
                    .put("description", description).put("status", status.name())
                    .put("dependencies", new JSONArray(dependsOn))
                    .put("attempts", attempts).put("evidence", evidence)
                    .put("result", result).put("failure_reason", failureReason)
                    .put("generation", generation);
        } catch (Exception ignored) { return new JSONObject(); }
    }
}
