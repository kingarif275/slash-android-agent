package com.slash.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AgentGoalBuilderTest {
    @Test public void mediaGoalKeepsFullRequestAndSubject() {
        AgentGoal goal = new AgentGoalBuilder().build(
                "Open Spotify and play any song by The Weeknd.");

        assertEquals(AgentGoal.Intent.PLAY_MEDIA, goal.intent);
        assertEquals("Open Spotify and play any song by The Weeknd.", goal.objective);
        assertEquals("The Weeknd", goal.requestedSubject);
        assertTrue(goal.successConditions.toString().contains("active media app"));
        assertEquals("spotify", goal.provider);
        assertEquals("The Weeknd", goal.artist);
    }

    @Test public void mediaSubjectExcludesTrailingProvider() {
        AgentGoal goal = new AgentGoalBuilder().build(
                "Play any song by The Weeknd on Spotify.");

        assertEquals("The Weeknd", goal.artist);
        assertEquals("spotify", goal.provider);
        assertEquals("play_music", goal.toJson().optString("intent"));
        assertEquals("playing", goal.toJson().optJSONObject("success").optString("playback"));
    }

    @Test public void openGoalHasForegroundSuccessCondition() {
        AgentGoal goal = new AgentGoalBuilder().build("Open Spotify.");

        assertEquals(AgentGoal.Intent.OPEN_DESTINATION, goal.intent);
        assertTrue(goal.successConditions.toString().contains("foreground"));
    }

    @Test public void compoundGoalBuildsOrderedObjectives() {
        AgentGoal goal = new AgentGoalBuilder().build(
                "Open YouTube, find Display, go home, then open Notes and write text.");
        AgentObjectiveGraph graph = new AgentGoalBuilder().buildGraph(goal);

        assertTrue(graph.all().size() >= 4);
        assertEquals("objective_001", graph.all().get(0).id);
        assertEquals(AgentObjective.Type.OPEN_APP, graph.all().get(0).type);
        assertEquals("objective_001", graph.all().get(1).dependsOn.get(0));
        assertEquals(AgentObjective.Type.FIND_DESTINATION, graph.all().get(1).type);
    }

    @Test public void openingAppDoesNotEraseLaterObjective() {
        AgentGoal goal = new AgentGoalBuilder().build("Open Notes and write random stuff.");
        AgentObjectiveGraph graph = new AgentGoalBuilder().buildGraph(goal);
        assertEquals(AgentObjective.Type.OPEN_APP, graph.current().type);
        graph.complete(graph.current().id, "Notes foreground", "com.example.notes");
        assertEquals(AgentObjective.Type.TYPE_TEXT, graph.current().type);
        assertFalse(graph.complete());
    }

    @Test public void crossAppInformationAndCalculationClausesAreRepresented() {
        AgentGoal goal = new AgentGoalBuilder().build(
                "Open YouTube, extract the video title, open Notes and write it, then calculate 2 + 2");
        AgentObjectiveGraph graph = new AgentGoalBuilder().buildGraph(goal);
        assertTrue(graph.all().stream().anyMatch(o -> o.type == AgentObjective.Type.EXTRACT_INFORMATION));
        assertTrue(graph.all().stream().anyMatch(o -> o.type == AgentObjective.Type.TYPE_TEXT));
        assertTrue(graph.all().stream().anyMatch(o -> o.type == AgentObjective.Type.CALCULATE));
    }
}
