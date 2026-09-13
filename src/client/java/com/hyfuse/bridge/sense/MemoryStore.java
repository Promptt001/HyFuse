package com.hyfuse.bridge.sense;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * MemoryStore — persistent memory across client restarts.
 *
 * A JSON-backed store of agent records (POIs, mob identities, container
 * baselines, misc notes) that survives client restarts. File lives at
 * {@code config/hyfuse_memory.json} (FabricLoader config dir) and is owned
 * EXCLUSIVELY by the mod. Read by the
 * memory-save / memory-read / memory-forget tool handlers and summarized in
 * get-agent-snapshot's {@code memory} block.
 *
 * Write discipline: serialize to {@code *.tmp} then
 * atomic move (write-then-move). A corrupt/undecodable file is renamed to
 * {@code hyfuse_memory.json.corrupt-<millis>} at load and the store starts
 * empty — never crash the client over memory.
 *
 * Bounds: 512 records max, notes capped at 4096 chars,
 * game-state data only.
 */
public final class MemoryStore {

    public static final int SCHEMA_VERSION = 1;
    static final int MAX_RECORDS = 512;
    static final int MAX_NOTE_LENGTH = 4096;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path file;
    private final Path tmpFile;
    private final LinkedHashMap<String, JsonObject> records = new LinkedHashMap<>();

    private volatile boolean loaded = false;

    public MemoryStore(Path file) {
        this.file = file;
        this.tmpFile = file.resolveSibling(file.getFileName() + ".tmp");
    }

    /** Loads the file once (idempotent). Missing file -> empty store. */
    public synchronized void loadIfNeeded() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(file)) return;
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonElement recordsEl = root.get("records");
            if (recordsEl != null && recordsEl.isJsonArray()) {
                for (JsonElement el : recordsEl.getAsJsonArray()) {
                    if (!el.isJsonObject()) continue;
                    JsonObject rec = el.getAsJsonObject();
                    JsonElement idEl = rec.get("id");
                    if (idEl == null || !idEl.isJsonPrimitive()) continue;
                    records.put(idEl.getAsString(), rec);
                    if (records.size() >= MAX_RECORDS) break;
                }
            }
        } catch (Throwable corrupt) {
            // Quarantine the unreadable file and start empty (never crash).
            try {
                Files.move(file, file.resolveSibling(file.getFileName()
                        + ".corrupt-" + System.currentTimeMillis()));
            } catch (IOException ignored) {
                // Quarantine failure is non-fatal too.
            }
            records.clear();
        }
    }

    /** Last-writer-wins upsert by record id. Returns the stored record. */
    public synchronized JsonObject upsert(JsonObject record) {
        loadIfNeeded();
        String id = record.get("id").getAsString();
        JsonObject stored = record.deepCopy();
        JsonElement noteEl = stored.get("note");
        if (noteEl != null && noteEl.isJsonPrimitive()
                && noteEl.getAsString().length() > MAX_NOTE_LENGTH) {
            stored.addProperty("note", noteEl.getAsString().substring(0, MAX_NOTE_LENGTH));
        }
        stored.addProperty("lastSeen", System.currentTimeMillis() / 1000L);
        records.put(id, stored);
        while (records.size() > MAX_RECORDS) {
            String oldest = records.keySet().iterator().next();
            records.remove(oldest);
        }
        persist();
        return stored.deepCopy();
    }

    /** Records filtered by kind (or all), id, or matching-position, in insertion order. */
    public synchronized List<JsonObject> query(String kind, String id, Integer x, Integer y, Integer z) {
        loadIfNeeded();
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject rec : records.values()) {
            if (id != null && !id.equals(stringOf(rec, "id"))) continue;
            if (kind != null && !kind.equals(stringOf(rec, "kind"))) continue;
            if (x != null || y != null || z != null) {
                JsonObject pos = objectOf(rec, "position");
                if (pos == null) continue;
                if (x != null && x != intOf(pos, "x")) continue;
                if (y != null && y != intOf(pos, "y")) continue;
                if (z != null && z != intOf(pos, "z")) continue;
            }
            out.add(rec.deepCopy());
        }
        return out;
    }

    /** Delete by id. Returns true when a record was removed. */
    public synchronized boolean forget(String id) {
        loadIfNeeded();
        boolean removed = records.remove(id) != null;
        if (removed) persist();
        return removed;
    }

    /** Compact summary for get-agent-snapshot's memory block. */
    public synchronized JsonObject summary() {
        loadIfNeeded();
        JsonObject summary = new JsonObject();
        summary.addProperty("schemaVersion", SCHEMA_VERSION);
        summary.addProperty("records", records.size());
        JsonObject kinds = new JsonObject();
        for (JsonObject rec : records.values()) {
            String kind = stringOf(rec, "kind");
            if (kind != null) kinds.addProperty(kind, kinds.has(kind)
                    ? kinds.get(kind).getAsInt() + 1 : 1);
        }
        summary.add("kinds", kinds);
        JsonArray ids = new JsonArray();
        for (JsonObject rec : records.values()) {
            String id = stringOf(rec, "id");
            if (id != null && ids.size() < 32) ids.add(id);
        }
        summary.add("ids", ids);
        return summary;
    }

    /** Serialize + atomic write-then-move. Best effort; never throws. */
    private void persist() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("schemaVersion", SCHEMA_VERSION);
            JsonArray arr = new JsonArray();
            for (JsonObject rec : records.values()) arr.add(rec.deepCopy());
            root.add("records", arr);
            Files.writeString(tmpFile, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            // Memory persistence is best-effort by /Q5 — a failed
            // write loses the last change, never the client.
        }
    }

    private static String stringOf(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private static Integer intOf(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            float f = el.getAsFloat();
            if (f == Math.rint(f)) return (int) f;
        }
        return null;
    }

    private static JsonObject objectOf(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }
}
