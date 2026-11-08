package dev.meshcore.atak.engine;

import dev.meshcore.atak.mctak.Airtime;
import dev.meshcore.atak.mctak.Counters;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Token-bucket airtime governor. Position reports are not queued: if they
 * cannot go out on this tick they are skipped. Chat and receipts wait in a
 * bounded queue until they are sent or they expire.
 */
public final class Governor {
    public enum Klass { P0, P1, P2 }

    public static final class Item {
        public final Klass klass;
        public final byte[] frame;
        public final int costMs;
        public final long deadlineMs;
        public final String label;

        Item(Klass klass, byte[] frame, int costMs, long deadlineMs, String label) {
            this.klass = klass;
            this.frame = frame;
            this.costMs = costMs;
            this.deadlineMs = deadlineMs;
            this.label = label;
        }
    }

    private final Counters counters;
    private final double duty;
    private final int capacityMs;
    private double tokens;
    private long lastRefillMs;
    private long lastEmitMs = Long.MIN_VALUE / 4;
    private final Deque<Item> p0 = new ArrayDeque<>();
    private final Deque<Item> p1 = new ArrayDeque<>();
    private final Deque<Item> p2 = new ArrayDeque<>();

    public Governor(Counters counters, int freqKhz, long nowMs) {
        this.counters = counters;
        this.duty = Airtime.duty(freqKhz);
        this.capacityMs = Airtime.bucketCapacityMs(duty);
        this.tokens = capacityMs;
        this.lastRefillMs = nowMs;
    }

    public double duty() {
        return duty;
    }

    public int tokensMs() {
        return (int) Math.floor(tokens);
    }

    public int queued() {
        return p0.size() + p1.size() + p2.size();
    }

    public void refill(long nowMs) {
        long elapsed = nowMs - lastRefillMs;
        if (elapsed > 0) {
            tokens = Math.min(capacityMs, tokens + elapsed * duty);
        }
        lastRefillMs = nowMs;
        expire(p0, nowMs);
        expire(p1, nowMs);
        expire(p2, nowMs);
    }

    /**
     * @param skipNotQueue position reports use this. They are not stored.
     * @return false when a skip-not-queue item is not sent, or when a queued item was dropped for depth
     */
    public boolean offer(Klass klass, byte[] frame, int costMs, long nowMs, String label, boolean skipNotQueue) {
        refill(nowMs);
        if (skipNotQueue) {
            if (tokens < costMs || !p0.isEmpty() || !p1.isEmpty() || !pacing(nowMs)) {
                counters.inc("pli_skipped");
                return false;
            }
        }
        Deque<Item> queue = queue(klass);
        int depth = 8;
        if (queue.size() >= depth) {
            queue.removeFirst();
            counters.inc("queue_drop");
        }
        long life = klass == Klass.P0 ? 30_000L : 60_000L;
        queue.addLast(new Item(klass, Arrays.copyOf(frame, frame.length), costMs, nowMs + life, label));
        return true;
    }

    /** Next item that {@link #poll} would remove, without removing it. */
    public Item peek(long nowMs) {
        refill(nowMs);
        if (!pacing(nowMs)) {
            return null;
        }
        Item item = head(p0);
        if (item == null && p0.isEmpty()) {
            item = head(p1);
        }
        if (item == null && p0.isEmpty() && p1.isEmpty()) {
            item = head(p2);
        }
        return item;
    }

    public Item poll(long nowMs) {
        Item item = peek(nowMs);
        if (item == null) {
            return null;
        }
        Deque<Item> queue = queue(item.klass);
        if (queue.peekFirst() != item) {
            return null;
        }
        queue.removeFirst();
        tokens -= item.costMs;
        lastEmitMs = nowMs;
        return item;
    }

    private Item head(Deque<Item> queue) {
        Item item = queue.peekFirst();
        if (item == null || tokens < item.costMs) {
            return null;
        }
        return item;
    }

    private boolean pacing(long nowMs) {
        return nowMs - lastEmitMs >= 250L;
    }

    private void expire(Deque<Item> queue, long nowMs) {
        while (!queue.isEmpty() && queue.peekFirst().deadlineMs <= nowMs) {
            queue.removeFirst();
            counters.inc("queue_expired");
        }
    }

    private Deque<Item> queue(Klass klass) {
        if (klass == Klass.P0) {
            return p0;
        }
        if (klass == Klass.P1) {
            return p1;
        }
        return p2;
    }
}
