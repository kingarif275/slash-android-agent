package com.slash.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public final class NativeToolCallParserTest {
    @Test public void parsesOnlyStructuredToolEnvelope() {
        NativeToolCallParser.Result result = NativeToolCallParser.parse(
                "<tool_call>{\"name\":\"DELEGATE_TO_AGENT\",\"arguments\":{\"goal\":\"Open Maps\"}}</tool_call>");
        assertNotNull(result.toolCall);
        assertEquals("DELEGATE_TO_AGENT", result.toolCall.optString("tool"));
        assertEquals("Open Maps", result.toolCall.optJSONObject("arguments").optString("goal"));
        assertEquals("", result.text);
    }

    @Test public void naturalLanguageNeverTriggersTool() {
        NativeToolCallParser.Result result = NativeToolCallParser.parse(
                "I can use DELEGATE_TO_AGENT if you ask me to open an app.");
        assertNull(result.toolCall);
        assertEquals("I can use DELEGATE_TO_AGENT if you ask me to open an app.", result.text);
    }

    @Test public void normalizesKnownQwenArgumentAliases() {
        NativeToolCallParser.Result result = NativeToolCallParser.parse(
                "<tool_call>{\"name\":\"open_app\",\"arguments\":{\"application_name\":\"Maps\"}}</tool_call>");
        assertNotNull(result.toolCall);
        assertEquals("OPEN_APP", result.toolCall.optString("tool"));
        assertEquals("Maps", result.toolCall.optJSONObject("arguments").optString("app_query"));
    }

    @Test public void removesPrivateThinkingAndSpecialTokens() {
        NativeToolCallParser.Result result = NativeToolCallParser.parse(
                "<think>hidden chain of thought</think>Hello there.<|im_end|>");
        assertNull(result.toolCall);
        assertEquals("Hello there.", result.text);
        assertFalse(result.text.contains("hidden"));
    }

    @Test public void normalJsonObjectIsNotAcceptedAsUnknownTool() {
        NativeToolCallParser.Result result = NativeToolCallParser.parse(
                "{\"answer\":\"This is ordinary JSON\"}");
        assertNull(result.toolCall);
        assertEquals("{\"answer\":\"This is ordinary JSON\"}", result.text);
    }
}
