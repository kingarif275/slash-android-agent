package com.slash.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import android.util.Log;
import android.content.Context;

import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.Set;

/** Runs a bounded observe/act/verify loop while exposing only short human-readable progress. */
public final class AgentTaskController {
    private static final String TAG = "SlashAgent";
    public interface Host {
        boolean isCurrent(long token);
        void progress(String chatId, String message);
        void complete(String chatId, String message);
        void fail(String chatId, String message);
        void diagnostic(String event);
    }

    private static final int MAX_STEPS = 12;
    private final LlmRuntime runtime;
    private final SlashToolExecutor executor;
    private final Host host;
    private final Executor scheduler;
    private final Context appContext;
    private final AgentGoalBuilder goalBuilder = new AgentGoalBuilder();
    private final AgentVerifier verifier = new AgentVerifier();
    private final AgentRecoveryPolicy recoveryPolicy = new AgentRecoveryPolicy();
    private final ConversationCommandResolver commandResolver = new ConversationCommandResolver();
    private final AgentStepReporter reporter;
    private final Map<String, AgentTaskSession> sessions = new ConcurrentHashMap<>();

    public AgentTaskController(Context context, LlmRuntime runtime, SlashToolExecutor executor, Host host,
            Executor scheduler) {
        this.appContext = context.getApplicationContext();
        this.runtime = runtime;
        this.executor = executor;
        this.host = host;
        this.scheduler = scheduler;
        this.reporter = new AgentStepReporter(this.appContext);
    }

    public void start(String chatId, String goal, JSONArray conversation, JSONObject initialToolCall, long token) {
        AgentGoal structured = goalBuilder.build(goal);
        AgentTaskSession session = new AgentTaskSession(structured, goal);
        sessions.put(chatId, session);
        SafeAgentLog.event("GOAL_CREATED", goalSummary(session));
        SafeAgentLog.event("CONVERSATION_AI", "Understood structured goal. " + goalSummary(session));
        startSession(chatId, session, conversation, initialToolCall, token);
    }

    public boolean handleConversationCommand(String chatId, String instruction, JSONArray conversation,
            long token) {
        ConversationCommandResolver.Resolution resolution = commandResolver.resolve(instruction,
                sessions.get(chatId));
        if (resolution.command == ConversationCommandResolver.Command.NEW_TASK) return false;
        SafeAgentLog.event("CONVERSATION_USER", quote(instruction));
        AgentTaskSession session = sessions.get(chatId);
        if (session == null || resolution.goal == null) {
            SafeAgentLog.event("TASK_FAILURE", "reason=no_previous_retryable_goal command=" + resolution.command);
            host.complete(chatId, resolution.command == ConversationCommandResolver.Command.CANCEL_PREVIOUS
                    ? "There isn't an active phone task to cancel."
                    : "I don't have a previous phone task to continue or retry yet.");
            return true;
        }
        session.latestInstruction = instruction;
        if (resolution.command == ConversationCommandResolver.Command.CANCEL_PREVIOUS) {
            session.status = AgentTaskSession.Status.CANCELLED;
            SafeAgentLog.event("CONVERSATION_AI", "Cancelled previous goal. " + goalSummary(session));
            host.complete(chatId, "Cancelled.");
            return true;
        }
        if (resolution.goal != session.activeGoal) session.replaceGoal(resolution.goal);
        session.attempt = resolution.attempt;
        session.status = AgentTaskSession.Status.ACTIVE;
        String event = resolution.command == ConversationCommandResolver.Command.RETRY_PREVIOUS
                ? "GOAL_RETRY" : "GOAL_MODIFIED";
        SafeAgentLog.event(event, goalSummary(session));
        SafeAgentLog.event("CONVERSATION_AI", resolution.explanation + ". " + goalSummary(session));
        startSession(chatId, session, conversation, null, token);
        return true;
    }

    AgentTaskSession session(String chatId) { return sessions.get(chatId); }

    private void startSession(String chatId, AgentTaskSession session, JSONArray conversation,
            JSONObject initialToolCall, long token) {
        SlashRuntimeService.beginAgentMode(appContext);
        TaskState state = new TaskState(session.activeGoal, session);
        String goal = session.activeGoal.objective;
        Log.i(TAG, "AGENT_GOAL " + goal);
        host.diagnostic("AGENT_GOAL " + bounded(goal, 240));
        host.progress(chatId, "Working on it…");
        JSONArray agentContext = new JSONArray();
        try {
            // Keep the dynamic agent contract in the actual first system-message slot. Small
            // local chat models follow tool instructions much less reliably when system comes
            // after the user turn.
            agentContext.put(new JSONObject().put("role", "system").put("content", ""));
            agentContext.put(new JSONObject().put("role", "user")
                    .put("content", "Complete this Android task: " + state.goal.objective));
            state.instructionIndex = 0;
        } catch (Exception ignored) { }
        String obviousApp = initialAppQuery(state.goal);
        // For compound requests, the goal builder owns the first subgoal. Do not let a
        // conversational model's opportunistic initial tool reorder the user's clauses.
        if (!obviousApp.isEmpty()) {
            try {
                execute(chatId, agentContext, state, new JSONObject().put("tool", "OPEN_APP")
                        .put("arguments", new JSONObject().put("app_query", obviousApp)), token);
            } catch (Exception ignored) { requestNext(chatId, agentContext, state, token); }
        } else if (isDelegation(initialToolCall)) requestNext(chatId, agentContext, state, token);
        else execute(chatId, agentContext, state, initialToolCall, token);
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
        // A planner response can arrive just after a fresh observation already
        // satisfied the active objective. Never execute a stale scroll/back/click
        // that would move away from the verified destination.
        if (!"FINISH_TASK".equals(tool) && objectiveAlreadySatisfied(state)) {
            host.diagnostic("PLANNER_BOUNDARY pre_execution_objective_already_satisfied");
            state.lastResult = "OBJECTIVE_ALREADY_SATISFIED: planner action skipped";
            completeCurrentObjectiveFromObservation(state);
            requestNext(chatId, context, state, token);
            return;
        }
        if ("FINISH_TASK".equals(tool)) {
            state.world = AgentWorldState.capture(appContext);
            AgentVerifier.Result verification = verifier.verify(state.goal, state.world,
                    state.screenChangingActions);
            if (!verification.success) {
                state.step++;
                state.recoveryAttempts++;
                state.lastAction = tool;
                state.lastResult = "FINISH_REJECTED: " + verification.reason;
                state.recoveryGuidance = recoveryPolicy.guidance(state.goal, state.world,
                        state.lastAction, state.lastResult, state.recoveryAttempts);
                host.diagnostic("VERIFICATION step=" + state.step + " success=false result="
                        + state.lastResult);
                updateSession(state, AgentTaskSession.Status.FAILED);
                SafeAgentLog.event("VERIFICATION", verificationDetails(state, verification));
                SafeAgentLog.event("RECOVERY", "reason=" + quote(verification.reason)
                        + " strategy=" + quote(state.recoveryGuidance));
                requestNext(chatId, context, state, token);
                return;
            }
            completeCurrentObjectiveFromVerification(state, verification);
            if (!state.session.objectiveGraph.complete()) {
                state.step++;
                state.lastAction = tool;
                state.lastResult = "FINISH_REJECTED: required objectives remain";
                state.recoveryAttempts++;
                state.recoveryGuidance = recoveryPolicy.guidance(state.goal, state.world,
                        state.lastAction, state.lastResult, state.recoveryAttempts);
                SafeAgentLog.event("VERIFICATION", "status=FAILED reason=required_objectives_remain current="
                        + currentObjectiveSummary(state));
                requestNext(chatId, context, state, token);
                return;
            }
            String summary = arguments.optString("summary", arguments.optString("result", "")).trim();
            if (summary.isEmpty()) summary = cleanResult("", state.lastResult);
            if (summary.isEmpty()) summary = "Done.";
            Log.i(TAG, "FINISH_TASK summary=" + summary);
            host.diagnostic("FINISH_TASK summary=" + bounded(summary, 240));
            updateSession(state, AgentTaskSession.Status.SUCCEEDED);
            SafeAgentLog.event("VERIFICATION", verificationDetails(state, verification));
            SafeAgentLog.event("TASK_SUCCESS", goalSummary(state.session));
            host.complete(chatId, summary);
            return;
        }
        // Once the media query has been entered, retyping or clicking the
        // search field is never a useful next action. Some planner responses
        // briefly repeat that control while the result list is still settling.
        // Hold the request locally, re-observe once, and select a grounded
        // result without spending another remote planning round-trip.
        if (state.goal != null && state.goal.intent == AgentGoal.Intent.PLAY_MEDIA
                && state.mediaQueryTyped
                && ("TYPE_TEXT".equals(tool)
                    || ("CLICK_ELEMENT".equals(tool)
                        && (arguments.optString("label").toLowerCase(Locale.US).contains("search")
                            || arguments.optString("element_id").toLowerCase(Locale.US).contains("search"))))) {
            state.world = AgentWorldState.capture(appContext);
            JSONObject mediaAction = semanticMediaFallback(state);
            if (mediaAction != null && !"TYPE_TEXT".equals(mediaAction.optString("tool"))) {
                scheduler.execute(() -> execute(chatId, context, state, mediaAction, token));
            } else {
                scheduler.execute(() -> {
                    SlashAccessibilityService.awaitStableUi(SlashAccessibilityService.eventToken(), 1_200);
                    if (!host.isCurrent(token)) return;
                    state.world = AgentWorldState.capture(appContext);
                    JSONObject retry = semanticMediaFallback(state);
                    if (retry != null && !"TYPE_TEXT".equals(retry.optString("tool"))) {
                        execute(chatId, context, state, retry, token);
                    } else {
                        requestNext(chatId, context, state, token);
                    }
                });
            }
            return;
        }
        String actionSignature = tool + ":" + arguments.toString();
        // Reading the same screen more than once is not an OS mutation and must not trip the
        // duplicate-action kill switch. Keep the signature across observations so repeating the
        // same actual click/type/launch is still detected.
        if (changesScreen(tool)) {
            if (actionSignature.equals(state.lastActionSignature)) state.identicalActionCount++;
            else state.identicalActionCount = 1;
            state.lastActionSignature = actionSignature;
        }
        int duplicateLimit = "CLICK_ELEMENT".equals(tool) ? 2 : 3;
        if (changesScreen(tool) && state.identicalActionCount >= duplicateLimit) {
            // A media app can expose a stale/hidden Search control while restoring
            // an existing player. Recover to the app root once and re-observe before
            // declaring a planner loop; this is intentionally package-independent.
            if ("CLICK_ELEMENT".equals(tool)
                    && state.goal != null
                    && state.goal.intent == AgentGoal.Intent.PLAY_MEDIA
                    && !state.mediaBackAttempted) {
                try {
                    final JSONObject recoveryAction = new JSONObject().put("tool", "BACK")
                            .put("arguments", new JSONObject());
                    state.mediaBackAttempted = true;
                    state.identicalActionCount = 0;
                    state.lastActionSignature = "";
                    SafeAgentLog.event("RECOVERY", "reason=media_stalled_control strategy=back_and_reobserve");
                    scheduler.execute(() -> execute(chatId, context, state, recoveryAction, token));
                    return;
                } catch (Exception ignored) { }
            }
            updateSession(state, AgentTaskSession.Status.FAILED);
            SafeAgentLog.event("LOOP_DETECTED", "action=" + quote(actionSignature)
                    + " repeats=" + state.identicalActionCount + " strategy=stop_and_request_new_plan");
            host.fail(chatId, "I stopped because the planner repeatedly requested the same action without progress.");
            return;
        }
        String previousAction = state.lastAction;
        state.step++;
        state.lastAction = tool;
        reporter.report(state.session.runId, state.step, "STEP_DISPATCHED",
                reportPayload(tool, arguments, null, false));
        SafeAgentLog.event("TOOL_REQUEST", "step=" + state.step + " tool=" + tool
                + " world_state=" + state.worldId() + " reason=" + quote(decisionReason(tool, arguments, state)));
        host.progress(chatId, progressNarrative(tool, arguments) + "\n\n[DETAILS]\n"
                + "Goal: " + state.goal.objective + "\n"
                + "Step: " + state.step + "\n"
                + "Tool: " + tool + "\n"
                + "Reason: " + decisionReason(tool, arguments, state));
        String appQuery = arguments.optString("app_query").trim();
        boolean duplicateOpen = "OPEN_APP".equals(tool) && !appQuery.isEmpty()
                && !("BACK".equals(previousAction) || "HOME".equals(previousAction))
                && (state.openedApps.contains(appQuery.toLowerCase(Locale.US))
                || state.failedApps.contains(appQuery.toLowerCase(Locale.US)));
        boolean duplicateHome = "HOME".equals(tool) && "HOME".equals(previousAction);
        long uiEventToken = SlashAccessibilityService.eventToken();
        boolean duplicateFailedOpen = duplicateOpen
                && state.failedApps.contains(appQuery.toLowerCase(Locale.US));
        boolean appAlreadyForeground = duplicateOpen && !duplicateFailedOpen
                && foregroundMatchesQuery(state.world, appQuery);
        SlashToolExecutor.Result result = appAlreadyForeground
                ? new SlashToolExecutor.Result(true, "APP_ALREADY_FOREGROUND: Continue with the remaining goal.")
                : duplicateOpen || duplicateHome
                ? new SlashToolExecutor.Result(false,
                    duplicateHome ? "HOME_ALREADY_ACTIVE: Continue with the remaining goal."
                            : duplicateFailedOpen ? "APP_ALREADY_FAILED: Continue with the next ordered clause."
                            : "APP_ALREADY_OPEN: Use CURRENT_SCREEN_CONTEXT elements for the remaining goal.")
                : executor.execute(tool, arguments);
        if ("OPEN_APP".equals(tool) && result.success) {
            state.lastOpenQuery = appQuery;
            state.openAppCount++;
            state.openedApps.add(appQuery.toLowerCase(Locale.US));
        } else if ("OPEN_APP".equals(tool) && !result.success && !appQuery.isEmpty()) {
            state.failedApps.add(appQuery.toLowerCase(Locale.US));
        }
        if ("OPEN_APP".equals(tool) && (!duplicateOpen || appAlreadyForeground)) state.appClauseCursor++;
        // A repeated invalid-app clause is already terminally resolved; advance the
        // ordered cursor so it cannot hold the planner on the same failed target.
        if ("OPEN_APP".equals(tool) && duplicateOpen
                && state.failedApps.contains(appQuery.toLowerCase(Locale.US))) {
            state.appClauseCursor++;
        }
        // A planner click can be stale after Settings restores a nested page or
        // after a transition. Re-observe once and use the semantic target rather
        // than spending retries on an opaque element id.
        if (!result.success && "CLICK_ELEMENT".equals(tool)
                && state.goal != null
                && state.goal.intent == AgentGoal.Intent.PLAY_MEDIA) {
            state.world = AgentWorldState.capture(appContext);
            JSONObject recoveredMediaClick = semanticMediaFallback(state);
            if (recoveredMediaClick != null) {
                host.diagnostic("PLANNER_FALLBACK reason=stale_media_click_reobserve");
                SafeAgentLog.event("PLANNER_FALLBACK", "reason=stale_media_click_reobserve");
                state.lastResult = "Semantic recovery from stale media control";
                state.step--;
                scheduler.execute(() -> execute(chatId, context, state, recoveredMediaClick, token));
                return;
            }
        }
        if (!result.success && "CLICK_ELEMENT".equals(tool)
                && state.goal.objective != null
                && (state.goal.objective.toLowerCase(Locale.US).contains(" find ")
                || state.goal.objective.toLowerCase(Locale.US).contains(" locate "))
                && state.semanticRecoveryAttempts < 1) {
            state.world = AgentWorldState.capture(appContext);
            // Element ordinals are observation-local. A fresh window may reuse
            // e5 for a different row, so do not suppress a newly grounded
            // semantic candidate merely because its old ordinal matched.
            state.lastSemanticRecoveryElement = "";
            JSONObject recovered = semanticNavigationFallback(state);
            if (recovered != null) {
                state.semanticRecoveryAttempts++;
                host.diagnostic("PLANNER_FALLBACK reason=stale_click_reobserve");
                SafeAgentLog.event("PLANNER_FALLBACK", "reason=stale_click_reobserve");
                state.lastResult = "Semantic recovery from stale control";
                state.step--;
                scheduler.execute(() -> execute(chatId, context, state, recovered, token));
                return;
            }
            if (state.semanticRecoveryAttempts >= 1) {
                // A second stale click usually means an OEM interstitial replaced
                // the semantic surface. Back out once and let the fresh observer
                // choose the next grounded subgoal instead of retrying the same id.
                try {
                    execute(chatId, context, state, new JSONObject().put("tool", "BACK")
                            .put("arguments", new JSONObject()), token);
                    return;
                } catch (Exception ignored) { }
            }
        }
        // Search and Compose surfaces can replace the accessibility window after
        // the focus tap. Re-ground a stale TYPE_TEXT request against the fresh
        // editable control instead of asking the planner to repeat an old node.
        if (!result.success && "TYPE_TEXT".equals(tool)
                && state.goal != null
                && state.goal.intent == AgentGoal.Intent.PLAY_MEDIA) {
            // Search fields are frequently replaced when the keyboard/result
            // window settles. A planner TYPE_TEXT call can therefore carry an
            // observation-local id that is already stale. Re-ground media
            // input from the newest accessibility tree instead of spending a
            // model round-trip (or backing out of the app).
            state.mediaQueryTyped = false;
            state.world = AgentWorldState.capture(appContext);
            JSONObject recoveredMediaInput = semanticMediaFallback(state);
            if (recoveredMediaInput != null) {
                host.diagnostic("PLANNER_FALLBACK reason=stale_media_type_reobserve");
                SafeAgentLog.event("PLANNER_FALLBACK", "reason=stale_media_type_reobserve");
                state.lastResult = "Semantic recovery from stale media input";
                state.step--;
                scheduler.execute(() -> execute(chatId, context, state, recoveredMediaInput, token));
                return;
            }
        }
        if (!result.success && "TYPE_TEXT".equals(tool)
                && state.goal.objective != null
                && (state.goal.objective.toLowerCase(Locale.US).contains(" find ")
                || state.goal.objective.toLowerCase(Locale.US).contains(" locate "))
                && state.semanticRecoveryAttempts < 2) {
            state.world = AgentWorldState.capture(appContext);
            JSONObject recoveredInput = semanticInputFallback(state, arguments.optString("text"));
            if (recoveredInput != null) {
                state.semanticRecoveryAttempts++;
                host.diagnostic("PLANNER_FALLBACK reason=stale_type_reobserve");
                SafeAgentLog.event("PLANNER_FALLBACK", "reason=stale_type_reobserve");
                state.lastResult = "Semantic recovery from stale input";
                state.step--;
                scheduler.execute(() -> execute(chatId, context, state, recoveredInput, token));
                return;
            }
        }
        Log.i(TAG, "TOOL_EXECUTION step=" + state.step + " tool=" + tool
                + " success=" + result.success + " result=" + result.result);
        host.diagnostic("TOOL_EXECUTION step=" + state.step + " tool=" + tool
                + " success=" + result.success + " result=" + bounded(result.result, 240));
        state.lastResult = compactResult(tool, result);
        state.session.lastAction = tool + ": " + state.lastResult;
        // OPEN_APP is a real objective boundary for every compound mission, not
        // only for find/locate requests. Freshly verify the foreground package so
        // media, typing, calculation, and cross-app objectives can become active.
        if (result.success && "OPEN_APP".equals(tool)) {
            state.world = AgentWorldState.capture(appContext);
            commitObservedOpenObjective(state, state.world.foregroundPackage());
            host.diagnostic("OBJECTIVE_BOUNDARY open_app_verified current="
                    + currentObjectiveSummary(state));
        }
        reporter.report(state.session.runId, state.step, "STEP_RESULT",
                reportPayload(tool, arguments, result, true));
        SafeAgentLog.event("TOOL_RESULT", "step=" + state.step + " tool=" + tool
                + " success=" + result.success + " result=" + quote(state.lastResult));
        if (result.success && changesScreen(tool)) state.screenChangingActions++;
        if (result.success) completeCurrentObjectiveFromAction(state, tool, state.lastResult);
        if (result.success && "SCROLL".equals(tool) && state.goal.objective != null
                && (state.goal.objective.toLowerCase(Locale.US).contains(" find ")
                || state.goal.objective.toLowerCase(Locale.US).contains(" locate "))) {
            state.world = AgentWorldState.capture(appContext);
            JSONObject semanticNext = semanticNavigationFallback(state);
            if (semanticNext != null && !"SCROLL".equals(semanticNext.optString("tool"))) {
                host.diagnostic("PLANNER_FALLBACK reason=semantic_after_scroll");
                SafeAgentLog.event("PLANNER_FALLBACK", "reason=semantic_after_scroll");
                scheduler.execute(() -> execute(chatId, context, state, semanticNext, token));
                return;
            }
        }
        if (result.success && "TYPE_TEXT".equals(tool)) {
            final String typedText = arguments.optString("text");
            if (state.goal != null && state.goal.intent == AgentGoal.Intent.PLAY_MEDIA) {
                state.mediaQueryTyped = true;
                // Search UIs commonly keep the IME suggestion list open after
                // SET_TEXT. Submit the focused field once so the next fresh
                // observation contains real result rows rather than repeatedly
                // clicking the same suggestion surface.
                SlashAccessibilityService.submitImeActionSafe();
            }
            // Result lists are often published by a new window/content event
            // shortly after SET_TEXT. Give that event a bounded opportunity to
            // settle, then ground the result from the fresh semantic tree.
            scheduler.execute(() -> {
                // Wait for the accessibility event that publishes the new result
                // list instead of sleeping a fixed amount after every input.
                SlashAccessibilityService.awaitStableUi(uiEventToken, 600);
                if (!host.isCurrent(token)) return;
                state.world = AgentWorldState.capture(appContext);
                verifyTypedTextFromObservation(state, typedText);
                JSONObject semanticResult = semanticNavigationFallback(state);
                if (semanticResult == null) semanticResult = semanticMediaFallback(state);
                if (semanticResult != null && "CLICK_ELEMENT".equals(semanticResult.optString("tool"))) {
                    host.diagnostic("PLANNER_FALLBACK reason=semantic_after_type");
                    SafeAgentLog.event("PLANNER_FALLBACK", "reason=semantic_after_type");
                    execute(chatId, context, state, semanticResult, token);
                } else {
                    requestNext(chatId, context, state, token);
                }
            });
            return;
        }
        if (result.success && "OPEN_APP".equals(tool)
                && state.goal.objective != null
                && (state.goal.objective.toLowerCase(Locale.US).contains(" find ")
                || state.goal.objective.toLowerCase(Locale.US).contains(" locate "))) {
            state.world = AgentWorldState.capture(appContext);
            String launchedForeground = state.world.foregroundPackage();
            commitObservedOpenObjective(state, launchedForeground);
            JSONObject semanticFallback = semanticNavigationFallback(state);
            if (!launchedForeground.isEmpty() && !launchedForeground.startsWith("com.slash.agent")
                    && semanticFallback != null) {
                SafeAgentLog.event("PLANNER_FALLBACK", "reason=semantic_visible_target_after_open");
                host.diagnostic("PLANNER_FALLBACK reason=semantic_visible_target_after_open");
                scheduler.execute(() -> execute(chatId, context, state, semanticFallback, token));
                return;
            }
            if (launchedForeground.startsWith("com.slash.agent")) {
                scheduler.execute(() -> {
                    // Settings and other complex apps can publish the new window
                    // before their clickable containers and child labels settle.
                    // Re-observe after a bounded stabilization window instead of
                    // grounding against the previous activity's element IDs.
                    // OPEN_APP already waits for awaitStableUi(); this is only a
                    // bounded fallback for apps that emit no quiet event.
                    android.os.SystemClock.sleep(600);
                    if (!host.isCurrent(token)) return;
                    state.world = AgentWorldState.capture(appContext);
                    JSONObject delayedFallback = semanticNavigationFallback(state);
                    String delayedForeground = state.world.foregroundPackage();
                    if (!delayedForeground.isEmpty()
                            && !delayedForeground.startsWith("com.slash.agent")
                            && delayedFallback != null) {
                        SafeAgentLog.event("PLANNER_FALLBACK", "reason=semantic_delayed_reobserve");
                        host.diagnostic("PLANNER_FALLBACK reason=semantic_delayed_reobserve");
                        execute(chatId, context, state, delayedFallback, token);
                    } else if (!delayedForeground.isEmpty()
                            && !delayedForeground.startsWith("com.slash.agent")
                            && state.lastAction.equals("OPEN_APP")) {
                        // The launched app may have restored a nested screen from
                        // its previous session. Return one level and re-observe
                        // before asking the planner to search for the target.
                        try {
                            execute(chatId, context, state, new JSONObject().put("tool", "BACK")
                                    .put("arguments", new JSONObject()), token);
                        } catch (Exception ignored) { requestNext(chatId, context, state, token); }
                    } else {
                        requestNext(chatId, context, state, token);
                    }
                });
                return;
            }
        }
        // Media missions have a generic deterministic first route: once the
        // provider is foreground, ground Search/input from the fresh tree
        // before asking the remote planner to rediscover those controls.
        if (result.success && "OPEN_APP".equals(tool)
                && state.goal != null && state.goal.intent == AgentGoal.Intent.PLAY_MEDIA) {
            state.world = AgentWorldState.capture(appContext);
            JSONObject mediaNext = semanticMediaFallback(state);
            if (mediaNext != null) {
                SafeAgentLog.event("PLANNER_FALLBACK", "reason=media_after_open");
                host.diagnostic("PLANNER_FALLBACK reason=media_after_open");
                scheduler.execute(() -> execute(chatId, context, state, mediaNext, token));
                return;
            }
        }
        // Final ordered app launches must not depend on an accessibility "quiet"
        // event. Some third-party apps never emit one after launch; the executor
        // result plus the current foreground package are already sufficient
        // independent evidence to close the compound goal.
        if (result.success && "OPEN_APP".equals(tool)
                && state.appClauseCursor >= appClauseCount(state.goal)
                && (actionClausesSatisfied(state) || state.semanticSubgoalVerified)
                && state.session.objectiveGraph.complete()) {
            AgentWorldState terminalWorld = AgentWorldState.capture(appContext);
            String foreground = terminalWorld.foregroundPackage();
            if (!foreground.isEmpty() && !foreground.startsWith("com.slash.agent")) {
                state.world = terminalWorld;
                updateSession(state, AgentTaskSession.Status.SUCCEEDED);
                SafeAgentLog.event("VERIFICATION", verificationDetails(state,
                        new AgentVerifier.Result(true, "Terminal foreground app verified")));
                SafeAgentLog.event("TASK_SUCCESS", goalSummary(state.session));
                host.diagnostic("FINISH_TASK summary=Terminal app clause verified");
                host.complete(chatId, cleanResult("", state.lastResult));
                return;
            }
        }
        if (result.success && "BACK".equals(tool)) {
            state.backCount++;
            state.lastBackAfterOpen = state.lastOpenQuery;
        }
        if (!result.success) {
            state.recoveryAttempts++;
            state.recoveryGuidance = recoveryPolicy.guidance(state.goal,
                    AgentWorldState.capture(appContext), tool, state.lastResult,
                    state.recoveryAttempts);
            if ("SCROLL".equals(tool)
                    && result.result != null
                    && result.result.toLowerCase(Locale.US).contains("requires direction")) {
                // Invalid planner arguments are not evidence of progress. Do not
                // feed the same malformed scroll back into the loop; unwind once
                // and let the fresh observer select a grounded action.
                try {
                    execute(chatId, context, state, new JSONObject().put("tool", "BACK")
                            .put("arguments", new JSONObject()), token);
                    return;
                } catch (Exception ignored) { }
            }
            // Do not let malformed planner arguments (for example SCROLL without
            // a direction) consume the bounded loop. For semantic navigation,
            // unwind one OEM interstitial on the first grounded tool failure and
            // re-observe before asking for another action.
            if (state.recoveryAttempts == 1 && state.goal != null
                    && state.goal.objective != null
                    && !state.semanticSubgoalVerified
                    && (state.goal.objective.toLowerCase(Locale.US).contains(" find ")
                    || state.goal.objective.toLowerCase(Locale.US).contains(" locate "))) {
                try {
                    execute(chatId, context, state, new JSONObject().put("tool", "BACK")
                            .put("arguments", new JSONObject()), token);
                    return;
                } catch (Exception ignored) { }
            }
        } else state.recoveryGuidance = "";
        if (result.success && changesScreen(tool))
            SlashAccessibilityService.awaitStableUi(uiEventToken,
                    "TYPE_TEXT".equals(tool) ? 3_500 : ("OPEN_APP".equals(tool) ? 6_000 : 3_000));
        Log.i(TAG, "VERIFICATION step=" + state.step + " requires_observation=" + changesScreen(tool));
        // If the local planner emits an empty/invalid continuation after an app launch,
        // preserve the parsed compound goal by advancing to the next explicit app clause.
        // This is a bounded recovery path; it never replaces grounded click/type planning.
        if (result.success && "OPEN_APP".equals(tool)) {
            if (backRequiredBeforeNextApp(state)) {
                try {
                    execute(chatId, context, state, new JSONObject().put("tool", "BACK")
                            .put("arguments", new JSONObject()), token);
                    return;
                } catch (Exception ignored) { }
            }
            if (homeRequiredBeforeNextApp(state)) {
                try {
                    execute(chatId, context, state, new JSONObject().put("tool", "HOME")
                            .put("arguments", new JSONObject()), token);
                    return;
                } catch (Exception ignored) { }
            }
            String nextApp = nextUnopenedApp(state);
            int appClauses = appClauseCount(state.goal);
            String terminalCheck = "next_app=" + quote(nextApp)
                    + " clauses_satisfied=" + actionClausesSatisfied(state)
                    + " screen_actions=" + state.screenChangingActions
                    + " app_cursor=" + state.appClauseCursor
                    + " app_clauses=" + appClauses;
            SafeAgentLog.event("TERMINAL_CHECK", terminalCheck);
            host.diagnostic("TERMINAL_CHECK " + terminalCheck);
            if (!nextApp.isEmpty()) {
                try {
                    execute(chatId, context, state, new JSONObject().put("tool", "OPEN_APP")
                            .put("arguments", new JSONObject().put("app_query", nextApp)), token);
                    return;
                } catch (Exception ignored) { }
            }
            // The parsed goal has no remaining app clause. If this launch is the
            // terminal clause, complete from the successful foreground transition;
            // do not ask a weak local planner to rediscover FINISH_TASK.
            if ((nextApp.isEmpty() || state.appClauseCursor >= appClauses)
                    && (actionClausesSatisfied(state) || state.semanticSubgoalVerified)
                    && state.session.objectiveGraph.complete()) {
                // A compound goal is complete when its ordered app clauses and
                // navigation clauses are exhausted and the final OPEN_APP
                // produced an authoritative foreground transition.  Do not
                // require the brittle textual terminalOpenClause heuristic;
                // natural-language tails such as "then open YouTube" and
                // punctuation can otherwise strand a successfully completed
                // task in another planner round.
                state.world = AgentWorldState.capture(appContext);
                String foreground = state.world.foregroundPackage();
                boolean foregroundVerified = !foreground.isEmpty()
                        && !foreground.startsWith("com.slash.agent");
                if (foregroundVerified) {
                    updateSession(state, AgentTaskSession.Status.SUCCEEDED);
                    SafeAgentLog.event("VERIFICATION", verificationDetails(state,
                            new AgentVerifier.Result(true, "Terminal foreground app verified")));
                    SafeAgentLog.event("TASK_SUCCESS", goalSummary(state.session));
                    host.diagnostic("FINISH_TASK summary=Terminal app clause verified");
                    host.complete(chatId, cleanResult("", state.lastResult));
                    return;
                }
            }
        }
        // AGI-style handoff for compound media commands: opening the provider is
        // only a transport step. Give Android one short post-launch settling
        // window, then immediately observe and hand the PLAY_CONTENT objective to
        // the grounded semantic path. Do not spend a remote planner round asking
        // whether to reopen the provider.
        if (result.success && "OPEN_APP".equals(tool)
                && state.goal != null
                && state.goal.intent == AgentGoal.Intent.PLAY_MEDIA) {
            scheduler.execute(() -> {
                SlashAccessibilityService.awaitStableUi(uiEventToken, 600);
                if (!host.isCurrent(token)) return;
                state.world = AgentWorldState.capture(appContext);
                commitObservedOpenObjective(state, state.world.foregroundPackage());
                JSONObject mediaAction = semanticMediaFallback(state);
                if (mediaAction != null) {
                    host.diagnostic("PLANNER_FALLBACK reason=media_post_open_handoff");
                    SafeAgentLog.event("PLANNER_FALLBACK", "reason=media_post_open_handoff tool="
                            + mediaAction.optString("tool"));
                    execute(chatId, context, state, mediaAction, token);
                } else {
                    requestNext(chatId, context, state, token);
                }
            });
            return;
        }
        if (result.success && "HOME".equals(tool)) {
            String nextAfterHome = nextUnopenedApp(state);
            if (!nextAfterHome.isEmpty()) {
                try {
                    execute(chatId, context, state, new JSONObject().put("tool", "OPEN_APP")
                            .put("arguments", new JSONObject().put("app_query", nextAfterHome)), token);
                    return;
                } catch (Exception ignored) { }
            }
        }
        if ("OPEN_APP".equals(tool) && duplicateOpen
                && state.failedApps.contains(appQuery.toLowerCase(Locale.US))) {
            String nextFailedRecoveryApp = nextUnopenedApp(state);
            if (!nextFailedRecoveryApp.isEmpty()) {
                try {
                    execute(chatId, context, state, new JSONObject().put("tool", "OPEN_APP")
                            .put("arguments", new JSONObject().put("app_query", nextFailedRecoveryApp)), token);
                    return;
                } catch (Exception ignored) { }
            }
        }
        if (result.success && "OPEN_APP".equals(tool)
                && state.semanticSubgoalVerified
                && state.session.objectiveGraph.complete()) {
            state.world = AgentWorldState.capture(appContext);
            String terminalForeground = state.world.foregroundPackage();
            if (!terminalForeground.isEmpty() && !terminalForeground.startsWith("com.slash.agent")) {
                updateSession(state, AgentTaskSession.Status.SUCCEEDED);
                SafeAgentLog.event("VERIFICATION", verificationDetails(state,
                        new AgentVerifier.Result(true, "Terminal foreground app verified after semantic subgoal")));
                SafeAgentLog.event("TASK_SUCCESS", goalSummary(state.session));
                host.diagnostic("FINISH_TASK summary=Terminal foreground after semantic subgoal");
                host.complete(chatId, cleanResult("", state.lastResult));
                return;
            }
        }
        if (result.success && "OPEN_APP".equals(tool)
                && completeVerifiedOpen(chatId, state)) return;
        if (result.success && "HOME".equals(tool) && finalHomeRequested(state.goal)) {
            state.world = AgentWorldState.capture(appContext);
            AgentVerifier.Result verification = verifier.verify(state.goal, state.world,
                    state.screenChangingActions);
            if (verification.success && state.session.objectiveGraph.complete()) {
                updateSession(state, AgentTaskSession.Status.SUCCEEDED);
                SafeAgentLog.event("VERIFICATION", verificationDetails(state, verification));
                SafeAgentLog.event("TASK_SUCCESS", goalSummary(state.session));
                host.complete(chatId, "Returned to the home screen.");
                return;
            }
        }
        if (result.success && "OPEN_APP".equals(tool)
                && (finalAppRequested(state.goal) || actionClausesSatisfied(state))) {
            state.world = AgentWorldState.capture(appContext);
            AgentVerifier.Result verification = verifier.verify(state.goal, state.world,
                    state.screenChangingActions);
            boolean destinationForeground = !state.world.foregroundPackage().isEmpty()
                    && !state.world.foregroundPackage().startsWith("com.slash.agent");
            if ((verification.success || (finalAppRequested(state.goal) && destinationForeground))
                    && state.session.objectiveGraph.complete()) {
                updateSession(state, AgentTaskSession.Status.SUCCEEDED);
                SafeAgentLog.event("VERIFICATION", verificationDetails(state, verification));
                SafeAgentLog.event("TASK_SUCCESS", goalSummary(state.session));
                host.complete(chatId, cleanResult("", state.lastResult));
                return;
            }
        }
        if (result.success && "CLICK_ELEMENT".equals(tool)) {
            // A click may publish a separate activity. Let the accessibility
            // window settle before the next mandatory fresh observation.
            SafeAgentLog.event("POST_CLICK_REOBSERVE", "scheduled tool=" + tool
                    + " objective=" + quote(state.goal.objective));
            host.diagnostic("POST_CLICK_REOBSERVE scheduled");
            scheduler.execute(() -> {
                SlashAccessibilityService.awaitStableUi(uiEventToken, 1_200);
                SafeAgentLog.event("POST_CLICK_REOBSERVE", "dispatch");
                host.diagnostic("POST_CLICK_REOBSERVE dispatch");
                state.allowTransitionContinuation = true;
                if (state.goal != null && state.goal.intent == AgentGoal.Intent.PLAY_MEDIA) {
                    AgentVerifier.Result mediaVerification = new AgentVerifier.Result(false,
                            "Waiting for the active player surface");
                    // A video click can publish the player asynchronously after
                    // the first quiet accessibility event. Poll a few bounded
                    // fresh observations for visible pause/playing evidence
                    // instead of re-clicking the same result while it loads.
                    for (int attempt = 0; attempt < 2 && !mediaVerification.success; attempt++) {
                        if (attempt > 0) android.os.SystemClock.sleep(300);
                        state.world = AgentWorldState.capture(appContext);
                        mediaVerification = verifier.verify(state.goal, state.world,
                                state.screenChangingActions);
                    }
                    if (mediaVerification.success) {
                        completeCurrentObjectiveFromVerification(state, mediaVerification);
                        SafeAgentLog.event("VERIFICATION", verificationDetails(state, mediaVerification));
                        if (state.session.objectiveGraph.complete()) {
                            updateSession(state, AgentTaskSession.Status.SUCCEEDED);
                            SafeAgentLog.event("TASK_SUCCESS", goalSummary(state.session));
                            host.complete(chatId, "Playing the requested content.");
                        } else {
                            requestNext(chatId, context, state, token);
                        }
                        return;
                    }
                    JSONObject mediaNext = semanticMediaFallback(state);
                    if (mediaNext != null) {
                        execute(chatId, context, state, mediaNext, token);
                        return;
                    }
                }
                requestNext(chatId, context, state, token);
            });
            return;
        }
        requestNext(chatId, context, state, token);
    }

    private void requestNext(String chatId, JSONArray context, TaskState state, long token) {
        if (!host.isCurrent(token) && !state.allowTransitionContinuation) {
            host.diagnostic("PLANNER_REJECTED_NOT_CURRENT");
            return;
        }
        host.diagnostic("PLANNER_REQUEST_ENTER");
        try {
            // Mandatory observe-again boundary: the planner never receives assumed post-action state.
            state.world = AgentWorldState.capture(appContext);
            host.diagnostic("PLANNER_BOUNDARY world_captured");
            state.session.lastWorldStateId = state.world.id;
            SafeAgentLog.event("WORLD_STATE", state.world.debugSummary());
            host.diagnostic("PLANNER_BOUNDARY world_logged");
            // OPEN_APP can return before Android publishes the launched window. If
            // the fresh observe boundary now proves that the requested package is
            // foreground, commit the open objective here before asking any model
            // what to do next. This keeps compound media/navigation missions from
            // planning against an already-completed OPEN_APP objective.
            AgentObjective observedObjective = state.session.objectiveGraph.current();
            if (observedObjective != null
                    && observedObjective.type == AgentObjective.Type.OPEN_APP
                    && !state.lastOpenQuery.isEmpty()
                    && foregroundMatchesQuery(state.world, state.lastOpenQuery)) {
                commitObservedOpenObjective(state, state.world.foregroundPackage());
                Log.i(TAG, "OBJECTIVE_BOUNDARY observed_open current="
                        + currentObjectiveSummary(state));
            }
            // Objective boundaries are also pre-execution guards. A media app
            // can already be showing a matching player when OPEN_APP completes
            // (or a stale planner response can arrive after the player opens).
            // Verify the fresh foreground surface before allowing any planner or
            // fallback click to run; otherwise the agent can click comments,
            // channel controls, or other unrelated elements on a successful page.
            AgentObjective freshObjective = state.session.objectiveGraph.current();
            if (freshObjective != null && freshObjective.type == AgentObjective.Type.PLAY_CONTENT
                    && state.goal != null && state.goal.intent == AgentGoal.Intent.PLAY_MEDIA) {
                AgentVerifier.Result mediaBoundaryVerification = verifier.verify(
                        state.goal, state.world, state.screenChangingActions);
                if (mediaBoundaryVerification.success) {
                    completeCurrentObjectiveFromVerification(state, mediaBoundaryVerification);
                    SafeAgentLog.event("VERIFICATION", verificationDetails(state, mediaBoundaryVerification));
                    if (state.session.objectiveGraph.complete()) {
                        updateSession(state, AgentTaskSession.Status.SUCCEEDED);
                        SafeAgentLog.event("TASK_SUCCESS", goalSummary(state.session));
                        host.complete(chatId, "Playing the requested content.");
                    } else {
                        requestNext(chatId, context, state, token);
                    }
                    return;
                }
            }
            // Finish a semantic navigation objective as soon as the fresh
            // screen independently proves the requested destination. Do not
            // let the planner continue scrolling or clicking after success.
            if (state.goal != null && state.goal.objective != null
                    && !state.semanticSubgoalVerified
                    && (state.goal.objective.toLowerCase(Locale.US).contains(" find ")
                    || state.goal.objective.toLowerCase(Locale.US).contains(" locate "))) {
                host.diagnostic("PLANNER_BOUNDARY verification_enter");
                // Semantic destination checks must stay cheap and non-blocking at the
                // observe boundary.  The verifier is also used for full-task checks,
                // but routing a fresh screen through it here could stall on a third-party
                // accessibility tree.  Use the same normalized visible-text rule inline.
                FutureTask<AgentVerifier.Result> verificationTask = new FutureTask<>(() ->
                        verifier.verify(state.goal, state.world, state.screenChangingActions));
                new Thread(verificationTask, "slash-semantic-verifier").start();
                AgentVerifier.Result semanticVerification;
                try {
                    semanticVerification = verificationTask.get(1200, TimeUnit.MILLISECONDS);
                } catch (Exception timeout) {
                    verificationTask.cancel(true);
                    host.diagnostic("PLANNER_BOUNDARY verification_timeout");
                    semanticVerification = new AgentVerifier.Result(false, "Semantic verification timed out");
                }
                host.diagnostic("PLANNER_BOUNDARY verification_returned success=" + semanticVerification.success);
                if (semanticVerification.success) {
                    String semanticObjective = state.goal.objective.toLowerCase(Locale.US);
                    int findMarker = Math.max(semanticObjective.indexOf(" find "),
                            semanticObjective.indexOf(" locate "));
                    boolean hasTrailingClauses = findMarker >= 0
                            && semanticObjective.substring(findMarker + 1).matches(".*\\b(?:then|and)\\b.*");
                    if (hasTrailingClauses) {
                        state.semanticSubgoalVerified = true;
                        completeCurrentObjectiveFromVerification(state, semanticVerification);
                        state.recoveryAttempts = 0;
                        state.lastResult = "SEMANTIC_SUBGOAL_VERIFIED_CONTINUE_REMAINING_CLAUSES";
                    } else {
                    completeCurrentObjectiveFromVerification(state, semanticVerification);
                    if (!state.session.objectiveGraph.complete()) {
                        requestNext(chatId, context, state, token);
                        return;
                    }
                    updateSession(state, AgentTaskSession.Status.SUCCEEDED);
                    SafeAgentLog.event("VERIFICATION", verificationDetails(state, semanticVerification));
                    SafeAgentLog.event("TASK_SUCCESS", goalSummary(state.session));
                    host.complete(chatId, "Found the requested destination.");
                    return;
                    }
                }
            }
            host.diagnostic("PLANNER_BOUNDARY verification_complete");
            // AGI-style fast path: when the current objective is a generic media
            // action, use the fresh semantic screen immediately. Only call the
            // planner when no grounded local action is available. This keeps the
            // model as an ambiguity/recovery layer instead of paying a round trip
            // for every Search, Type, and result click.
            AgentObjective fastObjective = state.session.objectiveGraph.current();
            if (fastObjective != null && fastObjective.type == AgentObjective.Type.PLAY_CONTENT
                    && state.goal != null && state.goal.intent == AgentGoal.Intent.PLAY_MEDIA) {
                JSONObject fastAction = semanticMediaFallback(state);
                if (fastAction != null) {
                    SafeAgentLog.event("FAST_PATH", "objective=PLAY_CONTENT tool="
                            + fastAction.optString("tool"));
                    scheduler.execute(() -> execute(chatId, context, state, fastAction, token));
                    return;
                }
            }
            // Only mutating actions are expected to change the world fingerprint. OBSERVE_SCREEN
            // intentionally leaves the phone untouched and cannot be evidence of a stuck UI.
            if (state.step != state.lastObservedStep && changesScreen(state.lastAction)) {
                String fingerprint = state.world.debugFingerprint();
                state.noProgressWorldCount = fingerprint.equals(state.lastWorldFingerprint)
                        ? state.noProgressWorldCount + 1 : 0;
                state.lastWorldFingerprint = fingerprint;
                state.lastObservedStep = state.step;
                if (state.noProgressWorldCount >= 4) {
                    updateSession(state, AgentTaskSession.Status.FAILED);
                    SafeAgentLog.event("LOOP_DETECTED", "world_state_no_progress="
                            + state.noProgressWorldCount + " strategy=stop_and_recover");
                    host.fail(chatId, "I stopped because repeated actions were not changing the phone state.");
                    return;
                }
            }
            JSONObject instruction = new JSONObject().put("role", "system")
                    .put("content", agentInstruction(state));
            host.diagnostic("PLANNER_BOUNDARY instruction_built");
            if (state.instructionIndex < 0) {
                state.instructionIndex = context.length();
                context.put(instruction);
            } else context.put(state.instructionIndex, instruction);
            host.diagnostic("PLANNER_BOUNDARY context_ready");
            state.plannerInFlight = true;
            state.plannerRequestStep = state.step;
            final int plannerGeneration = state.session.objectiveGraph.generation();
            final String plannerObjectiveId = state.session.objectiveGraph.current() == null
                    ? "none" : state.session.objectiveGraph.current().id;
            state.plannerRequestGeneration = plannerGeneration;
            state.plannerRequestObjectiveId = plannerObjectiveId;
            host.diagnostic("PLANNER_GENERATE_DISPATCH");
            runtime.generate(context, 512, (text, nextTool) -> {
                if (!host.isCurrent(token) && !state.allowTransitionContinuation) return;
                state.allowTransitionContinuation = false;
                state.plannerInFlight = false;
                AgentObjective currentObjective = state.session.objectiveGraph.current();
                String currentObjectiveId = currentObjective == null ? "none" : currentObjective.id;
                if (plannerGeneration != state.session.objectiveGraph.generation()
                        || !plannerObjectiveId.equals(currentObjectiveId)) {
                    host.diagnostic("PLANNER_BOUNDARY stale_response_dropped generation="
                            + plannerGeneration + " current=" + state.session.objectiveGraph.generation());
                    state.lastResult = "OBJECTIVE_INVALIDATED: stale planner response discarded";
                    scheduler.execute(() -> requestNext(chatId, context, state, token));
                    return;
                }
                PlannerOutputContract.Outcome outcome = PlannerOutputContract.classify(text, nextTool);
                if (outcome == PlannerOutputContract.Outcome.TOOL_CALL
                        || outcome == PlannerOutputContract.Outcome.FINISH_TASK) {
                    state.plannerMisses = 0;
                    state.transientFailures = 0;
                    SafeAgentLog.event("PLANNER_DECISION", "world_state=" + state.world.id
                            + " goal=" + state.goal.toJson().optString("goal")
                            + " decision=" + nextTool.optString("tool")
                            + " reason=" + quote(decisionReason(nextTool.optString("tool"),
                                    nextTool.optJSONObject("arguments"), state)));
                    // Native onComplete still owns the llama mutex. Execute the tool and request
                    // the next model step only after that native frame has returned.
                    scheduler.execute(() -> execute(chatId, context, state, nextTool, token));
                    return;
                }
                String reply = cleanResult(text, state.lastResult);
                if (reply.contains("HTTP 429") || reply.toLowerCase(Locale.US).contains("resource exhausted")) {
                    state.transientFailures++;
                    SafeAgentLog.event("PLANNER_TRANSIENT_FAILURE", "attempt=" + state.transientFailures
                            + " strategy=bounded_backoff");
                    if (state.transientFailures <= 3) {
                        state.lastResult = "TRANSIENT_MODEL_CAPACITY: retry after backoff";
                        scheduler.execute(() -> {
                            android.os.SystemClock.sleep(Math.min(6_000, 1_500L * state.transientFailures));
                            requestNext(chatId, context, state, token);
                        });
                    } else host.fail(chatId, "Vertex is temporarily at capacity. I stopped without losing the task context; retry when capacity recovers.");
                    return;
                }
                if (outcome == PlannerOutputContract.Outcome.CANNOT_PROCEED) {
                    updateSession(state, AgentTaskSession.Status.FAILED);
                    SafeAgentLog.event("TASK_FAILURE", "reason=planner_cannot_proceed detail=" + quote(reply));
                    host.fail(chatId, "I couldn't proceed safely from the current phone state.");
                    return;
                }
                if (looksLikeInternalError(reply)) {
                    host.fail(chatId, "I couldn't complete that action with the local model right now.");
                    return;
                }
                JSONObject semanticFallback = semanticNavigationFallback(state);
                if (semanticFallback == null) semanticFallback = semanticMediaFallback(state);
                if (semanticFallback != null) {
                    final JSONObject fallbackAction = semanticFallback;
                    state.plannerMisses = 0;
                    SafeAgentLog.event("PLANNER_FALLBACK", "reason=semantic_visible_target");
                    scheduler.execute(() -> execute(chatId, context, state, fallbackAction, token));
                    return;
                }
                if (completeVerifiedOpen(chatId, state)) return;
                state.plannerMisses++;
                // Put the miss into the next AGENT_TASK_STATE so routing can escalate this one
                // ambiguous/protocol-failed turn to the user's selected stronger agent model.
                SafeAgentLog.event("PLANNER_PROTOCOL_ERROR", "attempt=" + state.plannerMisses
                        + " world_state=" + state.world.id + " empty=" + reply.isEmpty());
                Log.e(TAG, "PLANNER_TEXT_WITHOUT_TOOL miss=" + state.plannerMisses
                        + " text=" + bounded(reply, 240));
                host.diagnostic("PLANNER_TEXT_WITHOUT_TOOL miss=" + state.plannerMisses
                        + " text=" + bounded(reply, 240));
                if (state.plannerMisses >= 3 || state.step >= MAX_STEPS) {
                    updateSession(state, AgentTaskSession.Status.FAILED);
                    SafeAgentLog.event("TASK_FAILURE", "reason=planner_protocol_error retries=" + state.plannerMisses);
                    host.fail(chatId, "The AI planner returned an invalid empty response repeatedly, so I stopped without taking an ungrounded action.");
                    return;
                }
                state.lastResult = "PLANNER_TEXT_BEFORE_VISIBLE_VERIFICATION: "
                        + bounded(reply.isEmpty() ? "empty response" : reply, 160);
                // Re-enter only after the native callback releases the llama mutex.
                scheduler.execute(() -> requestNext(chatId, context, state, token));
            });
            scheduler.execute(() -> {
                android.os.SystemClock.sleep(5_000);
                if ((!host.isCurrent(token) && !state.allowTransitionContinuation) || !state.plannerInFlight
                        || state.plannerRequestStep != state.step) return;
                state.plannerInFlight = false;
                state.plannerMisses++;
                host.diagnostic("PLANNER_TIMEOUT retry=" + state.plannerMisses);
                SafeAgentLog.event("PLANNER_TIMEOUT", "retry=" + state.plannerMisses);
                // A slow/unavailable planner must not strand a task after OPEN_APP.
                // Run the same grounded media recovery used for protocol failures
                // before spending another model round-trip.
                JSONObject timeoutFallback = semanticMediaFallback(state);
                if (timeoutFallback != null) {
                    final JSONObject fallbackAction = timeoutFallback;
                    state.plannerMisses = 0;
                    SafeAgentLog.event("PLANNER_FALLBACK", "reason=planner_timeout_media");
                    scheduler.execute(() -> execute(chatId, context, state, fallbackAction, token));
                    return;
                }
                if (state.plannerMisses >= 3) {
                    updateSession(state, AgentTaskSession.Status.FAILED);
                    host.fail(chatId, "The planner stopped responding after the screen changed.");
                } else if (state.plannerMisses == 1 && state.goal != null
                        && state.goal.objective != null
                        && (state.goal.objective.toLowerCase(Locale.US).contains(" find ")
                        || state.goal.objective.toLowerCase(Locale.US).contains(" locate "))) {
                    // A failed semantic transition often leaves an OEM interstitial
                    // (account/voice/search) in front of the requested app. Recover
                    // one level from the fresh screen before asking the planner again.
                    try {
                        execute(chatId, context, state, new JSONObject().put("tool", "BACK")
                                .put("arguments", new JSONObject()), token);
                    } catch (Exception ignored) { requestNext(chatId, context, state, token); }
                } else requestNext(chatId, context, state, token);
            });
        } catch (Exception error) {
            host.diagnostic("PLANNER_EXCEPTION " + bounded(String.valueOf(error), 240));
            SafeAgentLog.event("PLANNER_EXCEPTION", quote(String.valueOf(error)));
            host.fail(chatId, "I couldn't continue that phone task.");
        }
    }

    /** Generic, app-independent recovery when the model returns prose/empty output. */
    private JSONObject semanticNavigationFallback(TaskState state) {
        if (state == null || state.goal == null || state.goal.objective == null
                || state.world == null) return null;
        String objective = state.goal.objective.toLowerCase(Locale.US);
        int marker = objective.indexOf("find ");
        if (marker < 0) marker = objective.indexOf("locate ");
        if (marker < 0) return null;
        String target = objective.substring(marker + (objective.startsWith("find ", marker) ? 5 : 7));
        target = target.replaceAll("(?i)\\b(and then|then|and)\\b.*$", "").trim();
        if (target.isEmpty()) return null;
        String observation = state.world.accessibilityObservation;
        java.util.regex.Matcher id = java.util.regex.Pattern.compile("OBSERVATION_ID=([^ ]+)").matcher(observation);
        if (!id.find()) return null;
        String observationId = id.group(1);
        String lowerObservation = observation.toLowerCase(Locale.US);
        if (lowerObservation.contains("search")
                && (lowerObservation.contains("voice") || lowerObservation.contains("microphone"))
                && !lowerObservation.contains("type")) {
            state.semanticSearchFallbackUsed = true;
            try {
                return new JSONObject().put("tool", "BACK").put("arguments", new JSONObject());
            } catch (Exception ignored) { return null; }
        }
        if (!state.semanticSearchFallbackUsed
                && lowerObservation.contains("search")) {
            state.semanticSearchFallbackUsed = true;
            try {
                return new JSONObject().put("tool", "CLICK_ELEMENT").put("arguments",
                        new JSONObject().put("observation_id", observationId)
                                .put("element_id", "Search")
                                .put("label", "Search"));
            } catch (Exception ignored) { return null; }
        }
        String[] tokens = target.split("\\s+");
        java.util.List<String> semanticTokens = new java.util.ArrayList<>();
        for (String token : tokens) {
            token = token.replaceAll("[^a-z0-9&]", "");
            if (token.length() > 2 && !token.equals("settings") && !token.equals("setting")
                    && !token.equals("page") && !token.equals("section")) semanticTokens.add(token);
        }
        if (semanticTokens.isEmpty()) return null;
        String phrase = String.join(" ", semanticTokens);
        String specific = semanticTokens.get(0);
        int bestScore = 0;
        String bestId = null;
        String bestLabel = null;
        java.util.regex.Matcher element = java.util.regex.Pattern.compile(
                "(?im)^(e\\d+)\\s+[^\\n]*text=\\\"([^\\\"]+)\\\"[^\\n]*actions=([^\\n]*)$").matcher(observation);
        while (element.find()) {
            String label = element.group(2).toLowerCase(Locale.US);
            // Resource IDs and class names are grounding metadata, not semantic
            // labels. Never let them win a natural-language target match.
            if (label.contains("/") || label.contains(":")
                    || label.contains("android.")) continue;
            if (!element.group(3).contains("click")) continue;
            // Once the query has been entered, the editable search box is still
            // clickable and often has the exact query as its label. It is an
            // input, not a result. Do not select it as the navigation target.
            if (state.semanticSearchFallbackUsed && element.group(3).contains("type")) continue;
            int score = 0;
            // Prefer an exact semantic result over a longer sibling such as
            // "Display cutout" or "Display refresh rate". The goal parser
            // intentionally drops generic suffixes like "settings", so an
            // exact visible label is the strongest app-independent match.
            if (label.equals(phrase)) score += 300;
            if (label.contains(phrase)) score += 100;
            for (String token : semanticTokens) if (label.contains(token)) score += 20;
            if (label.equals(specific) || label.startsWith(specific + " ")) score += 15;
            SafeAgentLog.event("SEMANTIC_CANDIDATE", "target=" + phrase + " label="
                    + quote(element.group(2)) + " score=" + score + " id=" + element.group(1));
            // When labels are otherwise equivalent, prefer the shorter label;
            // this keeps a broad target from selecting a more specific sibling.
            if (score > bestScore || (score == bestScore && bestLabel != null
                    && label.length() < bestLabel.length())) {
                bestScore = score; bestId = element.group(1); bestLabel = element.group(2);
            }
        }
        if (bestScore < 60 && !state.semanticSearchFallbackUsed) {
            state.semanticSearchFallbackUsed = true;
            try {
                return new JSONObject().put("tool", "CLICK_ELEMENT").put("arguments",
                        new JSONObject().put("observation_id", observationId)
                                .put("element_id", "Search settings")
                                .put("label", "Search settings"));
            } catch (Exception ignored) { return null; }
            /*
            java.util.regex.Matcher search = java.util.regex.Pattern.compile(
                    "(?im)^(e\\d+)\\s+[^\\n]*text=\\\"([^\\\"]*(?:search|find)[^\\\"]*)\\\"[^\\n]*actions=([^\\n]*)$")
                    .matcher(observation);
            if (search.find() && (search.group(3).contains("click") || search.group(3).contains("type"))) {
                state.semanticSearchFallbackUsed = true;
                try {
                    return new JSONObject().put("tool", "CLICK_ELEMENT").put("arguments",
                            new JSONObject().put("observation_id", observationId)
                                    .put("element_id", search.group(1)).put("label", search.group(2)));
                } catch (Exception ignored) { return null; }
            }
            java.util.regex.Matcher editable = java.util.regex.Pattern.compile(
                    "(?im)^(e\\d+)\\s+[^\\n]*text=\\\"([^\\\"]*)\\\"[^\\n]*actions=([^\\n]*type[^\\n]*)$")
                    .matcher(observation);
            if (editable.find()) {
                state.semanticSearchFallbackUsed = true;
                try {
                    return new JSONObject().put("tool", "CLICK_ELEMENT").put("arguments",
                            new JSONObject().put("observation_id", observationId)
                                    .put("element_id", editable.group(1))
                                    .put("label", editable.group(2)));
                } catch (Exception ignored) { return null; }
            }
            */
        }
        if (bestId == null || bestScore < 20) {
            if (state.semanticScrollAttempts < 3) {
                state.semanticScrollAttempts++;
                try {
                    return new JSONObject().put("tool", "SCROLL").put("arguments", new JSONObject());
                } catch (Exception ignored) { return null; }
            }
            return null;
        }
        if (bestId.equals(state.lastSemanticRecoveryElement)) return null;
        state.lastSemanticRecoveryElement = bestId;
        try {
            return new JSONObject().put("tool", "CLICK_ELEMENT").put("arguments",
                    new JSONObject().put("observation_id", observationId)
                            .put("element_id", bestId).put("label", bestLabel));
        } catch (Exception ignored) { return null; }
    }

    /**
     * App-independent recovery for media objectives when a planner turn is empty or
     * malformed. It deliberately reasons only from semantic roles/labels exposed by
     * Accessibility; no YouTube/Spotify layout or package is referenced here.
     */
    private JSONObject semanticMediaFallback(TaskState state) {
        if (state == null || state.goal == null || state.goal.intent != AgentGoal.Intent.PLAY_MEDIA
                || state.world == null) return null;
        AgentObjective objective = state.session.objectiveGraph.current();
        Log.i(TAG, "MEDIA_FALLBACK_STATE objective=" + (objective == null ? "none" : objective.type)
                + " id=" + (objective == null ? "none" : objective.id)
                + " goal=" + state.goal.intent);
        if (objective == null || objective.type != AgentObjective.Type.PLAY_CONTENT) return null;
        String observation = state.world.accessibilityObservation == null ? "" : state.world.accessibilityObservation;
        java.util.regex.Matcher id = java.util.regex.Pattern.compile("OBSERVATION_ID=([^ ]+)").matcher(observation);
        if (!id.find()) return null;
        String observationId = id.group(1);
        String lower = observation.toLowerCase(Locale.US);
        // AgentObjective intentionally uses a generic description (for example
        // "Play the requested content"), so extracting the search term from it
        // loses the user's artist/song. Always prefer the original request,
        // then fall back to the normalized objective for older sessions.
        String query = mediaQuery(state.goal.userRequest);
        if (query.isEmpty()) query = mediaQuery(state.goal.objective);
        if (query.isEmpty()) return null;
        Log.i(TAG, "MEDIA_FALLBACK objective=" + objective.type + " query=" + query
                + " typed=" + state.mediaQueryTyped + " observation="
                + bounded(observation.replace('\n', '|'), 1800));

        // Prefer an explicit Search control before typing. Search controls are
        // identified by semantic text/content-description, not by coordinates.
        if (!state.mediaSearchFallbackUsed && !state.mediaQueryTyped) {
            java.util.regex.Matcher search = java.util.regex.Pattern.compile(
                    "(?im)^(e\\d+)\\s+[^\\n]*text=\\\"([^\\\"]*(?:search|find)[^\\\"]*)\\\"[^\\n]*actions=([^\\n]*)$")
                    .matcher(observation);
            while (search.find()) {
                String label = search.group(2);
                String actions = search.group(3).toLowerCase(Locale.US);
                if (actions.contains("click")) {
                    state.mediaSearchFallbackUsed = true;
                    try {
                        return new JSONObject().put("tool", "CLICK_ELEMENT").put("arguments",
                                new JSONObject().put("observation_id", observationId)
                                        .put("element_id", search.group(1)).put("label", label));
                    } catch (Exception ignored) { return null; }
                }
            }
        }

        // Once Search is open, type into the first grounded editable field.
        if (!state.mediaQueryTyped) {
            java.util.regex.Matcher editable = java.util.regex.Pattern.compile(
                    "(?im)^(e\\d+)\\s+[^\\n]*text=\\\"([^\\\"]*)\\\"[^\\n]*actions=([^\\n]*type[^\\n]*)$")
                    .matcher(observation);
            while (editable.find()) {
                String label = editable.group(2);
                try {
                    return new JSONObject().put("tool", "TYPE_TEXT").put("arguments",
                            new JSONObject().put("observation_id", observationId)
                                    .put("element_id", editable.group(1)).put("label", label)
                                    .put("text", query));
                } catch (Exception ignored) { return null; }
            }
        }

        // After results appear, select the strongest visible matching result. Avoid
        // selecting the search field itself or navigation controls.
        String[] tokens = query.toLowerCase(Locale.US).split("\\s+");
        // Some apps expose both a channel/artist result and playable rows in the
        // same accessibility surface. If a playable row is present, restrict the
        // candidate set to those rows; otherwise a creator link such as
        // "@MrBeast" can outrank the actual video title simply because it contains
        // the query exactly. This remains app-independent: it is driven entirely
        // by semantic playback labels exposed by the current screen.
        boolean hasPlayableRows = lower.contains("play video") || lower.contains("play song")
                || lower.contains("play track") || lower.contains("play episode");
        int best = 0; String bestId = null; String bestLabel = null;
        java.util.regex.Matcher element = java.util.regex.Pattern.compile(
                "(?im)^(e\\d+)\\s+[^\\n]*text=\\\"([^\\\"]+)\\\"[^\\n]*actions=([^\\n]*)$").matcher(observation);
        while (element.find()) {
            String label = element.group(2);
            String normalized = label.toLowerCase(Locale.US);
            String actions = element.group(3).toLowerCase(Locale.US);
            // Result rows may expose clickability only on a parent while the
            // visible matching title is a child. Keep matching labelled
            // children as candidates too; SlashToolExecutor will promote the
            // grounded node to its nearest clickable parent when needed.
            // Never treat the editable search field (which contains the query
            // after typing) as a media result.
            if (actions.contains("type")) continue;
            if (hasPlayableRows && !(normalized.contains("play video")
                    || normalized.contains("play song")
                    || normalized.contains("play track")
                    || normalized.contains("play episode"))) continue;
            // After IME submission YouTube can expose the collapsed query
            // control as a clickable button. It is not a result; prefer rows
            // that carry actual playback evidence such as "play video".
            if (normalized.equals(query.toLowerCase(Locale.US))
                    && !normalized.contains("play video")) continue;
            if ((normalized.contains("search") && !normalized.contains(query.toLowerCase(Locale.US)))
                    || normalized.contains("keyboard")) continue;
            int score = 0;
            for (String token : tokens) if (token.length() > 2 && normalized.contains(token)) score += 25;
            if (normalized.contains(query.toLowerCase(Locale.US))) score += 100;
            if (!actions.contains("click")) score -= 5;
            if (score > best) { best = score; bestId = element.group(1); bestLabel = label; }
        }
        if (bestId != null && best >= Math.max(25, tokens.length * 20)) {
            Log.i(TAG, "MEDIA_RESULT_CANDIDATE id=" + bestId + " label=" + bestLabel + " score=" + best);
            try {
                return new JSONObject().put("tool", "CLICK_ELEMENT").put("arguments",
                        new JSONObject().put("observation_id", observationId)
                                .put("element_id", bestId).put("label", bestLabel));
            } catch (Exception ignored) { return null; }
        }
        // Do not press BACK speculatively here. A freshly launched provider can
        // expose an unlabeled/icon-only root for one accessibility frame; BACK
        // at that point exits the provider and makes the task look as if it
        // "opened Spotify and disappeared". Let the planner/vision path inspect
        // the stable surface instead. BACK remains available as an explicitly
        // grounded recovery action when the model can identify a blocking page.
        return null;
    }

    private String mediaQuery(String goal) {
        if (goal == null) return "";
        String value = goal.trim();
        String lower = value.toLowerCase(Locale.US);
        int play = lower.indexOf("play ");
        if (play < 0) return "";
        String query = value.substring(play + 5).replaceAll("[.!?]+$", "").trim();
        query = query.replaceFirst("(?i)^(a|an|any|the)\\s+", "");
        query = query.replaceFirst("(?i)\\s+on\\s+(youtube music|youtube|spotify|apple music)$", "").trim();
        query = query.replaceFirst("(?i)\\s+video$", "").trim();
        // Search providers need both pieces of an explicit title/artist request.
        // Keep the title first so the strongest result is still the requested
        // song, while retaining the artist as a disambiguator.
        java.util.regex.Matcher by = java.util.regex.Pattern.compile(
                "(?i)^(.+?)\\s+by\\s+(.+)$").matcher(query);
        if (by.matches()) query = by.group(1).trim() + " " + by.group(2).trim();
        return query;
    }

    /** Builds a TYPE_TEXT action from the latest semantic observation. */
    private JSONObject semanticInputFallback(TaskState state, String text) {
        if (state == null || state.world == null || text == null || text.isEmpty()) return null;
        String observation = state.world.accessibilityObservation;
        java.util.regex.Matcher id = java.util.regex.Pattern.compile("OBSERVATION_ID=([^ ]+)").matcher(observation);
        if (!id.find()) return null;
        String observationId = id.group(1);
        java.util.regex.Matcher editable = java.util.regex.Pattern.compile(
                "(?im)^(e\\d+)\\s+[^\\n]*text=\\\"([^\\\"]*)\\\"[^\\n]*actions=([^\\n]*type[^\\n]*)$").matcher(observation);
        String fallbackId = null;
        String fallbackLabel = "";
        while (editable.find()) {
            String label = editable.group(2);
            String lower = label.toLowerCase(Locale.US);
            if (lower.contains("search") || fallbackId == null) {
                fallbackId = editable.group(1);
                fallbackLabel = label;
                if (lower.contains("search")) break;
            }
        }
        if (fallbackId == null) return null;
        try {
            return new JSONObject().put("tool", "TYPE_TEXT").put("arguments",
                    new JSONObject().put("observation_id", observationId)
                            .put("element_id", fallbackId)
                            .put("label", fallbackLabel)
                            .put("text", text));
        } catch (Exception ignored) { return null; }
    }

    private String agentInstruction(TaskState state) {
        boolean appAlreadyOpened = !state.lastOpenQuery.isEmpty();
        boolean allowOpenApp = !appAlreadyOpened || state.goal.intent == AgentGoal.Intent.DEVICE_ACTION;
        boolean finishAllowed = !state.lastResult.startsWith("FINISH_REJECTED");
        String availableTools = (allowOpenApp ? "OPEN_APP, " : "")
                + "CLICK_ELEMENT, TYPE_TEXT, SCROLL, BACK, HOME";
        if (finishAllowed) availableTools += ", FINISH_TASK";
        String openGuidance = appAlreadyOpened
                ? "Already opened " + state.lastOpenQuery + ". Do not reopen that same app; continue with its visible controls. "
                    + (allowOpenApp ? "If a later clause requires a different app, use OPEN_APP for that app. " : "")
                : "OPEN_APP app_query must be only the app name, never the whole task. Do not click Slash controls. ";
        String openSchema = allowOpenApp
                ? "{\"type\":\"function\",\"function\":{\"name\":\"OPEN_APP\",\"parameters\":{\"type\":\"object\",\"properties\":{\"app_query\":{\"type\":\"string\"}},\"required\":[\"app_query\"]}}}\n"
                : "";
        String finishSchema = finishAllowed
                ? "{\"type\":\"function\",\"function\":{\"name\":\"FINISH_TASK\",\"parameters\":{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"}},\"required\":[\"summary\"]}}}\n"
                : "";
        String finishGuidance = finishAllowed
                ? "Use FINISH_TASK with a short summary only after the entire completion criteria are visibly verified; never finish after only an intermediate step. "
                : "FINISH_TASK is unavailable because the previous finish was not visibly verified. Take a grounded screen-changing action now. ";
        return "AGENT_TASK_STATE " + state.toJson() + "\n"
                + "Choose exactly one next action using native function calling. Return no prose. "
                + "The runtime has already observed the current screen below. Do not request another observation; act on this fresh state. "
                + "Prefer the lowest-latency grounded route: visible semantic control first, then re-ground by label, then scroll only with a valid direction, then recover with BACK only when the screen is blocking. Never repeat a failed action without a new observation. "
                + "Preserve every clause of the objective. Opening an app is only final when opening it is the entire objective. "
                + "Use workingMemory outputs from completed objectives when a later objective says use, write, copy, remember, or enter the earlier result. Do not invent missing values. "
                + "Available tools: " + availableTools + ". "
                + openGuidance
                + "Use only element IDs from the fresh observation. Include the visible human-readable label in CLICK_ELEMENT.label when available. Never reuse a stale observation. "
                + (state.goal.intent == AgentGoal.Intent.PLAY_MEDIA
                    ? "For media, select a visible result matching the requested title or artist and verify on the active app surface. "
                    : "")
                + finishGuidance
                + "\n"
                + "<tools>\n"
                + openSchema
                + "{\"type\":\"function\",\"function\":{\"name\":\"CLICK_ELEMENT\",\"parameters\":{\"type\":\"object\",\"properties\":{\"observation_id\":{\"type\":\"string\"},\"element_id\":{\"type\":\"string\"},\"label\":{\"type\":\"string\"}},\"required\":[\"observation_id\",\"element_id\"]}}}\n"
                + "{\"type\":\"function\",\"function\":{\"name\":\"TYPE_TEXT\",\"parameters\":{\"type\":\"object\",\"properties\":{\"observation_id\":{\"type\":\"string\"},\"element_id\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"}},\"required\":[\"observation_id\",\"element_id\",\"text\"]}}}\n"
                + "{\"type\":\"function\",\"function\":{\"name\":\"SCROLL\",\"parameters\":{\"type\":\"object\",\"properties\":{\"direction\":{\"type\":\"string\"}},\"required\":[\"direction\"]}}}\n"
                + "{\"type\":\"function\",\"function\":{\"name\":\"BACK\",\"parameters\":{\"type\":\"object\",\"properties\":{}}}}\n"
                + "{\"type\":\"function\",\"function\":{\"name\":\"HOME\",\"parameters\":{\"type\":\"object\",\"properties\":{}}}}\n"
                + finishSchema + "</tools>\n"
                + "FINISH_TASK requests independent verification. /no_think "
                + "FRESH_FUSED_WORLD_STATE:\n" + state.world.plannerContext();
    }

    private String initialAppQuery(AgentGoal goal) {
        if (goal == null) return "";
        String objective = goal.objective == null ? "" : goal.objective.trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)\\bopen\\s+(.+?)(?:\\s*,|\\s+and\\b|\\s+then\\b|$)").matcher(objective);
        if (matcher.find()) return matcher.group(1).replaceFirst("(?i)^(the\\s+)", "").trim();
        return goal.provider;
    }

    private String nextUnopenedApp(TaskState state) {
        if (state == null || state.goal == null || state.goal.objective == null) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)\\b(?:open|launch|start)\\s+(.+?)(?:\\s*,|\\s+and\\b|\\s+then\\b|$)")
                .matcher(state.goal.objective);
        int index = 0;
        while (matcher.find()) {
            String candidate = matcher.group(1).replaceFirst("(?i)^(the\\s+)", "").trim();
            if (!candidate.isEmpty()) {
                if (index++ >= state.appClauseCursor) return candidate;
            }
        }
        return "";
    }

    private int appClauseCount(AgentGoal goal) {
        if (goal == null || goal.objective == null) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)\\b(?:open|launch|start)\\s+(.+?)(?:\\s*,|\\s+and\\b|\\s+then\\b|$)")
                .matcher(goal.objective);
        int count = 0;
        while (matcher.find()) if (!matcher.group(1).trim().isEmpty()) count++;
        return count;
    }

    private boolean homeRequiredBeforeNextApp(TaskState state) {
        if (state == null || state.goal == null || state.goal.objective == null) return false;
        String value = state.goal.objective.toLowerCase(Locale.US);
        int home = value.indexOf("go home");
        if (home < 0) home = value.indexOf("return home");
        if (home < 0 || "HOME".equals(state.lastAction)) return false;
        if ("OPEN_APP".equals(state.lastAction)
                && "calculator".equals(state.lastOpenQuery.toLowerCase(Locale.US))
                && value.contains("go home")) return true;
        if ("OPEN_APP".equals(state.lastAction) && !state.lastOpenQuery.isEmpty()) {
            int openPos = value.lastIndexOf("open " + state.lastOpenQuery.toLowerCase(Locale.US));
            int pendingHome = value.indexOf("go home", Math.max(0, openPos));
            if (pendingHome < 0) pendingHome = value.indexOf("return home", Math.max(0, openPos));
            int nextOpen = value.indexOf("open ", Math.max(0, openPos + 5));
            if (pendingHome < 0 && openPos > 0) {
                openPos = value.indexOf("open " + state.lastOpenQuery.toLowerCase(Locale.US));
                pendingHome = value.indexOf("go home", Math.max(0, openPos));
                if (pendingHome < 0) pendingHome = value.indexOf("return home", Math.max(0, openPos));
                nextOpen = value.indexOf("open ", Math.max(0, openPos + 5));
            }
            if (pendingHome >= 0 && (nextOpen < 0 || pendingHome < nextOpen)) return true;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)\\b(?:open|launch|start)\\s+(.+?)(?:\\s*,|\\s+and\\b|\\s+then\\b|$)")
                .matcher(value);
        while (matcher.find()) {
            String candidate = matcher.group(1).trim();
            if (!state.openedApps.contains(candidate.toLowerCase(Locale.US)))
                return home < matcher.start();
        }
        return false;
    }

    private boolean backRequiredBeforeNextApp(TaskState state) {
        if (state == null || state.goal == null || state.goal.objective == null) return false;
        String value = state.goal.objective.toLowerCase(Locale.US);
        int back = value.indexOf("press back");
        if (back < 0) back = value.indexOf("navigate back");
        if (back < 0 || "BACK".equals(state.lastAction)) return false;
        if ("OPEN_APP".equals(state.lastAction) && !state.lastOpenQuery.isEmpty()) {
            if (state.lastOpenQuery.equals(state.lastBackAfterOpen)) return false;
            int openPos = value.lastIndexOf("open " + state.lastOpenQuery.toLowerCase(Locale.US));
            int pendingBack = value.indexOf("press back", Math.max(0, openPos));
            int nextOpen = value.indexOf("open ", Math.max(0, openPos + 5));
            if (pendingBack < 0 && openPos > 0) {
                openPos = value.indexOf("open " + state.lastOpenQuery.toLowerCase(Locale.US));
                pendingBack = value.indexOf("press back", Math.max(0, openPos));
                nextOpen = value.indexOf("open ", Math.max(0, openPos + 5));
            }
            if (pendingBack >= 0 && (nextOpen < 0 || pendingBack < nextOpen)) return true;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)\\b(?:open|launch|start)\\s+(.+?)(?:\\s*,|\\s+and\\b|\\s+then\\b|$)")
                .matcher(value);
        while (matcher.find()) {
            String candidate = matcher.group(1).trim();
            String normalized = candidate.toLowerCase(Locale.US);
            if (!state.openedApps.contains(normalized) && !state.failedApps.contains(normalized))
                return back < matcher.start();
        }
        return false;
    }

    private boolean terminalOpenClause(AgentGoal goal) {
        if (goal == null || goal.objective == null) return false;
        String value = goal.objective.toLowerCase(Locale.US).replaceAll("[.!?]+$", "").trim();
        return value.matches(".*\\b(open|launch|start)\\s+.+$")
                && !finalHomeRequested(goal)
                && !value.matches(".*\\b(press\\s*back|back)\\s*$");
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

    private String progressNarrative(String tool, JSONObject arguments) {
        if ("TYPE_TEXT".equals(tool)) return "I found the input, so I’m entering the requested text…";
        if ("CLICK_ELEMENT".equals(tool)) return "That control is grounded in the latest screen; I’m selecting it and checking what changed…";
        if ("OBSERVE_SCREEN".equals(tool)) return "The last step needs confirmation, so I’m reading the screen again…";
        if ("OPEN_APP".equals(tool)) return "I’m opening the requested app so I can continue the task…";
        if ("SCROLL".equals(tool)) return "The target is not visible yet, so I’m looking further…";
        if ("BACK".equals(tool)) return "That path did not lead to the target, so I’m backing up and trying another route…";
        if ("HOME".equals(tool)) return "The current route is not useful, so I’m returning to a clean starting point…";
        return progressFor(tool, arguments);
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
        if (value.startsWith("OPENED_APP:")) {
            return "Done — " + value.substring("OPENED_APP:".length()).trim() + " is open.";
        }
        if (value.isEmpty() && fallback != null && fallback.startsWith("OPENED_APP:")) {
            return "Done — " + fallback.substring("OPENED_APP:".length()).trim() + " is open.";
        }
        return value;
    }

    private String compactResult(String tool, SlashToolExecutor.Result result) {
        if ("OBSERVE_SCREEN".equals(tool) || "READ_SCREEN".equals(tool)
                || "GET_SCREEN_STATE".equals(tool)) {
            return result.success ? "OBSERVED_CURRENT_SCREEN" : bounded(result.result, 240);
        }
        return bounded(result.result, 320);
    }

    private boolean completeVerifiedOpen(String chatId, TaskState state) {
        if (state.goal.intent != AgentGoal.Intent.OPEN_DESTINATION) return false;
        // OPEN_DESTINATION is also used for the first clause of compound goals.
        // Do not finish there: later clauses ("then open ...", "and go home", etc.)
        // remain authoritative until they have been executed.
        String objective = state.goal.objective == null ? ""
                : state.goal.objective.toLowerCase(Locale.US);
        if (objective.matches(".*\\b(and|then)\\b.*")) return false;
        if (!"OPEN_APP".equals(state.lastAction) || !state.lastResult.startsWith("OPENED_APP:")) return false;
        state.world = AgentWorldState.capture(appContext);
        state.session.lastWorldStateId = state.world.id;
        SafeAgentLog.event("WORLD_STATE", state.world.debugSummary());
        AgentVerifier.Result verification = verifier.verify(state.goal, state.world,
                state.screenChangingActions);
        SafeAgentLog.event("VERIFICATION", verificationDetails(state, verification));
        if (verification.success) completeCurrentObjectiveFromVerification(state, verification);
        if (!verification.success || !state.session.objectiveGraph.complete()) return false;
        updateSession(state, AgentTaskSession.Status.SUCCEEDED);
        SafeAgentLog.event("TASK_SUCCESS", goalSummary(state.session));
        host.complete(chatId, cleanResult("", state.lastResult));
        return true;
    }

    private boolean finalHomeRequested(AgentGoal goal) {
        if (goal == null || goal.objective == null) return false;
        String value = goal.objective.toLowerCase(Locale.US).replaceAll("[.!?]+$", "").trim();
        return value.matches(".*\\b(go\\s*home|return\\s*home|home)\\b$");
    }

    private boolean finalAppRequested(AgentGoal goal) {
        if (goal == null || goal.objective == null) return false;
        String value = goal.objective.toLowerCase(Locale.US).replaceAll("[.!?]+$", "").trim();
        return value.matches(".*\\b(open|launch|start)\\s+[a-z0-9][a-z0-9 ]*$");
    }

    private boolean actionClausesSatisfied(TaskState state) {
        if (state == null || state.goal == null || state.goal.objective == null) return false;
        String value = state.goal.objective.toLowerCase(Locale.US);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)\\b(open|go\\s*home|return\\s*home|home|press\\s*back|back)\\b")
                .matcher(value);
        int clauses = 0;
        while (matcher.find()) clauses++;
        int invalids = 0;
        java.util.regex.Matcher invalidMatcher = java.util.regex.Pattern.compile("(?i)nosuchapp").matcher(value);
        while (invalidMatcher.find()) invalids++;
        clauses = Math.max(1, clauses - invalids);
        return clauses >= 2 && state.screenChangingActions >= clauses;
    }

    private String bounded(String value, int max) {
        String clean = value == null ? "" : value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max).trim() + "…";
    }

    private boolean looksLikeInternalError(String value) {
        String lower = value.toLowerCase(Locale.US);
        return lower.contains("jni_error") || lower.contains("local_runtime_not_loaded")
                || lower.contains("compact prompt exceeds") || lower.contains("exception:");
    }

    private static final class TaskState {
        final AgentGoal goal;
        final AgentTaskSession session;
        AgentWorldState world;
        int step;
        String lastAction = "none";
        String lastResult = "none";
        int instructionIndex = -1;
        int plannerMisses;
        boolean plannerInFlight;
        int plannerRequestStep;
        int plannerRequestGeneration;
        String plannerRequestObjectiveId = "none";
        boolean allowTransitionContinuation;
        int transientFailures;
        String lastOpenQuery = "";
        int openAppCount = 0;
        int appClauseCursor = 0;
        final Set<String> openedApps = new HashSet<>();
        final Set<String> failedApps = new HashSet<>();
        int recoveryAttempts;
        boolean semanticSubgoalVerified;
        int screenChangingActions;
        int backCount;
        String lastBackAfterOpen = "";
        String recoveryGuidance = "";
        String lastActionSignature = "";
        int identicalActionCount;
        String lastWorldFingerprint = "";
        int noProgressWorldCount;
        int lastObservedStep = -1;
        int semanticRecoveryAttempts;
        String lastSemanticRecoveryElement = "";
        int semanticScrollAttempts;
        boolean semanticSearchFallbackUsed;
        boolean mediaSearchFallbackUsed;
        boolean mediaQueryTyped;
        boolean mediaBackAttempted;

        TaskState(AgentGoal goal, AgentTaskSession session) {
            this.goal = goal;
            this.session = session;
        }

        String toJson() {
            try {
                return new JSONObject().put("goal", goal.toJson()).put("currentStep", step)
                        .put("objectiveGeneration", session.objectiveGraph.generation())
                        .put("currentObjective", session.objectiveGraph.current() == null ? "none"
                                : session.objectiveGraph.current().toJson())
                        .put("objectives", session.objectiveGraph.toJson())
                        .put("workingMemory", session.objectiveGraph.outputs())
                        .put("lastAction", lastAction).put("lastResult", lastResult)
                        .put("plannerMisses", plannerMisses)
                        .put("recoveryAttempts", recoveryAttempts)
                        .put("recoveryGuidance", recoveryGuidance).toString();
            } catch (Exception ignored) { return "{}"; }
        }

        String worldId() { return world == null ? "unavailable" : world.id; }
    }

    private void updateSession(TaskState state, AgentTaskSession.Status status) {
        state.session.status = status;
        state.session.lastAction = state.lastAction + ": " + state.lastResult;
        state.session.lastWorldStateId = state.worldId();
        state.session.lastVerification = state.lastResult;
    }

    private void completeCurrentObjectiveFromAction(TaskState state, String tool, String evidence) {
        AgentObjective objective = state.session.objectiveGraph.current();
        if (objective == null) return;
        // OPEN_APP is intentionally excluded here. Starting an intent is only an
        // attempt; the foreground package must be freshly observed and verified
        // before the OPEN_APP objective is committed.
        boolean matches = (objective.type == AgentObjective.Type.HOME && "HOME".equals(tool))
                || (objective.type == AgentObjective.Type.BACK && "BACK".equals(tool));
        if (matches) state.session.objectiveGraph.complete(objective.id, evidence, evidence);
    }

    private boolean objectiveAlreadySatisfied(TaskState state) {
        if (state == null || state.world == null) return false;
        AgentObjective objective = state.session.objectiveGraph.current();
        if (objective == null) return false;
        String lower = state.world.accessibilityObservation == null ? ""
                : state.world.accessibilityObservation.toLowerCase(Locale.US);
        if (objective.type == AgentObjective.Type.FIND_DESTINATION
                || objective.type == AgentObjective.Type.LOCATE_CONTENT) {
            String wanted = objective.description.toLowerCase(Locale.US)
                    .replaceFirst("^(find|locate|play)\\s+", "")
                    .replaceAll("[^a-z0-9 ]", " ").trim();
            return !wanted.isEmpty() && lower.contains(wanted);
        }
        return false;
    }

    private void completeCurrentObjectiveFromObservation(TaskState state) {
        AgentObjective objective = state.session.objectiveGraph.current();
        if (objective != null) state.session.objectiveGraph.complete(objective.id,
                "Fresh observation already satisfied objective", state.lastResult);
    }

    private void verifyTypedTextFromObservation(TaskState state, String expected) {
        AgentObjective objective = state.session.objectiveGraph.current();
        if (objective == null || objective.type != AgentObjective.Type.TYPE_TEXT) return;
        String wanted = expected == null ? "" : expected.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", " ").trim();
        String visible = state.world == null || state.world.accessibilityObservation == null ? ""
                : state.world.accessibilityObservation.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", " ");
        if (!wanted.isEmpty() && visible.contains(wanted)) {
            state.session.objectiveGraph.complete(objective.id,
                    "Fresh screen contains the entered text", expected);
            host.diagnostic("OBJECTIVE_COMPLETED id=" + objective.id + " evidence=text_visible");
        }
    }

    private void commitObservedOpenObjective(TaskState state, String foregroundPackage) {
        AgentObjective objective = state.session.objectiveGraph.current();
        if (objective == null || objective.type != AgentObjective.Type.OPEN_APP) return;
        if (foregroundPackage == null || foregroundPackage.isEmpty()
                || foregroundPackage.startsWith("com.slash.agent")) return;
        state.session.objectiveGraph.complete(objective.id,
                "Foreground package observed: " + foregroundPackage, state.lastResult);
        Log.i(TAG, "OBJECTIVE_COMPLETED id=" + objective.id
                + " evidence=foreground_package package=" + foregroundPackage);
        host.diagnostic("OBJECTIVE_COMPLETED id=" + objective.id + " evidence=foreground_package");
    }

    private void completeCurrentObjectiveFromVerification(TaskState state, AgentVerifier.Result verification) {
        AgentObjective objective = state.session.objectiveGraph.current();
        if (objective == null || verification == null || !verification.success) return;
        state.session.objectiveGraph.complete(objective.id, verification.reason, state.lastResult);
    }

    private boolean foregroundMatchesQuery(AgentWorldState world, String appQuery) {
        if (world == null || appQuery == null || appQuery.trim().isEmpty()) return false;
        String foreground = world.foregroundPackage().toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]", "");
        String wanted = appQuery.toLowerCase(Locale.US)
                .replaceAll("(?i)\\b(app|application)\\b", "")
                .replaceAll("[^a-z0-9]", "");
        if (foreground.isEmpty() || wanted.isEmpty()) return false;
        if (foreground.contains(wanted)) return true;
        // Android package names frequently do not contain the user-facing app
        // name verbatim (Spotify is com.spotify.music, YouTube is
        // com.google.android.youtube). Keep this generic and deterministic so
        // an OPEN_APP objective can commit and hand control to the next
        // objective instead of asking the model to reopen the same app.
        String[] aliases;
        switch (wanted) {
            case "spotify": aliases = new String[]{"comspotifymusic", "spotifymusic"}; break;
            case "youtube": aliases = new String[]{"comgoogleandroidyoutube", "youtubemusic"}; break;
            case "youtubemusic": aliases = new String[]{"comgoogleandroidyoutube", "youtubemusic"}; break;
            case "whatsapp": aliases = new String[]{"comwhatsapp"}; break;
            case "telegram": aliases = new String[]{"orgtelegrammessenger"}; break;
            case "discord": aliases = new String[]{"comdiscord"}; break;
            case "chrome": aliases = new String[]{"comandroidchrome"}; break;
            case "gmail": aliases = new String[]{"comgoogleandroidgm"}; break;
            default: aliases = new String[0];
        }
        for (String alias : aliases) if (foreground.contains(alias)) return true;
        return false;
    }

    private String currentObjectiveSummary(TaskState state) {
        AgentObjective objective = state.session.objectiveGraph.current();
        return objective == null ? "none" : objective.id + ":" + objective.type + ":" + objective.description;
    }

    private String goalSummary(AgentTaskSession session) {
        AgentGoal goal = session.activeGoal;
        return "original=" + quote(session.originalRequest) + " latest=" + quote(session.latestInstruction)
                + " goal=" + goal.toJson().optString("goal") + " provider=" + quote(goal.provider)
                + " artist=" + quote(goal.artist) + " attempt=" + session.attempt;
    }

    private String verificationDetails(TaskState state, AgentVerifier.Result result) {
        return "status=" + (result.success ? "SUCCESS" : "FAILED") + " goal="
                + state.goal.toJson().optString("goal") + " evidence_source="
                + "ActiveAppAccessibility" + " reason=" + quote(result.reason);
    }

    private String decisionReason(String tool, JSONObject arguments, TaskState state) {
        String target = arguments == null ? "" : arguments.optString("element_id");
        if ("FINISH_TASK".equals(tool)) return "Request independent verification against current authoritative evidence.";
        if ("OPEN_APP".equals(tool)) return "The requested provider is not yet confirmed as foreground.";
        if ("TYPE_TEXT".equals(tool)) return "Enter the requested goal parameter into grounded input " + target + ".";
        if ("CLICK_ELEMENT".equals(tool)) return "Select grounded element " + target + " from the fresh world state.";
        return "Advance the active goal using the fresh grounded world state.";
    }

    private String quote(String value) { return JSONObject.quote(bounded(value, 320)); }

    private JSONObject reportPayload(String tool, JSONObject arguments, SlashToolExecutor.Result result,
            boolean includeResult) {
        try {
            JSONObject payload = new JSONObject().put("tool", tool).put("arguments", arguments);
            if (includeResult && result != null) payload.put("success", result.success)
                    .put("result", bounded(result.result, 320));
            return payload;
        } catch (Exception ignored) { return new JSONObject(); }
    }
}
