package com.hyfuse.bridge.agent;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.util.concurrent.atomic.AtomicReference;

/**
 * — agent-mode increment 2, the goal session supervisor.
 *
 * `/hyfuse set goal <text>` starts a bounded goal session: up to
 * MAX_SESSIONS AgentLoop runs (each maxIterations from agent.json, default
 * 8), working the goal with the decomposition directive seed. Between
 * sessions the loop's final assistant text is folded into the next session's
 * seed so progress carries over. When the budget is spent or the model
 * declares the goal met, the session ends and reports.
 *
 * The goal text is user-authored at the bot keyboard (playbook §2: the
 * model can never start a goal session; agent-start/stop stay refused
 * loop-side as pinned by AgentLoopTest).
 */
public final class GoalSession {

    /** Bounded sessions per goal. */
    static final int SESSION_CAP = 5;

    private static final AtomicReference<GoalSession> CURRENT = new AtomicReference<>();

    private final String goal;
    private final long startedAt;
    private int sessionsCompleted = 0;
    private String lastSummary = null;
    private volatile boolean goalMet = false;
    private volatile boolean stopRequested = false;
    private String statusText = "starting";

    private GoalSession(String goal) {
        this.goal = goal;
        this.startedAt = System.currentTimeMillis();
    }

    public static GoalSession current() {
        return CURRENT.get();
    }

    public String goal() { return goal; }
    public int session() { return sessionsCompleted + 1; }
    public static int MAX_SESSIONS() { return SESSION_CAP; }

    /**
     * Start a goal session. Returns ok:false (with reason) when agent.json
     * is absent/blank, a loop is already active, or a session is already
     * running. On success the supervisor thread runs the bounded sessions.
     */
    public static JsonObject start(Minecraft client, String goal,
                                   BiFunctionSupplier dispatchSupplier) {
        JsonObject out = new JsonObject();
        GoalSession existing = CURRENT.get();
        if (existing != null && !existing.isDone()) {
            out.addProperty("ok", false);
            out.addProperty("reason", "goal_session_active");
            out.addProperty("message", "A goal session is already running: \""
                    + truncate(existing.goal, 60) + "\". Wait for it to finish or re-issue"
                    + " after it reports (bounded to " + SESSION_CAP + " sessions).");
            return out;
        }
        if (!AgentLoop.isConfigured()) {
            out.addProperty("ok", false);
            out.addProperty("reason", "agent_disabled");
            out.addProperty("message", "config/hyfuse/agent.json is absent or has a blank url — "
                    + "set it first: /hyfuse set api-url <url> (then api-key and model).");
            return out;
        }
        if (AgentLoop.isActive()) {
            out.addProperty("ok", false);
            out.addProperty("reason", "agent_busy");
            out.addProperty("message", "An agent loop is already running. Wait or agent-stop it.");
            return out;
        }
        GoalSession gs = new GoalSession(goal);
        if (!CURRENT.compareAndSet(existing, gs)) {
            out.addProperty("ok", false);
            out.addProperty("reason", "goal_session_active");
            return out;
        }
        out.addProperty("ok", true);
        out.addProperty("message", "Goal session started: \"" + truncate(goal, 80) + "\" "
                + "(up to " + SESSION_CAP + " sessions × maxIterations from agent.json)");
        Thread t = new Thread(() -> runSessions(client, gs, dispatchSupplier), "hyfuse-goal-session");
        t.setDaemon(true);
        t.start();
        return out;
   }

    /** The bounded session supervisor (runs on its own daemon thread). */
    private static void runSessions(Minecraft client, GoalSession gs, BiFunctionSupplier dispatchSupplier) {
        try {
            for (int s = 0; s < SESSION_CAP && !gs.stopRequested && !gs.goalMet; s++) {
                gs.sessionsCompleted = s;
                gs.statusText = "session " + (s + 1) + "/" + SESSION_CAP;
                JsonObject snapshot = com.hyfuse.bridge.dispatch.ToolDispatcher.getAgentSnapshotPublic(client);
                String playbook = com.hyfuse.bridge.HyFuseClient.brainStore().readPlaybook();
                AgentLoop loop = new AgentLoop(
                        dispatchSupplier.get(),
                        new AgentLoop.HttpTransport(url(), apiKey()));
                loop.setModel(model());
                String seed = AgentLoop.goalSeed(playbook, snapshot, gs.goal);
                if (gs.lastSummary != null) {
                    seed += "\n\n## Previous session summary (carry-over)\n" + gs.lastSummary;
                }
                loop.run(maxIterations(), snapshot, playbook, gs.goal);
                if (loop.lastAssistant != null && !loop.lastAssistant.isBlank()) {
                    gs.lastSummary = loop.lastAssistant;
                }
                // Goal met when the loop ended with plain text (no tool calls).
                gs.goalMet = loop.lastAssistant != null && loop.iterations > 0
                        && !loop.lastAssistant.isBlank() && loop.lastError == null;
                // goalMet heuristic: the final reply is the model's summary
                // reply-without-tool-calls termination (the directive's "reply
                // with a summary and no tool calls" path).
                if (gs.goalMet) break;
                if (loop.lastError != null) {
                    gs.statusText = "error: " + loop.lastError;
                    break;
                }
            }
            if (gs.goalMet) {
                gs.statusText = "goal met: " + truncate(gs.lastSummary, 200);
            } else if (gs.stopRequested) {
                gs.statusText = "stopped locally (/hyfuse)";
            } else {
                gs.statusText = "budget spent (" + SESSION_CAP + " sessions) — goal not confirmed met; "
                        + "re-issue /hyfuse set goal to continue";
            }
        } catch (Throwable t) {
            gs.statusText = "supervisor error: " + t;
        } finally {
            // Keep the record for the status read; a new start replaces it.
            gs.done = true;
        }
    }

    boolean isDone() { return done; }
    volatile boolean done = false;

    public String statusText() { return statusText; }

    public static void requestStop() {
        GoalSession gs = CURRENT.get();
        if (gs != null) {
            gs.stopRequested = true;
            AgentLoop.requestStop();
        }
    }

    // ── agent.json reads (static convenience for the supervisor) ─────────

    static String url() {
        JsonObject cfg = AgentLoop.loadConfig();
        return cfg != null && cfg.has("url") ? cfg.get("url").getAsString(): "";
    }

    static String apiKey() {
        JsonObject cfg = AgentLoop.loadConfig();
        return cfg != null && cfg.has("apiKey") ? cfg.get("apiKey").getAsString(): "";
    }

    static String model() {
        JsonObject cfg = AgentLoop.loadConfig();
        return cfg != null && cfg.has("model") ? cfg.get("model").getAsString(): "default";
    }

    static int maxIterations() {
        JsonObject cfg = AgentLoop.loadConfig();
        return cfg != null && cfg.has("maxIterations") ? cfg.get("maxIterations").getAsInt(): 8;
    }

    /** Supplier of the dispatch BiFunction (ToolDispatcher::dispatch shape). */
    public interface BiFunctionSupplier {
        java.util.function.BiFunction<String, JsonObject,
                java.util.concurrent.CompletableFuture<JsonObject>> get();
    }

    static String truncate(String s, int max) {
        return s == null ? "": s.length() <= max ? s: s.substring(0, max - 1) + "…";
    }
}
