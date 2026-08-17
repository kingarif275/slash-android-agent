package com.slash.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class ToolArgumentNormalizerTest {
    @Test public void clickAliasesBecomeGroundedCanonicalArguments() throws Exception {
        JSONObject normalized = ToolArgumentNormalizer.normalize("click_element",
                new JSONObject().put("observationId", "obs-4").put("elementId", "e7")
                        .put("label", "Continue"));

        assertEquals("obs-4", normalized.getString("observation_id"));
        assertEquals("e7", normalized.getString("element_id"));
        assertEquals("Continue", normalized.getString("target_description"));
        assertNull(ToolArgumentNormalizer.validate("CLICK_ELEMENT", normalized));
    }

    @Test public void ungroundedClickIsRejected() throws Exception {
        JSONObject normalized = ToolArgumentNormalizer.normalize("CLICK_ELEMENT",
                new JSONObject().put("target", "Continue"));

        assertTrue(ToolArgumentNormalizer.validate("CLICK_ELEMENT", normalized)
                .contains("element_id"));
    }

    @Test public void ungroundedTypeIsRejected() throws Exception {
        JSONObject normalized = ToolArgumentNormalizer.normalize("TYPE_TEXT",
                new JSONObject().put("text", "hello"));

        assertTrue(ToolArgumentNormalizer.validate("TYPE_TEXT", normalized)
                .contains("observation_id"));
    }
}
