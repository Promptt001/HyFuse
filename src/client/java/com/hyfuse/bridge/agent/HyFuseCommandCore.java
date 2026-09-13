package com.hyfuse.bridge.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * HyFuseCommandCore — the /hyfuse command core.
 *
 * Minecraft-free parsing and execution of the /hyfuse command tree; the
 * Brigadier registration lives in {@link com.hyfuse.bridge.chat.HyFuseCommands}.
 * The tree:
 *
 * <pre>
 * /hyfuse status
 * /hyfuse set api-url &lt;url&gt;
 * /hyfuse set api-key &lt;key&gt;
 * /hyfuse set model &lt;name&gt;
 * /hyfuse set goal &lt;goal text...&gt; -- starts a goal session
 * /hyfuse set agent_mode &lt;on|off&gt;
 * /hyfuse reset intelligence | memory | config | all
 * </pre>
 *
 * Authority model (playbook §2): /hyfuse commands are client-local and never
 * sent to the server — only the user at the bot's keyboard can run them
 * (other players cannot see or invoke them). The agent goal session uses the
 * same user-gated agent-start path; the model itself can never start one.
 */
public final class HyFuseCommandCore {

    private HyFuseCommandCore() { }

    /** Parse + route one /hyfuse invocation. Returns the user feedback text. */
    public static String execute(String rawArgs) {
        String rest = rawArgs == null ? "": rawArgs.trim();
        if (rest.isEmpty()) return usage();
        String[] parts = rest.split("\\s+", 2);
        String sub = parts[0].toLowerCase();
        String remainder = parts.length > 1 ? parts[1].trim(): "";
        switch (sub) {
            case "status": return status();
            case "set": return set(remainder);
            case "reset": return reset(remainder);
            default: return "Unknown subcommand '" + sub + "'.\n" + usage();
        }
    }

    // ── status ───────────────────────────────────────────────────────────

    static String status() {
        JsonObject cfg = AgentLoop.loadConfig();
        StringBuilder sb = new StringBuilder("HyFuse ");
        sb.append("agent_mode: ").append(AgentMode.isOn() ? "on": "off");
        sb.append(" | loop: ").append(AgentLoop.isActive() ? "active": "idle");
        if (GoalSession.current() != null) {
            GoalSession gs = GoalSession.current();
            sb.append(" | goal: \"").append(truncate(gs.goal(), 60)).append('"')
.append(" (session ").append(gs.session()).append('/')
              .append(GoalSession.MAX_SESSIONS()).append(')');
            // A goal session with an idle loop used to look healthy while
            // its sessions had already died — surface the supervisor state.
            if (!AgentLoop.isActive() && gs.statusText() != null && !gs.statusText().isBlank()) {
                sb.append(" | session state: ").append(truncate(gs.statusText(), 120));
            }
        }
        // Surface the loop's lastError too (agent-status JSON already had
        // it; /hyfuse status was silent about it).
        if (!AgentLoop.isActive()) {
            try {
                JsonObject loopStatus = JsonParser.parseString(AgentLoop.status()).getAsJsonObject();
                if (loopStatus.has("lastError")) {
                    sb.append(" | lastError: ").append(truncate(loopStatus.get("lastError").getAsString(), 120));
                }
            } catch (Exception ignored) { }
        }
        if (cfg != null) {
            sb.append(" | api: ").append(maskedUrl(cfg));
            sb.append(" | model: ").append(cfg.has("model") ? cfg.get("model").getAsString(): "(default)");
        } else {
            sb.append(" | api: NOT SET (/hyfuse set api-url...)");
        }
        sb.append(" | playbook: ");
        sb.append(playbookSource());
        return sb.toString();
    }

    // ── set ──────────────────────────────────────────────────────────────

    static String set(String rest) {
        if (rest.isEmpty()) {
            return "set what? /hyfuse set api-url|api-key|model|goal|agent_mode <value>";
        }
        String[] kv = rest.split("\\s+", 2);
        String key = kv[0].toLowerCase();
        String value = kv.length > 1 ? kv[1].trim(): "";
        switch (key) {
            case "api-url": case "url": return setApiUrl(value);
            case "api-key": case "key": return setApiKey(value);
            case "model": return setModel(value);
            case "goal": return setGoal(value);
            case "agent_mode": case "agent-mode": case "agentmode": return setAgentMode(value);
            default: return "Unknown setting '" + key
                    + "'. Valid: api-url, api-key, model, goal, agent_mode";
        }
        }

    static String setApiUrl(String url) {
        if (url.isEmpty()) return "Usage: /hyfuse set api-url <url> (empty not allowed)";
        JsonObject cfg = readOrNewConfig();
        cfg.addProperty("url", url);
        return writeConfig(cfg) ? "api-url set. agent.json now: " + maskedUrl(cfg)
: "ERROR: could not write config/hyfuse/agent.json";
    }

    static String setApiKey(String key) {
        JsonObject cfg = readOrNewConfig();
        if (key.isEmpty()) { // explicit clear
            cfg.remove("apiKey");
            return writeConfig(cfg) ? "api-key cleared (public endpoint mode)."
: "ERROR: could not write config/hyfuse/agent.json";
        }
        cfg.addProperty("apiKey", key);
        return writeConfig(cfg) ? "api-key set (never displayed)."
: "ERROR: could not write config/hyfuse/agent.json";
    }

    static String setModel(String model) {
        if (model.isEmpty()) return "Usage: /hyfuse set model <name>";
        JsonObject cfg = readOrNewConfig();
        cfg.addProperty("model", model);
        return writeConfig(cfg) ? "model set: " + model
: "ERROR: could not write config/hyfuse/agent.json";
    }

    /** /hyfuse set goal — starts a bounded goal session (agent_mode rides along). */
    static String setGoal(String goal) {
        if (goal.isEmpty() || goal.isBlank()) {
            return "Usage: /hyfuse set goal <goal text> — empty goals are rejected";
        }
        JsonObject args = new JsonObject();
        args.addProperty("goal", goal);
        JsonObject result = com.hyfuse.bridge.dispatch.ToolDispatcher.agentGoalStart(
                Minecraft.getInstance(), args);
        if (result.has("ok") && result.get("ok").getAsBoolean()) {
            return "Goal session started: \"" + truncate(goal, 80) + "\"\n"
                    + "Reflect mode: " + (AgentMode.isOn() ? "on": "off (set agent_mode on to enable)")
                    + "\n" + stringOf(result, "message");
        }
        return "Goal session NOT started: " + stringOf(result, "message")
                + (result.has("reason") ? " (" + stringOf(result, "reason") + ")": "");
    }

    static String setAgentMode(String value) {
        boolean on = "on".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value);
        if (!on && !("off".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))) {
            return "Usage: /hyfuse set agent_mode <on|off>";
        }
        JsonObject args = new JsonObject();
        args.addProperty("mode", on ? "on": "off");
        JsonObject result = com.hyfuse.bridge.dispatch.ToolDispatcher.agentModeSet(
                Minecraft.getInstance(), args);
        if (result.has("ok") && result.get("ok").getAsBoolean()) {
            return "agent_mode " + (on ? "on": "off") + " — "
                    + (on ? "KillAura armed; survival reflexes armed."
: "KillAura disarmed; reflect rows removed.");
        }
        return "agent_mode change failed: " + stringOf(result, "message");
    }

    // ── reset ────────────────────────────────────────────────────────────

    static String reset(String what) {
        String target = what.trim().toLowerCase();
        if (target.isEmpty()) {
            return "reset what? /hyfuse reset intelligence|memory|config|all";
        }
        List<String> done = new ArrayList<>();
        List<String> missed = new ArrayList<>();
        boolean wantAll = "all".equals(target);
        if (wantAll || "intelligence".equals(target)) resetIntelligence(done, missed);
        if (wantAll || "memory".equals(target)) resetMemory(done, missed);
        if (wantAll || "config".equals(target)) resetConfig(done, missed);
        if (done.isEmpty() && missed.isEmpty()) {
            return "Unknown reset target '" + target + "'. Valid: intelligence, memory, config, all";
        }
        StringBuilder sb = new StringBuilder("Reset: ");
        sb.append(done.isEmpty() ? "(nothing)": String.join(", ", done));
        if (!missed.isEmpty()) sb.append(" | skipped (already default): ").append(String.join(", ", missed));
        sb.append("\nPlaybook now: ").append(playbookSource());
        if ("all".equals(target)) sb.append(" | memory/journal per-server files removed");
        return sb.toString();
    }

    static void resetIntelligence(List<String> done, List<String> missed) {
        try {
            Path file = configDir().resolve("hyfuse/INTELLIGENCE.md");
            if (Files.exists(file)) {
                Files.delete(file);
                done.add("intelligence (config INTELLIGENCE.md deleted — packaged default active)");
            } else {
                missed.add("intelligence (no config copy)");
            }
        } catch (Throwable t) {
            missed.add("intelligence (delete failed: " + t.getMessage() + ")");
        }
    }

    static void resetMemory(List<String> done, List<String> missed) {
        try {
            String sid = currentServerId();
            int removed = 0;
            List<String> notes = new ArrayList<>();
            if (sid != null) {
                com.hyfuse.bridge.HyFuseClient.brainStore().clearServerStore(sid);
                Path mem = configDir().resolve("hyfuse_memory_" + sid + ".json");
                if (Files.exists(mem)) { Files.delete(mem); removed++; }
                if (com.hyfuse.bridge.HyFuseClient.brainStore().clearJournal(sid)) {
                    notes.add("journal removed");
                }
            }
            if (removed > 0 || !notes.isEmpty()) {
                done.add("memory (per-server store" + (notes.isEmpty() ? "": " + " + notes.get(0))
                        + "; global store untouched)");
            } else {
                missed.add("memory (no per-server data or not connected)");
            }
        } catch (Throwable t) {
            missed.add("memory (failed: " + t.getMessage() + ")");
        }
        // policies survive resets (they reseed); nothing to do.
    }

    static void resetConfig(List<String> done, List<String> missed) {
        try {
            Path file = configDir().resolve("hyfuse/agent.json");
            if (Files.exists(file)) {
                Files.delete(file);
                done.add("config (agent.json deleted — agent disabled until re-set)");
            } else {
                missed.add("config (agent.json absent)");
            }
        } catch (Throwable t) {
            missed.add("config (delete failed: " + t.getMessage() + ")");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    static Path configDir() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
    }

    static String currentServerId() {
        try {
            return com.hyfuse.bridge.HyFuseClient.currentServerId();
        } catch (Throwable t) {
            return null;
        }
    }

    static JsonObject readOrNewConfig() {
        try {
            Path file = configDir().resolve("hyfuse/agent.json");
            if (Files.exists(file)) {
                return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            }
        } catch (Throwable ignored) { }
        return new JsonObject();
    }

    static boolean writeConfig(JsonObject cfg) {
        try {
            Path dir = configDir().resolve("hyfuse");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("agent.json"), cfg.toString());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static String maskedUrl(JsonObject cfg) {
        String url = cfg.has("url") ? cfg.get("url").getAsString(): "";
        int scheme = url.indexOf("://");
        return url.isEmpty() ? "(unset)"
: (scheme > 0 ? url.substring(0, scheme) + "://...": url.substring(0, Math.min(12, url.length())) + "...");
    }

    static String playbookSource() {
        try {
            return com.hyfuse.bridge.HyFuseClient.brainStore().playbookFromConfig()
                    ? "config copy (/hyfuse reset intelligence to restore default)"
: "packaged default";
        } catch (Throwable t) {
            return "(unavailable)";
        }
    }

    static String stringOf(JsonObject o, String key) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString(): "";
        } catch (Throwable t) {
            return "";
        }
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s: s.substring(0, max - 1) + "…";
    }

    static String usage() {
        return "HyFuse commands:\n"
                + "/hyfuse status\n"
                + "/hyfuse set api-url <url>\n"
                + "/hyfuse set api-key <key>\n"
                + "/hyfuse set model <name>\n"
                + "/hyfuse set goal <goal text>\n"
                + "/hyfuse set agent_mode <on|off>\n"
                + "/hyfuse reset intelligence|memory|config|all";
    }
}
