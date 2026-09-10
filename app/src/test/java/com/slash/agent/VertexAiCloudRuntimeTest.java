package com.slash.agent;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class VertexAiCloudRuntimeTest {
    @Test public void conversationRequestUsesGeminiNativeContents() throws Exception {
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", "Be concise"))
                .put(new JSONObject().put("role", "user").put("content", "Hello"));
        JSONObject body = VertexAiCloudRuntime.requestBody(messages, 128);
        assertEquals("Hello", body.getJSONArray("contents").getJSONObject(0)
                .getJSONArray("parts").getJSONObject(0).getString("text"));
        assertEquals(128, body.getJSONObject("generationConfig").getInt("maxOutputTokens"));
        assertFalse(body.has("toolConfig"));
    }

    @Test public void agentRequestForcesSchemaValidatedFunctionCall() throws Exception {
        String tools = "AGENT_TASK_STATE {}\n<tools>\n"
                + "{\"type\":\"function\",\"function\":{\"name\":\"FINISH_TASK\",\"parameters\":{\"type\":\"object\"}}}\n"
                + "</tools>";
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", tools))
                .put(new JSONObject().put("role", "user").put("content", "Do it"));
        JSONObject body = VertexAiCloudRuntime.requestBody(messages, 128);
        assertEquals("ANY", body.getJSONObject("toolConfig")
                .getJSONObject("functionCallingConfig").getString("mode"));
        assertEquals("FINISH_TASK", body.getJSONArray("tools").getJSONObject(0)
                .getJSONArray("functionDeclarations").getJSONObject(0).getString("name"));
        String systemText = body.getJSONObject("systemInstruction").getJSONArray("parts")
                .getJSONObject(0).getString("text");
        assertTrue(systemText.contains("AGENT_TASK_STATE"));
        assertFalse("Native function schemas must not also be pasted into the prompt",
                systemText.contains("<tools>"));
    }

    @Test public void fastAgentHasEnoughVisibleBudgetAndDisablesThinking() throws Exception {
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", "AGENT_TASK_STATE {}"))
                .put(new JSONObject().put("role", "user").put("content", "Do it"));
        JSONObject config = VertexAiCloudRuntime.requestBody(messages, 128, true)
                .getJSONObject("generationConfig");
        assertEquals(512, config.getInt("maxOutputTokens"));
        assertEquals(0, config.getJSONObject("thinkingConfig").getInt("thinkingBudget"));
    }
}
