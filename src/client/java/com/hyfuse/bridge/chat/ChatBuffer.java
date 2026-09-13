package com.hyfuse.bridge.chat;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe ring buffer that captures incoming chat and game messages
 * via Fabric API's {@link ClientReceiveMessageEvents}.
 * <p>
 * Registered once at mod init; drained by the {@code read-chat} tool handler.
 * Each entry is a JSON-serializable record with timestamp, type (chat/game),
 * sender (for chat messages), and the plain-text content.
 */
public final class ChatBuffer {

    /** Maximum number of messages retained in the buffer. */
    private static final int MAX_CAPACITY = 200;

    private final ConcurrentLinkedDeque<ChatEntry> entries = new ConcurrentLinkedDeque<>();
    private final AtomicInteger totalCount = new AtomicInteger(0);

    /** Register event listeners. Call once during client init. */
    public void register() {
        ClientReceiveMessageEvents.CHAT.register((message, playerChatMessage, profile, bound, timestamp) -> {
            add(new ChatEntry("chat", profile != null ? profile.name(): "",
                    message.getString(), timestamp));
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            add(new ChatEntry("game", "", message.getString(), Instant.now()));
        });
    }

    private void add(ChatEntry entry) {
        entries.addLast(entry);
        int total = totalCount.incrementAndGet();
        // Trim oldest entries if over capacity
        while (total > MAX_CAPACITY) {
            entries.pollFirst();
            total = totalCount.decrementAndGet();
        }
    }

    /**
     * Drain up to {@code count} messages from the buffer.
     * Returns the newest-first list (most recent message first).
     * If count <= 0, returns all buffered messages.
     */
    public List<ChatEntry> drain(int count) {
        List<ChatEntry> result = new ArrayList<>();
        if (count <= 0) count = MAX_CAPACITY;
        ChatEntry entry;
        while (result.size() < count && (entry = entries.pollLast()) != null) {
            result.add(entry);
            totalCount.decrementAndGet();
        }
        return result;
    }

    /** Peek at up to {@code count} messages without draining. Newest-first. */
    public List<ChatEntry> peek(int count) {
        if (count <= 0) count = MAX_CAPACITY;
        List<ChatEntry> result = new ArrayList<>();
        var descIter = entries.descendingIterator();
        while (result.size() < count && descIter.hasNext()) {
            result.add(descIter.next());
        }
        return result;
    }

    /** Total messages currently in the buffer. */
    public int size() {
        return entries.size();
    }

    /** Clear all buffered messages. */
    public void clear() {
        entries.clear();
        totalCount.set(0);
    }

    /** A single buffered chat/game message entry. */
    public static final class ChatEntry {
        public final String type; // "chat" or "game"
        public final String sender;    // player name for chat; empty for game messages
        public final String content;   // plain-text message
        public final Instant timestamp;

        ChatEntry(String type, String sender, String content, Instant timestamp) {
            this.type = type;
            this.sender = sender;
            this.content = content;
            this.timestamp = timestamp;
        }
    }
}
