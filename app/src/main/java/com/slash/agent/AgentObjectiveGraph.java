package com.slash.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Ordered, dependency-aware mission graph. UI navigation remains one action at a time. */
public final class AgentObjectiveGraph {
    private final List<AgentObjective> objectives = new ArrayList<>();
    private int generation = 1;

    public void add(AgentObjective objective) {
        if (objective == null || byId(objective.id) != null) throw new IllegalArgumentException("Duplicate objective");
        objectives.add(objective);
    }

    public List<AgentObjective> all() { return Collections.unmodifiableList(objectives); }
    public AgentObjective byId(String id) {
        if (id == null) return null;
        for (AgentObjective objective : objectives) if (id.equals(objective.id)) return objective;
        return null;
    }

    public AgentObjective current() {
        for (AgentObjective objective : objectives) {
            if (objective.status == AgentObjective.Status.COMPLETED
                    || objective.status == AgentObjective.Status.CANCELLED) continue;
            if (objective.dependenciesComplete(this)) {
                if (objective.status == AgentObjective.Status.PENDING) objective.status = AgentObjective.Status.READY;
                return objective;
            }
        }
        return null;
    }

    public void activateCurrent() {
        AgentObjective current = current();
        if (current != null) { current.status = AgentObjective.Status.ACTIVE; current.generation = generation; }
    }

    public void complete(String id, String evidence, String result) {
        AgentObjective objective = require(id);
        objective.status = AgentObjective.Status.COMPLETED;
        objective.evidence = evidence == null ? "" : evidence;
        objective.result = result == null ? "" : result;
        objective.failureReason = "";
        generation++;
        // Completing an objective is a hard boundary: the next dependent objective
        // becomes the only active planning target.
        activateCurrent();
    }

    public void fail(String id, String reason) {
        AgentObjective objective = require(id);
        objective.status = AgentObjective.Status.FAILED;
        objective.failureReason = reason == null ? "" : reason;
    }

    public void invalidate(String id, String reason) {
        AgentObjective objective = require(id);
        objective.status = AgentObjective.Status.INVALIDATED;
        objective.failureReason = reason == null ? "" : reason;
        generation++;
    }

    public int generation() { return generation; }
    public boolean complete() {
        return !objectives.isEmpty() && objectives.stream()
                .allMatch(item -> item.status == AgentObjective.Status.COMPLETED);
    }

    public JSONArray toJson() {
        JSONArray array = new JSONArray();
        for (AgentObjective objective : objectives) array.put(objective.toJson());
        return array;
    }

    /** Structured cross-objective outputs for later objectives; never flatten into chat text. */
    public JSONObject outputs() {
        JSONObject result = new JSONObject();
        for (AgentObjective objective : objectives) {
            if (objective.status != AgentObjective.Status.COMPLETED || objective.result.isEmpty()) continue;
            try {
                result.put(objective.id, new JSONObject()
                        .put("type", objective.type.name())
                        .put("value", objective.result)
                        .put("evidence", objective.evidence));
            } catch (Exception ignored) { }
        }
        return result;
    }

    private AgentObjective require(String id) {
        AgentObjective objective = byId(id);
        if (objective == null) throw new IllegalArgumentException("Unknown objective: " + id);
        return objective;
    }
}
