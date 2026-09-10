package com.slash.agent;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public final class AgentObjectiveGraphTest {
    @Test public void activatesOnlyObjectivesWhoseDependenciesAreComplete() {
        AgentObjectiveGraph graph = new AgentObjectiveGraph();
        graph.add(new AgentObjective("o1", AgentObjective.Type.OPEN_APP, "Open Settings", Collections.emptyList()));
        graph.add(new AgentObjective("o2", AgentObjective.Type.FIND_DESTINATION, "Find Display", Collections.singletonList("o1")));
        assertEquals("o1", graph.current().id);
        graph.activateCurrent();
        graph.complete("o1", "Settings foreground", "com.android.settings");
        assertEquals("o2", graph.current().id);
    }

    @Test public void completingObjectiveInvalidatesOldGeneration() {
        AgentObjectiveGraph graph = new AgentObjectiveGraph();
        graph.add(new AgentObjective("o1", AgentObjective.Type.OPEN_APP, "Open YouTube", Collections.emptyList()));
        int before = graph.generation();
        graph.complete("o1", "YouTube foreground", "com.google.android.youtube");
        assertTrue(graph.generation() > before);
        assertTrue(graph.complete());
    }

    @Test public void duplicateObjectiveIdsAreRejected() {
        AgentObjectiveGraph graph = new AgentObjectiveGraph();
        graph.add(new AgentObjective("o1", AgentObjective.Type.OPEN_APP, "Open Notes", Collections.emptyList()));
        try {
            graph.add(new AgentObjective("o1", AgentObjective.Type.TYPE_TEXT, "Write text", Collections.singletonList("o1")));
            fail("duplicate objective id should fail");
        } catch (IllegalArgumentException expected) { }
    }
}
