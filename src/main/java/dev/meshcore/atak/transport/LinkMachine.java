package dev.meshcore.atak.transport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Connection stages for one companion radio link. No Android types, so the
 * rules can be tested on a JVM without a phone.
 *
 * <p>Flow: IDLE, SCAN, CONNECTING, PAIRING, DISCOVER, MTU, NOTIFY, HANDSHAKE,
 * READY. Any stage can end in FAILED with the stage name, a reason and, when
 * Android gave one, the GATT status code.
 *
 * <p>The rule that fixes the v6 self-cancel bug (B1): the link may only be
 * started, torn down or restarted from IDLE or FAILED. A periodic tick must
 * never touch a link that is in any other stage. {@link #canStart()} and
 * {@link #autoStartDue} are the only gates the Android code uses for that.
 *
 * <p>All callers run on the Android main thread (GATT callbacks are posted
 * there), but the methods are synchronized so a stray caller cannot corrupt the
 * state.
 */
public final class LinkMachine {
    public enum State { IDLE, SCAN, CONNECTING, PAIRING, DISCOVER, MTU, NOTIFY, HANDSHAKE, READY, FAILED }

    /** "No GATT status" marker for history entries. */
    public static final int NO_STATUS = Integer.MIN_VALUE;

    public static final long SCAN_WINDOW_MS = 15_000L;
    /** After the first match, wait this long for a stronger one, then connect. */
    public static final long EARLY_CONNECT_WAIT_MS = 2_000L;
    public static final long CONNECT_TIMEOUT_MS = 20_000L;
    public static final long PAIRING_TIMEOUT_MS = 60_000L;
    public static final long DISCOVER_TIMEOUT_MS = 10_000L;
    public static final long MTU_TIMEOUT_MS = 5_000L;
    public static final long NOTIFY_TIMEOUT_MS = 5_000L;
    /** Safety net only. The scan window itself is closed by the link at SCAN_WINDOW_MS. */
    private static final long SCAN_SAFETY_MS = SCAN_WINDOW_MS + 1_000L;
    private static final int HISTORY_CAP = 64;

    public static final class Entry {
        public final long atMs;
        public final State state;
        public final String detail;
        public final int status;

        Entry(long atMs, State state, String detail, int status) {
            this.atMs = atMs;
            this.state = state;
            this.detail = detail;
            this.status = status;
        }
    }

    /** Called synchronously while the machine's lock is NOT held. */
    public interface Listener {
        void onTransition(Entry entry);
    }

    private State state = State.IDLE;
    private long stageStartMs;
    private String failReason = "";
    private State failStage = State.IDLE;
    private int failStatus = NO_STATUS;
    private boolean autoRetry;
    private boolean reachedReady;
    private final List<Entry> history = new ArrayList<Entry>();
    private Listener listener;

    public synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized long stageStartMs() {
        return stageStartMs;
    }

    public synchronized String failReason() {
        return failReason;
    }

    public synchronized State failStage() {
        return failStage;
    }

    public synchronized int failStatus() {
        return failStatus;
    }

    public synchronized boolean autoRetry() {
        return autoRetry;
    }

    public synchronized List<Entry> history() {
        return Collections.unmodifiableList(new ArrayList<Entry>(history));
    }

    /** True only in IDLE or FAILED. Nothing may close or restart the link otherwise. */
    public synchronized boolean canStart() {
        return state == State.IDLE || state == State.FAILED;
    }

    /** SCAN through HANDSHAKE: an attempt is in progress. */
    public synchronized boolean inProgress() {
        return state != State.IDLE && state != State.FAILED && state != State.READY;
    }

    public synchronized boolean isReady() {
        return state == State.READY;
    }

    /**
     * Whether the 1 s host tick may start a new attempt. Only after a link that
     * had reached READY was lost, only while the user wants the link, only from
     * IDLE or FAILED, and only once the session back-off has passed.
     */
    public static boolean autoStartDue(State state, boolean linkWanted, boolean autoRetry,
            boolean sessionReconnectDue) {
        return linkWanted && autoRetry && sessionReconnectDue
                && (state == State.IDLE || state == State.FAILED);
    }

    public boolean autoStartDue(boolean linkWanted, boolean sessionReconnectDue) {
        synchronized (this) {
            return autoStartDue(state, linkWanted, autoRetry, sessionReconnectDue);
        }
    }

    /**
     * Move to a stage. A new attempt (SCAN, or CONNECTING when a bonded radio is
     * used) may only begin from IDLE or FAILED. Every later stage may only be
     * entered while an attempt is in progress, so a late GATT callback cannot
     * revive a link that already failed or was stopped. Returns false and
     * changes nothing when the move is not allowed.
     */
    public boolean enter(State to, long nowMs, String detail, int status) {
        Entry entry;
        Listener l;
        synchronized (this) {
            boolean idleOrFailed = state == State.IDLE || state == State.FAILED;
            boolean newAttempt = to == State.SCAN || (to == State.CONNECTING && idleOrFailed);
            if (newAttempt) {
                if (!idleOrFailed) {
                    return false;
                }
                failReason = "";
                failStatus = NO_STATUS;
                failStage = State.IDLE;
                reachedReady = false;
            } else if (to == State.IDLE || to == State.FAILED || !inProgressLocked()) {
                return false;
            }
            state = to;
            stageStartMs = nowMs;
            if (to == State.READY) {
                reachedReady = true;
                autoRetry = true;
            }
            entry = record(nowMs, to, detail, status);
            l = listener;
        }
        notifyListener(l, entry);
        return true;
    }

    private boolean inProgressLocked() {
        return state != State.IDLE && state != State.FAILED && state != State.READY;
    }

    /**
     * End the attempt as FAILED. Records the stage that was running, the reason
     * and the status. A failure before READY switches auto-retry off so a
     * failing link can never loop silently; a loss after READY keeps it on.
     */
    public void fail(long nowMs, String reason, int status) {
        Entry entry;
        Listener l;
        synchronized (this) {
            if (state == State.FAILED) {
                return;
            }
            boolean wasReady = state == State.READY;
            failStage = state;
            failReason = reason == null ? "unknown" : reason;
            failStatus = status;
            state = State.FAILED;
            stageStartMs = nowMs;
            autoRetry = wasReady;
            entry = record(nowMs, State.FAILED, failStage.name() + ": " + failReason, status);
            l = listener;
        }
        notifyListener(l, entry);
    }

    /** User pressed DISCONNECT, or the host stopped. Back to IDLE with auto-retry off. */
    public void reset(long nowMs, String detail) {
        Entry entry;
        Listener l;
        synchronized (this) {
            state = State.IDLE;
            stageStartMs = nowMs;
            autoRetry = false;
            entry = record(nowMs, State.IDLE, detail, NO_STATUS);
            l = listener;
        }
        notifyListener(l, entry);
    }

    /** Stage time limit in ms, or 0 when the stage has none. */
    public static long limitMs(State s) {
        switch (s) {
            case SCAN: return SCAN_SAFETY_MS;
            case CONNECTING: return CONNECT_TIMEOUT_MS;
            case PAIRING: return PAIRING_TIMEOUT_MS;
            case DISCOVER: return DISCOVER_TIMEOUT_MS;
            case MTU: return MTU_TIMEOUT_MS;
            case NOTIFY: return NOTIFY_TIMEOUT_MS;
            default: return 0L;
        }
    }

    /**
     * Fails the current stage when its time limit has passed. HANDSHAKE has no
     * limit here: the companion session owns its own command timeout and retry.
     * Returns true when this call failed the stage.
     */
    public boolean checkTimeout(long nowMs) {
        State s;
        long start;
        synchronized (this) {
            s = state;
            start = stageStartMs;
        }
        long limit = limitMs(s);
        if (limit > 0L && nowMs - start >= limit) {
            fail(nowMs, s.name().toLowerCase(java.util.Locale.US) + "_timeout", NO_STATUS);
            return true;
        }
        return false;
    }

    /** Whole seconds left in the current stage, never below 0. */
    public synchronized int secondsLeft(long nowMs, long limitMs) {
        long left = limitMs - (nowMs - stageStartMs);
        return left <= 0L ? 0 : (int) ((left + 999L) / 1000L);
    }

    /** Text for the CONNECT button. */
    public synchronized String buttonLabel(long nowMs) {
        switch (state) {
            case SCAN:
                return "Scanning " + secondsLeft(nowMs, SCAN_WINDOW_MS) + "s\u2026";
            case CONNECTING:
            case DISCOVER:
            case MTU:
            case NOTIFY:
                return "Connecting\u2026";
            case PAIRING:
                return "Pairing\u2026";
            case HANDSHAKE:
                return "Handshake\u2026";
            case READY:
                return "Connected";
            default:
                return "CONNECT";
        }
    }

    /** One short phrase for the failure, for status line and toast. */
    public synchronized String failureText() {
        StringBuilder sb = new StringBuilder(failReason);
        if (failStatus != NO_STATUS) {
            sb.append(" (status ").append(failStatus).append(')');
        }
        return sb.toString();
    }

    public synchronized boolean reachedReady() {
        return reachedReady;
    }

    /** Adds a history line without changing the stage (extra detail such as a refused MTU request). */
    public void note(long nowMs, String detail, int status) {
        Entry entry;
        Listener l;
        synchronized (this) {
            entry = record(nowMs, state, detail, status);
            l = listener;
        }
        notifyListener(l, entry);
    }

    private Entry record(long nowMs, State s, String detail, int status) {
        Entry entry = new Entry(nowMs, s, detail == null ? "" : detail, status);
        if (history.size() >= HISTORY_CAP) {
            history.remove(0);
        }
        history.add(entry);
        return entry;
    }

    private static void notifyListener(Listener l, Entry entry) {
        if (l != null) {
            l.onTransition(entry);
        }
    }
}
