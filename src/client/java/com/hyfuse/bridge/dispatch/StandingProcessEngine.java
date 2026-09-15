package com.hyfuse.bridge.dispatch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hyfuse.bridge.sense.MemoryStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * StandingProcessEngine — supervisor for standing processes
 * (mine-and-deposit, guard).
 *
 * Supervisor + cycle serialization through the existing queue gate:
 * the supervisor thread NEVER drives
 * Baritone itself — each cycle is executed via the runner callback
 * (ToolDispatcher's enqueue rail: QUEUE_ACTIVE gate -> dispatchQueueTask
 * switch -> callOnClient marshalling), preserving the
 * one-Baritone-user-at-a-time invariant behind fixes 15-19. Ad-hoc tool
 * calls interleave at cycle boundaries; mid-cycle they surface
 * QUEUE_BUSY honestly.
 *
 * Lifecycle: RUNNING -> DONE (stop condition met) / PARKED (3
 * consecutive failed cycles — status explains why, never silently
 * spins) / STOPPED (explicit standing-stop) / INTERRUPTED (player death
 * or client restart — resume via standing-start, no auto-resume).
 *
 * Persistence (): definitions saved to the memory store as
 * kind "process" (id "process:<name>"); after restart they load back as
 * INTERRUPTED and only an explicit standing-start resumes (fresh cycle
 * counter). Recovery (): failed cycle backs off (interval x2,
 * capped x5); 3 consecutive failures -> PARK; death pauses. No reactive
 * policy inside the loop — the reactive PolicyEngine owns that.
 *
 * Framework-free (Gson + java.util only, QueueTelemetry pattern) so the
 * state machine is unit-testable offline.
 */
final class StandingProcessEngine {

    private StandingProcessEngine() {
    }

    /** Fixed process types (mine-and-deposit + standing guard). */
    static final List<String> TYPES =
            Collections.unmodifiableList(Arrays.asList("mine-and-deposit", "guard"));

    private static final int PARK_AFTER_CONSECUTIVE_FAILURES = 3;
    private static final int BACKOFF_CAP_MULTIPLIER = 5;
    private static final long DEFAULT_INTERVAL_MS = 5000L;
    private static final long STOP_WAIT_MS = 120000L;
    private static final long SUPERVISOR_POLL_MS = 500L;
    private static final long CYCLE_GATE_WAIT_MS = 30000L;

    /** Run one cycle's task list through the enqueue rail. */
    interface CycleRunner {
        JsonObject run(JsonArray tasks, String processName, String processType);
    }

    /**
     * J3-2 (T5.3-LIVE#2): mid-cycle interrupt hook. standing-stop sets this
     * while a cycle is executing; long-running legs (mine-blocks #mine poll,
     * goto-coords arrival poll, runQueueTaskList) check it between sleeps
     * and concede promptly, so a no-target cycle can no longer wedge the
     * single supervisor thread for minutes (live: op-113 ran >100s with
     * stop requests ineffective). Cleared by the engine before each cycle.
     */
    static volatile boolean cycleInterruptRequested = false;

    /** Probe player death (provider marshals the read to the client thread). */
    interface DeathProbe {
        boolean isDead();
    }

    /** Provide the memory store (provider hides HyFuseClient/Minecraft). */
    interface MemoryResolver {
        MemoryStore store();
    }

    private static final ExecutorService SUPERVISOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "hyfuse-standing-supervisor");
                t.setDaemon(true);
                return t;
            });
    private static final AtomicBoolean SUPERVISOR_ACTIVE = new AtomicBoolean(false);

    private static final Object REGISTRY_LOCK = new Object();
    private static final Map<String, ProcessState> REGISTRY = new LinkedHashMap<>();

    private static volatile CycleRunner cycleRunner;
    private static volatile DeathProbe deathProbe;
    private static volatile MemoryResolver memoryResolver;
    private static volatile boolean loadedFromMemory = false;

    /** Injected by ToolDispatcher (idempotent, safe to call repeatedly). */
    static void wire(CycleRunner runner, DeathProbe probe, MemoryResolver resolver,
            ChestLocator locator) {
        cycleRunner = runner;
        deathProbe = probe;
        memoryResolver = resolver;
        chestLocator = locator;
    }

    static boolean isWired() {
        return cycleRunner != null;
    }

    // ── Process state ───────────────────────────────────────────────

    static final class ProcessState {
        final String name;
        final String type;
        final JsonObject args;        // block/filter, countPerCycle, chest, guard radius, etc.
        final JsonObject stopWhen;    // {totalItems?, maxDurationMs?}
        final long intervalMs;        // base inter-cycle interval
        long startedAtMs;
        long lastCycleStartMs;
        long lastCycleEndMs;
        long nextRunAtMs;
        long stoppedAtMs;
        int cycles;
        int consecutiveFailures;
        int totalItems;               // cumulative deposited items (mine-and-deposit)
        String state;                 // RUNNING/DONE/PARKED/STOPPED/INTERRUPTED
        String lastError;
        String lastMessage;
        boolean stopRequested;        // graceful-stop latch, set by standing-stop
        JsonObject lastCycleResult;   // last cycle's queue result (audit)

        ProcessState(String name, String type, JsonObject args, JsonObject stopWhen, long intervalMs) {
            this.name = name;
            this.type = type;
            this.args = args == null ? new JsonObject() : args;
            this.stopWhen = stopWhen == null ? new JsonObject() : stopWhen;
            this.intervalMs = intervalMs > 0 ? intervalMs : DEFAULT_INTERVAL_MS;
            this.state = "RUNNING";
        }
    }

    // ── Public API (called from ToolDispatcher handlers) ────────────

    /** standing-start: create or resume a process. */
    static JsonObject start(String name, String type, JsonObject args, JsonObject stopWhen, long intervalMs) {
        loadFromMemoryIfNeeded();
        if (name == null || name.isBlank()) {
            return error("INVALID_ARGUMENT", "standing-start requires a non-empty 'name'");
        }
        if (name.contains(":") || name.contains(",")) {
            return error("INVALID_ARGUMENT", "process name cannot contain ':' or ','");
        }
        if (!TYPES.contains(type)) {
            return error("INVALID_ARGUMENT", "unknown process type '" + type + "' — supported: " + TYPES);
        }
        if (cycleRunner == null) {
            return error("NOT_WIRED", "standing processes not wired to the queue engine");
        }
        synchronized (REGISTRY_LOCK) {
            ProcessState existing = REGISTRY.get(name);
            if (existing != null && "RUNNING".equals(existing.state)) {
                return error("ALREADY_RUNNING", "process '" + name + "' is already running");
            }
            // Resume or fresh start: fresh cycle counter either way ().
            ProcessState p = new ProcessState(name, type,
                    args == null ? new JsonObject() : args,
                    stopWhen == null ? new JsonObject() : stopWhen,
                    intervalMs <= 0 ? DEFAULT_INTERVAL_MS : intervalMs);
            p.startedAtMs = System.currentTimeMillis();
            p.nextRunAtMs = p.startedAtMs;
            REGISTRY.put(name, p);
            persistDefinition(p);
            kickSupervisor();
        }
        return statusOf(name, true, "Standing process '" + name + "' (" + type + ") started");
    }

    /** standing-status {name?}: one process, or all (list folded in per ). */
    static JsonObject status(String name) {
        loadFromMemoryIfNeeded();
        if (name != null && !name.isBlank()) {
            return statusOf(name, true, null);
        }
        List<ProcessState> snapshot = copyAll();
        JsonArray list = new JsonArray();
        for (ProcessState p : snapshot) {
            list.add(statusJson(p));
        }
        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        r.add("processes", list);
        r.addProperty("count", list.size());
        r.addProperty("message", "Standing processes: " + list.size() + " defined");
        return r;
    }

    /** standing-stop {name, wait?}: graceful stop; state becomes STOPPED. */
    static JsonObject stop(String name, boolean wait) {
        loadFromMemoryIfNeeded();
        if (name == null || name.isBlank()) {
            return error("INVALID_ARGUMENT", "standing-stop requires a non-empty 'name'");
        }
        ProcessState p;
        synchronized (REGISTRY_LOCK) {
            p = REGISTRY.get(name);
            if (p == null) {
                return error("NOT_FOUND", "no process named '" + name + "'");
            }
            if ("STOPPED".equals(p.state) || "DONE".equals(p.state) || "PARKED".equals(p.state)) {
                JsonObject r = statusJson(p);
                r.addProperty("ok", true);
                r.addProperty("message", "process '" + name + "' already terminal: " + p.state);
                return r;
            }
            p.stopRequested = true; // supervisor honors it at the next boundary
            cycleInterruptRequested = true; // J3-2: running legs concede mid-cycle
        }
        if (wait) {
            long deadline = System.currentTimeMillis() + STOP_WAIT_MS;
            while (System.currentTimeMillis() < deadline) {
                String s = stateOf(name);
                if ("STOPPED".equals(s)) break;
                sleepQuiet(50);
            }
            if (!"STOPPED".equals(stateOf(name))) {
                return error("STOP_TIMEOUT",
                        "process '" + name + "' did not stop within " + STOP_WAIT_MS + "ms");
            }
        }
        return statusOf(name, true, null);
    }

    // ── Supervisor loop ─────────────────────────────────────────────

    private static void kickSupervisor() {
        if (SUPERVISOR_ACTIVE.compareAndSet(false, true)) {
            SUPERVISOR.execute(() -> {
                try {
                    supervisorLoop();
                } finally {
                    SUPERVISOR_ACTIVE.set(false);
                }
            });
        }
        // else: already active — the running loop picks the process up at
        // its next poll; no second thread is ever spawned.
    }

    private static void supervisorLoop() {
        while (true) {
            boolean anyRunning = false;
            ProcessState[] due = new ProcessState[0];
            synchronized (REGISTRY_LOCK) {
                long now = System.currentTimeMillis();
                List<ProcessState> dueList = new ArrayList<>();
                for (ProcessState p : REGISTRY.values()) {
                    if (!"RUNNING".equals(p.state)) {
                        continue;
                    }
                    if (p.stopRequested) {
                        p.state = "STOPPED";
                        p.stoppedAtMs = now;
                        persistDefinition(p);
                        continue;
                    }
                    anyRunning = true;
                    if (now >= p.nextRunAtMs) {
                        dueList.add(p);
                    }
                }
                if (!dueList.isEmpty()) {
                    due = dueList.toArray(new ProcessState[0]);
                }
            }

            for (ProcessState p : due) {
                runOneCycle(p);
            }

            if (!anyRunning) {
                return; // nothing RUNNING anywhere — supervisor exits; re-kicked on next start
            }
            if (due.length == 0) {
                sleepQuiet(SUPERVISOR_POLL_MS);
            }
        }
    }

    /** Execute one cycle of process p (on the supervisor thread). */
    private static void runOneCycle(ProcessState p) {
        DeathProbe probe = deathProbe;
        if (probe != null && probe.isDead()) {
            p.state = "INTERRUPTED"; // death pauses; resume via standing-start
            p.lastError = "player_died";
            p.lastMessage = "Cycle skipped: player died — process paused (resume with standing-start)";
            persistDefinition(p);
            return;
        }

        if ("guard".equals(p.type)) {
            // Guard cycle: the post is held by the guard-area cycle task
            // itself (bounded duration each cycle, then release). Nothing
            // to extract; cycles++ on completion below.
        }

        p.lastCycleStartMs = System.currentTimeMillis();
        cycleInterruptRequested = false; // J3-2: fresh latch each cycle
        JsonArray tasks = buildCycleTasks(p);
        if (tasks == null) {
            // Guard with no tasks (shouldn't happen) — treat as idle cycle.
            p.cycles++;
            p.lastCycleEndMs = System.currentTimeMillis();
            p.lastMessage = "guard cycle (post held)";
            p.nextRunAtMs = p.lastCycleEndMs + p.intervalMs;
            return;
        }

        JsonObject result = null;
        try {
            result = cycleRunner.run(tasks, p.name, p.type);
        } catch (Throwable t) {
            result = null;
        }
        p.lastCycleEndMs = System.currentTimeMillis();
        p.cycles++;
        p.lastCycleResult = result;

        boolean ok = result != null && result.has("ok") && result.get("ok").getAsBoolean();
        if (ok) {
            int deposited = extractDeposit(result);
            p.totalItems += deposited;
            p.consecutiveFailures = 0;
            p.nextRunAtMs = p.lastCycleEndMs + p.intervalMs;
            p.lastMessage = "cycle " + p.cycles + " ok" + (deposited > 0 ? " (+" + deposited + " items)": "");
            evaluateStopConditions(p);
        } else {
            String reason = result != null && result.has("reason") && result.get("reason").isJsonPrimitive()
                    ? result.get("reason").getAsString(): "cycle_failed";
            p.consecutiveFailures++;
            p.lastError = reason;
            int mult = Math.min(BACKOFF_CAP_MULTIPLIER,
                    (int) Math.pow(2, p.consecutiveFailures));
            p.nextRunAtMs = p.lastCycleEndMs + p.intervalMs * mult;
            p.lastMessage = "cycle " + p.cycles + " failed (" + reason + "), backoff x" + mult;
            if (p.consecutiveFailures >= PARK_AFTER_CONSECUTIVE_FAILURES) {
                p.state = "PARKED";
                p.lastMessage += " — PARKED after " + p.consecutiveFailures + " consecutive failures";
            }
        }
        persistDefinition(p);
    }

    /** stopWhen: {totalItems?, maxDurationMs?}. */
    private static void evaluateStopConditions(ProcessState p) {
        long now = System.currentTimeMillis();
        if (p.stopWhen.has("totalItems") && p.stopWhen.get("totalItems").isJsonPrimitive()) {
            int total = p.stopWhen.get("totalItems").getAsInt();
            if (p.totalItems >= total) {
                p.state = "DONE";
                p.lastMessage = "stop condition met: totalItems " + p.totalItems + " >= " + total;
                return;
            }
        }
        if (p.stopWhen.has("maxDurationMs") && p.stopWhen.get("maxDurationMs").isJsonPrimitive()) {
            long max = p.stopWhen.get("maxDurationMs").getAsLong();
            if (now - p.startedAtMs >= max) {
                p.state = "DONE";
                p.lastMessage = "stop condition met: duration " + (now - p.startedAtMs) + "ms >= " + max + "ms";
            }
        }
    }

    /** Sum "moved" from deposit-results of the cycle's queue result. */
    private static int extractDeposit(JsonObject result) {
        if (result == null || !result.has("results")) {
            return 0;
        }
        JsonArray results = result.getAsJsonArray("results");
        int total = 0;
        for (JsonElement el : results) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject t = el.getAsJsonObject();
            if (!t.has("result")) {
                continue;
            }
            JsonObject res = t.getAsJsonObject("result");
            if (res.has("moved") && res.has("reason")
                    && "ok".equals(res.get("reason").getAsString())) {
                total += res.get("moved").getAsInt();
            }
        }
        return total;
    }
    // ── Cycle builder ───────────────────────────────────────────────

    private static final long MAX_GUARD_CYCLE_MS = 120000L;
    private static final long MINE_CYCLE_MAX_DISTANCE = 512L;

    /**
     * Build the task list for one cycle of p.
     * mine-and-deposit: mine-blocks -> deposit-items (into base chest).
     * guard: guard-area (bounded cycle duration = interval, capped).
     */
    static JsonArray buildCycleTasks(ProcessState p) {
        if ("mine-and-deposit".equals(p.type)) {
            int perCycle = p.args.has("countPerCycle") && p.args.get("countPerCycle").isJsonPrimitive()
                    ? p.args.get("countPerCycle").getAsInt(): 8;
            JsonObject taskSpec = new JsonObject();
            taskSpec.addProperty("tool", "mine-blocks");
            JsonObject mineArgs = new JsonObject();
            if (p.args.has("block") && p.args.get("block").isJsonPrimitive()) {
                mineArgs.addProperty("block", p.args.get("block").getAsString());
            } else if (p.args.has("filter") && p.args.get("filter").isJsonArray()) {
                mineArgs.add("filter", p.args.get("filter"));
            } else {
                return null; // no mining target — cannot build a cycle
            }
            mineArgs.addProperty("count", perCycle);
            if (p.args.has("mode") && p.args.get("mode").isJsonPrimitive()) {
                mineArgs.addProperty("mode", p.args.get("mode").getAsString());
            }
            if (p.args.has("maxDistance") && p.args.get("maxDistance").isJsonPrimitive()) {
                mineArgs.addProperty("maxDistance", p.args.get("maxDistance").getAsInt());
            } else {
                mineArgs.addProperty("maxDistance", (int) MINE_CYCLE_MAX_DISTANCE);
            }
            taskSpec.add("args", mineArgs);
            taskSpec.addProperty("onFail", "continue");

            // Defect 1: navigate to the RESOLVED deposit chest by
            // explicit coords — the old get-to-block('chest') targeted the
            // NEAREST chest from the mine site (live: 20 cobble landed ~104
            // blocks away in the mineshaft chest). goto-coords is the new
            // arrival-gated queue goto. Resolution priority: process args'
            // chest x/y/z > container-record coords (nearest to args' anchor,
            // else nearest to player) > base POI > nearest known chest from
            // the CURRENT player position.
            JsonObject chestSpec = resolveDepositChest(p);
            JsonObject gotoSpec = new JsonObject();
            gotoSpec.addProperty("tool", "goto-coords");
            JsonObject gotoArgs = new JsonObject();
            gotoArgs.addProperty("x", chestSpec.get("x").getAsInt());
            gotoArgs.addProperty("y", chestSpec.get("y").getAsInt());
            gotoArgs.addProperty("z", chestSpec.get("z").getAsInt());
            gotoSpec.add("args", gotoArgs);
            gotoSpec.addProperty("onFail", "continue");

            JsonObject depositSpec = new JsonObject();
            depositSpec.addProperty("tool", "deposit-items");
            JsonObject depArgs = new JsonObject();
            if (p.args.has("item") && p.args.get("item").isJsonPrimitive()) {
                depArgs.addProperty("name", p.args.get("item").getAsString());
            } else if (p.args.has("block") && p.args.has("name") && p.args.get("name").isJsonPrimitive()) {
                depArgs.addProperty("name", p.args.get("name").getAsString());
            }
            // Defect 1: deposit into the resolved chest by coords —
            // containerName re-resolves to the NEAREST chest from wherever
            // the bot stands (the original defect).
            depArgs.addProperty("x", chestSpec.get("x").getAsInt());
            depArgs.addProperty("y", chestSpec.get("y").getAsInt());
            depArgs.addProperty("z", chestSpec.get("z").getAsInt());
            depArgs.addProperty("count", perCycle);
            depositSpec.add("args", depArgs);
            depositSpec.addProperty("onFail", "continue");

            JsonArray tasks = new JsonArray();
            tasks.add(taskSpec);
            tasks.add(gotoSpec);
            tasks.add(depositSpec);
            return tasks;
        }
        if ("guard".equals(p.type)) {
            JsonObject guardSpec = new JsonObject();
            guardSpec.addProperty("tool", "guard-area");
            JsonObject guardArgs = new JsonObject();
            if (p.args.has("x") && p.args.get("x").isJsonPrimitive()
                    && p.args.has("y") && p.args.get("y").isJsonPrimitive()
                    && p.args.has("z") && p.args.get("z").isJsonPrimitive()) {
                guardArgs.addProperty("x", p.args.get("x").getAsDouble());
                guardArgs.addProperty("y", p.args.get("y").getAsDouble());
                guardArgs.addProperty("z", p.args.get("z").getAsDouble());
            }
            if (p.args.has("radius") && p.args.get("radius").isJsonPrimitive()) {
                guardArgs.addProperty("radius", p.args.get("radius").getAsDouble());
            }
            long cycleMs = Math.min(p.intervalMs > 0 ? p.intervalMs : DEFAULT_INTERVAL_MS, MAX_GUARD_CYCLE_MS);
            guardArgs.addProperty("maxDurationMs", cycleMs);
            guardSpec.add("args", guardArgs);
            JsonArray tasks = new JsonArray();
            tasks.add(guardSpec);
            return tasks;
        }
        return null;
    }

    // ── Defect 1: deposit-chest resolution ────────────────────

    /**
     * Resolve the deposit chest to explicit coords for one cycle.
     * Priority: (1) explicit chest x/y/z in process args; (2) container
     * memory records — nearest to the args' anchor (chestAnchor/x/y/z in
     * args) when present, else nearest to the player's CURRENT position;
     * (3) the base POI; (4) nearest known chest scanned from the current
     * player position (fall-back only — new deployments should rely on
     * 1-3). Returns {x,y,z,source} for cycle-building and audit.
     */
    static JsonObject resolveDepositChest(ProcessState p) {
        JsonObject r = new JsonObject();
        int ax = Integer.MIN_VALUE, ay = 0, az = 0;
        boolean haveAnchor = false;
        if (p.args.has("chestAnchor")) {
            JsonObject a = p.args.getAsJsonObject("chestAnchor");
            if (a.has("x") && a.has("y") && a.has("z")) {
                ax = a.get("x").getAsInt(); ay = a.get("y").getAsInt(); az = a.get("z").getAsInt();
                haveAnchor = true;
            }
        } else if (p.args.has("chestX") && p.args.has("chestY") && p.args.has("chestZ")) {
            ax = p.args.get("chestX").getAsInt(); ay = p.args.get("chestY").getAsInt(); az = p.args.get("chestZ").getAsInt();
            haveAnchor = true;
        }
        if (haveAnchor) {
            r.addProperty("x", ax); r.addProperty("y", ay); r.addProperty("z", az);
            r.addProperty("source", "args");
            return r;
        }
        if (memoryResolver != null) {
            MemoryStore store = memoryResolver.store();
            if (store != null) {
                // Player position via client call is not available here
                // (engine is framework-free); anchor = process args'
                // anchor when present, else base POI, else first container.
                double anchorX = 0, anchorZ = 0;
                boolean anchored = false;
                java.util.List<JsonObject> pois = store.query("poi", null, null, null, null);
                for (JsonObject rec : pois) {
                    if (rec.has("id") && "base".equals(rec.getAsJsonPrimitive("id").getAsString())
                            && rec.has("position") && rec.get("position").isJsonObject()) {
                        JsonObject pos = rec.getAsJsonObject("position");
                        if (pos.has("x") && pos.has("z")) {
                            anchorX = pos.get("x").getAsDouble();
                            anchorZ = pos.get("z").getAsDouble();
                            anchored = true;
                        }
                    }
                }
                java.util.List<JsonObject> containers = store.query("container", null, null, null, null);
                if (!containers.isEmpty()) {
                    if (!anchored) {
                        // No base POI: take the first container record.
                        JsonObject pos0 = containers.get(0).getAsJsonObject("position");
                        r.addProperty("x", pos0.get("x").getAsInt());
                        r.addProperty("y", pos0.get("y").getAsInt());
                        r.addProperty("z", pos0.get("z").getAsInt());
                        r.addProperty("source", "memory-container");
                        return r;
                    }
                    // Nearest container record to the base POI (the base
                    // chest is the closest recorded container to base).
                    double best = Double.MAX_VALUE;
                    int bx = 0, by = 0, bz = 0;
                    for (JsonObject rec : containers) {
                        if (!rec.has("position") || !rec.get("position").isJsonObject()) continue;
                        JsonObject pos = rec.getAsJsonObject("position");
                        if (!pos.has("x") || !pos.has("y") || !pos.has("z")) continue;
                        double dx = pos.get("x").getAsDouble() - anchorX;
                        double dz = pos.get("z").getAsDouble() - anchorZ;
                        double d2 = dx * dx + dz * dz;
                        if (d2 < best) { best = d2; bx = pos.get("x").getAsInt(); by = pos.get("y").getAsInt(); bz = pos.get("z").getAsInt(); }
                    }
                    if (best < Double.MAX_VALUE) {
                        r.addProperty("x", bx); r.addProperty("y", by); r.addProperty("z", bz);
                        r.addProperty("source", "memory-container-nearest-base");
                        return r;
                    }
                }
                // Base POI present but no container records: use the base
                // POI itself (deposit-items' locateContainerBlock jiggles
                // y-1/y when the exact block isn't a container).
                if (anchored) {
                    for (JsonObject rec : pois) {
                        if (rec.has("id") && "base".equals(rec.getAsJsonPrimitive("id").getAsString())
                                && rec.has("position") && rec.get("position").isJsonObject()) {
                            JsonObject pos = rec.getAsJsonObject("position");
                            if (pos.has("x") && pos.has("y") && pos.has("z")) {
                                r.addProperty("x", pos.get("x").getAsInt());
                                r.addProperty("y", pos.get("y").getAsInt());
                                r.addProperty("z", pos.get("z").getAsInt());
                                r.addProperty("source", "base-poi");
                                return r;
                            }
                        }
                    }
                }
            }
        }
        // Fall-back (no memory): nearest known chest from the CURRENT
        // player position — via the runner rail. buildCycleTasks runs on
        // the supervisor thread (queue rail holds the Baritone gate), so a
        // client-thread scan here is safe (read-only, no Baritone).
        if (chestLocator != null) {
            int[] nearest = chestLocator.apply(p);
            if (nearest != null) {
                r.addProperty("x", nearest[0]); r.addProperty("y", nearest[1]); r.addProperty("z", nearest[2]);
                r.addProperty("source", "nearest-from-player");
                return r;
            }
        }
        // Last resort: assume base-relative chest at +0/-2 offsets won't be
        // right; return an invalid marker so deposit fails honestly.
        r.addProperty("x", 0); r.addProperty("y", -1); r.addProperty("z", 0);
        r.addProperty("source", "unresolved");
        return r;
    }

    /** Injected by ToolDispatcher: nearest chest from the current player. */
    interface ChestLocator { int[] apply(ProcessState p); }
    private static volatile ChestLocator chestLocator;

    // ── Status / registry helpers ───────────────────────────────────

    private static JsonObject statusOf(String name, boolean ok, String message) {
        ProcessState p = shallowCopy(name);
        if (p == null) {
            return error("NOT_FOUND", "no process named '" + name + "'");
        }
        JsonObject r = statusJson(p);
        r.addProperty("ok", ok);
        if (message != null) {
            r.addProperty("message", message);
        }
        return r;
    }

    private static JsonObject statusJson(ProcessState p) {
        JsonObject r = new JsonObject();
        r.addProperty("name", p.name);
        r.addProperty("type", p.type);
        r.addProperty("state", p.state);
        r.addProperty("cycles", p.cycles);
        r.addProperty("totalItems", p.totalItems);
        r.addProperty("consecutiveFailures", p.consecutiveFailures);
        r.addProperty("intervalMs", p.intervalMs);
        r.addProperty("startedAtMs", p.startedAtMs);
        r.addProperty("lastCycleEndMs", p.lastCycleEndMs);
        r.addProperty("nextRunAtMs", p.nextRunAtMs);
        r.addProperty("lastError", p.lastError == null ? "": p.lastError);
        r.addProperty("lastMessage", p.lastMessage == null ? "": p.lastMessage);
        // T5.3/J3-1: a RUNNING process with cycles==0 previously looked wedged
        // in standing-status (nextRunAtMs = startedAtMs, no lastMessage) while
        // its first cycle was merely queued behind the single-threaded rail
        // (T5.2: first cycle ~74s late). Surface the pending state explicitly
        // so operators/agents can tell "waiting for the rail" from "stuck".
        if ("RUNNING".equals(p.state) && p.cycles == 0) {
            r.addProperty("firstCyclePending", true);
            if (p.lastMessage == null || p.lastMessage.isEmpty()) {
                r.addProperty("lastMessage", "first cycle pending on the queue rail");
            }
        }
        r.add("args", p.args.deepCopy());
        r.add("stopWhen", p.stopWhen.deepCopy());
        return r;
    }

    /** Read-only shallow copy under the registry lock. */
    private static ProcessState shallowCopy(String name) {
        synchronized (REGISTRY_LOCK) {
            ProcessState src = REGISTRY.get(name);
            if (src == null) {
                return null;
            }
            ProcessState c = new ProcessState(src.name, src.type, src.args.deepCopy(),
                    src.stopWhen.deepCopy(), src.intervalMs);
            c.startedAtMs = src.startedAtMs;
            c.lastCycleStartMs = src.lastCycleStartMs;
            c.lastCycleEndMs = src.lastCycleEndMs;
            c.nextRunAtMs = src.nextRunAtMs;
            c.stoppedAtMs = src.stoppedAtMs;
            c.cycles = src.cycles;
            c.consecutiveFailures = src.consecutiveFailures;
            c.totalItems = src.totalItems;
            c.state = src.state;
            c.lastError = src.lastError;
            c.lastMessage = src.lastMessage;
            c.stopRequested = src.stopRequested;
            c.lastCycleResult = src.lastCycleResult;
            return c;
        }
    }

    private static List<ProcessState> copyAll() {
        synchronized (REGISTRY_LOCK) {
            List<ProcessState> out = new ArrayList<>();
            for (ProcessState src : REGISTRY.values()) {
                out.add(shallowCopy(src.name));
            }
            return out;
        }
    }

    private static String stateOf(String name) {
        synchronized (REGISTRY_LOCK) {
            ProcessState p = REGISTRY.get(name);
            return p == null ? "": p.state;
        }
    }

    // ── Persistence (memory store, kind "process") ──────────────────

    /** Persist the process definition + progress (best-effort). */
    private static void persistDefinition(ProcessState p) {
        MemoryResolver resolver = memoryResolver;
        if (resolver == null) {
            return;
        }
        try {
            JsonObject record = new JsonObject();
            record.addProperty("id", "process:" + p.name);
            record.addProperty("kind", "process");
            record.addProperty("note", "standing process " + p.type
                    + " — state " + p.state + ", cycles " + p.cycles
                    + ", items " + p.totalItems);
            JsonObject def = new JsonObject();
            def.addProperty("name", p.name);
            def.addProperty("type", p.type);
            def.add("args", p.args.deepCopy());
            def.add("stopWhen", p.stopWhen.deepCopy());
            def.addProperty("intervalMs", p.intervalMs);
            def.addProperty("state", p.state);
            def.addProperty("cycles", p.cycles);
            def.addProperty("totalItems", p.totalItems);
            def.addProperty("consecutiveFailures", p.consecutiveFailures);
            record.add("process", def);
            resolver.store().upsert(record);
        } catch (Throwable ignored) {
            // Best-effort: a failed persist loses the last status update,
            // never the client (MemoryStore design point).
        }
    }

    /** Load persisted definitions once; all load back as INTERRUPTED. */
    static synchronized void loadFromMemoryIfNeeded() {
        if (loadedFromMemory || memoryResolver == null) {
            return;
        }
        loadedFromMemory = true;
        try {
            List<JsonObject> records = memoryResolver.store().query("process", null, null, null, null);
            for (JsonObject rec : records) {
                if (rec == null || !rec.has("process") || !rec.get("process").isJsonObject()) {
                    continue;
                }
                JsonObject def = rec.getAsJsonObject("process");
                if (!def.has("name") || !def.has("type")) {
                    continue;
                }
                String name = def.get("name").getAsString();
                String type = def.get("type").getAsString();
                if (!TYPES.contains(type) || name.contains(":") || name.contains(",")) {
                    continue;
                }
                JsonObject args = def.has("args") && def.get("args").isJsonObject()
                        ? def.getAsJsonObject("args"): new JsonObject();
                JsonObject stopWhen = def.has("stopWhen") && def.get("stopWhen").isJsonObject()
                        ? def.getAsJsonObject("stopWhen"): new JsonObject();
                long interval = def.has("intervalMs") && def.get("intervalMs").isJsonPrimitive()
                        ? def.get("intervalMs").getAsLong(): DEFAULT_INTERVAL_MS;
                ProcessState p = new ProcessState(name, type, args, stopWhen, interval);
                p.state = "INTERRUPTED"; // never auto-resume ()
                p.lastMessage = "loaded from memory after restart — resume with standing-start";
                if (def.has("totalItems") && def.get("totalItems").isJsonPrimitive()) {
                    p.totalItems = def.get("totalItems").getAsInt();
                }
                if (def.has("cycles") && def.get("cycles").isJsonPrimitive()) {
                    p.cycles = def.get("cycles").getAsInt();
                }
                synchronized (REGISTRY_LOCK) {
                    REGISTRY.put(name, p);
                }
            }
        } catch (Throwable ignored) {
            // Best-effort load; registry starts empty on any failure.
        }
    }

    // ── Misc ────────────────────────────────────────────────────────

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static JsonObject error(String code, String message) {
        JsonObject r = new JsonObject();
        r.addProperty("ok", false);
        r.addProperty("error", code);
        r.addProperty("message", message);
        return r;
    }
}
