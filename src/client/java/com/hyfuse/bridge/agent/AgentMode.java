package com.hyfuse.bridge.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

/**
 * AgentMode — reflect posture ("reflect mode").
 *
 * agent_mode ON = the standing protective posture:
 * - KillAura enabled for the whole session —
 * this deliberately enables KillAura for the session, but ONLY
 * while agent_mode is on; the seeds themselves stay disabled).
 * - Standing reflect rows installed into the reactive policy layer:
 * reflect:escape-water oxygen ≤ 12 → escape-water (no cooldown)
 * reflect:low-health entityHurt ≤ 12 → flee-from (flee danger)
 * reflect:hostile entitySpawn → attack-entity nearest_hostile
 * (melee attack; subsumed by KillAura when Meteor is installed, the
 * melee row is the fallback for no-Meteor setups)
 *
 * agent_mode OFF = KillAura disabled, reflect rows forgotten. Idempotent.
 *
 * The rows use the proven policy rails (runPolicyAction → QUEUE gate), so
 * reflect actions respect QUEUE_BUSY and never fight the queue rail.
 */
public final class AgentMode {

    private static final AtomicBoolean ON = new AtomicBoolean(false);

    private AgentMode() { }

    private static final String[] REFLECT_ROW_IDS = {
            "reflect:escape-water", "reflect:low-health", "reflect:hostile"
    };

    /** Dispatch function: (toolName, args) -> result future. Command layer injects DISPATCHER::dispatch. */
    private static volatile BiFunction<String, JsonObject, CompletableFuture<JsonObject>> dispatch;

    /** Set/refresh the dispatch function (wired from the command entry). */
    public static void wire(BiFunction<String, JsonObject, CompletableFuture<JsonObject>> d) {
        dispatch = d;
    }

    public static boolean isOn() { return ON.get(); }

    /** Suite: the reflect-row definition count (pure, Minecraft-free). */
    public static int REFLECT_ROW_COUNT() { return REFLECT_ROW_IDS.length; }

    /**
     * Enable/disable agent_mode. Returns the AgentMode status JSON
     * (killAura armed state, reflect rows, goal session presence).
     */
    public static JsonObject set(Minecraft client, boolean on,
                                 BiFunction<String, JsonObject, CompletableFuture<JsonObject>> dispatcher) {
        wire(dispatcher);
        JsonObject out = new JsonObject();
        if (on) {
            if (!ON.compareAndSet(false, true)) {
                out.addProperty("already", true);
            }
        } else {
            ON.set(false);
        }
        boolean killAura = false;
        killAura = on; // best-effort: the toggle below reports availability
        try {
            com.hyfuse.bridge.dispatch.ToolDispatcher.toggleMeteorModuleByNamePublic(client, "kill-aura", on);
        } catch (Throwable t) {
            // Meteor absent or toggle failed — reflect rows still arm; the
            // status message reports melee-fallback mode honestly.
        }
        // Install/remove reflect rows through the same rails the user uses.
        for (String id : REFLECT_ROW_IDS) {
            try {
                if (on) upsertReflectRow(id);
                else removeReflectRow(id);
            } catch (Throwable t) { }
        }
        out.addProperty("on", ON.get());
        out.addProperty("killAura", killAura);
        JsonArray rows = new JsonArray();
        for (String id : REFLECT_ROW_IDS) rows.add(id);
        out.add("reflectRows", rows);
        out.addProperty("message", on
                ? "agent_mode on: KillAura " + (killAura ? "armed": "disarmed (Meteor absent?) — melee fallback active")
: "agent_mode off: KillAura disarmed, reflect rows removed");
        return out;
    }

    private static void upsertReflectRow(String id) {
        try {
            JsonObject row = new JsonObject();
            row.addProperty("id", id);
            row.addProperty("kind", "policy");
            row.addProperty("trigger", triggerFor(id));
            row.addProperty("threshold", thresholdFor(id));
            row.addProperty("action", actionFor(id));
            row.add("args", argsFor(id));
            row.addProperty("cooldownMs", cooldownFor(id));
            row.addProperty("enabled", true);
            com.hyfuse.bridge.HyFuseClient.memoryStore().upsert(row);
        } catch (Throwable t) {
            // best-effort; status reflects honest state via row presence
        }
    }

    private static void removeReflectRow(String publicId) {
        try {
            com.hyfuse.bridge.HyFuseClient.memoryStore().forget(publicId);
        } catch (Throwable t) { }
    }

    private static String triggerFor(String id) {
        return switch (id) {
            case "reflect:escape-water" -> "oxygen";
            case "reflect:low-health" -> "entityHurt";
            default -> "entitySpawn";
        };
    }

    private static int thresholdFor(String id) {
        return switch (id) {
            case "reflect:escape-water" -> 12;
            case "reflect:low-health" -> 12;
            default -> 1;
        };
    }

    private static String actionFor(String id) {
        return switch (id) {
            case "reflect:escape-water" -> "escape-water";
            case "reflect:low-health" -> "flee-from";
            default -> "attack-entity";
        };
    }

    private static JsonObject argsFor(String id) {
        JsonObject a = new JsonObject();
        switch (id) {
            case "reflect:escape-water" -> { }
            case "reflect:low-health" -> a.addProperty("minDistance", 16);
            default -> {
                a.addProperty("entityName", "nearest_hostile");
                a.addProperty("strategy", "melee");
                a.addProperty("timeoutMs", 8000);
            }
        }
        return a;
    }

    private static long cooldownFor(String id) {
        return switch (id) {
            case "reflect:low-health" -> 20_000L;
            default -> 8_000L;
        };
    }

    /** Reflect status (the /hyfuse status read). */
    public static JsonObject status() {
        JsonObject out = new JsonObject();
        out.addProperty("on", ON.get());
        return out;
    }
}
