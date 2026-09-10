package com.slash.agent;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ConversationCommandResolverTest {
    private final ConversationCommandResolver resolver = new ConversationCommandResolver();

    @Test public void retryReusesExactStructuredGoalAndIncrementsAtSessionLayer() {
        AgentGoal goal = new AgentGoalBuilder().build("Play any song by The Weeknd on Spotify.");
        AgentTaskSession session = new AgentTaskSession(goal, goal.userRequest);
        ConversationCommandResolver.Resolution result = resolver.resolve("try again", session);
        assertEquals(ConversationCommandResolver.Command.RETRY_PREVIOUS, result.command);
        assertSame(goal, result.goal);
        assertEquals("The Weeknd", result.goal.artist);
        assertEquals("spotify", result.goal.provider);
        assertEquals(2, result.attempt);
    }

    @Test public void retryWithoutPreviousDoesNotFabricateGoal() {
        ConversationCommandResolver.Resolution result = resolver.resolve("try again", null);
        assertEquals(ConversationCommandResolver.Command.RETRY_PREVIOUS, result.command);
        assertNull(result.goal);
    }

    @Test public void retryCanModifyProviderWithinSchema() {
        AgentTaskSession session = session();
        ConversationCommandResolver.Resolution result = resolver.resolve(
                "try again but use YouTube", session);
        assertEquals(ConversationCommandResolver.Command.MODIFY_PREVIOUS, result.command);
        assertEquals("youtube", result.goal.provider);
        assertEquals("The Weeknd", result.goal.artist);
    }

    @Test public void correctionChangesArtistAndPreservesMusicGoal() {
        ConversationCommandResolver.Resolution result = resolver.resolve(
                "No, I meant Bruno Mars", session());
        assertEquals(ConversationCommandResolver.Command.CORRECT_PREVIOUS, result.command);
        assertEquals(AgentGoal.Intent.PLAY_MEDIA, result.goal.intent);
        assertEquals("Bruno Mars", result.goal.artist);
        assertEquals("spotify", result.goal.provider);
    }

    @Test public void cancelResolvesAgainstActiveSession() {
        assertEquals(ConversationCommandResolver.Command.CANCEL_PREVIOUS,
                resolver.resolve("cancel", session()).command);
    }

    private AgentTaskSession session() {
        AgentGoal goal = new AgentGoalBuilder().build("Play any song by The Weeknd on Spotify.");
        return new AgentTaskSession(goal, goal.userRequest);
    }
}
