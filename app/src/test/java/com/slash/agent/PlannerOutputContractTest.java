package com.slash.agent;

import static org.junit.Assert.assertEquals;
import org.json.JSONObject;
import org.junit.Test;

public final class PlannerOutputContractTest {
    @Test public void blankResponseIsProtocolError() {
        assertEquals(PlannerOutputContract.Outcome.PROTOCOL_ERROR,
                PlannerOutputContract.classify("", null));
    }

    @Test public void toolAndFinishAreExplicitOutcomes() throws Exception {
        assertEquals(PlannerOutputContract.Outcome.TOOL_CALL,
                PlannerOutputContract.classify("", new JSONObject().put("tool", "CLICK_ELEMENT")));
        assertEquals(PlannerOutputContract.Outcome.FINISH_TASK,
                PlannerOutputContract.classify("", new JSONObject().put("tool", "FINISH_TASK")));
    }
}
