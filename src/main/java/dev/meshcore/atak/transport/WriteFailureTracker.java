package dev.meshcore.atak.transport;

/**
 * E5: when a GATT write fails, the disconnect callback that explains why often
 * arrives a moment later. v7.1 closed the connection at once, so that status was
 * never seen. This tracker holds the failure for up to {@link #WAIT_MS}. If the
 * disconnect callback arrives in that time its status becomes the final status.
 * Otherwise the write status is final. It also keeps how long ago the radio last
 * sent anything. No Android types.
 */
public final class WriteFailureTracker {
    public static final long WAIT_MS = 2_000L;

    /** The settled outcome of a write failure. */
    public static final class Resolution {
        public final int finalStatus;
        public final int writeStatus;
        public final boolean disconnectSeen;
        public final long sinceNotificationMs;
        public final long waitedMs;
        public final String reason;

        Resolution(int finalStatus, int writeStatus, boolean disconnectSeen, long sinceNotificationMs,
                   long waitedMs, String reason) {
            this.finalStatus = finalStatus;
            this.writeStatus = writeStatus;
            this.disconnectSeen = disconnectSeen;
            this.sinceNotificationMs = sinceNotificationMs;
            this.waitedMs = waitedMs;
            this.reason = reason;
        }

        /** One history line with everything E5 asks for. */
        public String detail() {
            return "write status=" + writeStatus
                    + (disconnectSeen ? ", then disconnect status=" + finalStatus : ", no disconnect in " + waitedMs + " ms")
                    + ", " + notificationText(sinceNotificationMs);
        }
    }

    private boolean pending;
    private long startedMs;
    private int writeStatus;
    private long sinceNotificationMs = -1L;

    /** {@code lastNotificationMs} is 0 when the radio has not sent anything on this link. */
    public synchronized void begin(long nowMs, int writeStatus, long lastNotificationMs) {
        if (pending) {
            return; // keep the first failure
        }
        pending = true;
        startedMs = nowMs;
        this.writeStatus = writeStatus;
        this.sinceNotificationMs = lastNotificationMs <= 0L ? -1L : Math.max(0L, nowMs - lastNotificationMs);
    }

    public synchronized boolean pending() {
        return pending;
    }

    public synchronized boolean expired(long nowMs) {
        return pending && nowMs - startedMs >= WAIT_MS;
    }

    /** The disconnect callback arrived. Its status is final. Null when nothing was pending. */
    public synchronized Resolution resolveByDisconnect(long nowMs, int disconnectStatus) {
        if (!pending) {
            return null;
        }
        pending = false;
        return new Resolution(disconnectStatus, writeStatus, true, sinceNotificationMs,
                nowMs - startedMs, "write failed, then radio disconnected");
    }

    /** No disconnect arrived in time. The write status is final. Null when nothing was pending. */
    public synchronized Resolution resolveByTimeout(long nowMs) {
        if (!pending) {
            return null;
        }
        pending = false;
        return new Resolution(writeStatus, writeStatus, false, sinceNotificationMs,
                nowMs - startedMs, "write failed");
    }

    public synchronized void clear() {
        pending = false;
    }

    /** "last notification 21345 ms ago" or "no notification on this link". */
    public static String notificationText(long sinceMs) {
        return sinceMs < 0L ? "no notification on this link" : "last notification " + sinceMs + " ms ago";
    }
}
