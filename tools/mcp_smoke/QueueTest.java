package com.hyfuse.bridge.dispatch;
import com.hyfuse.bridge.dispatch.QueueTelemetry;
import com.google.gson.JsonObject;

/**
 * Unit test: QueueTelemetry begin/markTask/end/snapshot state machine
 * (the get-current-action support). 10 checks.
 */
public class QueueTest {
    static int failures = 0;

    static void check(String label, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + label + (ok ? "" : " — " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        // 1. initial idle snapshot
        JsonObject idle = QueueTelemetry.snapshot();
        check("initial idle {active:false, elapsedMs:0}",
                !idle.get("active").getAsBoolean() && idle.get("elapsedMs").getAsInt() == 0,
                idle.toString());

        // 2. begin installs slot
        QueueTelemetry.begin("enqueue-tasks");
        JsonObject running = QueueTelemetry.snapshot();
        check("begin -> active, kind, id op-N, startedAt",
                running.get("active").getAsBoolean()
                        && "enqueue-tasks".equals(running.get("kind").getAsString())
                        && running.get("id").getAsString().startsWith("op-")
                        && running.get("startedAt").getAsLong() > 0,
                running.toString());

        // 3. deadline null (JsonNull)
        check("deadline is null (no hard op timeout)",
                running.has("deadline") && running.get("deadline").isJsonNull(),
                running.toString());

        // 4. elapsedMs >= 0 while running
        check("elapsedMs non-negative while running",
                running.get("elapsedMs").getAsLong() >= 0, running.toString());

        // 5. markTask updates queue detail + lastProgressAt
        long before = running.get("lastProgressAt").getAsLong();
        Thread.sleep(20);
        QueueTelemetry.markTask(0, "mine-blocks", 3, "gather");
        JsonObject marked = QueueTelemetry.snapshot();
        JsonObject detail = marked.getAsJsonObject("detail");
        check("markTask sets index/tool/total/name",
                detail.get("currentTaskIndex").getAsInt() == 0
                        && "mine-blocks".equals(detail.get("currentTaskTool").getAsString())
                        && detail.get("totalTasks").getAsInt() == 3
                        && "gather".equals(detail.get("currentTaskName").getAsString()),
                detail.toString());

        // 6. lastProgressAt advances
        check("lastProgressAt advances on markTask",
                marked.get("lastProgressAt").getAsLong() > before, marked.toString());

        // 7. empty task name omitted
        QueueTelemetry.markTask(1, "smelt-item", 3, "");
        JsonObject d2 = QueueTelemetry.snapshot().getAsJsonObject("detail");
        check("empty taskName omitted from detail",
                d2.has("currentTaskName") == false, d2.toString());

        // 8. second markTask overwrites
        check("second markTask overwrites index/tool",
                d2.get("currentTaskIndex").getAsInt() == 1
                        && "smelt-item".equals(d2.get("currentTaskTool").getAsString()),
                d2.toString());

        // 9. end clears the slot
        QueueTelemetry.end();
        JsonObject after = QueueTelemetry.snapshot();
        check("end -> idle again",
                !after.get("active").getAsBoolean() && after.get("elapsedMs").getAsInt() == 0,
                after.toString());

        // 10. stateVersion monotonic across the cycle (begin+marks+end >= 4 bumps)
        int v = QueueTelemetry.stateVersion();
        QueueTelemetry.begin("mine-blocks");
        QueueTelemetry.markTask(0, "mine-blocks", 1, null);
        QueueTelemetry.end();
        check("stateVersion monotonic (>= 3 more bumps)",
                QueueTelemetry.stateVersion() >= v + 3,
                QueueTelemetry.stateVersion() + " vs " + v);

        System.out.println(failures == 0 ? "\nALL CHECKS PASSED" : "\n" + failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
