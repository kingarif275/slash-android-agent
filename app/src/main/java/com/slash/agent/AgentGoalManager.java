package com.slash.agent;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;

/** Logical mission queue; foreground Android UI ownership remains serialized. */
public final class AgentGoalManager {
    public enum State { ACTIVE, QUEUED, PAUSED, COMPLETED, CANCELLED, BLOCKED }

    public static final class Entry {
        public final String id;
        public final AgentGoal goal;
        public State state;
        Entry(String id, AgentGoal goal, State state) { this.id = id; this.goal = goal; this.state = state; }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Queue<String> queue = new ArrayDeque<>();
    private String activeId;

    public synchronized Entry submit(String id, AgentGoal goal) {
        if (id == null || id.trim().isEmpty() || goal == null) throw new IllegalArgumentException("goal");
        if (entries.containsKey(id)) throw new IllegalArgumentException("duplicate goal");
        State state = activeId == null ? State.ACTIVE : State.QUEUED;
        Entry entry = new Entry(id, goal, state);
        entries.put(id, entry);
        if (activeId == null) activeId = id; else queue.add(id);
        return entry;
    }

    public synchronized Entry active() { return activeId == null ? null : entries.get(activeId); }
    public synchronized Map<String, Entry> all() { return Collections.unmodifiableMap(new LinkedHashMap<>(entries)); }

    public synchronized void pauseActive() {
        Entry entry = active();
        if (entry != null) entry.state = State.PAUSED;
    }

    public synchronized Entry resume(String id) {
        Entry entry = entries.get(id);
        if (entry == null) return null;
        if (activeId != null && !activeId.equals(id)) throw new IllegalStateException("UI mission already active");
        activeId = id;
        entry.state = State.ACTIVE;
        queue.remove(id);
        return entry;
    }

    public synchronized Entry finishActive(State terminalState) {
        if (terminalState != State.COMPLETED && terminalState != State.CANCELLED
                && terminalState != State.BLOCKED) throw new IllegalArgumentException("terminal state");
        Entry finished = active();
        if (finished == null) return null;
        finished.state = terminalState;
        activeId = null;
        while (!queue.isEmpty()) {
            String nextId = queue.remove();
            Entry next = entries.get(nextId);
            if (next != null && next.state == State.QUEUED) {
                next.state = State.ACTIVE;
                activeId = nextId;
                break;
            }
        }
        return finished;
    }
}
