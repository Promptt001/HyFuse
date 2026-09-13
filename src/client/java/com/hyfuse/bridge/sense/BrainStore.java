package com.hyfuse.bridge.sense;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The two-layer brain plumbing.
 *
 * Serves the server-agnostic playbook (INTELLIGENCE.md — read-only, runtime
 * read from the config dir so the user can edit between releases without
 * a redeploy), the per-server markdown journal (100 KB hard cap, user-
 * specified), and routes MemoryStore instances per server-id.
 *
 * server-id = sanitized current-server address (ServerData.ip via
 * Minecraft.getCurrentServer()). Null/blank or singleplayer → null → the
 * legacy global store (config/hyfuse_memory.json) remains authoritative.
 *
 * Policy rows (kind "policy",, LIVE-ACCEPTED) are server-agnostic
 * rails and ALWAYS live in the global store — never the per-server split.
 *
 * Copy-on-first-connect: when a per-server JSON store
 * file does not exist yet, it is seeded once from the global store's
 * game-state records (kind != policy). The global file is never touched.
 *
 * Write discipline as MemoryStore: tmp-then-atomic-move, never crash the
 * client over memory, LF-only writes.
 */
public final class BrainStore {

    /** User-specified hard cap for a per-server journal. */
    public static final int JOURNAL_MAX_BYTES = 100 * 1024;
    /** Hard cap on a single journal entry (append text). */
    public static final int JOURNAL_ENTRY_MAX_CHARS = 2000;
    /** Sections in the journal (memory template §1-§10). */
    static final String[] JOURNAL_SECTIONS = {
            "Identity & goal", "Server facts", "Base & shelter",
            "Containers & storage map", "Key locations (POIs)", "Mobs & threats",
            "Inventory & equipment baseline", "Plans & designs",
            "Chat & event log (rolling, newest first)", "History (completed, prune when over cap)"
    };

    private static final Map<String, MemoryStore> SERVER_STORES = new LinkedHashMap<>();
    private static final Object STORE_LOCK = new Object();

    private final Path configDir;
    private final MemoryStore globalStore;

    /** Production constructor: FabricLoader config dir + the global store. */
    public BrainStore(Path configDir, MemoryStore globalStore) {
        this.configDir = configDir;
        this.globalStore = globalStore;
    }

    // ── server-id ────────────────────────────────────────────────────────

    /**
     * Current server-id (sanitized address) or null when disconnected or in
     * singleplayer (the global store/journal applies then).
     */
    public static String serverId(net.minecraft.client.Minecraft client) {
        try {
            if (client == null || client.hasSingleplayerServer()
                    || client.getCurrentServer() == null) {
                return null;
            }
            String ip = client.getCurrentServer().ip;
            return ip == null ? null : sanitizeServerId(ip);
        } catch (Throwable t) {
            return null; // never crash over an id resolution
        }
    }

    /** [A-Za-z0-9.-] kept (colon port separator → '-'), lowercase, ≤64 chars. */
    static String sanitizeServerId(String raw) {
        String cleaned = raw.trim().toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cleaned.length() && sb.length() < 64; i++) {
            char c = cleaned.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-') {
                sb.append(c);
            } else if (c == ':') {
                sb.append('-');
            }
            // anything else dropped
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ── per-server JSON stores ───────────────────────────────────────────

    /**
     * The MemoryStore for the given server-id, or the global store when the
     * id is null (disconnected/singleplayer). Copy-on-first-connect: a fresh
     * per-server file is seeded once from the global store's game-state
     * records (kind != policy).
     */
    public MemoryStore storeFor(String serverId) {
        if (serverId == null) {
            return globalStore;
        }
        synchronized (STORE_LOCK) {
            MemoryStore existing = SERVER_STORES.get(serverId);
            if (existing != null) {
                existing.loadIfNeeded();
                return existing;
            }
            Path file = configDir.resolve("hyfuse_memory_" + serverId + ".json");
            boolean fresh = !Files.exists(file);
            MemoryStore store = new MemoryStore(file);
            if (fresh) {
                seedFromGlobal(store);
            }
            store.loadIfNeeded();
            SERVER_STORES.put(serverId, store);
            return store;
        }
    }

    /** One-time seed of game-state records (never policy rows) from global. */
    private void seedFromGlobal(MemoryStore target) {
        try {
            globalStore.loadIfNeeded();
            for (JsonObject rec : globalStore.query(null, null, null, null, null)) {
                JsonElement kindEl = rec.get("kind");
                if (kindEl != null && kindEl.isJsonPrimitive()
                        && "policy".equals(kindEl.getAsString())) {
                    continue; // policies are global rails — never copied
                }
                target.upsert(rec);
            }
        } catch (Throwable t) {
            // Seeding is best-effort: a failed seed leaves an empty store.
        }
    }

    /**
     * Forget the in-memory per-server store entry (after its file was
     * deleted on disk). The next storeFor() re-creates it fresh (seeded from
     * the global store by the normal copy-on-first-connect path).
     */
    public void clearServerStore(String serverId) {
        if (serverId == null) return;
        synchronized (STORE_LOCK) {
            SERVER_STORES.remove(serverId);
        }
    }

    /** Delete the per-server journal file (true when it existed). */
    public boolean clearJournal(String serverId) {
        if (serverId == null) return false;
        try {
            java.nio.file.Path file = journalFile(serverId);
            if (!java.nio.file.Files.exists(file)) return false;
            java.nio.file.Files.delete(file);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Size in bytes of the per-server JSON store file (0 when absent). */
    public long storeSize(String serverId) {
        if (serverId == null) {
            return 0;
        }
        try {
            Path file = configDir.resolve("hyfuse_memory_" + serverId + ".json");
            return Files.exists(file) ? Files.size(file) : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ── playbook (INTELLIGENCE.md) ───────────────────────────────────────

    /**
     * Runtime read of the playbook: config/hyfuse/INTELLIGENCE.md when the
     * user placed one there, else the packaged default resource, else
     * null. Never throws.
     */
    public String readPlaybook() {
        try {
            Path file = configDir.resolve("hyfuse/INTELLIGENCE.md");
            if (Files.exists(file)) {
                return Files.readString(file, StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            // fall through to the packaged default
        }
        return readPackagedDefault();
    }

    /** true when the playbook came from the config dir (vs packaged default). */
    public boolean playbookFromConfig() {
        try {
            return Files.exists(configDir.resolve("hyfuse/INTELLIGENCE.md"));
        } catch (Throwable t) {
            return false;
        }
    }

    private String readPackagedDefault() {
        try (InputStream in = BrainStore.class.getResourceAsStream(
                "/assets/hyfuse/INTELLIGENCE.default.md")) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    // ── per-server journal (markdown) ────────────────────────────────────

    private Path journalFile(String serverId) {
        return configDir.resolve("hyfuse/journals/" + serverId + ".md");
    }

    /** Journal text (or null when no journal exists yet for the server). */
    public String readJournal(String serverId) {
        if (serverId == null) {
            return null;
        }
        try {
            Path file = journalFile(serverId);
            if (!Files.exists(file)) {
                return null;
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Journal size in bytes (0 when absent). */
    public long journalSize(String serverId) {
        if (serverId == null) {
            checkJournalDir();
            return 0L;
        }
        try {
            Path file = journalFile(serverId);
            return Files.exists(file) ? Files.size(file) : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private void checkJournalDir() {
        try {
            Files.createDirectories(configDir.resolve("hyfuse/journals"));
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /**
     * Append an entry newest-first under the given section heading of the
     * per-server journal. Rejections are returned as ok:false + error and
     * mutate NOTHING on disk. Creates the journal (seeded from the packaged
     * header template) when absent.
     */
    public JsonObject appendJournal(String serverId, int section, String text, String timestamp) {
        JsonObject result = new JsonObject();
        if (serverId == null) {
            result.addProperty("ok", false);
            result.addProperty("error", "join a server first — the journal is per-server");
            return result;
        }
        if (text == null || text.isBlank()) {
            result.addProperty("ok", false);
            result.addProperty("error", "text required (nonblank, ≤" + JOURNAL_ENTRY_MAX_CHARS + " chars)");
            return result;
        }
        if (text.length() > JOURNAL_ENTRY_MAX_CHARS) {
            result.addProperty("ok", false);
            result.addProperty("error", "entry too long: " + text.length() + " chars (max "
                    + JOURNAL_ENTRY_MAX_CHARS + ") — split or shorten it");
            return result;
        }
        if (section < 1 || section > JOURNAL_SECTIONS.length) {
            result.addProperty("ok", false);
            result.addProperty("error", "section must be 1-" + JOURNAL_SECTIONS.length);
            return result;
        }
        try {
            Path file = journalFile(serverId);
            String content = Files.exists(file)
                    ? Files.readString(file, StandardCharsets.UTF_8)
                    : defaultJournalTemplate();
            byte[] entryBytes = ("- " + timestamp + " — " + text + "\n").getBytes(StandardCharsets.UTF_8);
            long currentSize = content.getBytes(StandardCharsets.UTF_8).length;
            if (currentSize + entryBytes.length > JOURNAL_MAX_BYTES) {
                result.addProperty("ok", false);
                result.addProperty("error", "journal_full: " + currentSize + " + " + entryBytes.length
                        + " bytes would exceed the " + JOURNAL_MAX_BYTES
                        + " cap — prune §10 history / stale entries (journal-read to see it)");
                result.addProperty("size", currentSize);
                return result;
            }
            String updated = insertUnderSection(content, section, timestamp, text);
            if (updated == null) {
                result.addProperty("ok", false);
                result.addProperty("error", "section heading '## " + section + ". ' not found in journal — "
                        + "it may be edited/custom; append aborted (no file change)");
                return result;
            }
            writeAtomic(file, updated);
            long newSize = journalSize(serverId);
            result.addProperty("ok", true);
            result.addProperty("appended", true);
            result.addProperty("section", section);
            result.addProperty("size", newSize);
            result.addProperty("headroom", Math.max(0, JOURNAL_MAX_BYTES - newSize));
            return result;
        } catch (Throwable t) {
            result.addProperty("ok", false);
            result.addProperty("error", "journal write failed: " + t.getClass().getSimpleName());
            return result;
        }
    }

    /**
     * Insert the entry line directly under the '## <n>.' heading (newest
     * first — before any existing entry lines under that heading). Null when
     * the heading is absent.
     */
    static String insertUnderSection(String content, int section, String timestamp, String text) {
        String marker = "## " + section + ".";
        int headingIdx = content.indexOf(marker);
        if (headingIdx < 0) {
            return null;
        }
        int lineEnd = content.indexOf('\n', headingIdx);
        if (lineEnd < 0) {
            lineEnd = content.length();
        }
        // first line after the heading line itself
        int insertAt = Math.min(lineEnd + 1, content.length());
        String entry = "- " + timestamp + " — " + text + "\n";
        return content.substring(0, insertAt) + entry + content.substring(insertAt);
    }

    /** Packaged 10-section header template (short form of brain/MEMORY.template.md). */
    private String defaultJournalTemplate() {
        StringBuilder sb = new StringBuilder();
        sb.append("# HYFUSE MEMORY — <server-id>\n\n");
        sb.append("> Per-server journal. Cap: 100 KB hard (mod-enforced). ");
        sb.append("Newest entries first in each section.\n\n");
        sb.append("Initialized: auto-created by journal-append.\n\n");
        for (int i = 0; i < JOURNAL_SECTIONS.length; i++) {
            sb.append("## ").append(i + 1).append(". ").append(JOURNAL_SECTIONS[i]).append("\n\n");
        }
        sb.append("---\n");
        return sb.toString();
    }

    private static void writeAtomic(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Names of the valid journal sections (for registry/test use). */
    public static JsonArray sectionNames() {
        JsonArray arr = new JsonArray();
        for (String s : JOURNAL_SECTIONS) {
            arr.add(s);
        }
        return arr;
    }
}
