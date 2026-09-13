package com.hyfuse.bridge.dispatch;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * QueueTelemetry — get-current-action support.
 *
 * Minimal queue-state exposure for the get-current-action tool: a single-slot
 * operation snapshot mirroring the documented describeOperation() shape,
 * plus queue-runner detail (current task index/tool,
 * total tasks, named task). Single-actor by design: the queue worker
 * (QUEUE_EXECUTOR) is the only writer — begin() before the worker body,
 * markTask() per queue task, end() in the worker's finally. Readers call
 * snapshot() from get-current-action.
 *
 * Deliberately framework-free (Gson + java.util only) so the
 * begin/markTask/end/snapshot state machine is unit-testable offline — the
 * same design point across the package.
 */
final class QueueTelemetry {

    private QueueTelemetry() {
    }

    /** Monotonic per-mutation counter. */
    private static int stateVersion = 0;

    // Snapshot fields — written only under LOCK by the queue worker thread.
    private static boolean active = false;
    private static String kind = null; // "enqueue-tasks" or a standalone composite tool name
    private static String id = null; // "op-1", "op-2", … (stable for log correlation)
    private static long startedAt = 0L;  // epoch ms
    private static long lastProgressAt = 0L;

    // Queue detail (enqueue-tasks only; null/absent for standalone composites).
    private static int currentTaskIndex = -1;
    private static int totalTasks = -1;
    private static String currentTaskTool = null;
    private static String currentTaskName = null;

    private static final Object LOCK = new Object();

    /** Worker start: install a fresh operation slot. */
    static void begin(String tool) {
        synchronized (LOCK) {
            stateVersion++;
            id = "op-" + stateVersion;
            kind = tool;
            startedAt = System.currentTimeMillis();
            lastProgressAt = startedAt;
            active = true;
            currentTaskIndex = -1;
            totalTasks = -1;
            currentTaskTool = null;
            currentTaskName = null;
        }
    }

    /** Per-task progress mark inside enqueueTasks' loop. */
    static void markTask(int index, String tool, int total, String taskName) {
        synchronized (LOCK) {
            stateVersion++;
            currentTaskIndex = index;
            currentTaskTool = tool;
            totalTasks = total;
            currentTaskName = taskName == null || taskName.isEmpty() ? null : taskName;
            lastProgressAt = System.currentTimeMillis();
        }
    }

    /** Worker end (finally): clear the slot. The worker's own result/exception
     * already carries the outcome to the caller — no failure side-channel here. */
    static void end() {
        synchronized (LOCK) {
            stateVersion++;
            active = false;
            kind = null;
            id = null;
            startedAt = 0L;
            lastProgressAt = 0L;
            currentTaskIndex = -1;
            totalTasks = -1;
            currentTaskTool = null;
            currentTaskName = null;
        }
    }

    /** Monotonic state version (exposed for tests). */
    static int stateVersion() {
        synchronized (LOCK) {
            return stateVersion;
        }
    }

    /**
     * Read-only snapshot (the get-current-action result). Shape mirrors the legacy
     * describeOperation(): {active, kind, id, startedAt, deadline, lastProgressAt,
     * elapsedMs, detail}. deadline is always null here (the queue worker has no
     * hard per-operation timeout — the per-task handlers bound themselves);
     * detail carries the queue-runner state (null fields omitted, the legacy-style).
     */
    static JsonObject snapshot() {
        synchronized (LOCK) {
            JsonObject out = new JsonObject();
            if (!active) {
                out.addProperty("active", false);
                out.addProperty("elapsedMs", 0);
                return out;
            }
            long now = System.currentTimeMillis();
            out.addProperty("active", true);
            out.addProperty("kind", kind);
            out.addProperty("id", id);
            out.addProperty("startedAt", startedAt);
            out.add("deadline", JsonNull.INSTANCE);
            out.addProperty("lastProgressAt", lastProgressAt);
            out.addProperty("elapsedMs", now - startedAt);
            JsonObject detail = new JsonObject();
            if (totalTasks >= 0) detail.addProperty("totalTasks", totalTasks);
            if (currentTaskIndex >= 0) detail.addProperty("currentTaskIndex", currentTaskIndex);
            if (currentTaskTool != null) detail.addProperty("currentTaskTool", currentTaskTool);
            if (currentTaskName != null) detail.addProperty("currentTaskName", currentTaskName);
            out.add("detail", detail);
            return out;
        }
    }
}
