package com.slash.agent;

/** Conversation-scoped state for one structured Android task across follow-up turns. */
public final class AgentTaskSession {
    public final String runId = "run-" + System.currentTimeMillis();
    public enum Status { ACTIVE, FAILED, SUCCEEDED, CANCELLED }

    public AgentGoal activeGoal;
    public AgentObjectiveGraph objectiveGraph;
    public final String originalRequest;
    public String latestInstruction;
    public Status status;
    public int attempt;
    public String lastVerification = "none";
    public String lastAction = "none";
    public String lastWorldStateId = "none";

    AgentTaskSession(AgentGoal goal, String instruction) {
        activeGoal = goal;
        objectiveGraph = new AgentGoalBuilder().buildGraph(goal);
        objectiveGraph.activateCurrent();
        originalRequest = goal.userRequest;
        latestInstruction = instruction;
        status = Status.ACTIVE;
        attempt = 1;
    }

    public boolean retryable() { return status != Status.CANCELLED && activeGoal != null; }

    public void replaceGoal(AgentGoal goal) {
        if (goal == null) throw new IllegalArgumentException("goal");
        activeGoal = goal;
        objectiveGraph = new AgentGoalBuilder().buildGraph(goal);
        objectiveGraph.activateCurrent();
        status = Status.ACTIVE;
    }
}
