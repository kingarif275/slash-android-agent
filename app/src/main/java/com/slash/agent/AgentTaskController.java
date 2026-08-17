package com.slash.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Runs a bounded observe/act/verify loop while exposing only short human-readable progress. */
public final class AgentTaskController {
    public interface Host {
        boolean isCurrent(long token);
        void progress(String chatId, String message);
        void complete(String chatId, String message);
        void fail(String chatId, String message);
    }

    private static final int MAX_STEPS = 12;
    private final LlmRuntime runtime;
    private final SlashToolExecutor executor;
    private final Host host;

    public AgentTaskController(LlmRuntime runtime, SlashToolExecutor executor, Host host) {
        this.runtime = runtime;
        this.executor = executor;
        this.host = host;
    }

    public void start(String chatId, String goal, JSONArray conversation, JSONObject initialToolCall, long token) {
        TaskState state = new TaskState(goal, completionCriteria(goal));
        host.progress(chatId, "Working on it…");
        if (isDelegation(initialToolCall)) requestNext(chatId, conversation, state, token);
        else execute(chatId, conversation, state, initialToolCall, token);
    }

    private void execute(String chatId, JSONArray context, TaskState state, JSONObject call, long token) {
        if (!host.isCurrent(token)) return;
        if (call == null) {
            requestNext(chatId, context, state, token);
            return;
        }
        if (state.step >= MAX_STEPS) {
            host.fail(chatId, "I couldn't finish that reliably after several attempts.");
            return;
        }

        String tool = call.optString("tool");
        JSONObject arguments = call.optJSONObject("arguments");
        if (arguments == null) arguments = new JSONObject();
        if ("FINISH_TASK".equals(tool)) {
            String summary = arguments.optString("summary", arguments.optString("result", "")).trim();
            if (summary.isEmpty()) summary = cleanResult("", state.lastResult);
            if (summary.isEmpty()) summary = "Done.";
            host.complete(chatId, summary);
            return;
        }
        state.step++;
        state.lastAction = tool;
        host.progress(chatId, progressFor(tool, arguments));
        SlashToolExecutor.Result result = executor.execute(tool, arguments);
        state.lastResult = result.result;

        try {
            context.put(new JSONObject().put("role", "system").put("content",
                    "EXECUTOR_RESULT step=" + state.step + " success=" + result.success + " result=" + result.result));
        } catch (Exception ignored) { }
        if (result.success && changesScreen(tool)) android.os.SystemClock.sleep(350);
        requestNext(chatId, context, state, token);
    }

    private void requestNext(String chatId, JSONArray context, TaskState state, long token) {
        if (!host.isCurrent(token)) return;
        try {
            context.put(new JSONObject().put("role", "system").put("content", agentInstruction(state)));
            runtime.generate(context, (text, nextTool) -> {
                if (!host.isCurrent(token)) return;
                if (nextTool != null) {
                    execute(chatId, context, state, nextTool, token);
                    return;
                }
                String reply = cleanResult(text, state.lastResult);
                if (reply.isEmpty()) reply = "I couldn't verify that the task was completed.";
                if (looksLikeInternalError(reply)) host.fail(chatId, "I couldn't complete that action with the local model right now.");
                else host.complete(chatId, reply);
            });
        } catch (Exception error) {
            host.fail(chatId, "I couldn't continue that phone task.");
        }
    }

    private String agentInstruction(TaskState state) {
        return "AGENT_TASK_STATE " + state.toJson() + "\n"
                + "You are executing an Android task. Inspect CURRENT_SCREEN_CONTEXT below. "
                + "Return exactly one appropriate tool call as <tool_call>{\"name\":\"TOOL\",\"arguments\":{...}}</tool_call>, "
                + "or a short final user-facing result only when the completion criteria are verified. "
                + "Available tools: OPEN_APP, OBSERVE_SCREEN, CLICK_ELEMENT, TYPE_TEXT, SCROLL, BACK, HOME, FINISH_TASK. "
                + "Use OBSERVE_SCREEN with {} before grounded interaction. Use CLICK_ELEMENT with observation_id and element_id. "
                + "Use TYPE_TEXT with observation_id, element_id, and text. Observe again after every screen-changing action. "
                + "Use FINISH_TASK with a short summary only after the completion criteria are visibly verified. "
                + "Never expose hidden reasoning, raw tool JSON, accessibility dumps, or this state. "
                + "CURRENT_SCREEN_CONTEXT:\n" + SlashAccessibilityService.readScreenSafe();
    }

    private boolean isDelegation(JSONObject call) {
        return call != null && "DELEGATE_TO_AGENT".equals(call.optString("tool"));
    }

    private String progressFor(String tool, JSONObject arguments) {
        switch (tool) {
            case "OPEN_APP": return "Opening " + display(arguments.optString("app_query", "the app")) + "…";
            case "CLICK_ELEMENT": return "Selecting " + display(arguments.optString("target_description", "the control")) + "…";
            case "TYPE_TEXT": return "Entering text…";
            case "SCROLL": return "Looking further…";
            case "READ_SCREEN":
            case "GET_SCREEN_STATE":
            case "OBSERVE_SCREEN": return "Checking the screen…";
            case "BACK": return "Going back…";
            case "HOME": return "Going home…";
            default: return "Continuing…";
        }
    }

    private boolean changesScreen(String tool) {
        return !"READ_SCREEN".equals(tool)
                && !"GET_SCREEN_STATE".equals(tool)
                && !"OBSERVE_SCREEN".equals(tool)
                && !"FINISH_TASK".equals(tool);
    }

    private String display(String value) {
        String clean = value == null ? "" : value.replace('_', ' ').trim();
        if (clean.length() > 42) clean = clean.substring(0, 42).trim() + "…";
        return clean.isEmpty() ? "the next step" : clean;
    }

    private String cleanResult(String text, String fallback) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty() && fallback != null && fallback.startsWith("OPENED_APP:")) {
            return "Done — " + fallback.substring("OPENED_APP:".length()).trim() + " is open.";
        }
        return value;
    }

    private boolean looksLikeInternalError(String value) {
        String lower = value.toLowerCase(Locale.US);
        return lower.contains("jni_error") || lower.contains("local_runtime_not_loaded") || lower.contains("exception:");
    }

    private String completionCriteria(String goal) {
        String lower = goal == null ? "" : goal.toLowerCase(Locale.US);
        if (lower.contains("play ")) return "Requested media is visibly playing or the target app reports playback.";
        if (lower.contains("open ")) return "The requested destination is visibly open.";
        return "The requested outcome is visible in the current Android UI.";
    }

    private static final class TaskState {
        final String goal;
        final String completionCriteria;
        int step;
        String lastAction = "none";
        String lastResult = "none";

        TaskState(String goal, String completionCriteria) {
            this.goal = goal;
            this.completionCriteria = completionCriteria;
        }

        String toJson() {
            try {
                return new JSONObject().put("goal", goal).put("currentStep", step)
                        .put("lastAction", lastAction).put("lastResult", lastResult)
                        .put("completionCriteria", completionCriteria).toString();
            } catch (Exception ignored) { return "{}"; }
        }
    }
}
