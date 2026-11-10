package dev.meshcore.atak.engine;

import dev.meshcore.atak.mctak.Counters;

import java.util.ArrayDeque;
import java.util.List;

/**
 * v7.5 R2: radio messages waiting to be shown in the ATAK chat room. The host calls
 * {@link #pump} every tick with the messages that arrived since the last tick. When showing fails
 * because the room is not up yet (ATAK has no room contact, or refused the event), the message
 * stays here and is tried again on the next tick, in the same order, instead of being dropped.
 *
 * <p>Bounded three ways: at most {@link #CAP} messages are held (the oldest goes first, counted),
 * a message older than {@link #MAX_AGE_MS} is dropped (counted), and at most {@link #PER_TICK}
 * are shown per call so a drained backlog does not hold the main thread. Every drop is a
 * counter; the message text is never logged. No ATAK types here, so it is unit tested without the SDK.
 */
public final class RoomShowQueue {
    /** One message is shown or refused by the room. */
    public interface Shower {
        boolean show(Engine.RoomMessage message);
    }

    /** Messages held at most: above the largest offline queue the drain can bring (300). */
    public static final int CAP = 320;
    /** Longest a message waits for the room before it is dropped. */
    public static final long MAX_AGE_MS = 120_000L;
    /** Most messages shown in one call. */
    public static final int PER_TICK = 40;

    private final ArrayDeque<Engine.RoomMessage> held = new ArrayDeque<>();

    /**
     * Shows what it can, oldest first. Stops at the first message the room refuses, so order is
     * kept, and tries it again on the next call.
     */
    public synchronized void pump(List<Engine.RoomMessage> fresh, long nowMs, Shower shower, Counters counters) {
        for (Engine.RoomMessage message : fresh) {
            if (held.size() >= CAP) {
                held.pollFirst();
                counters.inc("room_msg_show_failed");
                counters.inc("room_msg_retry_overflow");
            }
            held.addLast(message);
        }
        int shownNow = 0;
        while (!held.isEmpty() && shownNow < PER_TICK) {
            Engine.RoomMessage head = held.peekFirst();
            if (nowMs - head.receivedMs > MAX_AGE_MS) {
                held.pollFirst();
                counters.inc("room_msg_show_failed");
                counters.inc("room_msg_retry_expired");
                continue;
            }
            boolean ok;
            try {
                ok = shower.show(head);
            } catch (Throwable t) {
                ok = false;
            }
            if (!ok) {
                counters.inc("room_msg_show_retry");
                return;
            }
            held.pollFirst();
            counters.inc("room_msg_shown");
            shownNow++;
        }
    }

    /** The room went away (the channel or the link changed): what is held belongs to the old room. */
    public synchronized void clear(Counters counters) {
        for (int i = held.size(); i > 0; i--) {
            counters.inc("room_msg_show_failed");
        }
        if (!held.isEmpty()) {
            counters.inc("room_msg_retry_cleared");
        }
        held.clear();
    }

    public synchronized int size() {
        return held.size();
    }
}
