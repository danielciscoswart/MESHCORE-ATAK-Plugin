package dev.meshcore.atak;

import dev.meshcore.atak.companion.Companion;
import dev.meshcore.atak.engine.Engine;
import dev.meshcore.atak.transport.LinkMachine;
import dev.meshcore.atak.transport.LinkMachine.State;
import dev.meshcore.atak.transport.LinkRules;
import dev.meshcore.atak.transport.RadioSession;
import dev.meshcore.atak.util.Bytes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Checks for the second Claude review (C1 to C6) and the small recommended items.
 * What can only be seen with a Bluetooth stack (getConnectedDevices contents, the
 * write callback, createBond) is covered by source checks in
 * scripts/test-plugin-startup.sh, which verify that the Android code calls these rules.
 */
final class LinkRulesChecks {
    private LinkRulesChecks() {}

    /** C1: the held-by-another-app decision, and the text Daniel gets. */
    static void c1HeldByAnotherApp() {
        List<String> connected = Arrays.asList("AA:BB:CC:DD:EE:01", "aa:bb:cc:dd:ee:02");
        check(LinkRules.heldByAnotherApp(connected, "AA:BB:CC:DD:EE:02"));
        check(LinkRules.heldByAnotherApp(connected, "aa:bb:cc:dd:ee:01"));
        check(!LinkRules.heldByAnotherApp(connected, "AA:BB:CC:DD:EE:03"));
        check(!LinkRules.heldByAnotherApp(new ArrayList<String>(), "AA:BB:CC:DD:EE:03"));
        check(!LinkRules.heldByAnotherApp(null, "AA:BB:CC:DD:EE:03"));
        check(LinkRules.HELD_REASON.contains("force-stop MeshCore"));
        check(LinkRules.HELD_ADVICE.contains("Force-stop MeshCore"));
        // Just after our own close the list may still show our old connection: skip the check.
        check(LinkRules.ownCloseStillSettling(10_000L, 12_000L));
        check(!LinkRules.ownCloseStillSettling(10_000L, 15_000L));
        check(!LinkRules.ownCloseStillSettling(0L, 1L));
        // The failure carries the reason and fails the CONNECTING stage.
        LinkMachine m = new LinkMachine();
        m.enter(State.SCAN, 0L, "s", LinkMachine.NO_STATUS);
        m.enter(State.CONNECTING, 1L, "c", LinkMachine.NO_STATUS);
        m.fail(2L, LinkRules.HELD_REASON, LinkMachine.NO_STATUS);
        eq(State.CONNECTING, m.failStage());
        eq(LinkRules.HELD_REASON, m.failureText());
    }

    /** C2: which stages a failed write ends, and that the text keeps the status code. */
    static void c2WriteFailureEndsAttempt() {
        check(LinkRules.writeFailureEndsAttempt(State.HANDSHAKE));
        check(LinkRules.writeFailureEndsAttempt(State.READY));
        check(!LinkRules.writeFailureEndsAttempt(State.CONNECTING));
        check(!LinkRules.writeFailureEndsAttempt(State.IDLE));
        check(!LinkRules.writeFailureEndsAttempt(State.FAILED));
        LinkMachine m = new LinkMachine();
        m.enter(State.SCAN, 0L, "s", LinkMachine.NO_STATUS);
        m.enter(State.CONNECTING, 0L, "c", LinkMachine.NO_STATUS);
        m.enter(State.PAIRING, 0L, "p", LinkMachine.NO_STATUS);
        m.enter(State.DISCOVER, 0L, "d", LinkMachine.NO_STATUS);
        m.enter(State.MTU, 0L, "m", LinkMachine.NO_STATUS);
        m.enter(State.NOTIFY, 0L, "n", LinkMachine.NO_STATUS);
        m.enter(State.HANDSHAKE, 0L, "h", LinkMachine.NO_STATUS);
        m.fail(5L, "write failed", 5);
        eq("write failed (status 5), likely pairing keys rejected", m.failureText());
        eq(State.HANDSHAKE, m.failStage());
    }

    /** C3: exactly the codes Claude listed get a "likely" hint; others get none. */
    static void c3StatusHints() {
        for (int code : new int[] {5, 15, 137}) {
            eq("likely pairing keys rejected", LinkRules.hint(code));
            check(LinkRules.advice(code).contains("remove the radio from Android's paired devices"));
        }
        eq("likely link timed out", LinkRules.hint(8));
        eq("likely radio closed the link", LinkRules.hint(19));
        eq("likely could not connect", LinkRules.hint(133));
        eq("likely could not connect", LinkRules.hint(62));
        eq("", LinkRules.hint(0));
        eq("", LinkRules.hint(257));
        LinkMachine m = new LinkMachine();
        m.enter(State.SCAN, 0L, "s", LinkMachine.NO_STATUS);
        m.enter(State.CONNECTING, 0L, "c", LinkMachine.NO_STATUS);
        m.fail(1L, "gatt error", 133);
        eq("gatt error (status 133), likely could not connect", m.failureText());
        // No status, no hint.
        LinkMachine n = new LinkMachine();
        n.enter(State.SCAN, 0L, "s", LinkMachine.NO_STATUS);
        n.fail(1L, "scan_timeout seen=0", LinkMachine.NO_STATUS);
        eq("scan_timeout seen=0", n.failureText());
    }

    /** C4: createBond()==false is fatal only when the bond state is still NONE. */
    static void c4CreateBondRefused() {
        check(!LinkRules.bondRefusalIsFatal(LinkRules.BOND_BONDING));
        check(!LinkRules.bondRefusalIsFatal(LinkRules.BOND_BONDED));
        check(LinkRules.bondRefusalIsFatal(LinkRules.BOND_NONE));
        // Android's real constants, so the copies here cannot drift: NONE=10, BONDING=11, BONDED=12.
        eq(10, LinkRules.BOND_NONE);
        eq(11, LinkRules.BOND_BONDING);
        eq(12, LinkRules.BOND_BONDED);
        // Follow-up: never seeing BONDING for 10 s after createBond() is a failure; sooner is not.
        check(!LinkRules.pairingNeverStarted(false, LinkRules.BOND_NONE, 9_999L));
        check(LinkRules.pairingNeverStarted(false, LinkRules.BOND_NONE, 10_000L));
        check(!LinkRules.pairingNeverStarted(true, LinkRules.BOND_NONE, 60_000L));
        check(!LinkRules.pairingNeverStarted(false, LinkRules.BOND_BONDING, 60_000L));
    }

    /** C5: an MTU of 23 or less is a visible failure; anything larger passes. */
    static void c5MtuNotNegotiated() {
        eq("MTU not negotiated (23)", LinkRules.mtuProblem(23));
        eq("MTU not negotiated (20)", LinkRules.mtuProblem(20));
        check(LinkRules.mtuProblem(24) == null);
        check(LinkRules.mtuProblem(176) == null);
        check(LinkRules.mtuProblem(517) == null);
        LinkMachine m = new LinkMachine();
        m.enter(State.SCAN, 0L, "s", LinkMachine.NO_STATUS);
        m.enter(State.CONNECTING, 0L, "c", LinkMachine.NO_STATUS);
        m.enter(State.PAIRING, 0L, "p", LinkMachine.NO_STATUS);
        m.enter(State.DISCOVER, 0L, "d", LinkMachine.NO_STATUS);
        m.enter(State.MTU, 0L, "m", LinkMachine.NO_STATUS);
        m.fail(1L, LinkRules.mtuProblem(23), LinkMachine.NO_STATUS);
        eq(State.MTU, m.failStage());
        eq("MTU not negotiated (23)", m.failureText());
    }

    /** C6: handshake progress for the status line follows the real session phases. */
    static void c6HandshakeProgress() {
        Engine engine = new Engine();
        RadioSession session = new RadioSession(engine);
        long t = 10_000L;
        session.onConnected(t);
        session.onMtu(512, t);
        session.onNotifications(true);
        eq("handshake: app start", session.handshakeProgress());
        session.tick(t);
        check(session.pollWrite() != null);
        byte[] key = new byte[32];
        Bytes.putRaw4(key, 0, 0xCAFEBABE);
        session.onNotification(Companion.selfInfo(key, 910_525, 62_500, 8, 5, "node"), t + 10);
        eq("handshake: device query", session.handshakeProgress());
        session.tick(t + 80);
        check(session.pollWrite() != null);
        byte[] device = new byte[82];
        device[0] = Companion.PACKET_DEVICE_INFO;
        device[1] = 3;
        device[3] = 40;
        session.onNotification(device, t + 100);
        eq("handshake: channel 1/40", session.handshakeProgress());
        session.tick(t + 200);
        byte[] get = session.pollWrite();
        check(get != null && (get[0] & 0xFF) == Companion.CMD_GET_CHANNEL);
        byte[] secret = new byte[16];
        Arrays.fill(secret, (byte) 0x42);
        session.onNotification(Companion.channelInfo(0, "Public", Companion.PUBLIC_CHANNEL_SECRET), t + 250);
        eq("handshake: channel 2/40", session.handshakeProgress());
    }

    /** Recommended item: Android allows 5 scan starts per 30 s. */
    static void scanLimit() {
        List<Long> starts = new ArrayList<Long>();
        long now = 100_000L;
        for (int i = 0; i < 4; i++) {
            starts.add(now - i * 1_000L);
        }
        check(!LinkRules.scanLimitHit(starts, now));
        starts.add(now - 4_000L);
        check(LinkRules.scanLimitHit(starts, now));
        // The oldest start leaves the window after 30 s.
        check(!LinkRules.scanLimitHit(starts, now + 27_000L));
        check(LinkRules.SCAN_LIMIT_REASON.contains("wait 30 s"));
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
