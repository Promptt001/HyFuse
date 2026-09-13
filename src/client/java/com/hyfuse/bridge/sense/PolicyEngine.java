package com.hyfuse.bridge.sense;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import com.hyfuse.bridge.HyFuseClient;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PolicyEngine — the reactive policy layer.
 *
 * A tiny always-on engine that consumes EventBuffer events on the client
 * tick thread, matches them against user-editable policy rows stored
 * in MemoryStore (kind "policy"), and — on a match — hands a
 * {tool, args} action to the registered {@link ActionSink}. The sink
 * (wired in HyFuseClient) submits the action to the single-Baritone
 * queue rail via ToolDispatcher.runPolicyAction and returns a future
 * that never blocks the client thread. The engine NEVER dispatches
 * Baritone work itself.
 *
 * Policy row schema (MemoryStore record kind "policy"):
 * {id, kind:"policy", trigger:<eventType>, threshold?:<number>,
 * action:<toolName>, args?:<object>, cooldownMs?:<number>,
 * enabled:<boolean>}
 *
 * - trigger — EventBuffer event type: entityHurt, health, food,
 * oxygen, death, entitySpawn, weatherUpdate. For food/
 * health/entityHurt the event's `health` payload field
 * carries the current level (documented in get-events).
 * - threshold (optional) — fire only when the event value is at or
 * below the threshold (food/health/entityHurt levels).
 * For entitySpawn a threshold of 1 (or absent) fires on
 * any hostile sighting; >1 is not evaluated (honest skip
 * — the sampler emits one entitySpawn per mob type).
 * - action — a policy-rail tool name: any queue-rail tool plus the
 * survival reflexes eat-food / flee-from / escape-water
 * and toggle-meteor-module (ToolDispatcher.runPolicyAction
 * owns the allowlist; anything else audits as
 * unsupported_policy_action).
 * - args — JSON object passed to the action (e.g. {"minCount":18}
 * for eat-food, {"minDistance":16} for flee-from).
 * Placeholders {entity} {cause} {health} {message} are
 * substituted from the triggering event.
 * - cooldownMs — per-policy re-fire guard (default 15000; death
 * policies default to 5 minutes so a respawn loop can
 * never trip the server's 5-deaths/2min ban rule). A
 * queue_busy outcome rolls the cooldown back (the action
 * never ran).
 * - enabled — false rows are stored but never fire (the user
 * opt-in gate; aggressive policies ship disabled).
 *
 * Audit: every firing (and notable non-firing) lands in a bounded
 * in-memory ring (last 100: trigger, policy, action, result, ok,
 * timestamp) readable via policy-read.
 *
 * Concurrency: onEvent runs ONLY on the client thread (the EventBuffer
 * producer), so cooldown state needs no lock beyond ConcurrentHashMap
 * (the queue-busy rollback writes from the completing thread). The
 * engine deliberately holds NO monitor across MemoryStore calls —
 * policy-read (transport thread) takes store then engine locks, so an
 * inverted order here would deadlock.
 */
public final class PolicyEngine {

    /** Maximum audit entries retained. */
    private static final int MAX_AUDIT = 100;

    /** Default cooldown when a policy row omits one. */
    private static final long DEFAULT_COOLDOWN_MS = 15_000L;

    /** Long cooldown for the death trigger (never refire-loop respawns). */
    private static final long DEATH_COOLDOWN_MS = 300_000L;

    /** Cap on the audit `detail` payload (results can be verbose). */
    private static final int MAX_DETAIL = 300;

    /** An action handed by the engine to the sink (queue-rail dispatch). */
    public record Action(String policyId, String tool, JsonObject args) { }

    /**
     * Sink contract: submit the action to the queue rail and return a
     * future completed with the action's result JSON. MUST NOT block —
     * onEvent calls this on the client tick thread.
     */
    public interface ActionSink {
        CompletableFuture<JsonObject> execute(Action action);
    }

    private volatile ActionSink sink;
    private final ConcurrentHashMap<String, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final ArrayDeque<JsonObject> audit = new ArrayDeque<>();

    /** Wire the sink (called from HyFuseClient during boot, before events). */
    public void setSink(ActionSink sink) {
        this.sink = sink;
    }

    /** The audit ring as JSON (policy-read). Thread-safe via the audit monitor. */
    public JsonArray auditJson() {
        JsonArray out = new JsonArray();
        synchronized (audit) {
            for (JsonObject entry : audit) out.add(entry.deepCopy());
        }
        return out;
    }

    /** One entry of the audit ring: what fired and what happened. */
    private void auditFired(String trigger, String policyId, String action,
                            String reason, boolean ok, String detail) {
        JsonObject entry = new JsonObject();
        entry.addProperty("timestamp", System.currentTimeMillis());
        entry.addProperty("trigger", trigger);
        entry.addProperty("policy", policyId);
        entry.addProperty("action", action);
        entry.addProperty("fired", true);
        entry.addProperty("ok", ok);
        entry.addProperty("result", reason);
        if (detail != null) entry.addProperty("detail", detail);
        pushAudit(entry);
    }

    /** Audit a non-firing observation (disabled rows never reach here). */
    private void auditSkip(String trigger, String policyId, String why) {
        JsonObject entry = new JsonObject();
        entry.addProperty("timestamp", System.currentTimeMillis());
        entry.addProperty("trigger", trigger);
        entry.addProperty("policy", policyId);
        entry.addProperty("fired", false);
        entry.addProperty("result", why);
        pushAudit(entry);
    }

    private void pushAudit(JsonObject entry) {
        synchronized (audit) {
            audit.addLast(entry);
            while (audit.size() > MAX_AUDIT) audit.pollFirst();
        }
    }

    /**
     * Event consumer (client thread only — called by EventBuffer's
     * sampler via add()). Matches the event against enabled policy rows
     * and hands each match to the sink (all matches fire; cooldowns
     * serialize repeats). The death trigger is special-cased: auto-respawn
     * already auto-respawns on the sampler, so an action-less death
     * policy is audit-only.
     */
    public void onEvent(EventBuffer.Event event) {
        if (event == null || sink == null) return;
        try {
            List<JsonObject> rows = HyFuseClient.memoryStore().query("policy", null, null, null, null);
            long now = System.currentTimeMillis();
            for (JsonObject row : rows) {
                if (!boolOf(row, "enabled", false)) continue;
                String trigger = stringOf(row, "trigger");
                if (trigger == null || !trigger.equals(event.type)) continue;
                String id = stringOf(row, "id");
                if (id == null) id = "<policy>";

                if ("death".equals(event.type)) {
                    long cooldown = longOf(row, "cooldownMs", DEATH_COOLDOWN_MS);
                    long until = now + cooldown;
                    Long existing = cooldownUntil.putIfAbsent(id, until);
                    if (existing != null && now < existing) {
                        auditSkip(event.type, id, "cooldown");
                        continue;
                    }
                    String action = stringOf(row, "action");
                    if (action == null) {
                        // Audit-only death policy (the seed default): record
                        // the death's cause + message in the audit trail.
                        JsonObject detail = new JsonObject();
                        detail.addProperty("cause", event.cause);
                        detail.addProperty("message", event.message);
                        auditFired(event.type, id, "(audit-only)", "logged",
                                true, capDetail(detail.toString()));
                        continue;
                    }
                    fire(row, id, action, event, until);
                    continue;
                }

                // Threshold semantics: numeric-level triggers (food/health/
                // entityHurt) fire at-or-below; entitySpawn fires on any
                // sighting (threshold <= 1 or absent); other event types
                // with a threshold set skip honestly (no payload to test).
                JsonElement thresholdEl = row.get("threshold");
                if (thresholdEl != null && thresholdEl.isJsonPrimitive()
                        && thresholdEl.getAsJsonPrimitive().isNumber()) {
                    double threshold = thresholdEl.getAsDouble();
                    if (event.health != null) {
                        if (event.health > threshold) {
                            auditSkip(event.type, id, "threshold_not_met");
                            continue;
                        }
                    } else if ("entitySpawn".equals(event.type)) {
                        if (threshold > 1) {
                            auditSkip(event.type, id, "threshold_not_met");
                            continue;
                        }
                    } else {
                        auditSkip(event.type, id, "no_payload");
                        continue;
                    }
                }

                long cooldown = longOf(row, "cooldownMs", DEFAULT_COOLDOWN_MS);
                long until = now + cooldown;
                Long existing = cooldownUntil.putIfAbsent(id, until);
                if (existing != null && now < existing) {
                    auditSkip(event.type, id, "cooldown");
                    continue;
                }
                String action = stringOf(row, "action");
                if (action == null) {
                    auditSkip(event.type, id, "no_action");
                    continue;
                }
                fire(row, id, action, event, until);
            }
        } catch (Throwable t) {
            // The policy layer must never take the client down with it.
            auditSkip(event.type, "<policy>", "error:" + t.getClass().getSimpleName());
        }
    }

    /**
     * Substitute {entity}/{cause}/{health}/{message} into the row's args
     * and hand the action to the sink. The audit entry lands when the
     * action completes (queue worker thread); a queue_busy result rolls
     * the cooldown back so a busy rail doesn't mute the policy for a
     * full cooldown window.
     */
    private void fire(JsonObject row, String id, String action,
                      EventBuffer.Event event, long cooldownUntilValue) {
        JsonElement argsEl = row.get("args");
        JsonObject substituted = new JsonObject();
        if (argsEl != null && argsEl.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : argsEl.getAsJsonObject().entrySet()) {
                substituted.add(e.getKey(), substituteValue(e.getValue(), event));
            }
        }
        CompletableFuture<JsonObject> future;
        try {
            future = sink.execute(new Action(id, action, substituted));
        } catch (Throwable t) {
            future = CompletableFuture.completedFuture(errorJson(t));
        }
        future.whenComplete((result, err) -> {
            JsonObject res = err != null ? errorJson(err)
                    : (result != null ? result : new JsonObject());
            boolean ok = res.has("ok") && res.get("ok").isJsonPrimitive()
                    && res.get("ok").getAsBoolean();
            String reason = res.has("reason") && res.get("reason").isJsonPrimitive()
                    ? res.get("reason").getAsString(): (ok ? "done": "failed");
            if ("queue_busy".equals(reason)) {
                // The action never ran — un-mute the policy.
                cooldownUntil.remove(id, cooldownUntilValue);
            }
            auditFired(event.type, id, action, reason, ok, capDetail(res.toString()));
        });
    }

    /** Substitute placeholders in a string value (recursing into containers). */
    private static JsonElement substituteValue(JsonElement value, EventBuffer.Event event) {
        if (value == null) return JsonNull.INSTANCE;
        if (value.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> e : value.getAsJsonObject().entrySet()) {
                out.add(e.getKey(), substituteValue(e.getValue(), event));
            }
            return out;
        }
        if (value.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement el : value.getAsJsonArray()) {
                out.add(substituteValue(el, event));
            }
            return out;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String s = value.getAsString()
.replace("{entity}", event.entity == null ? "": event.entity)
.replace("{cause}", event.cause == null ? "": event.cause)
.replace("{message}", event.message == null ? "": event.message)
.replace("{health}", event.health == null ? "": String.valueOf(event.health));
            return new JsonPrimitive(s);
        }
        return value;
    }

    private static JsonObject errorJson(Throwable t) {
        JsonObject r = new JsonObject();
        r.addProperty("ok", false);
        r.addProperty("reason", "error");
        r.addProperty("error", t.getMessage() == null
                ? t.getClass().getSimpleName() : t.getMessage());
        return r;
    }

    private static String capDetail(String s) {
        if (s == null) return null;
        return s.length() <= MAX_DETAIL ? s: s.substring(0, MAX_DETAIL) + "…";
    }

    // ── JSON helpers (null-safe) ─────────────────────────────────────────

    private static String stringOf(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private static boolean boolOf(JsonObject obj, String key, boolean fallback) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()
                ? el.getAsBoolean() : fallback;
    }

    private static long longOf(JsonObject obj, String key, long fallback) {
        JsonElement el = obj.get(key);
        if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            return el.getAsLong();
        }
        return fallback;
    }
}
