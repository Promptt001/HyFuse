package com.hyfuse.bridge.sense;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Death-cause/location memory (port of the legacy {@code DeathMemory}:
 * bounded ring of the last 8 deaths + derived causeCounts summary).
 *
 * The planner uses it to avoid returning to lethal coordinates and to
 * recognize repeated causes. Fed exclusively from {@link EventBuffer}'s
 * death callback (wired in HyFuseClient); read by the {@code get-last-death}
 * tool handler.
 */
public final class DeathMemory {

    private static final int MAX_DEATHS = 8;

    /** A single death record (JSON-serializable). */
    public static final class DeathRecord {
        public final int id;
        public final int x, y, z;
        public final String cause;      // 'drowning' | 'lava' | 'fall' | 'mob' | 'unknown' | ...
        public final String message;    // server death text, or null
        public final long timestamp;    // epoch millis

        DeathRecord(int id, int x, int y, int z, String cause, String message, long timestamp) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.cause = cause;
            this.message = message;
            this.timestamp = timestamp;
        }
    }

    private final ArrayDeque<DeathRecord> deaths = new ArrayDeque<>();
    private int nextId = 1;

    /** Record a death; drops the oldest when over capacity. Returns the record. */
    public synchronized DeathRecord record(int x, int y, int z, String cause, String message) {
        DeathRecord record = new DeathRecord(nextId++, x, y, z, cause, message, System.currentTimeMillis());
        deaths.addLast(record);
        while (deaths.size() > MAX_DEATHS) {
            deaths.pollFirst();
        }
        return record;
    }

    /** Most recent death, or null. */
    public synchronized DeathRecord last() {
        return deaths.peekLast();
    }

    /** Full history, oldest-first. */
    public synchronized List<DeathRecord> history() {
        return new ArrayList<>(deaths);
    }

    /** Number of stored deaths. */
    public synchronized int size() {
        return deaths.size();
    }

    /** Per-cause counts across the stored history. */
    public synchronized Map<String, Integer> causeCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (DeathRecord d : deaths) {
            counts.merge(d.cause, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Infer a death cause from the server death message (port of the legacy
     * {@code inferCauseFromMessage}): Minecraft death messages follow stable
     * patterns ("X drowned", "X tried to swim in lava", "X was slain by Z", …).
     * Best-effort; falls back to 'unknown'.
     */
    public static String inferCauseFromMessage(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        String m = message.toLowerCase();
        if (m.contains("drown")) return "drowning";
        if (m.contains("lava") || m.contains("magma")) return "lava";
        if (m.contains("fire") || m.contains("burned")) return "fire";
        if (m.contains("fell") || m.contains("hit the ground")) return "fall";
        if (m.contains("explosion") || m.contains("exploded") || m.contains("blown up")) return "explosion";
        if (m.contains("slain") || m.contains("shot") || m.contains("blasted")) return "mob";
        if (m.contains("starv")) return "starvation";
        if (m.contains("cactus") || m.contains("sweet berry")) return "hazard_block";
        if (m.contains("wither")) return "wither";
        if (m.contains("fell out of the world") || m.contains("void")) return "void";
        return "unknown";
    }
}
