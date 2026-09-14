package com.hyfuse.bridge.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * AgentLoop smoke suite. No Minecraft, no network: a scripted
 * Transport stub answers with tool_calls on the first iteration and plain
 * text on the second, asserting the loop dispatches, appends tool results,
 * self-guard (agent-* refused), history shaping, and clean termination.
 *
 * Compile/run like the other suites (see run_suites.sh); main() exits
 * nonzero on any failure.
 */
public final class AgentLoopTest {

    static int failures = 0;

    static void check(String label, boolean ok, String detail) {
        System.out.println((ok ? "ok   " : "FAIL ") + label + (ok ? "" : " — " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        // 1. Self-guard: model-initiated agent-start/stop/status is refused.
        List<String> dispatched = new ArrayList<>();
        AgentLoop loop = new AgentLoop(
                (name, a) -> {
                    dispatched.add(name);
                    JsonObject ok = new JsonObject();
                    ok.addProperty("ok", true);
                    return CompletableFuture.completedFuture(ok);
                },
                null);
        JsonObject refused = loop.dispatchTool("agent-stop", new JsonObject());
        check("agent-tool self-guard refuses agent-stop", !refused.get("ok").getAsBoolean()
                && "agent_tool_refused".equals(refused.get("reason").getAsString()), refused.toString());
        JsonObject refused2 = loop.dispatchTool("agent-start", new JsonObject());
        check("agent-tool self-guard refuses agent-start", "agent_tool_refused"
                .equals(refused2.get("reason").getAsString()), refused2.toString());
        check("self-guard dispatched nothing", dispatched.isEmpty(), "dispatched: " + dispatched);

        // 2. dispatchTool forwards normal tools and captures errors honestly.
        loop.dispatchTool("get-world-time", new JsonObject());
        check("normal tool dispatched", dispatched.equals(List.of("get-world-time")),
                "dispatched: " + dispatched);

        // 3. toolsForApi shape: type=function, name/description/parameters.
        JsonArray tools = AgentLoop.toolsForApi();
        check("toolsForApi nonempty", tools.size() > 0, "size 0");
        boolean allWellFormed = true;
        for (var el : tools) {
            JsonObject t = el.getAsJsonObject();
            JsonObject fn = t.getAsJsonObject("function");
            if (!"function".equals(t.get("type").getAsString())
                    || !fn.has("name") || !fn.has("description")
                    || !fn.has("parameters")) allWellFormed = false;
        }
        check("toolsForApi entries well-formed", allWellFormed, "some entry malformed");

        // 3b. Agent toolset pin (T4.2): Ring-1 exposure is exactly the lean
        //     set from docs/AGENT_TOOLSET.md; Ring-2 is hidden unless its
        //     capability gate reads true. Default presence snapshot = empty
        //     -> Ring-2 hidden.
        Set<String> names = new HashSet<>();
        for (var el : tools) {
            names.add(el.getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
        }
        check("agent toolset size is Ring-1 only (24)", names.size() == 24,
                "size " + names.size());
        check("agent toolset excludes operator tools",
                !names.contains("agent-stop") && !names.contains("send-chat")
                        && !names.contains("navigate-v2") && !names.contains("move-in-direction"),
                "operator/steering tool leaked into agent set");
        check("agent toolset includes spine tools",
                names.contains("get-agent-snapshot") && names.contains("goto-coords")
                        && names.contains("craft-item") && names.contains("standing-start"),
                "spine tool missing");
        // Ring-2 gating: with an empty presence snapshot, gated tools hidden;
        // with worldCache=true, the gated sensing tools appear.
        AgentLoop.capabilityPresence = Map.of("worldCache", true);
        JsonArray gated = AgentLoop.toolsForApi();
        Set<String> gatedNames = new HashSet<>();
        for (var el : gated) {
            gatedNames.add(el.getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
        }
        check("worldCache=true admits scan-nearby-entities + find-ore-veins",
                gatedNames.contains("scan-nearby-entities") && gatedNames.contains("find-ore-veins")
                        && gatedNames.size() == 26, "gated size " + gatedNames.size());
        AgentLoop.capabilityPresence = Map.of(); // restore default

        // 4. Full two-iteration loop with a scripted transport.
        //    Iteration 0: respond with one tool_call (get-world-time).
        //    Iteration 1: respond with plain text -> loop ends.
        List<JsonObject> requests = new ArrayList<>();
        AgentLoop.Transport script = new AgentLoop.Transport() {
            int n = 0;
            @Override
            public String call(JsonObject request) {
                requests.add(request);
                JsonObject resp = new JsonObject();
                JsonArray choices = new JsonArray();
                JsonObject choice = new JsonObject();
                JsonObject message = new JsonObject();
                message.addProperty("role", "assistant");
                if (n == 0) {
                    JsonArray toolCalls = new JsonArray();
                    JsonObject call = new JsonObject();
                    call.addProperty("id", "call_1");
                    JsonObject fn = new JsonObject();
                    fn.addProperty("name", "get-world-time");
                    fn.addProperty("arguments", "{}");
                    call.add("function", fn);
                    toolCalls.add(call);
                    message.add("tool_calls", toolCalls);
                    message.addProperty("content", (String) null);
                } else {
                    message.addProperty("content", "all done");
                }
                choice.add("message", message);
                choices.add(choice);
                resp.add("choices", choices);
                n++;
                return resp.toString();
            }
        };
        AgentLoop loop2 = new AgentLoop(
                (name, a) -> {
                    JsonObject ok = new JsonObject();
                    ok.addProperty("ok", true);
                    ok.addProperty("tool", name);
                    return CompletableFuture.completedFuture(ok);
                },
                script);
        loop2.setModel("test-model");
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("health", 20);
        loop2.run(5, snapshot, "TEST PLAYBOOK");
        check("loop ran 2 iterations", loop2.iterations == 2, "iterations=" + loop2.iterations);
        check("1 tool call executed", loop2.toolCallsTotal == 1, "total=" + loop2.toolCallsTotal);
        check("final assistant text captured", "all done".equals(loop2.lastAssistant),
                loop2.lastAssistant);
        check("no loop error", loop2.lastError == null, String.valueOf(loop2.lastError));
        check("ACTIVE cleared after run", !AgentLoop.isActive(), "still active");
        check("2 requests made", requests.size() == 2, "requests=" + requests.size());
        JsonObject req0 = requests.get(0);
        check("request carries model", "test-model".equals(
                req0.get("model").getAsString()), req0.toString());
        check("request carries tools", req0.has("tools") && req0.getAsJsonArray("tools").size() > 0, "");
        JsonArray msgs0 = req0.getAsJsonArray("messages");
        check("request first message is user with playbook", "user".equals(
                msgs0.get(0).getAsJsonObject().get("role").getAsString())
                && msgs0.get(0).getAsJsonObject().get("content").getAsString()
                        .contains("TEST PLAYBOOK"), msgs0.get(0).toString());
        JsonArray msgs1 = requests.get(1).getAsJsonArray("messages");
        boolean sawToolResult = false;
        boolean sawAssistantToolCall = false;
        for (var m : msgs1) {
            JsonObject mo = m.getAsJsonObject();
            if ("tool".equals(mo.get("role").getAsString())) sawToolResult = true;
            if (mo.has("tool_calls")) sawAssistantToolCall = true;
        }
        check("second request carries assistant tool_call turn", sawAssistantToolCall, msgs1.toString());
        check("second request carries tool result", sawToolResult, msgs1.toString());

        // 5. Budget: maxIterations=1 stops after one iteration even with
        //    tool calls pending.
        AgentLoop loop3 = new AgentLoop(
                (name, a) -> CompletableFuture.completedFuture(new JsonObject()),
                new AgentLoop.Transport() {
                    @Override
                    public String call(JsonObject request) {
                        JsonObject resp = new JsonObject();
                        JsonArray choices = new JsonArray();
                        JsonObject choice = new JsonObject();
                        JsonObject message = new JsonObject();
                        JsonArray toolCalls = new JsonArray();
                        JsonObject call = new JsonObject();
                        call.addProperty("id", "c");
                        JsonObject fn = new JsonObject();
                        fn.addProperty("name", "get-weather");
                        fn.addProperty("arguments", "{}");
                        call.add("function", fn);
                        toolCalls.add(call);
                        message.add("tool_calls", toolCalls);
                        choice.add("message", message);
                        choices.add(choice);
                        resp.add("choices", choices);
                        return resp.toString();
                    }
                });
        loop3.run(1, new JsonObject(), "TEST PLAYBOOK");
        check("budget=1 halts after one iteration", loop3.iterations == 1, "" + loop3.iterations);

        // 6. Transport failure is honest: lastError set, loop ends cleanly.
        AgentLoop loop4 = new AgentLoop(
                (name, a) -> CompletableFuture.completedFuture(new JsonObject()),
                new AgentLoop.Transport() {
                    @Override
                    public String call(JsonObject request) throws Exception {
                        throw new IllegalStateException("connection refused");
                    }
                });
        loop4.run(3, new JsonObject(), "TEST PLAYBOOK");
        check("transport error captured", "connection refused".equals(loop4.lastError),
                String.valueOf(loop4.lastError));
        check("error loop still clears ACTIVE", !AgentLoop.isActive(), "still active");

        System.out.println(failures == 0
                ? "AgentLoopTest: ALL PASS"
                : "AgentLoopTest: " + failures + " FAILURE(S)");
        if ( failures > 0 ) System.exit(1);
    }
}