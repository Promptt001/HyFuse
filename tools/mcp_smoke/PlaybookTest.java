package com.hyfuse.bridge.sense;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Regression test: brain plumbing pins.
 *
 * Targets BrainStore directly (public API; the dispatcher handlers delegate
 * to it) plus registration/dispatch wiring by reflection. A null-Minecraft
 * arg is valid for every path tested — nothing here touches the world.
 *
 * Pins:
 *  - server-id sanitization (lowercase, charset, port separator, cap)
 *  - copy-on-first-connect seeds game-state records, excludes policy rows,
 *    never touches the global file
 *  - journal append newest-first under §9; section validation; entry cap
 *  - 100 KB journal cap rejects BEFORE any mutation (journal_full)
 *  - playbook read prefers the config dir over the packaged default
 */
public class PlaybookTest {
    static int fails = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "ok   " : "FAIL ") + name + (ok ? "" : " — " + detail));
        if (!ok) fails++;
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("playbook-test-");
        Path globalFile = tmp.resolve("hyfuse_memory.json");
        MemoryStore global = new MemoryStore(globalFile);
        seedGlobal(global);
        BrainStore brain = new BrainStore(tmp, global);

        // ── 1. server-id sanitization ──────────────────────────────────
        Method sanitize = BrainStore.class.getDeclaredMethod("sanitizeServerId", String.class);
        sanitize.setAccessible(true);
        check("id_lowercases", "play.example.com".equals(sanitize.invoke(null, "Play.Example.COM")),
                "lowercase expected");
        check("id_port_separator", "play.example.com-25565".equals(
                sanitize.invoke(null, "play.example.com:25565")), "colon -> dash");
        check("id_strips_bad_chars", "abcde.f".equals(sanitize.invoke(null, "a b/c!d\ne.f")),
                "spaces/slashes/bangs dropped (no dash substitution)");
        check("id_caps_64", ((String) sanitize.invoke(null, "x".repeat(100))).length() == 64,
                "capped at 64");
        check("id_blank_is_null", sanitize.invoke(null, "///") == null, "blank -> null");

        // ── 2. copy-on-first-connect ────────────────────────────────────
        MemoryStore perServer = brain.storeFor("play.example.com");
        List<JsonObject> seeded = perServer.query(null, null, null, null, null);
        check("seed_count", seeded.size() == 3, "3 game-state records seeded, got " + seeded.size());
        check("seed_excludes_policy", perServer.query("policy", null, null, null, null).isEmpty(),
                "policy rows must NOT be seeded per-server");
        check("seed_file_exists", Files.exists(tmp.resolve("hyfuse_memory_play.example.com.json")),
                "per-server file created");
        String globalRaw = Files.readString(globalFile);
        check("global_untouched", globalRaw.contains("\"base\"") && globalRaw.contains("survival:eat"),
                "global file still holds all 4 records (incl. policy)");
        // second storeFor returns the SAME instance (cache)
        check("store_cache", brain.storeFor("play.example.com") == perServer, "cached instance");
        // null id -> global store
        check("null_id_is_global", brain.storeFor(null) == global, "null id routes to global");

        // ── 3. journal append ───────────────────────────────────────────
        JsonObject first = brain.appendJournal("play.example.com", 9, "brain tools deployed", "2026-08-30 16:00");
        check("append_ok", first.get("ok").getAsBoolean(), "first append should succeed");
        Path journal = tmp.resolve("hyfuse/journals/play.example.com.md");
        String j1 = Files.readString(journal);
        check("journal_created_from_template", j1.contains("## 9. Chat & event log"),
                "template section 9 present");
        int firstIdx = j1.indexOf("- 2026-08-30 16:00 — brain tools deployed");
        check("append_newest_first", j1.indexOf("- 2026-08-30 16:00 — brain tools deployed") >= 0
                && j1.indexOf("## 9.") < firstIdx, "entry under §9 heading");

        JsonObject second = brain.appendJournal("play.example.com", 9, "second entry", "2026-08-30 16:05");
        check("append2_ok", second.get("ok").getAsBoolean(), "second append ok");
        String j2 = Files.readString(journal);
        int s1 = j2.indexOf("- 2026-08-30 16:00 — brain tools deployed");
        int s2 = j2.indexOf("- 2026-08-30 16:05 — second entry");
        check("newest_first_order", s2 < s1 && s2 >= 0, "newest entry above older (s2=" + s2 + " s1=" + s1 + ")");

        // section validation
        JsonObject badSection = brain.appendJournal("play.example.com", 11, "x", "t");
        check("section11_rejected", !badSection.get("ok").getAsBoolean()
                && badSection.get("error").getAsString().contains("1-10"), "section must be 1-10");
        JsonObject badText = brain.appendJournal("play.example.com", 9, "  ", "t");
        check("blank_text_rejected", !badText.get("ok").getAsBoolean(), "blank text rejected");
        JsonObject tooLong = brain.appendJournal("play.example.com", 9, "x".repeat(2001), "t");
        check("long_text_rejected", !tooLong.get("ok").getAsBoolean()
                && tooLong.get("error").getAsString().contains("2000"), "entry >2000 rejected");

        // no-server rejection
        JsonObject noServer = brain.appendJournal(null, 9, "x", "t");
        check("null_server_rejected", !noServer.get("ok").getAsBoolean()
                && noServer.get("error").getAsString().contains("per-server"), "null server honest error");

        // ── 4. 100 KB cap rejects BEFORE mutation ───────────────────────
        // Fill the journal to just under the cap, then try to blow past it.
        StringBuilder big = new StringBuilder();
        big.append("# filler\n\n");
        while (big.length() < 99 * 1024) {
            big.append("x".repeat(200)).append('\n');
        }
        Files.writeString(journal, big.toString());
        byte[] before = Files.readAllBytes(journal);
        JsonObject capHit = brain.appendJournal("play.example.com", 9,
                "y".repeat(100), "t");
        // filler has no section headings -> section-missing rejection OR cap
        // rejection; either way NOTHING on disk may change.
        boolean rejected = !capHit.get("ok").getAsBoolean();
        check("cap_or_section_rejected", rejected, "expected rejection, got " + capHit);
        check("cap_no_mutation", java.util.Arrays.equals(before, Files.readAllBytes(journal)),
                "file byte-identical after rejection");

        // Direct cap math: append exactly to the cap edge.
        // Rebuild a journal with a valid §9 heading ~200 bytes under cap.
        StringBuilder near = new StringBuilder();
        near.append("# j\n\n## 9. Chat & event log (rolling, newest first)\n\n");
        while (near.length() < 100 * 1024 - 300) {
            near.append("x".repeat(200)).append('\n');
        }
        Files.writeString(journal, near.toString());
        JsonObject fits = brain.appendJournal("play.example.com", 9, "small", "t");
        check("under_cap_fits", fits.get("ok").getAsBoolean(), "small append under cap ok: " + fits);
        JsonObject over = brain.appendJournal("play.example.com", 9, "z".repeat(2000), "t");
        check("over_cap_rejected", !over.get("ok").getAsBoolean()
                && over.get("error").getAsString().contains("journal_full"),
                "journal_full at the edge: " + (over.has("error") ? over.get("error").getAsString().substring(0, 40) : "?"));

        // constants pin the operator contract
        Field maxBytes = BrainStore.class.getDeclaredField("JOURNAL_MAX_BYTES");
        maxBytes.setAccessible(true);
        check("cap_is_100kb", maxBytes.getInt(null) == 102400, "expected 102400");
        Field maxEntry = BrainStore.class.getDeclaredField("JOURNAL_ENTRY_MAX_CHARS");
        maxEntry.setAccessible(true);
        check("entry_cap_2000", maxEntry.getInt(null) == 2000, "expected 2000");
        JsonArray names = BrainStore.sectionNames();
        check("ten_sections", names.size() == 10, "10 sections");

        // ── 5. playbook: config-dir file preferred over packaged default ─
        // NOTE: the packaged default ships at build/resources/client/assets/...
        // — that dir must be on the test classpath (runner adds it).
        String defaultOnly = brain.readPlaybook();
        check("default_playbook_present", defaultOnly != null && defaultOnly.contains("HYFUSE PLAYBOOK"),
                "packaged default served when no config file");
        check("source_default", !brain.playbookFromConfig(), "source=default when no config file");
        Files.createDirectories(tmp.resolve("hyfuse"));
        Files.writeString(tmp.resolve("hyfuse/INTELLIGENCE.md"), "# LIVE PLAYBOOK\noperator content\n");
        String live = brain.readPlaybook();
        check("config_playbook_preferred", live != null && live.contains("LIVE PLAYBOOK"),
                "config-dir file wins");
        check("source_config", brain.playbookFromConfig(), "source=config when file present");

        // ── 6. dispatcher + registry wiring (reflection) ────────────────
        Class<?> td = Class.forName("com.hyfuse.bridge.dispatch.ToolDispatcher");
        Method namesMethod = td.getDeclaredMethod("handlerNames");
        namesMethod.setAccessible(true);
        java.util.Set<?> handlers = (java.util.Set<?>) namesMethod.invoke(null);
        check("dispatch_get_playbook", handlers.contains("get-playbook"), "get-playbook in dispatch map");
        check("dispatch_journal_read", handlers.contains("journal-read"), "journal-read in dispatch map");
        check("dispatch_journal_append", handlers.contains("journal-append"), "journal-append in dispatch map");
        Class<?> reg = Class.forName("com.hyfuse.bridge.mcp.McpToolRegistry");
        Method regList = reg.getDeclaredMethod("toolsListResult");
        regList.setAccessible(true);
        JsonObject tools = (JsonObject) regList.invoke(null);
        StringBuilder toolNames = new StringBuilder();
        for (var el : tools.getAsJsonArray("tools")) {
            toolNames.append(el.getAsJsonObject().get("name").getAsString()).append('\n');
        }
        check("registry_get_playbook", toolNames.indexOf("get-playbook") >= 0, "get-playbook registered");
        check("registry_journal_read", toolNames.indexOf("journal-read") >= 0, "journal-read registered");
        check("registry_journal_append", toolNames.indexOf("journal-append") >= 0, "journal-append registered");

        System.out.println(fails == 0 ? "PlaybookTest: ALL PASS" : "PlaybookTest: " + fails + " FAIL");
        if (fails > 0) System.exit(1);
    }

    static void seedGlobal(MemoryStore global) {
        JsonObject base = new JsonObject();
        base.addProperty("id", "base");
        base.addProperty("kind", "poi");
        base.addProperty("note", "dug-in base");
        JsonObject pos = new JsonObject();
        pos.addProperty("x", 728960);
        pos.addProperty("y", 64);
        pos.addProperty("z", -1284116);
        base.add("position", pos);
        global.upsert(base);
        JsonObject creepo = new JsonObject();
        creepo.addProperty("id", "mob:creepo");
        creepo.addProperty("kind", "mob");
        creepo.addProperty("note", "creeper near base");
        global.upsert(creepo);
        JsonObject chest = new JsonObject();
        chest.addProperty("id", "container:728959,64,-1284118");
        chest.addProperty("kind", "container");
        global.upsert(chest);
        JsonObject policy = new JsonObject();
        policy.addProperty("id", "survival:eat");
        policy.addProperty("kind", "policy");
        policy.addProperty("trigger", "food");
        policy.addProperty("threshold", 6);
        policy.addProperty("action", "eat-food");
        policy.addProperty("enabled", true);
        global.upsert(policy);
    }
}
