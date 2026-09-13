package com.hyfuse.bridge.agent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hyfuse.bridge.mcp.McpToolRegistry;
import com.hyfuse.bridge.sense.BrainStore;

import java.net.URI;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

/**
 * AgentLoop — the bottom-up agent loop.
 *
 * The mod itself drives an LLM: each iteration POSTs the playbook (system
 * prompt), the running message history, and the full MCP tool list to an
 * OpenAI-compatible endpoint (OpenWebUI /api/chat/completions), parses any
 * {@code tool_calls} from the assistant message, dispatches each through
 * ToolDispatcher, appends the results as tool messages, and asks again.
 * The loop ends when the model replies without tool calls, the iteration
 * budget is spent, or agent-stop / a fatal transport error intervenes.
 *
 * Authority model (playbook §2): the loop is started ONLY by the user's
 * agent-start MCP tool. Model-initiated actions are restricted to the same
 * policy rail as policy actions — the loop will not dispatch agent-start
 * or agent-stop recursively, and tool results are returned to the model
 * verbatim (the model sees honest results, never fabrications).
 *
 * Config: config/hyfuse/agent.json — {"url": "...", "apiKey": "...",
 * "model": "...", "maxIterations": N, "pollIntervalMs": M}. Absent file or
 * blank url => disabled; agent-start answers {@code agent_disabled}.
 *
 * Threading: run() blocks on its own executor thread (never the client
 * thread); dispatch() marshals Minecraft access itself, as always.
 */
public final class AgentLoop {

    private static final Gson GSON = new Gson();
    private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);

    /** One iteration of the loop (see run()); visible for the smoke suite. */
    public record Iteration(int index, boolean hadToolCalls, int toolCalls,
                            String assistantText) { }

    /** Set by the real wiring; the smoke suite injects a fake. */
    public interface Transport {
        /** Convenience base for scripted transports (tests, offline). */
        /** POST the chat request; return the raw response body. */
        String call(JsonObject request) throws Exception;
    }

    /** Dispatch function (ToolDispatcher.dispatch for real). */
    private final BiFunction<String, JsonObject, CompletableFuture<JsonObject>> dispatch;
    private final Transport transport;

    private static volatile boolean stopRequested = false;
    int iterations = 0;

    // package-visible for the smoke suite (same package)
    int toolCallsTotal = 0;
    String lastError = null;
    String lastAssistant = null;
    private List<JsonObject> history = new ArrayList<>();

    public AgentLoop(BiFunction<String, JsonObject, CompletableFuture<JsonObject>> dispatch,
                     Transport transport) {
        this.dispatch = dispatch;
        this.transport = transport;
    }

    // ── Config ───────────────────────────────────────────────────────────

    /** Parsed agent.json; null when disabled (absent file / blank url). */
    public static JsonObject loadConfig() {
        try {
            Path agentConf = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("hyfuse/agent.json");
            if (!java.nio.file.Files.exists(agentConf)) return null;
            JsonObject cfg = JsonParser.parseString(
                    java.nio.file.Files.readString(agentConf)).getAsJsonObject();
            String url = cfg.has("url") ? cfg.get("url").getAsString(): "";
            if (url == null || url.isBlank()) return null;
            return cfg;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isConfigured() {
        return loadConfig() != null;
    }

    // ── Lifecycle (user MCP tools only) ─────────────────────────────

    public static boolean start() {
        if (!isConfigured()) return false;
        return ACTIVE.compareAndSet(false, true);
    }

    public static void requestStop() {
        stopRequested = true;
    }

    public static boolean isActive() {
        return ACTIVE.get();
    }

    /** Last error of the most recent loop, kept after CURRENT is nulled. */
    static volatile String lastEndedError;

    public static String status() {
        AgentLoop loop = CURRENT;
        JsonObject out = new JsonObject();
        out.addProperty("active", ACTIVE.get());
        String err = loop != null && loop.lastError != null ? loop.lastError : lastEndedError;
        if (err != null) out.addProperty("lastError", err);
        if (loop != null) {
            out.addProperty("iterations", loop.iterations);
            out.addProperty("toolCallsTotal", loop.toolCallsTotal);
            if (loop.lastError != null) out.addProperty("lastError", loop.lastError);
            if (loop.lastAssistant != null) {
                String s = loop.lastAssistant;
                out.addProperty("lastAssistant", s.length() > 400 ? s.substring(0, 400): s);
            }
        }
        return GSON.toJson(out);
    }

    /** The running loop instance, if any (agent-status reads it). */
    static volatile AgentLoop CURRENT;

    // ── The loop ─────────────────────────────────────────────────────────

    /**
     * Run the loop on the calling thread until budget/end/stop. Call from a
     * dedicated executor thread, never the client thread.
     *
     * @param maxIterations hard budget (config, default 8)
     * @param snapshot initial context (get-agent-snapshot result)
     * @param playbook system-prompt text (BrainStore.readPlaybook())
     */
    public void run(int maxIterations, JsonObject snapshot, String playbook) {
        CURRENT = this;
        stopRequested = false;
        history = new ArrayList<>();
                if (goal != null && !goal.isBlank()) {
            // Goal mode — the seed message carries the decomposition
            // directive; the loop works the goal across the session budget.
            history.add(userMessage(goalSeed(playbook, snapshot, goal)));
        } else {
            history.add(userMessage(systemish(playbook, snapshot)));
        }
        try {
            for (int i = 0; i < maxIterations && !stopRequested; i++) {
                Iteration it = oneIteration(i);
                iterations = i + 1;
                if (!it.hadToolCalls) {
                    lastAssistant = it.assistantText();
                    break;
                }
            }
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            // Keep the error visible after CURRENT is nulled (agent-status
            // and /hyfuse status read it post-mortem).
            lastEndedError = lastError;
            CURRENT = null;
            ACTIVE.set(false);
            stopRequested = false;
        }
    }

    /**
     * Goal-mode run. The seed message directs the model to decompose
     * the goal into tasks, work them with tools, and END with the player at
     * a safe standby holding position. Same loop mechanics as run().
     */
    public void run(int maxIterations, JsonObject snapshot, String playbook, String goal) {
        this.goal = goal;
        run(maxIterations, snapshot, playbook);
    }

    /** Active goal (null = the Plain-session seed). */
    String goal = null;

    /**
     * The goal-mode seed message. Playbook + snapshot + goal + the
     * decomposition directive the user specified verbatim.
     */
    static String goalSeed(String playbook, JsonObject snapshot, String goal) {
        JsonObject mem = memoryDigest();
        return "You are the HyFuse agent working a goal on a Minecraft server."
                + "\n\n## Playbook (system rules)\n" + (playbook == null ? "(none)": playbook)
                + "\n\n## Persistent memory\n" + (mem == null ? "{}": mem.toString())
                + "\n\n## Current state\n" + snapshot.toString()
                + "\n\n## Goal\n" + goal
                + "\n\n## Directive\n"
                + "Decompose this goal into a series of tasks. Work through them using tools."
                + " The final task must have the player return to a safe standby holding"
                + " position (well-lit, no hostile mobs nearby, not drowning)."
                + "\nMOVEMENT: travel is Baritone-only (goto-coords / navigate-v2) for"
                + " anything beyond ~4 blocks. Manual move-in-direction walking is"
                + " forbidden except ≤4-block adjustments — it is jerky, slow, and"
                + " burns iterations. Issue ONE goto, then wait on standing-status,"
                + " never step-by-step."
                + " When the goal is met and the player is at the standby position,"
                + " reply with a summary and no tool calls.";
    }

    /** One ask-parse-dispatch-respond round. Visible for the smoke suite. */
    public Iteration oneIteration(int index) throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("model", model);
        request.add("messages", messagesForApi(history));
        request.add("tools", toolsForApi());
        JsonObject response = JsonParser.parseString(
                transport.call(request)).getAsJsonObject();
        JsonArray choices = response.has("choices")
                ? response.getAsJsonArray("choices"): new JsonArray();
        if (choices.isEmpty()) throw new IllegalStateException("no choices in response");
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        JsonArray toolCalls = message.has("tool_calls")
                && message.get("tool_calls").isJsonArray()
                ? message.getAsJsonArray("tool_calls"): new JsonArray();
        // Record the assistant turn (including tool_calls) so the API sees
        // its own prior request when we return the results. Some lenient
        // endpoints omit "role" on the message — set it defensively.
        if (!message.has("role")) message.addProperty("role", "assistant");
        history.add(message);
        if (toolCalls.isEmpty()) {
            String text = message.has("content") && !message.get("content").isJsonNull()
                    ? message.get("content").getAsString(): "";
            return new Iteration(index, false, 0, text);
        }
        int executed = 0;
        for (var el : toolCalls) {
            if (stopRequested) break;
            JsonObject call = el.getAsJsonObject();
            String id = call.has("id") ? call.get("id").getAsString(): "call_" + index + "_" + executed;
            JsonObject fn = call.has("function") ? call.getAsJsonObject("function"): new JsonObject();
            String name = fn.has("name") ? fn.get("name").getAsString(): "";
            String argsJson = fn.has("arguments") ? fn.get("arguments").getAsString(): "{}";
            JsonObject args;
            try {
                args = JsonParser.parseString(argsJson).getAsJsonObject();
            } catch (Exception parse) {
                args = new JsonObject();
                args.addProperty("_parseError", argsJson);
            }
            JsonObject result = dispatchTool(name, args);
            toolCallsTotal++;
            executed++;
            JsonObject toolMsg = new JsonObject();
            toolMsg.addProperty("role", "tool");
            toolMsg.addProperty("tool_call_id", id);
            toolMsg.addProperty("content", GSON.toJson(result));
            history.add(toolMsg);
        }
        return new Iteration(index, true, executed, "");
    }

    /** Dispatch with the agent guard: never agent-start/agent-stop (§2). */
    JsonObject dispatchTool(String name, JsonObject args) {
        if ("agent-start".equals(name) || "agent-stop".equals(name)
                || "agent-status".equals(name)) {
            JsonObject refused = new JsonObject();
            refused.addProperty("ok", false);
            refused.addProperty("reason", "agent_tool_refused");
            refused.addProperty("message", "The agent loop may not control itself "
                    + "from model-initiated tool calls (playbook §2 authority model).");
            return refused;
        }
        try {
            return dispatch.apply(name, args).join();
        } catch (Throwable t) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("reason", "dispatch_error");
            err.addProperty("message", String.valueOf(t.getMessage()));
            return err;
        }
    }

    // ── Request shaping ──────────────────────────────────────────────────

    private String model = "default";

    public void setModel(String model) {
        if (model != null && !model.isBlank()) this.model = model;
    }

    /** Tools list for the API: name + description + JSON Schema params. */
    static JsonArray toolsForApi() {
        JsonArray tools = new JsonArray();
        for (McpToolRegistry.Tool tool : McpToolRegistry.tools()) {
            JsonObject t = new JsonObject();
            t.addProperty("type", "function");
            JsonObject fn = new JsonObject();
            fn.addProperty("name", tool.name());
            fn.addProperty("description", tool.description());
            fn.add("parameters", tool.inputSchema());
            t.add("function", fn);
            tools.add(t);
        }
        return tools;
    }

    /** history entries -> OpenAI messages (first user msg = playbook+snapshot). */
    static JsonArray messagesForApi(List<JsonObject> history) {
        JsonArray messages = new JsonArray();
        for (JsonObject m : history) {
            messages.add(m);
        }
        return messages;
    }

    static JsonObject userMessage(String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "user");
        m.addProperty("content", content);
        return m;
    }

    /**
     * Compact memory digest for the goal seed. Best-effort; null when
     * the store is unavailable (the seed then omits the memory section).
     */
    static JsonObject memoryDigest() {
        try {
            return com.hyfuse.bridge.HyFuseClient.memoryStore().summary();
        } catch (Throwable t) {
            return null;
        }
    }

    static String systemish(String playbook, JsonObject snapshot) {
        return playbook + "\n\n## Current state\n" + GSON.toJson(snapshot)
                + "\n\nYou are running as the embedded agent loop. Choose the "
                + "right tools to make progress toward the goal; when done, "
                + "reply with plain text (no tool calls) to end the loop.";
    }

    // ── Real transport (OpenAI-compatible HTTP) ──────────────────────────

    public static final class HttpTransport implements Transport {
        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        private final String url;
        private final String apiKey;

        public HttpTransport(String url, String apiKey) {
            this.url = url;
            this.apiKey = apiKey;
        }

        @Override
        public String call(JsonObject request) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(request)));
            if (apiKey != null && !apiKey.isBlank()) {
                b.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("LLM endpoint HTTP " + resp.statusCode());
            }
            return resp.body();
        }
    }
}