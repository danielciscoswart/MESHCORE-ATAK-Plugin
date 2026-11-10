package dev.meshcore.atak.plugin;

import java.util.ArrayDeque;

/**
 * H3: the short hand-off queue between ATAK's comms thread and the plugin tick, with no ATAK types
 * so it can be tested. When it is full the NEW item is refused and counted; an item the user
 * already typed is never dropped silently. The caller turns the refusal into a visible message.
 */
public final class BoundedHold<T> {
    private final ArrayDeque<T> items = new ArrayDeque<T>();
    private final int capacity;
    private int refused;

    public BoundedHold(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** True when the item was kept; false when the hold was full (the refusal is counted). */
    public synchronized boolean offer(T item) {
        if (items.size() >= capacity) {
            refused++;
            return false;
        }
        items.addLast(item);
        return true;
    }

    public synchronized T poll() {
        return items.pollFirst();
    }

    public synchronized int size() {
        return items.size();
    }

    /** Refusals since the last call. */
    public synchronized int takeRefused() {
        int n = refused;
        refused = 0;
        return n;
    }

    public synchronized void clear() {
        items.clear();
    }
}
