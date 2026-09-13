package com.hyfuse.bridge;

import com.hyfuse.bridge.chat.ChatBuffer;
import com.hyfuse.bridge.dispatch.ToolDispatcher;
import com.hyfuse.bridge.mcp.McpHttpServer;
import com.hyfuse.bridge.sense.DeathMemory;
import com.hyfuse.bridge.sense.EventBuffer;
import com.hyfuse.bridge.sense.BrainStore;
import com.hyfuse.bridge.sense.MemoryStore;
import com.hyfuse.bridge.sense.PolicyEngine;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

public final class HyFuseClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("hyfuse");
    /** Integrated MCP (Streamable HTTP) server — the sole transport since. */
    private McpHttpServer mcpServer;
    /** Shared chat buffer — captures incoming chat/game messages for read-chat tool. */
    public static final ChatBuffer CHAT_BUFFER = new ChatBuffer();

    /** Shared event ring buffer — health/death/hostile-spawn/weather events for get-events. */
    public static final EventBuffer EVENT_BUFFER = new EventBuffer();

    /** Shared death memory — bounded death history + cause counts for get-last-death. */
    public static final DeathMemory DEATH_MEMORY = new DeathMemory();

    /**
     *: the reactive policy engine. Consumes every
     * EventBuffer event at add-time (client thread) and submits matched
     * policy actions to the single-Baritone queue rail.
     */
    public static final PolicyEngine POLICY_ENGINE = new PolicyEngine();

    /**
     * (item 4 increment 1): persistent memory store. Lazily built —
     * the config-dir path needs FabricLoader, so construction is deferred
     * past class init; {@link #memoryStore()} loads the file on first use
     * (boot path calls it explicitly in onInitializeClient).
     */
    private static MemoryStore memoryStore;

    /**
     * The brain plumbing — playbook (INTELLIGENCE.md runtime read),
     * per-server journal (100 KB cap), and per-server MemoryStore routing
     * (copy-on-first-connect; policy rows stay global). Lazily built like
     * the memory store; null-safe when disconnected.
     */
    private static BrainStore brainStore;

    /** The shared brain store (creates on first call; global store inside). */
    public static synchronized BrainStore brainStore() {
        if (brainStore == null) {
            brainStore = new BrainStore(
                    net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir(),
                    memoryStore());
        }
        return brainStore;
    }

    /**
     * Current per-server id (sanitized current-server address) or
     * null when disconnected/singleplayer (the global store applies).
     */
    public static String currentServerId() {
        return BrainStore.serverId(net.minecraft.client.Minecraft.getInstance());
    }

    /** The shared persistent memory store (creates + loads on first call). */
    public static synchronized MemoryStore memoryStore() {
        if (memoryStore == null) {
            java.nio.file.Path file = net.fabricmc.loader.api.FabricLoader.getInstance()
.getConfigDir().resolve("hyfuse_memory.json");
            memoryStore = new MemoryStore(file);
        }
        memoryStore.loadIfNeeded();
        return memoryStore;
    }

    @Override
    public void onInitializeClient() {
        // (HyFuse rebrand): read hyfuse.* system properties,
        // falling back to the legacy mcagent.* names for back-compat.
        // Hyfuse.port/mcagent.port alias the MCP port
        // (the WebSocket server on:25580 is gone; the sole transport
        // is the Streamable-HTTP MCP server).
        String bindAddress = sysProp("hyfuse.bind", "mcagent.bind", "127.0.0.1").trim();
        String token = sysProp("hyfuse.token", "mcagent.token", "").trim();

        if (bindAddress.isBlank()) {
            throw new IllegalArgumentException("hyfuse.bind cannot be blank");
        }
        InetAddress resolvedAddress;
        try {
            resolvedAddress = InetAddress.getByName(bindAddress);
        } catch (UnknownHostException error) {
            throw new IllegalArgumentException("Unable to resolve hyfuse.bind address: " + bindAddress, error);
        }
        if (!resolvedAddress.isLoopbackAddress() && token.isBlank()) {
            throw new IllegalStateException(
                    "A non-loopback hyfuse.bind requires a nonblank -Dhyfuse.token (or legacy -Dmcagent.token) value");
        }

        // Register chat message capture (Fabric API event — no mixin needed)
        CHAT_BUFFER.register();

        // The /hyfuse client command tree (user-at-keyboard use;
        // local-only, never sent to the server — see HyFuseCommands).
        com.hyfuse.bridge.chat.HyFuseCommands.register();

        // (item 4 increment 1): load persistent memory at boot so the
        // very first get-agent-snapshot / memory-read sees the file's records.
        memoryStore();

        // Event sampler + death memory. The tick sampler pushes game
        // events into EVENT_BUFFER and reports deaths to DEATH_MEMORY (the
        // same three-way split: event buffer + death memory).
        // The sampler consults CHAT_BUFFER for the server's death
        // message when kill credit is absent (environmental deaths).
        EVENT_BUFFER.setChatBuffer(CHAT_BUFFER);
        EVENT_BUFFER.register(facts ->
                DEATH_MEMORY.record(facts.x(), facts.y(), facts.z(), facts.cause(), facts.message()));

        //: the reactive policy layer. The engine
        // sees every event at add-time; matched policies submit actions
        // through ToolDispatcher.runPolicyAction (the queue rail — the
        // QUEUE_ACTIVE gate is never bypassed; a busy rail answers
        // queue_busy and the engine rolls its cooldown back). Seed the
        // default rows once: survival set ENABLED, aggressive DISABLED
        POLICY_ENGINE.setSink(ToolDispatcher::runPolicyAction);
        seedDefaultPolicies();
        EVENT_BUFFER.addEventConsumer(POLICY_ENGINE::onEvent);

        // The legacy WebSocket ToolServer (:25580) is removed.
        // hyfuse.port / mcagent.port are retained as aliases of the MCP
        // port for back-compat (user-ratified Q2, ).
        ToolDispatcher dispatcher = new ToolDispatcher();
        int mcpPort = Integer.getInteger("hyfuse.mcp.port",
                Integer.getInteger("hyfuse.port",
                Integer.getInteger("mcagent.mcp.port",
                Integer.getInteger("mcagent.port", 25581))));
        if (mcpPort > 0) {
            String mcpToken = sysProp("hyfuse.mcp.token", "mcagent.mcp.token", token).trim();
            mcpServer = new McpHttpServer(resolvedAddress, mcpPort, dispatcher, mcpToken);
            mcpServer.start();
        }
    }

    /**
     * Seed the default policy rows once (first boot after the
     * feature lands). Survival set ENABLED, aggressive DISABLED — the baseline).
     * Existing rows are never overwritten: user edits (policy-save) win on
     * every later boot. Forgetting ALL rows restores the seeds on the
     * next boot.
     */
    private static void seedDefaultPolicies() {
        MemoryStore store = memoryStore();
        if (!store.query("policy", null, null, null, null).isEmpty()) {
            return;
        }
        JsonObject eatArgs = new JsonObject();
        eatArgs.addProperty("minCount", 18);
        seedPolicy(store, "survival:eat", "food", 14, "eat-food",
                eatArgs, 60_000L, true,
                "Eat when the food level drops to 14 (sprint + regen kept up).");
        JsonObject fleeArgs = new JsonObject();
        fleeArgs.addProperty("minDistance", 16);
        seedPolicy(store, "survival:flee", "entityHurt", 8, "flee-from",
                fleeArgs, 30_000L, true,
                "Flee when health drops to 8 (flee-from nearest_hostile).");
        seedPolicy(store, "survival:oxygen", "oxygen", null, "escape-water",
                new JsonObject(), 45_000L, true,
                "Escape water on oxygen loss.");
        seedPolicy(store, "survival:death-audit", "death", null, null,
                new JsonObject(), 300_000L, true,
                "Audit-only: log every death (cause+message); Respawns.");
        JsonObject kaArgs = new JsonObject();
        kaArgs.addProperty("module", "kill-aura");
        kaArgs.addProperty("action", "enable");
        seedPolicy(store, "aggressive:killaura", "entitySpawn", 1,
                "toggle-meteor-module", kaArgs, 60_000L, false,
                "DISABLED by default: KillAura on hostile spawn (user opt-in).");
    }

    private static void seedPolicy(MemoryStore store, String id, String trigger,
                                   Integer threshold, String action, JsonObject args,
                                   long cooldownMs, boolean enabled, String note) {
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("kind", "policy");
        row.addProperty("trigger", trigger);
        if (threshold != null) row.addProperty("threshold", threshold);
        if (action != null) row.addProperty("action", action);
        if (args != null && !args.isEmpty()) row.add("args", args);
        row.addProperty("cooldownMs", cooldownMs);
        row.addProperty("enabled", enabled);
        row.addProperty("note", note);
        store.upsert(row);
    }

    /**
     * Hyfuse.* / legacy mcagent.* system-property read with the
     * correct precedence: primary name first, legacy name second, then the
     * fallback ("" when omitted). NEVER returns null, so chained.trim()
     * and.isBlank() are safe even when the client launches with no -D
     * flags (the 26.2 MultiMC instance boots with zero sysprops — the
     * inverted-argument firstNonBlank calls NPE'd on exactly that).
     */
    private static String sysProp(String primary, String legacy, String fallback) {
        String value = System.getProperty(primary);
        if (value == null || value.isBlank()) {
            value = System.getProperty(legacy);
        }
        if (value == null || value.isBlank()) {
            return fallback == null ? "": fallback;
        }
        return value;
    }

    /**
     * (HyFuse rebrand): returns the first non-blank value among
     * {@code values}, or {@code fallback} when all are null/blank.
     * DEPRECATED since Its (fallback-first) argument order inverted
     * the intended precedence at every call site and caused the 1.2.18 boot
     * NPE (returned null when the primary property was unset and every
     * candidate blank). Use {@link #sysProp} for hyfuse / mcagent sysprop reads.
     * Kept only for reference; no live callers remain.
     */
    private static String firstNonBlank(String fallback, String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }
}
