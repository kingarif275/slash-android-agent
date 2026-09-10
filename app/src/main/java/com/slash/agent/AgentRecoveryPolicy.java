package com.slash.agent;

/** Converts execution/verifier failures into bounded evidence for the next planning turn. */
public final class AgentRecoveryPolicy {
    public String guidance(AgentGoal goal, AgentWorldState world, String action, String result,
            int recoveryAttempt) {
        String reason = result == null ? "unknown failure" : result.trim();
        return "RECOVERY_ATTEMPT=" + recoveryAttempt
                + " GOAL=" + goal.toJson()
                + " PREVIOUS_ACTION=" + (action == null ? "none" : action)
                + " FAILURE=" + reason
                + " CURRENT_WORLD_STATE_ID=" + (world == null ? "unavailable" : world.id)
                + " Re-plan from the fresh WORLD_STATE and choose a corrective next subgoal. Do not repeat the same failed action "
                + "unless its target or state has visibly changed.";
    }
}
