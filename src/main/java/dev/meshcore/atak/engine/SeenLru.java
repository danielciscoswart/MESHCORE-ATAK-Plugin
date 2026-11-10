package dev.meshcore.atak.engine;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v7.5: a bounded, expiring "have I seen this key" set for inbound radio messages.
 *
 * <p>It is an access-ordered LRU: a repeat moves its key to the newest position, and when the set
 * is full the least recently seen key is dropped. A key also expires {@code ttlMs} after it was
 * FIRST seen (a repeat does not extend it), so a message that is genuinely sent again much later is
 * not swallowed forever. Keys are short strings built by the caller from a channel, a sender
 * timestamp and a text hash; the text itself is never stored.
 *
 * <p>Not thread-safe. The engine calls it from one thread.
 */
public final class SeenLru {
    private final int capacity;
    private final long ttlMs;
    private final LinkedHashMap<String, Long> firstSeen;

    public SeenLru(int capacity, long ttlMs) {
        if (capacity < 1 || ttlMs < 1) {
            throw new IllegalArgumentException("capacity and ttl must be positive");
        }
        this.capacity = capacity;
        this.ttlMs = ttlMs;
        this.firstSeen = new LinkedHashMap<String, Long>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > SeenLru.this.capacity;
            }
        };
    }

    /**
     * Records {@code key} and returns true when it is new (the caller should process it), or false
     * when it was already seen inside the window (the caller should drop it).
     */
    public boolean firstTime(String key, long nowMs) {
        expire(nowMs);
        Long before = firstSeen.get(key); // access order: this also marks it most recently used
        if (before != null) {
            return false;
        }
        firstSeen.put(key, nowMs);
        return true;
    }

    public int size() {
        return firstSeen.size();
    }

    public void clear() {
        firstSeen.clear();
    }

    private void expire(long nowMs) {
        Iterator<Map.Entry<String, Long>> it = firstSeen.entrySet().iterator();
        while (it.hasNext()) {
            if (nowMs - it.next().getValue() > ttlMs) {
                it.remove();
            }
        }
    }
}
