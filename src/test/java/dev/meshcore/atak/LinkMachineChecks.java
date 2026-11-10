package dev.meshcore.atak;

import dev.meshcore.atak.companion.Companion;
import dev.meshcore.atak.engine.Engine;
import dev.meshcore.atak.transport.LinkMachine;
import dev.meshcore.atak.transport.LinkMachine.State;
import dev.meshcore.atak.transport.RadioSession;

/**
 * Connect-stage rules without a phone. R7 / B1: a tick while the link is in
 * progress must never close or restart it. Also covers stage time limits,
 * auto-retry rules, and the B3 write-refusal and R5 handshake failure paths.
 */
final class LinkMachineChecks {
    private LinkMachineChecks() {}

    private static final State[] IN_PROGRESS = {
        State.SCAN, State.CONNECTING, State.PAIRING, State.DISCOVER, State.MTU, State.NOTIFY, State.HANDSHAKE
    };

    /** Walk a fresh machine up to {@code target} along the normal path. */
    private static LinkMachine at(State target, long t0) {
        LinkMachine m = new LinkMachine();
        check(m.enter(State.SCAN, t0, "start", LinkMachine.NO_STATUS));
        State[] path = {State.CONNECTING, State.PAIRING, State.DISCOVER, State.MTU, State.NOTIFY, State.HANDSHAKE, State.READY};
        if (target == State.SCAN) {
            return m;
        }
        for (State s : path) {
            check(m.enter(s, t0, "step", LinkMachine.NO_STATUS));
            if (s == target) {
                return m;
            }
        }
        throw new AssertionError("unreachable target " + target);
    }

    /**
     * B1 regression. The v6 host tick called start() whenever the session said
     * "not connected", and start() tore down a connection still being made. The
     * v7 tick asks the machine first. Here the tick runs every second for the
     * whole stage limit with the real session reporting reconnectDue=true.
     */
    static void tickNeverClosesLinkInProgress() {
        RadioSession session = new RadioSession(new Engine());
        long t0 = 1_000_000L;
        for (State stage : IN_PROGRESS) {
            LinkMachine m = at(stage, t0);
            long limit = LinkMachine.limitMs(stage);
            int teardowns = 0;
            long end = limit > 0 ? limit - 1L : 120_000L;
            for (long t = t0 + 1_000L; t <= t0 + end; t += 1_000L) {
                boolean reconnectDue = session.reconnectDue(t);
                check(reconnectDue); // the exact condition that broke v6
                // Host tick decision, with the user wanting the link and auto-retry on or off.
                if (LinkMachine.autoStartDue(m.state(), true, true, reconnectDue)
                        || LinkMachine.autoStartDue(m.state(), true, false, reconnectDue)) {
                    teardowns++;
                }
                // A user CONNECT tap or any start() path is gated by canStart().
                if (m.canStart()) {
                    teardowns++;
                }
                check(!m.checkTimeout(t));
            }
            eq(0, teardowns);
            eq(stage, m.state());
        }
        // READY is also untouchable.
        LinkMachine ready = at(State.READY, t0);
        check(!ready.canStart());
        check(!LinkMachine.autoStartDue(State.READY, true, true, true));
    }

    static void stageTimeouts() {
        long t0 = 5_000L;
        eq(20_000L, LinkMachine.limitMs(State.CONNECTING));
        eq(10_000L, LinkMachine.limitMs(State.DISCOVER));
        eq(5_000L, LinkMachine.limitMs(State.MTU));
        eq(5_000L, LinkMachine.limitMs(State.NOTIFY));
        eq(60_000L, LinkMachine.limitMs(State.PAIRING));

        LinkMachine m = at(State.CONNECTING, t0);
        check(!m.checkTimeout(t0 + 19_999L));
        check(m.checkTimeout(t0 + 20_000L));
        eq(State.FAILED, m.state());
        eq(State.CONNECTING, m.failStage());
        eq("connecting_timeout", m.failReason());
        check(m.canStart());
        // A failure before READY never retries on its own.
        check(!m.autoRetry());
        check(!m.autoStartDue(true, true));

        LinkMachine pair = at(State.PAIRING, t0);
        check(!pair.checkTimeout(t0 + 59_999L));
        check(pair.checkTimeout(t0 + 60_000L));

        // A status code is kept with the stage name.
        LinkMachine g = at(State.DISCOVER, t0);
        g.fail(t0 + 1, "discover_failed", 133);
        eq(133, g.failStatus());
        eq(State.DISCOVER, g.failStage());
        check(g.failureText().contains("status 133"));

        // Loss after READY keeps auto-retry on; the retry only fires from FAILED/IDLE.
        LinkMachine up = at(State.READY, t0);
        up.fail(t0 + 10, "radio disconnected", 8);
        eq(State.READY, up.failStage());
        check(up.autoRetry());
        check(up.autoStartDue(true, true));
        check(!up.autoStartDue(false, true));
        check(!up.autoStartDue(true, false));
        // User DISCONNECT turns it off.
        up.reset(t0 + 20, "stopped");
        check(!up.autoRetry());
        eq(State.IDLE, up.state());
    }

    static void lateCallbacksCannotReviveALink() {
        LinkMachine m = at(State.CONNECTING, 0L);
        m.fail(10L, "gatt error", 133);
        // A stale GATT callback tries to move the failed link forward.
        check(!m.enter(State.DISCOVER, 20L, "late", LinkMachine.NO_STATUS));
        check(!m.enter(State.READY, 20L, "late", LinkMachine.NO_STATUS));
        eq(State.FAILED, m.state());
        // A new attempt may start, and it clears the old failure.
        check(m.enter(State.SCAN, 30L, "again", LinkMachine.NO_STATUS));
        eq("", m.failReason());
        // A second new attempt while one runs is refused.
        check(!m.enter(State.SCAN, 31L, "again", LinkMachine.NO_STATUS));
    }

    static void buttonLabels() {
        LinkMachine m = at(State.SCAN, 0L);
        eq("Scanning 15s\u2026", m.buttonLabel(0L));
        eq("Scanning 12s\u2026", m.buttonLabel(3_000L));
        m.enter(State.CONNECTING, 3_100L, "c", LinkMachine.NO_STATUS);
        eq("Connecting\u2026", m.buttonLabel(3_100L));
        m.enter(State.PAIRING, 4_000L, "p", LinkMachine.NO_STATUS);
        eq("Pairing\u2026", m.buttonLabel(4_000L));
        LinkMachine idle = new LinkMachine();
        eq("CONNECT", idle.buttonLabel(0L));
    }

    /** B3: a refused write keeps the frame for a retry instead of dropping it. */
    static void refusedWriteKeepsFrame() {
        Engine engine = new Engine();
        RadioSession session = new RadioSession(engine);
        long t = 10_000L;
        session.onConnected(t);
        session.onMtu(512, t);
        session.onNotifications(true);
        session.tick(t);
        byte[] first = session.peekWrite();
        check(first != null && (first[0] & 0xFF) == Companion.CMD_APP_START);
        // peek does not remove.
        check(session.peekWrite() == first);
        eq(1, session.queuedWrites());
        // Android refuses.
        session.writeRefused();
        eq(0, session.queuedWrites());
        eq(1, session.consecutiveWriteRefusals());
        eq(1, engine.counters().get("write_refused"));
        // Next tick re-offers the same command, once.
        session.tick(t + 100L);
        byte[] again = session.peekWrite();
        check(again != null && (again[0] & 0xFF) == Companion.CMD_APP_START);
        eq(1, session.queuedWrites());
        // Accepted: now it leaves the queue and the refusal count resets.
        session.writeAccepted();
        check(session.pollWrite() != null);
        eq(0, session.consecutiveWriteRefusals());
        check(!session.handshakeFailed());
    }

    /** R5: both ways the handshake can give up are now visible state, not hidden counters. */
    static void handshakeFailuresAreVisible() {
        // No reply: after the retry the session reports which command got no answer.
        Engine engine = new Engine();
        RadioSession session = new RadioSession(engine);
        long t = 10_000L;
        session.onConnected(t);
        session.onMtu(512, t);
        session.onNotifications(true);
        session.tick(t);
        check(session.pollWrite() != null);
        long timedOut = t + 5_000L;
        session.tick(timedOut);
        session.tick(timedOut + 100L);
        check(session.pollWrite() != null);
        long second = timedOut + 100L + 5_000L;
        session.tick(second);
        check(session.handshakeFailed());
        eq("APP_START", session.handshakeFailedCommand());

        // MTU too small for the first handshake frame (APP_START is 19 bytes, needs MTU 22).
        Engine small = new Engine();
        RadioSession tiny = new RadioSession(small);
        tiny.onConnected(t);
        tiny.onMtu(20, t);
        tiny.onNotifications(true);
        tiny.tick(t);
        check(tiny.handshakeMtuBlocked());
        eq(20, tiny.mtu());
        check(tiny.pollWrite() == null);
    }

    private static void check(boolean value) {
        if (!value) {
            throw new AssertionError("expected true");
        }
    }

    private static void eq(Object expect, Object actual) {
        if (expect == null ? actual != null : !expect.equals(actual)) {
            throw new AssertionError("expected " + expect + " but was " + actual);
        }
    }

    private static void eq(long expect, long actual) {
        if (expect != actual) {
            throw new AssertionError("expected " + expect + " but was " + actual);
        }
    }
}
