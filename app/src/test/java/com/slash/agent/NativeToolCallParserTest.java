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

    @Test public void parsesNetworkStatusWithoutArguments() {
        NativeToolCallParser.Result result = NativeToolCallParser.parse(
                "<tool_call>{\"name\":\"NETWORK_STATUS\",\"arguments\":{}}</tool_call>");
        assertNotNull(result.toolCall);
        assertEquals("NETWORK_STATUS", result.toolCall.optString("tool"));
        assertEquals("", result.text);
    }

    @Test public void parsesWebSearchQueryAlias() {
        NativeToolCallParser.Result result = NativeToolCallParser.parse(
                "<tool_call>{\"name\":\"WEB_SEARCH\",\"arguments\":{\"q\":\"weather today\"}}</tool_call>");
        assertNotNull(result.toolCall);
        assertEquals("weather today",
                result.toolCall.optJSONObject("arguments").optString("query"));
    }

    @Test public void parsesStrictKnownFunctionCallSyntax() {
        NativeToolCallParser.Result result = NativeToolCallParser.parse(
                "DELEGATE_TO_AGENT(goal=\"Open Spotify\")");
        assertNotNull(result.toolCall);
        assertEquals("DELEGATE_TO_AGENT", result.toolCall.optString("tool"));
        assertEquals("Open Spotify",
                result.toolCall.optJSONObject("arguments").optString("goal"));
        assertEquals("", result.text);
    }

    @Test public void unknownFunctionLikeTextRemainsConversation() {
        NativeToolCallParser.Result result = NativeToolCallParser.parse("SUMMARIZE(file=\"notes.txt\")");
        assertNull(result.toolCall);
        assertEquals("SUMMARIZE(file=\"notes.txt\")", result.text);
    }

    @Test public void parsesExactLiteModelDelegationSentinel() {
        NativeToolCallParser.Result result = NativeToolCallParser.parse("CALL_DELEGATE_TO_AGENT");
        assertNotNull(result.toolCall);
        assertEquals("DELEGATE_TO_AGENT", result.toolCall.optString("tool"));
        assertEquals("", result.text);
    }

    @Test public void parsesObservedSpacedDelegationSentinel() {
        NativeToolCallParser.Result result = NativeToolCallParser.parse("CALL DELEGATE_TO_AGENT.");
        assertNotNull(result.toolCall);
        assertEquals("DELEGATE_TO_AGENT", result.toolCall.optString("tool"));
        assertEquals("", result.text);
    }

    @Test public void sentinelInsideConversationDoesNotTrigger() {
        NativeToolCallParser.Result result = NativeToolCallParser.parse(
                "The token CALL_DELEGATE_TO_AGENT is an internal name.");
        assertNull(result.toolCall);
    }

    @Test public void parsesKnownBareCallSentinelOnlyWhenWholeOutputMatches() {
        NativeToolCallParser.Result result = NativeToolCallParser.parse("CALL_NETWORK_STATUS");
        assertNotNull(result.toolCall);
        assertEquals("NETWORK_STATUS", result.toolCall.optString("tool"));
    }

    @Test public void doesNotParseBareCallSentinelEmbeddedInConversation() {
        NativeToolCallParser.Result result = NativeToolCallParser.parse(
                "The literal token CALL_NETWORK_STATUS is part of this explanation.");
        assertNull(result.toolCall);
    }
}
