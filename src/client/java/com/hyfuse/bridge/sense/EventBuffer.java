package com.hyfuse.bridge.sense;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.monster.Enemy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Bounded, TTL-limited ring buffer of real-time game events (port of the
 * legacy {@code EventStore}: 100 events / 30s TTL / poll-and-drain).
 *
 * Fed by a client-tick sampler registered from {@link #register(Consumer)}
 * (health changes, deaths, hostile spawns, weather changes) and drained by
 * the {@code get-events} tool handler. Thread-safe: producers run on the
 * client thread (tick sampler), consumers on transport threads.
 *
 * The the legacy design routes a single {@code onGameEvent} stream three ways;
 * here the tick sampler feeds this buffer, and death events are ALSO pushed
 * to {@link DeathMemory} (wired in HyFuseClient) so get-last-death and the
 * event stream stay in sync.
 */
public final class EventBuffer {

    /** Maximum events retained. */
    private static final int MAX_EVENTS = 100;

    /** Events older than this are dropped on drain/peek. */
    private static final long TTL_MS = 30_000L;

    /** A single buffered event (JSON-serializable). */
    public static final class Event {
        public final String type;          // entityHurt, health, entitySpawn, death, weatherUpdate, ...
        public final long timestamp;       // epoch millis
        public final long stateVersion;    // monotonic version (shared with snapshot telemetry)

        // Optional payload fields (null = absent; omitted from JSON)
        public final String entity; // "self" for bot damage, mob name for spawns
        public final String cause;         // death cause
        public final Double health;        // health value for health/entityHurt events
        public final Boolean hostile;      // for entitySpawn events
        public final String weather;       // for weatherUpdate events
        public final String message;       // death message (server text)

        Event(String type, long timestamp, long stateVersion, String entity, String cause,
              Double health, Boolean hostile, String weather, String message) {
            this.type = type;
            this.timestamp = timestamp;
            this.stateVersion = stateVersion;
            this.entity = entity;
            this.cause = cause;
            this.health = health;
            this.hostile = hostile;
            this.weather = weather;
            this.message = message;
        }
    }

    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private long lastStateVersion = 0;

    // ── Producer side (client thread via tick sampler; guarded) ──────────

    /** Push an event into the ring; drops the oldest when over capacity. */
    public synchronized void add(String type, String entity, String cause, Double health,
                                 Boolean hostile, String weather, String message) {
        Event event = new Event(type, System.currentTimeMillis(), ++lastStateVersion,
                entity, cause, health, hostile, weather, message);
        events.addLast(event);
        while (events.size() > MAX_EVENTS) {
            events.pollFirst();
        }
        //: fan out to registered policy consumers.
        notifyConsumers(event);
    }

    // ──: policy consumers ──────────────────────────

    /** Consumers see every event at add-time (client thread only). */
    private final java.util.List<java.util.function.Consumer<Event>> eventConsumers =
            new java.util.ArrayList<>();
    private final Object eventConsumersLock = new Object();
    private volatile java.util.List<java.util.function.Consumer<Event>> eventConsumersView =
            java.util.List.of();

    /** Register an add-time event consumer (the PolicyEngine hook). */
    public void addEventConsumer(java.util.function.Consumer<Event> consumer) {
        synchronized (eventConsumersLock) {
            eventConsumers.add(consumer);
            eventConsumersView = java.util.List.copyOf(eventConsumers);
        }
    }

    private void notifyConsumers(Event event) {
        if (eventConsumersView.isEmpty()) return;
        for (java.util.function.Consumer<Event> c : eventConsumersView) {
            try { c.accept(event); } catch (Throwable ignored) { }
        }
    }

    // ── Consumer side (transport threads) ────────────────────────────────

    /** Return fresh events and clear the queue (poll-and-drain). */
    public synchronized List<Event> drain() {
        evictExpired();
        List<Event> drained = new ArrayList<>(events);
        events.clear();
        return drained;
    }

    /** Return fresh events without clearing. */
    public synchronized List<Event> peek() {
        evictExpired();
        return new ArrayList<>(events);
    }

    public synchronized int size() {
        evictExpired();
        return events.size();
    }

    private void evictExpired() {
        long cutoff = System.currentTimeMillis() - TTL_MS;
        while (!events.isEmpty() && events.peekFirst().timestamp < cutoff) {
            events.pollFirst();
        }
        // Deque is not TTL-sorted beyond insertion order; a mid-queue expired
        // event can only exist if timestamps are non-monotonic, which the
        // single producer thread guarantees against.
    }

    // ── Tick sampler (registers on the Fabric client tick event) ─────────

    /**
     * Registers a client-tick sampler that watches health/oxygen/death and
     * hostile spawns. Call once during client init. The {@code onDeath}
     * callback receives (position, cause, message) for wiring into
     * {@link DeathMemory} — the same three-way split
     * (event store + death memory from one death event).
     */
    public void register(Consumer<DeathFacts> onDeath) {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) {
                return;
            }
            var player = client.player;
            long now = System.currentTimeMillis();

            // ── Health changes (self damage/heal + death detection) ──
            if (lastPlayerHealth != null && player.getHealth() != lastPlayerHealth) {
                if (player.isDeadOrDying()) {
                    // Kill credit covers mob kills; environmental
                    // deaths (drown/lava/fall/…) have none — fall back to the
                    // most recent game-message death text from ChatBuffer so
                    // inferCauseFromMessage sees the real server wording.
                    String message = player.getKillCredit() != null
                            ? "killed by " + player.getKillCredit()
                            : findDeathMessageInChat(player);
                    String cause = DeathMemory.inferCauseFromMessage(message);
                    add("death", "self", cause, (double) player.getHealth(), null, null, message);
                    if (onDeath != null) {
                        onDeath.accept(new DeathFacts(
                                player.blockPosition().getX(), player.blockPosition().getY(),
                                player.blockPosition().getZ(), cause, message));
                    }
                    // Arm auto-respawn — the death screen accepts a
                    // respawn request after it fully opens; 2s is comfortably
                    // past the vanilla fade for tick-cadence scheduling.
                    if (respawnAttempts == 0) {
                        respawnDeadlineMs = now + RESPAWN_DELAY_MS;
                    }
                } else {
                    add(lastPlayerHealth > player.getHealth() ? "entityHurt": "health",
                            "self", null, (double) player.getHealth(), null, null, null);
                }
            }

            // ── Auto-respawn while the death screen is up ──
            // LocalPlayer.respawn() is the vanilla death-screen Respawn
            // button path (sends ServerboundRespawnPacket). Bounded retries
            // (2) so a rejected/hard state never spams the server; state
            // resets the moment the player is alive again.
            if (player.isDeadOrDying()) {
                if (respawnAttempts < MAX_RESPAWN_ATTEMPTS && now >= respawnDeadlineMs) {
                    respawnAttempts++;
                    respawnDeadlineMs = now + RESPAWN_RETRY_MS;
                    player.respawn();
                }
            } else if (respawnAttempts > 0) {
                respawnAttempts = 0;
                respawnDeadlineMs = 0L;
            }
            lastPlayerHealth = player.getHealth();

            // ── Oxygen ──
            if (player.getAirSupply() < player.getMaxAirSupply()) {
                if (lastAirSupply != null && lastAirSupply > player.getAirSupply()
                        && System.currentTimeMillis() - lastAirEventMs > 2000) {
                    add("oxygen", "self", null, null, null, null, null);
                    lastAirEventMs = System.currentTimeMillis();
                }
            }
            lastAirSupply = player.getAirSupply();

            // ──: food level (survival trigger) ──
            // A food-level change emits a "food" event whose `health`
            // payload carries the current level; policies match it
            // at-or-below a threshold (eat at <= 14 keeps sprint and
            // regeneration up).
            if (lastFoodLevel != null
                    && player.getFoodData().getFoodLevel() != lastFoodLevel) {
                add("food", "self", null,
                        (double) player.getFoodData().getFoodLevel(),
                        null, null, null);
            }
            lastFoodLevel = player.getFoodData().getFoodLevel();

            // ── Hostile spawns: sample the vicinity on a slower cadence ──
            if (now - lastSpawnScanMs > 5000) {
                lastSpawnScanMs = now;
                scanForHostileSpawns(client);
            }

            // ── Weather ──
            if (client.level.isRaining() != lastRaining
                    || client.level.isThundering() != lastThundering) {
                lastRaining = client.level.isRaining();
                lastThundering = client.level.isThundering();
                add("weatherUpdate", null, null, null, null,
                        (lastRaining ? (lastThundering ? "thunder": "rain"): "clear"), null);
            }
            // keep now consistent
            lastTickMs = now;
        });
    }

    /** Position/cause/message facts handed to the death-memory consumer. */
    public record DeathFacts(int x, int y, int z, String cause, String message) { }

    private void scanForHostileSpawns(Minecraft client) {
        // Nearby-hostile snapshot compare: a newly-seen hostile ⇒ entitySpawn event.
        // Radius kept small (32) so the per-5s scan stays cheap on the client thread.
        List<String> hostilesNow = new ArrayList<>();
        client.level.entitiesForRendering().forEach(e -> {
            if (e instanceof Enemy && e.isAlive() && client.player != null
                    && e.distanceToSqr(client.player) < 32 * 32) {
                hostilesNow.add(e.getType().toShortString());
            }
        });

        for (String type : hostilesNow) {
            if (!lastHostileSet.contains(type)) {
                add("entitySpawn", type, null, null, true, null, null);
            }
        }
        lastHostileSet.clear();
        lastHostileSet.addAll(hostilesNow);
    }

    //: ChatBuffer reference for death-message
    // fallback — environmental deaths carry no kill credit, so the
    // server's game-message death text is the only honest source.
    // Wired in HyFuseClient BEFORE register().
    private com.hyfuse.bridge.chat.ChatBuffer chatBuffer;

    /** Set the chat buffer consulted on death (nullable). */
    public void setChatBuffer(com.hyfuse.bridge.chat.ChatBuffer buffer) {
        this.chatBuffer = buffer;
    }

    // Sampler state (client thread only).
    private static final long RESPAWN_DELAY_MS = 2000L;
    private static final long RESPAWN_RETRY_MS = 3000L;
    private static final int MAX_RESPAWN_ATTEMPTS = 2;
    private int respawnAttempts = 0;
    private long respawnDeadlineMs = 0L;

    /**
     * Scan the chat buffer's newest GAME entries for the
     * server's death message (e.g. "PlayerName drowned"). Prefers an
     * entry naming this player whose inferred cause is not "unknown";
     * falls back to any recent game message with a recognizable death
     * cause; returns null when nothing plausible is buffered.
     */
    private String findDeathMessageInChat(net.minecraft.client.player.LocalPlayer player) {
        if (chatBuffer == null) {
            return null;
        }
        String playerName = player.getGameProfile().name();
        String named = null;
        String anyCause = null;
        for (com.hyfuse.bridge.chat.ChatBuffer.ChatEntry entry : chatBuffer.peek(10)) {
            if (!"game".equals(entry.type) || entry.content == null) {
                continue;
            }
            String cause = DeathMemory.inferCauseFromMessage(entry.content);
            if ("unknown".equals(cause)) {
                continue;
            }
            if (entry.content.contains(playerName)) {
                named = entry.content;
                break;
            }
            if (anyCause == null) {
                anyCause = entry.content;
            }
        }
        return named != null ? named : anyCause;
    }

    // Sampler state (client thread only)
    private Float lastPlayerHealth;
    private Integer lastFoodLevel;
    private Integer lastAirSupply;
    private long lastAirEventMs;
    private long lastSpawnScanMs;
    private long lastTickMs;
    private boolean lastRaining;
    private boolean lastThundering;
    private final java.util.HashSet<String> lastHostileSet = new java.util.HashSet<>();
}
