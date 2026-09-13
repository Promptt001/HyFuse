package com.hyfuse.bridge.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Smoke suite — goal mode + goal session supervisor + command core.
 * No Minecraft, no network: scripted Transport stubs and pure-function
 * checks (HyFuseCommandCore.execute runs its Minecraft-free parse paths;
 * Minecraft-touching set-paths are guarded by reflection-absent catches).
 *
 * Compile/run like the other suites (see run_suites.sh); main() exits
 * nonzero on any failure.
 */
public final class AgentGoalTest {

    static int failures = 0;

    static void check(String label, boolean ok, String detail) {
        System.out.println((ok ? "ok   " : "FAIL ") + label + (ok ? "" : " — " + detail));
        if (!ok) failures++;
    }

    /** Scripted transport: tool_calls on iteration 0, plain text after. */
    static AgentLoop.Transport scripted(int toolCallsFirstRound) {
        return new AgentLoop.Transport() {
            int n = 0;
            @Override
            public String call(JsonObject request) {
                JsonObject resp = new JsonObject();
                JsonArray choices = new JsonArray();
                JsonObject choice = new JsonObject();
                JsonObject message = new JsonObject();
                message.addProperty("role", "assistant");
                if (n == 0) {
                    JsonArray toolCalls = new JsonArray();
                    for (int i = 0; i < toolCallsFirstRound; i++) {
                        JsonObject call = new JsonObject();
                        call.addProperty("id", "call_" + i);
                        JsonObject fn = new JsonObject();
                        fn.addProperty("name", "get-world-time");
                        fn.addProperty("arguments", "{}");
                        call.add("function", fn);
                        toolCalls.add(call);
                    }
                    message.add("tool_calls", toolCalls);
                    message.addProperty("content", (String) null);
                } else {
                    message.addProperty("content", "goal met: all tasks done, standing by safely");
                }
                choice.add("message", message);
                choices.add(choice);
                resp.add("choices", choices);
                n++;
                return resp.toString();
            }
        };
    }

    public static void main(String[] args) {
        // 1. Goal-mode seed: run(goal) shapes a goal seed with the
        //    decomposition directive + goal text.
        List<JsonObject> requests = new ArrayList<>();
        AgentLoop loop = new AgentLoop(
                (name, a) -> {
                    JsonObject ok = new JsonObject();
                    ok.addProperty("ok", true);
                    return CompletableFuture.completedFuture(ok);
                },
                capture(scripted(1), requests));
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("health", 20);
        loop.run(5, snapshot, "TEST PLAYBOOK", "get iron ingots");
        check("goal run terminates with summary", loop.iterations == 2, "" + loop.iterations);
        check("goal run executed tool call", loop.toolCallsTotal == 1, "" + loop.toolCallsTotal);
        JsonObject firstMsg = requests.get(0).getAsJsonArray("messages")
                .get(0).getAsJsonObject();
        String content = firstMsg.get("content").getAsString();
        check("seed carries playbook", content.contains("TEST PLAYBOOK"), "no playbook");
        check("seed carries goal text", content.contains("get iron ingots"), "no goal");
        check("seed carries decomposition directive",
                content.contains("Decompose this goal into a series of tasks"),
                "no directive");
        check("seed carries standby requirement",
                content.contains("safe standby holding position"), "no standby");
        check("seed carries current state", content.contains("\"health\":20"), "no snapshot");

        // 2. Plain run (no goal) still uses the standard seed shape.
        List<JsonObject> requests2 = new ArrayList<>();
        AgentLoop loop2 = new AgentLoop(
                (name, a) -> CompletableFuture.completedFuture(new JsonObject()),
                capture(scripted(1), requests2));
        loop2.run(5, new JsonObject(), "TEST PLAYBOOK");
        String content2 = requests2.get(0).getAsJsonArray("messages")
                .get(0).getAsJsonObject().get("content").getAsString();
        check("plain run keeps systemish seed (no goal directive)",
                !content2.contains("Decompose this goal"), "goal seed leaked into plain run");
        check("plain run carries playbook", content2.contains("TEST PLAYBOOK"), "no playbook");

        // 3. AgentLoop.goalSeed is a pure function — directives pinned.
        String seed = AgentLoop.goalSeed("PB", new JsonObject(), "mine 10 coal");
        check("goalSeed directive pinned", seed.contains("Decompose this goal into a series of tasks"), "");
        check("goalSeed final-task pinned", seed.contains("The final task must have the player return to a safe standby holding"), "");

        // 4. AgentMode row definitions are correct trigger/threshold/action
        //    shapes (pure switch checks — no Minecraft needed).
        check("AgentMode reflect rows count", AgentMode.REFLECT_ROW_COUNT() == 3, "");

        // 5. GoalSession constants + pure helpers.
        check("GoalSession session cap", GoalSession.MAX_SESSIONS() == 5, "");
        check("GoalSession truncate", GoalSession.truncate("abcdefgh", 5).length() == 5, "");

        // 6. HyFuseCommandCore pure routing: usage + unknown subcommand +
        //    status-path parse (status itself touches Minecraft refs — the
        //    parse-only routes are checked).
        String usage = com.hyfuse.bridge.agent.HyFuseCommandCore.execute("");
        check("empty args -> usage", usage.contains("/hyfuse status"), usage);
        String unknown = com.hyfuse.bridge.agent.HyFuseCommandCore.execute("frobnicate x");
        check("unknown subcommand reported", unknown.contains("Unknown subcommand"), unknown);
        String setEmpty = com.hyfuse.bridge.agent.HyFuseCommandCore.execute("set");
        check("bare set gives guidance", setEmpty.contains("set what?"), setEmpty);
        String resetEmpty = com.hyfuse.bridge.agent.HyFuseCommandCore.execute("reset");
        check("bare reset gives guidance", resetEmpty.contains("reset what?"), resetEmpty);
        String badSetting = com.hyfuse.bridge.agent.HyFuseCommandCore.execute("set volume 5");
        check("unknown setting rejected", badSetting.contains("Unknown setting"), badSetting);
        String badReset = com.hyfuse.bridge.agent.HyFuseCommandCore.execute("reset frobnicate");
        check("unknown reset target rejected", badReset.contains("Unknown reset target"), badReset);
        String badMode = com.hyfuse.bridge.agent.HyFuseCommandCore.execute("set agent_mode maybe");
        check("bad agent_mode value rejected", badMode.contains("Usage: /hyfuse set agent_mode"), badMode);

        System.out.println(failures == 0
                ? "AgentGoalTest: ALL PASS"
                : "AgentGoalTest: " + failures + " FAILURE(S)");
        if (failures > 0) System.exit(1);
    }

    /** Wrap a transport to capture each request for seed assertions. */
    static AgentLoop.Transport capture(AgentLoop.Transport inner, List<JsonObject> sink) {
        return request -> {
            sink.add(JsonParser.parseString(request.toString()).getAsJsonObject());
            return inner.call(request);
        };
    }
}
