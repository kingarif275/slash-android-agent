package com.slash.agent;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class AgentGoalManagerTest {
    @Test public void queuesMissionsAndPromotesOnlyAtBoundary() {
        AgentGoal first = new AgentGoalBuilder().build("Open Settings");
        AgentGoal second = new AgentGoalBuilder().build("Open Calculator");
        AgentGoalManager manager = new AgentGoalManager();
        manager.submit("g1", first);
        manager.submit("g2", second);
        assertEquals("g1", manager.active().id);
        assertEquals(AgentGoalManager.State.QUEUED, manager.all().get("g2").state);
        manager.finishActive(AgentGoalManager.State.COMPLETED);
        assertEquals("g2", manager.active().id);
        assertEquals(AgentGoalManager.State.ACTIVE, manager.active().state);
    }

    @Test public void goalReplacementDropsOldObjectiveGraph() {
        AgentTaskSession session = new AgentTaskSession(
                new AgentGoalBuilder().build("Open YouTube and play MrBeast"), "initial");
        assertEquals(AgentObjective.Type.OPEN_APP, session.objectiveGraph.current().type);
        session.replaceGoal(new AgentGoalBuilder().build("Open Settings and find Display"));
        assertEquals(AgentObjective.Type.OPEN_APP, session.objectiveGraph.current().type);
        assertEquals("Open Settings", session.objectiveGraph.current().description);
    }
}
