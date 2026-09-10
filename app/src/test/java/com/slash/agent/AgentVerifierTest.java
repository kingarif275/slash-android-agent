package com.slash.agent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.json.JSONArray;

public final class AgentVerifierTest {
    @Test public void remoteMediaSessionDoesNotVerifyPlayback() {
        AgentGoal goal = new AgentGoalBuilder().build(
                "Play any song by The Weeknd on Spotify.");
        AgentWorldState wrong = world(true, "com.spotify.music", "Die With A Smile",
                "Lady Gaga, Bruno Mars");
        AgentVerifier.Result result = new AgentVerifier().verify(goal, wrong, 3);

        assertFalse(result.success);
        assertTrue(result.reason.contains("Visible playback evidence"));
    }

    @Test public void visibleActiveAppPlaybackSucceedsEvenWithoutMediaSession() {
        AgentGoal goal = new AgentGoalBuilder().build(
                "Play any song by The Weeknd on Spotify.");
        AgentWorldState matching = new AgentWorldState("test-world", 1L,
                "OBSERVATION_ID=test PACKAGE=com.spotify.music\n"
                        + "e0 button text=\"Pause\" actions=click\n"
                        + "e1 text text=\"The Weeknd\" actions=-",
                true, true, false, true, false,
                new AgentWorldState.MediaState(false, false, "", "", ""));

        assertTrue(new AgentVerifier().verify(goal, matching, 3).success);
    }

    @Test public void findGoalNeverUsesStaleMediaVerification() {
        AgentGoal stale = new AgentGoal("find request", AgentGoal.Intent.PLAY_MEDIA,
                "Open Settings and find Display settings", new JSONArray(), new JSONArray(),
                "", "", "The Weeknd", "");
        AgentWorldState settings = new AgentWorldState("test-world", 1L,
                "OBSERVATION_ID=test PACKAGE=com.android.settings\n"
                        + "e0 button text=\"Display\" actions=click",
                true, true, false, true, false,
                new AgentWorldState.MediaState(false, false, "", "", ""));

        assertTrue(new AgentVerifier().verify(stale, settings, 2).success);
    }

    @Test public void calculationRequiresVisibleResult() {
        AgentGoal goal = new AgentGoalBuilder().build("Open Calculator and calculate 125 times 8");
        AgentWorldState blank = new AgentWorldState("test-world", 1L,
                "OBSERVATION_ID=test PACKAGE=com.android.calculator2\ne0 text text=\"125\"",
                true, true, false, true, false,
                new AgentWorldState.MediaState(false, false, "", "", ""));
        assertFalse(new AgentVerifier().verify(goal, blank, 2).success);
        AgentWorldState result = new AgentWorldState("test-world", 2L,
                "OBSERVATION_ID=test PACKAGE=com.android.calculator2\ne0 text text=\"Result 1000\"",
                true, true, false, true, false,
                new AgentWorldState.MediaState(false, false, "", "", ""));
        assertTrue(new AgentVerifier().verify(goal, result, 2).success);
    }

    private AgentWorldState world(boolean playing, String packageName, String title,
            String artist) {
        return new AgentWorldState("test-world", 1L,
                "OBSERVATION_ID=test PACKAGE=" + packageName + "\nELEMENTS=NONE",
                true, true, false, true, false,
                new AgentWorldState.MediaState(true, playing, packageName, title, artist));
    }
}
