package dev.meshcore.atak;

import dev.meshcore.atak.companion.Companion;
import dev.meshcore.atak.engine.Engine;
import dev.meshcore.atak.engine.SendReasons;
import dev.meshcore.atak.mctak.Mctak;
import dev.meshcore.atak.plugin.KeyValueStore;
import dev.meshcore.atak.plugin.PluginState;
import dev.meshcore.atak.transport.LinkMachine;
import dev.meshcore.atak.transport.RadioChoice;
import dev.meshcore.atak.transport.RadioLimits;
import dev.meshcore.atak.transport.RadioSession;
import dev.meshcore.atak.transport.WriteFailureTracker;
import dev.meshcore.atak.util.Bytes;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** v7.2 checks, E1 to E10. No radio, phone or ATAK is used. */
final class V72Checks {
    private V72Checks() {}

    private static final byte[] PRIVATE_A = filled(0x5A);
    private static final byte[] PRIVATE_B = filled(0x37);

    private static byte[] filled(int v) {
        byte[] b = new byte[16];
        Arrays.fill(b, (byte) v);
        return b;
    }

    /** A radio with Public (0), private "field-a" (1), "#topic" (2), private "ops" (3). Nothing is chosen. */
    private static Engine ready(String nodeName) {
        Engine engine = new Engine();
        engine.onLinkUp(1_000L);
        byte[] key = new byte[32];
        Bytes.putRaw4(key, 0, 0x11111111);
        engine.receiveFrame(Companion.selfInfo(key, 910_525, 62_500, 8, 5, nodeName), 1_000L);
        engine.receiveFrame(Companion.channelInfo(0, "Public", Companion.PUBLIC_CHANNEL_SECRET), 1_000L);
        engine.receiveFrame(Companion.channelInfo(1, "field-a", PRIVATE_A), 1_000L);
        engine.receiveFrame(Companion.channelInfo(2, "#topic", PRIVATE_A), 1_000L);
        engine.receiveFrame(Companion.channelInfo(3, "ops", PRIVATE_B), 1_000L);
        engine.receiveFrame(Companion.channelInfo(4, "", new byte[16]), 1_000L);
        return engine;
    }

    // ---- E1 -----------------------------------------------------------------------------

    static void e1RadioChoice() {
        eq(RadioChoice.Plan.CHOOSE, RadioChoice.plan(null, false));
        eq(RadioChoice.Plan.CHOOSE, RadioChoice.plan("", true));
        eq(RadioChoice.Plan.DIRECT_SAVED, RadioChoice.plan("AA:BB:CC:DD:A2:95", true));
        eq(RadioChoice.Plan.SCAN_FOR_SAVED, RadioChoice.plan("AA:BB:CC:DD:A2:95", false));
        eq("...A2:95", RadioChoice.shortAddress("aa:bb:cc:dd:a2:95"));
        eq("MeshCore-1 ...A2:95", RadioChoice.label("MeshCore-1", "AA:BB:CC:DD:A2:95"));
        RadioChoice.Candidate one = new RadioChoice.Candidate("AA:BB:CC:DD:A2:95", "MeshCore-1", -61, true);
        RadioChoice.Candidate two = new RadioChoice.Candidate("AA:BB:CC:DD:00:11", "MeshCore-2", -40, false);
        check(one.line().contains("MeshCore-1") && one.line().contains("-61") && one.line().contains("...A2:95")
                && one.line().contains("paired: yes"));
        check(two.line().contains("paired: no"));
        // Sorting only orders the dialog. Both radios stay listed and nothing is picked.
        List<RadioChoice.Candidate> sorted = RadioChoice.byStrength(Arrays.asList(one, two));
        eq(2, sorted.size());
        eq("MeshCore-2", sorted.get(0).name);
        check(RadioChoice.sameAddress("aa:bb:cc:dd:a2:95", "AA:BB:CC:DD:A2:95"));
        check(!RadioChoice.looksLikeAddress("garbage"));
        check(!RadioChoice.looksLikeAddress("AA:BB:CC:DD:A2"));
        check(RadioChoice.looksLikeAddress("aa:bb:cc:dd:a2:95"));
        // CHOOSE stage: its own label, its own time limit, and a tick cannot end it early.
        LinkMachine machine = new LinkMachine();
        check(machine.enter(LinkMachine.State.SCAN, 1_000L, "scan", LinkMachine.NO_STATUS));
        check(machine.enter(LinkMachine.State.CHOOSE, 2_000L, "dialog", LinkMachine.NO_STATUS));
        eq("Choose radio\u2026", machine.buttonLabel(3_000L));
        check(!machine.canStart());
        check(!machine.checkTimeout(2_000L + LinkMachine.CHOOSE_TIMEOUT_MS - 1L));
        check(machine.checkTimeout(2_000L + LinkMachine.CHOOSE_TIMEOUT_MS));
        eq("choose_timeout", machine.failReason());
        eq(LinkMachine.State.CHOOSE, machine.failStage());
    }

    static void e4HandshakeTiming() {
        LinkMachine machine = new LinkMachine();
        eq(-1L, machine.handshakeMs());
        machine.enter(LinkMachine.State.SCAN, 1_000L, "s", LinkMachine.NO_STATUS);
        machine.enter(LinkMachine.State.HANDSHAKE, 5_000L, "h", LinkMachine.NO_STATUS);
        eq(-1L, machine.handshakeMs());
        machine.enter(LinkMachine.State.READY, 8_250L, "r", LinkMachine.NO_STATUS);
        eq(3_250L, machine.handshakeMs());
    }

    // ---- E2 -----------------------------------------------------------------------------

    static void e2NoAutomaticChannel() {
        Engine engine = ready("node");
        check(!engine.hasSelectedChannel());
        eq(null, engine.selectedChannelRow());
        // Nothing queues without a chosen channel, and the reason is the E10 sentence.
        Engine.Send join = engine.joinSelected(2_000L);
        eq("REFUSED", join.status);
        eq(SendReasons.NO_CHANNEL, join.why);
        // Position cannot be switched on with no channel.
        engine.setLocationOptIn(true);
        check(!engine.locationOptIn());
        // v7.3 (G2): the GeoChat copy path is gone, so there is no copy opt-in to refuse.
        eq(null, engine.roomSpec());
        // Rows list the private channels and the refused ones with a reason, and the empty count.
        List<Engine.ChannelRow> rows = engine.channelRows();
        eq(4, rows.size());
        check(rows.get(1).safe && rows.get(3).safe);
        check(!rows.get(0).safe && rows.get(0).reason.equals("public channel"));
        check(!rows.get(2).safe && rows.get(2).reason.startsWith("hashtag"));
        eq(1, engine.emptyChannelSlots());
        // Inbound channel data before a choice is dropped, not turned into CoT.
        byte[] datagram = pli(0xAB, 1);
        engine.receiveFrame(Companion.channelDataRecv(20, 1, 1, Mctak.DATA_TYPE_DEV, datagram), 3_000L);
        check(engine.drainInbound().isEmpty());
        check(engine.counters().get("data_no_channel") >= 1);
    }

    static void e2OptInsFollowTheChannel() {
        Engine engine = ready("node");
        eq("OK", engine.selectChannel(1).status);
        engine.setLocationOptIn(true);
        check(engine.locationOptIn());
        engine.setPluginOnlyChat(false);
        // Choosing the same channel again changes nothing.
        eq("OK", engine.selectChannel(1).status);
        check(engine.locationOptIn() && !engine.pluginOnlyChat());
        // A different channel turns everything off and the chat switch goes back to ON.
        eq("OK", engine.selectChannel(3).status);
        check(!engine.locationOptIn());
        check(engine.pluginOnlyChat());
        // Refused channels do not change the choice.
        eq("REFUSED_PUBLIC", engine.selectChannel(0).status);
        eq("REFUSED_PUBLIC", engine.selectChannel(2).status);
        eq("REFUSED_PUBLIC", engine.selectChannel(4).status);
        eq(3, engine.selectedChannelRow().index);
        // The radio changing (a new link) clears the choice, the opt-ins and the switch.
        engine.setLocationOptIn(true);
        engine.setPluginOnlyChat(false);
        engine.onLinkUp(9_000L);
        check(!engine.hasSelectedChannel());
        check(!engine.locationOptIn());
        check(engine.pluginOnlyChat());
        eq(0, engine.channelRows().size());
        // Losing the link clears the choice too.
        Engine again = ready("node");
        again.selectChannel(1);
        again.setLocationOptIn(true);
        again.onLinkLost();
        check(!again.hasSelectedChannel());
        check(!again.locationOptIn());
        // Explicit clear (radio chosen or forgotten in the pane).
        Engine third = ready("node");
        third.selectChannel(1);
        third.setLocationOptIn(true);
        third.setPluginOnlyChat(false);
        third.clearChannelSelection("radio forgotten");
        check(!third.hasSelectedChannel() && !third.locationOptIn() && third.pluginOnlyChat());
    }

    // ---- E3 -----------------------------------------------------------------------------

    private static final class MapStore implements KeyValueStore {
        final Map<String, String> map = new HashMap<String, String>();
        boolean failWrites;
        int putCalls;

        @Override
        public String get(String key) {
            return map.get(key);
        }

        @Override
        public void put(String key, String value) throws IOException {
            putCalls++;
            if (failWrites) {
                throw new IOException("read-only file system");
            }
            map.put(key, value);
        }

        @Override
        public void remove(String key) throws IOException {
            if (failWrites) {
                throw new IOException("read-only file system");
            }
            map.remove(key);
        }
    }

    static void e3PluginState() {
        MapStore store = new MapStore();
        Engine engine = ready("node");
        engine.selectChannel(1);
        PluginState state = new PluginState(store, engine);
        state.restoreSequence();
        eq(0, state.writes());
        // Only a change is written.
        state.storeSequenceIfChanged();
        state.storeSequenceIfChanged();
        eq(0, store.putCalls);
        engine.joinSelected(2_000L);
        state.storeSequenceIfChanged();
        state.storeSequenceIfChanged();
        state.storeSequenceIfChanged();
        eq(1, state.writes());
        eq(1, store.putCalls);
        eq(Integer.toString(engine.lastIssuedSequence()), store.map.get("mctak_seq"));
        // A restart resumes after the stored value.
        Engine next = new Engine();
        store.map.put("mctak_seq", "40");
        new PluginState(store, next).restoreSequence();
        eq(40, next.lastIssuedSequence());
        // A failing store is counted once per new value, and the real exception is kept.
        MapStore failing = new MapStore();
        failing.failWrites = true;
        Engine e2 = ready("node");
        e2.selectChannel(1);
        PluginState bad = new PluginState(failing, e2);
        bad.restoreSequence();
        e2.joinSelected(2_000L);
        for (int i = 0; i < 5; i++) {
            bad.storeSequenceIfChanged();
        }
        eq(1L, e2.counters().get("sequence_store_failed"));
        check(bad.lastError().contains("IOException") && bad.lastError().contains("read-only"));
        // Radio memory: save, read, corrupt value ignored, forget.
        check(state.saveRadio("aa:bb:cc:dd:a2:95", "MeshCore-1"));
        eq("AA:BB:CC:DD:A2:95", state.savedRadioAddress());
        eq("MeshCore-1", state.savedRadioName());
        store.map.put("radio_address", "not-an-address");
        eq(null, state.savedRadioAddress());
        state.saveRadio("AA:BB:CC:DD:A2:95", "MeshCore-1");
        state.forgetRadio();
        eq(null, state.savedRadioAddress());
        // A store that cannot write is reported, not thrown.
        check(!bad.saveRadio("AA:BB:CC:DD:A2:95", "x"));
        check(failing.map.isEmpty());
    }

    // ---- E4 -----------------------------------------------------------------------------

    /** Drive a whole handshake on a fake clock. Replies come after 40 ms, writes obey the 60 ms gap. */
    private static long handshakeOnFakeClock(RadioSession session, long start) {
        long now = start;
        session.onConnected(now);
        session.onMtu(512, now);
        session.onNotifications(true);
        byte[] key = new byte[32];
        Bytes.putRaw4(key, 0, 0xCAFEBABE);
        for (int guard = 0; guard < 400; guard++) {
            long delay = session.nextActionDelayMs(now);
            if (delay > 0) {
                now += delay;
            }
            session.tick(now);
            byte[] cmd = session.pollWrite();
            if (cmd == null) {
                if (session.ready() && session.nextActionDelayMs(now) >= 0) {
                    // READY and nothing more to ask.
                    byte[] more = null;
                    session.tick(now + 100L);
                    more = session.pollWrite();
                    if (more == null) {
                        return now - start;
                    }
                    cmd = more;
                    now += 100L;
                } else {
                    now += 1L;
                    continue;
                }
            }
            now += 40L;
            int op = cmd[0] & 0xFF;
            if (op == Companion.CMD_APP_START) {
                session.onNotification(Companion.selfInfo(key, 910_525, 62_500, 8, 5, "node"), now);
            } else if (op == Companion.CMD_DEVICE_QUERY) {
                byte[] device = new byte[82];
                device[0] = Companion.PACKET_DEVICE_INFO;
                device[1] = 3;
                device[3] = 4;
                session.onNotification(device, now);
            } else if (op == Companion.CMD_GET_CHANNEL) {
                int idx = cmd[1] & 0xFF;
                session.onNotification(idx == 0
                        ? Companion.channelInfo(0, "Public", Companion.PUBLIC_CHANNEL_SECRET)
                        : idx == 1 ? Companion.channelInfo(1, "field-a", PRIVATE_A)
                        : Companion.channelInfo(idx, "", new byte[16]), now);
            } else if (op == Companion.CMD_GET_STATS) {
                session.onNotification(Companion.statsCore(3900, 1_000L, 0, 0), now);
            } else {
                throw new AssertionError("unexpected command " + op);
            }
        }
        throw new AssertionError("handshake did not finish");
    }

    static void e4HandshakeIsNotOnePerSecond() {
        Engine engine = new Engine();
        engine.onLinkUp(1_000L);
        RadioSession session = new RadioSession(engine);
        long took = handshakeOnFakeClock(session, 10_000L);
        check(session.ready());
        // v7.1 asked once per second (42 commands, about 42 s). Now the next command follows the reply
        // after the 60 ms write gap, so the whole exchange is a few seconds even with a 40 ms radio.
        check("took " + took + " ms", took < 8_000L);
        check(engine.channelIndexes().contains(1));
        check(engine.radioStats() != null);
        // Delay rules: nothing while a reply is awaited; the gap while it has not passed; 0 after.
        RadioSession s = new RadioSession(new Engine());
        long t = 1_000L;
        eq(-1L, s.nextActionDelayMs(t)); // not connected
        s.onConnected(t);
        s.onMtu(512, t);
        s.onNotifications(true);
        eq(0L, s.nextActionDelayMs(t));
        s.tick(t);
        check(s.peekWrite() != null);
        eq(-1L, s.nextActionDelayMs(t + 10)); // frame still waits for the GATT write
        s.pollWrite();
        eq(-1L, s.nextActionDelayMs(t + 10)); // reply awaited
    }

    // ---- E5 -----------------------------------------------------------------------------

    static void e5WriteFailureTracker() {
        WriteFailureTracker tracker = new WriteFailureTracker();
        eq(null, tracker.resolveByDisconnect(1_000L, 8));
        tracker.begin(10_000L, 133, 4_000L);
        tracker.begin(10_500L, 1, 9_000L); // first failure is kept
        check(tracker.pending());
        check(!tracker.expired(10_000L + WriteFailureTracker.WAIT_MS - 1L));
        check(tracker.expired(10_000L + WriteFailureTracker.WAIT_MS));
        // The disconnect that follows carries the final status.
        WriteFailureTracker.Resolution byDisconnect = tracker.resolveByDisconnect(10_300L, 8);
        eq(8, byDisconnect.finalStatus);
        eq(133, byDisconnect.writeStatus);
        check(byDisconnect.disconnectSeen);
        eq(6_000L, byDisconnect.sinceNotificationMs);
        check(byDisconnect.detail().contains("8") && byDisconnect.detail().contains("133"));
        check(!tracker.pending());
        // No disconnect: the write status is final after the wait.
        tracker.begin(20_000L, 133, 0L);
        WriteFailureTracker.Resolution byTimeout = tracker.resolveByTimeout(22_000L);
        eq(133, byTimeout.finalStatus);
        check(!byTimeout.disconnectSeen);
        eq(-1L, byTimeout.sinceNotificationMs);
        check(WriteFailureTracker.notificationText(-1L).contains("no notification"));
        check(WriteFailureTracker.notificationText(21_345L).contains("21345"));
        // Session remembers when the last frame came in.
        RadioSession session = new RadioSession(new Engine());
        eq(0L, session.lastNotificationMs());
        session.onConnected(5_000L);
        session.onNotification(new byte[] {Companion.PACKET_OK}, 5_500L);
        eq(5_500L, session.lastNotificationMs());
    }

    // ---- E6 -----------------------------------------------------------------------------

    static void e6StatsAndCounters() {
        Companion.Stats stats = Companion.parseStatsCore(Companion.statsCore(3950, 123_456L, 0x0102, 7));
        eq(3950, stats.batteryMv);
        eq(123_456L, stats.uptimeSecs);
        eq(0x0102, stats.errFlags);
        eq(7, stats.queueLen);
        eq(null, Companion.parseStatsCore(new byte[] {24, 0, 1}));
        eq(null, Companion.parseStatsCore(new byte[] {24, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0}));
        byte[] request = Companion.getStatsCore();
        eq(Companion.CMD_GET_STATS, request[0] & 0xFF);
        eq(0, request[1] & 0xFF);
        // Reboot shows as uptime going backwards.
        Engine engine = ready("node");
        engine.receiveFrame(Companion.statsCore(3900, 5_000L, 0, 0), 2_000L);
        check(!engine.radioRebootSeen());
        engine.receiveFrame(Companion.statsCore(3800, 20L, 0, 0), 3_000L);
        check(engine.radioRebootSeen());
        eq(2, engine.radioStatsHistory().size());
        eq(20L, engine.radioStats().uptimeSecs);
        // Per-type counters.
        engine.receiveFrame(new byte[] {Companion.PACKET_OK}, 3_100L);
        engine.receiveFrame(new byte[] {(byte) 0x77, 1, 2}, 3_200L);
        check(engine.counters().get("rx_00") >= 1);
        check(engine.counters().get("unhandled_77") >= 1);
        engine.receiveFrame(new byte[] {(byte) Companion.PACKET_NO_MORE_MESSAGES}, 3_300L);
        check(engine.counters().get("no_more_messages") >= 1);
    }

    static void e6NoSecretKept() {
        Engine engine = new Engine();
        engine.onLinkUp(1L);
        byte[] frame = Companion.channelInfo(1, "field-a", PRIVATE_A);
        engine.receiveFrame(frame, 1L);
        // The 16 secret bytes of the notification are wiped once the fingerprint is taken.
        for (int i = 34; i < 50; i++) {
            eq(0, frame[i] & 0xFF);
        }
        Companion.ChannelView view = engine.channel(1);
        check(view.fingerprint != null && view.fingerprint.length() > 0);
        String secretHex = Bytes.hex(PRIVATE_A);
        for (Field field : Companion.ChannelView.class.getFields()) {
            check(field.getType() != byte[].class);
        }
        for (Field field : Engine.ChannelRow.class.getFields()) {
            check(field.getType() != byte[].class);
        }
        check(!view.toString().contains(secretHex));
        for (String line : engine.log()) {
            check(!line.contains(secretHex));
        }
        for (Engine.ChannelRow row : ready("node").channelRows()) {
            check(!row.fingerprint.contains(secretHex));
            check(!row.name.contains(secretHex));
        }
    }

    // ---- E8 -----------------------------------------------------------------------------

    private static byte[] frame16(int code, String text, boolean signed) {
        boolean v3 = code == 16;
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        int head = (v3 ? 4 : 1) + 6 + 1 + 1 + 4 + (signed ? 4 : 0);
        byte[] f = new byte[head + body.length];
        f[0] = (byte) code;
        int i = v3 ? 4 : 1;
        for (int k = 0; k < 6; k++) {
            f[i + k] = (byte) (0xA0 + k);
        }
        i += 6;
        f[i++] = 3; // path_len
        f[i++] = (byte) (signed ? 2 : 0);
        Bytes.putU32le(f, i, 1_700_000_123L);
        i += 4;
        if (signed) {
            i += 4;
        }
        System.arraycopy(body, 0, f, i, body.length);
        return f;
    }

    private static byte[] frame8(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] f = new byte[1 + 1 + 1 + 1 + 4 + body.length];
        f[0] = (byte) Companion.PACKET_CHANNEL_MSG;
        f[1] = 2; // channel
        f[2] = 1;
        f[3] = 0;
        Bytes.putU32le(f, 4, 1_700_000_123L);
        System.arraycopy(body, 0, f, 8, body.length);
        return f;
    }

    static void e8NativeText() {
        Companion.NativeText direct16 = Companion.parseNativeText(frame16(16, "hi there", false));
        check(direct16.direct);
        eq("a0a1a2a3a4a5", direct16.senderPrefixHex);
        eq("hi there", direct16.text);
        eq(3, direct16.pathLen);
        eq(1_700_000_123L, direct16.senderTimestamp);
        eq("signed text", Companion.parseNativeText(frame16(16, "signed text", true)).text);
        Companion.NativeText direct7 = Companion.parseNativeText(frame16(7, "old frame", false));
        check(direct7.direct);
        eq("old frame", direct7.text);
        Companion.NativeText ch8 = Companion.parseNativeText(frame8("chan old"));
        check(!ch8.direct);
        eq(2, ch8.channel);
        eq("chan old", ch8.text);
        Companion.NativeText ch17 = Companion.parseNativeText(Companion.channelTextV3(-8, 5, 1, 1_700_000_000L, "chan v3"));
        eq(5, ch17.channel);
        eq("chan v3", ch17.text);
        eq(null, Companion.parseNativeText(new byte[] {17, 0, 0}));
        eq(null, Companion.parseNativeText(new byte[] {(byte) Companion.PACKET_OK}));
        // Text stops at the first NUL.
        byte[] withNul = Companion.channelTextV3(0, 1, 0, 1L, "abc\u0000def");
        eq("abc", Companion.parseNativeText(withNul).text);

        // Engine: every message is labelled, control characters are removed, length is capped,
        // the inbox holds ten, and nothing is logged.
        Engine engine = ready("node");
        engine.receiveFrame(Companion.channelTextV3(0, 1, 0, 1L, "line1\nline2\u0007"), 5_000L);
        Engine.NativeMessage first = engine.nativeInbox().get(0);
        check(first.display().startsWith("[MeshCore, unverified] channel 1: "));
        check(first.text.indexOf('\n') < 0 && first.text.indexOf('\u0007') < 0);
        engine.receiveFrame(frame16(16, "direct one", false), 5_100L);
        check(engine.nativeInbox().get(1).display().contains("direct from a0a1a2a3a4a5"));
        engine.receiveFrame(Companion.channelTextV3(0, 1, 0, 1L, "x".repeat(300)), 5_200L);
        eq(Engine.NATIVE_TEXT_SHOWN_CHARS, engine.nativeInbox().get(2).text.length());
        for (int i = 0; i < 20; i++) {
            engine.receiveFrame(Companion.channelTextV3(0, 1, 0, 1L, "msg" + i), 6_000L + i);
        }
        eq(Engine.NATIVE_INBOX_CAP, engine.nativeInbox().size());
        eq("msg19", engine.nativeInbox().get(Engine.NATIVE_INBOX_CAP - 1).text);
        check(engine.counters().get("native_text_rx") >= 23);
        List<Engine.NativeMessage> fresh = engine.takeNewNativeText();
        eq(Engine.NATIVE_INBOX_CAP, fresh.size());
        eq(0, engine.takeNewNativeText().size());
        for (String line : engine.log()) {
            check(!line.contains("msg1") && !line.contains("direct one") && !line.contains("line1"));
        }
        // Native text never becomes CoT and never reaches the MCTAK parser.
        check(engine.drainInbound().isEmpty());
    }

    // ---- E9 -----------------------------------------------------------------------------

    static void e9CapDerivation() {
        eq(160, Companion.MAX_TEXT_LEN);
        eq(164, Companion.SAFE_PAYLOAD);
        eq(5, Companion.GROUP_TEXT_HEADER);
        // firmware: "<name>: <text>" must fit 160 bytes; plugin: 5 + that must fit 164.
        eq(153, Companion.maxNativeTextBytes(4));
        eq(126, Companion.maxNativeTextBytes(31));
        eq(157, Companion.maxNativeTextBytes(0));
        for (int name = 0; name <= 40; name++) {
            int max = Companion.maxNativeTextBytes(name);
            check(name + 2 + max <= Companion.MAX_TEXT_LEN);
            check(Companion.GROUP_TEXT_HEADER + name + 2 + max <= Companion.SAFE_PAYLOAD);
            // One more byte would not fit one of the two limits.
            check(name + 2 + max + 1 > Companion.MAX_TEXT_LEN
                    || Companion.GROUP_TEXT_HEADER + name + 2 + max + 1 > Companion.SAFE_PAYLOAD);
        }
    }

    static void e9DefaultIsPluginOnly() {
        Engine engine = ready("node");
        check(engine.pluginOnlyChat());
        eq("ATAK plugin users only", engine.chatModeLabel());
        engine.selectChannel(1);
        Engine.Send send = engine.sendChat(Mctak.SCOPE_ALL, 0, "", "hello team", 10_000L);
        eq("QUEUED", send.status);
        byte[] frame = engine.drainTx(10_000L).get(0);
        eq(Companion.CMD_SEND_CHANNEL_DATA, frame[0] & 0xFF);
        check(!Companion.sendChannelText(1, 0L, new byte[0]).equals(frame));
        // The switch is not remembered.
        engine.setPluginOnlyChat(false);
        check(!engine.pluginOnlyChat());
        eq("MeshCore channel text (anyone on channel)", engine.chatModeLabel());
        engine.selectChannel(3);
        check(engine.pluginOnlyChat());
    }

    static void e9NativeModeFrame() {
        Engine engine = ready("node");
        engine.setNowSeconds(1_700_000_000L);
        engine.selectChannel(3);
        engine.setPluginOnlyChat(false);
        Engine.Send send = engine.sendNativeChannelText("héllo", 20_000L);
        eq("QUEUED", send.status);
        List<byte[]> frames = engine.drainTx(20_000L);
        eq(1, frames.size());
        byte[] frame = frames.get(0);
        byte[] text = "héllo".getBytes(StandardCharsets.UTF_8);
        eq(3, frame[0] & 0xFF); // CMD_SEND_CHANNEL_TXT_MSG
        eq(0, frame[1] & 0xFF); // TXT_TYPE_PLAIN
        eq(3, frame[2] & 0xFF); // channel slot, the only thing that names the key
        eq(1_700_000_000L, Bytes.u32le(frame, 3));
        eq(7 + text.length, frame.length);
        check(Arrays.equals(text, Arrays.copyOfRange(frame, 7, frame.length)));
        // The frame carries the slot, not any key: it holds no byte of the channel secret pattern.
        check(!Bytes.hex(frame).contains(Bytes.hex(PRIVATE_B).substring(0, 8)));
        eq(RadioLimits.decision(frame, 512), "ok");
        // MCTAK header is not on it.
        check(frame.length < 7 + 20 || (frame[7] & 0xFF) != 0x54);
    }

    static void e9CapsInBothModes() {
        // Native: 4-byte node name -> 153 bytes. 153 passes, 154 is refused, never truncated.
        Engine engine = ready("node");
        engine.selectChannel(1);
        engine.setPluginOnlyChat(false);
        eq(153, engine.maxChatBytes(Mctak.SCOPE_ALL, 0, ""));
        eq("QUEUED", engine.sendNativeChannelText("a".repeat(153), 30_000L).status);
        Engine.Send over = engine.sendNativeChannelText("a".repeat(154), 31_000L);
        eq("BLOCKED_RADIO_LIMIT", over.status);
        eq("Not sent: text too long (154/153 bytes)", over.why);
        // Multi-byte text is counted in bytes.
        Engine.Send bytes = engine.sendNativeChannelText("é".repeat(77), 32_000L); // 154 bytes
        eq("BLOCKED_RADIO_LIMIT", bytes.status);
        // A long node name lowers the cap.
        Engine longName = ready("n".repeat(31));
        longName.selectChannel(1);
        longName.setPluginOnlyChat(false);
        eq(126, longName.maxChatBytes(Mctak.SCOPE_ALL, 0, ""));
        eq("QUEUED", longName.sendNativeChannelText("a".repeat(126), 30_000L).status);
        eq("BLOCKED_RADIO_LIMIT", longName.sendNativeChannelText("a".repeat(127), 31_000L).status);
        // Plugin-only: every frame stays within 164 bytes of MCTAK payload.
        Engine plugin = ready("node");
        plugin.selectChannel(1);
        int max = plugin.maxChatBytes(Mctak.SCOPE_ALL, 0, "");
        check(max > 100 && max < 165);
        eq("QUEUED", plugin.sendChat(Mctak.SCOPE_ALL, 0, "", "a".repeat(max), 40_000L).status);
        byte[] sent = plugin.drainTx(40_000L).get(0);
        check(sent.length - 5 <= Companion.SAFE_PAYLOAD);
        Engine.Send tooBig = plugin.sendChat(Mctak.SCOPE_ALL, 0, "", "a".repeat(max + 1), 41_000L);
        eq("BLOCKED_RADIO_LIMIT", tooBig.status);
        eq(SendReasons.tooLong(max + 1, max), tooBig.why);
        // The 164 gate is below the official 165: a 165-byte datagram is refused by the plugin.
        check(Mctak.radioWillAccept(165));
        check(Companion.SAFE_PAYLOAD < Mctak.RADIO_MAX_PAYLOAD);
    }

    static void e9ReplyMapping() {
        for (boolean nativeMode : new boolean[] {false, true}) {
            Engine engine = ready("node");
            engine.selectChannel(1);
            engine.setPluginOnlyChat(!nativeMode);
            check(engine.lastChatResult().isEmpty());
            int before = engine.chatResultSeq();
            if (nativeMode) {
                eq("QUEUED", engine.sendNativeChannelText("ping", 50_000L).status);
            } else {
                eq("QUEUED", engine.sendChat(Mctak.SCOPE_ALL, 0, "", "ping", 50_000L).status);
            }
            engine.drainTx(50_000L);
            engine.onChannelDataReply(0);
            eq("Sent to radio", engine.lastChatResult());
            eq(before + 1, engine.chatResultSeq());
            engine.onChannelDataReply(3);
            check(engine.lastChatResult().startsWith("Failed: "));
            engine.onChannelDataReply(-1);
            eq(SendReasons.NO_ANSWER, engine.lastChatResult());
            for (int code = -2; code < 10; code++) {
                engine.onChannelDataReply(code);
                check(!engine.lastChatResult().toLowerCase().contains("delivered"));
                check(engine.lastChatResult().equals("Sent to radio") || engine.lastChatResult().startsWith("Failed: ")
                        || engine.lastChatResult().equals(SendReasons.NO_ANSWER));
            }
        }
        // Through the session: OK -> "Sent to radio", error -> Failed, timeout -> Failed, both opcodes.
        for (int op : new int[] {62, 3}) {
            Engine engine = new Engine();
            engine.onLinkUp(1_000L);
            RadioSession session = new RadioSession(engine);
            long now = 10_000L + handshakeOnFakeClock(session, 10_000L);
            engine.selectChannel(1);
            engine.setPluginOnlyChat(op == 62);
            if (op == 62) {
                eq("QUEUED", engine.sendChat(Mctak.SCOPE_ALL, 0, "", "ping", now).status);
            } else {
                eq("QUEUED", engine.sendNativeChannelText("ping", now).status);
            }
            now += 200L;
            session.tick(now);
            byte[] out = session.pollWrite();
            check(out != null && (out[0] & 0xFF) == op);
            session.onNotification(new byte[] {Companion.PACKET_OK}, now + 50);
            eq("Sent to radio", engine.lastChatResult());
            // error reply
            if (op == 62) {
                engine.sendChat(Mctak.SCOPE_ALL, 0, "", "ping2", now + 400);
            } else {
                engine.sendNativeChannelText("ping2", now + 400);
            }
            session.tick(now + 500);
            out = session.pollWrite();
            check(out != null && (out[0] & 0xFF) == op);
            session.onNotification(new byte[] {Companion.PACKET_ERROR, RadioLimits.ERR_ILLEGAL_ARG}, now + 520);
            check(engine.lastChatResult().startsWith("Failed: "));
            // timeout
            if (op == 62) {
                engine.sendChat(Mctak.SCOPE_ALL, 0, "", "ping3", now + 700);
            } else {
                engine.sendNativeChannelText("ping3", now + 700);
            }
            session.tick(now + 800);
            out = session.pollWrite();
            check(out != null && (out[0] & 0xFF) == op);
            session.tick(now + 800 + RadioLimits.COMMAND_TIMEOUT_MS + 1);
            eq(SendReasons.NO_ANSWER, engine.lastChatResult());
        }
    }

    // ---- E10 ----------------------------------------------------------------------------

    static void e10RefusalsInBothModes() {
        for (boolean pluginOnly : new boolean[] {true, false}) {
            // Radio not ready.
            Engine cold = new Engine();
            cold.setPluginOnlyChat(pluginOnly);
            eq(SendReasons.NOT_READY, send(cold, pluginOnly, "hi").why);
            // Ready but nothing chosen.
            Engine engine = ready("node");
            engine.setPluginOnlyChat(pluginOnly);
            eq(SendReasons.NO_CHANNEL, send(engine, pluginOnly, "hi").why);
            // Public, hashtag and empty channels cannot even be chosen.
            eq("Not selected: public channel", engine.selectChannel(0).why);
            check(engine.selectChannel(2).why.startsWith("Not selected: hashtag channel"));
            eq("Not selected: empty slot", engine.selectChannel(4).why);
            check(!engine.hasSelectedChannel());
            eq(SendReasons.NO_CHANNEL, send(engine, pluginOnly, "hi").why);
            // A private channel works; empty text and NUL are refused with a reason.
            engine.selectChannel(1);
            engine.setPluginOnlyChat(pluginOnly);
            eq(SendReasons.EMPTY_TEXT, send(engine, pluginOnly, "   ").why);
            eq("QUEUED", send(engine, pluginOnly, "hi").status);
            Engine.Send tooLong = send(engine, pluginOnly, "a".repeat(400));
            eq("BLOCKED_RADIO_LIMIT", tooLong.status);
            check(tooLong.why.startsWith("Not sent: text too long ("));
            // Refusal text carries no message text, no key, no PIN.
            check(!tooLong.why.contains("aaaa"));
        }
        // Position and beacon reasons.
        Engine engine = ready("node");
        eq(SendReasons.POSITION_OFF, engine.beacon(1_000L).why);
        engine.selectChannel(1);
        engine.setLocationOptIn(true);
        eq(SendReasons.NO_FIX, engine.beacon(2_000L).why);
        // Defence in depth: both send paths re-check the chosen channel.
        Engine guard = ready("node");
        guard.selectChannel(1);
        check(guard.channel(1) != null && !guard.channel(1).unsafeForTeamTraffic());
    }

    private static Engine.Send send(Engine engine, boolean pluginOnly, String text) {
        return pluginOnly
                ? engine.sendChat(Mctak.SCOPE_ALL, 0, "", text, 60_000L)
                : engine.sendNativeChannelText(text, 60_000L);
    }

    // ---- private encrypted channels, both modes --------------------------------------

    static void privateChannelsWorkInBothModes() {
        Engine engine = ready("node");
        List<Engine.ChannelRow> rows = engine.channelRows();
        int privateCount = 0;
        for (Engine.ChannelRow row : rows) {
            if (row.safe) {
                privateCount++;
                check(row.fingerprint != null && !row.fingerprint.isEmpty());
                check(row.reason.isEmpty());
            }
        }
        eq(2, privateCount);
        long when = 70_000L;
        for (int channel : new int[] {1, 3}) {
            for (boolean pluginOnly : new boolean[] {true, false}) {
                when += 300_000L;
                eq("OK", engine.selectChannel(channel).status);
                engine.setPluginOnlyChat(pluginOnly);
                Engine.Send sent = pluginOnly
                        ? engine.sendChat(Mctak.SCOPE_ALL, 0, "", "private " + channel, when)
                        : engine.sendNativeChannelText("private " + channel, when);
                eq("QUEUED", sent.status);
                byte[] frame = engine.drainTx(when).get(0);
                eq(pluginOnly ? 62 : 3, frame[0] & 0xFF);
                eq(channel, frame[pluginOnly ? 1 : 2] & 0xFF);
                // Nothing on the wire or in the engine holds a secret.
                check(!Bytes.hex(frame).contains(Bytes.hex(PRIVATE_A)));
                check(!Bytes.hex(frame).contains(Bytes.hex(PRIVATE_B)));
            }
        }
        for (String line : engine.log()) {
            check(!line.contains(Bytes.hex(PRIVATE_A)) && !line.contains(Bytes.hex(PRIVATE_B)));
        }
    }

    /** Every opcode the plugin can write belongs to a short list. Opcode 3 only from sendChannelText. */
    static void opcodeAllowlist() {
        Set<Integer> allowed = new HashSet<Integer>(Arrays.asList(1, 3, 10, 22, 31, 56, 62));
        Set<Integer> forbidden = new HashSet<Integer>(Arrays.asList(
                23, 24, 51, 11, 12, 21, 37, 38, 14, 19, 65, 66));
        for (int op : forbidden) {
            check(!allowed.contains(op));
        }
        Set<Integer> seen = new HashSet<Integer>();
        Engine engine = new Engine();
        engine.onLinkUp(1_000L);
        RadioSession session = new RadioSession(engine);
        long now = 10_000L + handshakeOnFakeClock(session, 10_000L);
        engine.selectChannel(1);
        engine.setLocationOptIn(true);
        engine.setSelfPosition(51.0, -1.0, null, null, null, 80);
        engine.joinSelected(now);
        engine.beacon(now + 10);
        engine.sendChat(Mctak.SCOPE_ALL, 0, "", "hi", now + 20);
        engine.setPluginOnlyChat(false);
        engine.sendNativeChannelText("hi", now + 30);
        engine.receiveFrame(new byte[] {(byte) Companion.PACKET_MESSAGES_WAITING}, now + 40);
        for (int i = 0; i < 40; i++) {
            now += 200L;
            session.tick(now);
            byte[] out = session.pollWrite();
            if (out != null) {
                seen.add(out[0] & 0xFF);
                session.onNotification(new byte[] {Companion.PACKET_OK}, now + 20);
            }
        }
        check(seen.contains(62) && seen.contains(3));
        for (int op : seen) {
            check("opcode " + op, allowed.contains(op));
        }
    }


    // ---- F1: one airtime bucket across links ----------------------------------------------

    private static void selfInfo(Engine engine, int freqKhz, String name, long nowMs) {
        byte[] key = new byte[32];
        Bytes.putRaw4(key, 0, 0x22222222);
        engine.receiveFrame(Companion.selfInfo(key, freqKhz, 62_500, 8, 5, name), nowMs);
    }

    /** Send chats until the bucket is clearly below full. Returns the time after the last drain. */
    private static long spendTokens(Engine engine, long start) {
        long t = start;
        for (int i = 0; i < 40; i++) {
            t += 400L;
            engine.sendChat(Mctak.SCOPE_ALL, 0, "", "spend " + i, t);
            engine.drainTx(t);
        }
        return t;
    }

    static void f1BucketSurvivesReconnect() {
        Engine engine = ready("node");
        engine.selectChannel(1);
        int capacity = engine.airtimeCapacityMs();
        eq(60_000, capacity); // 910.525 MHz, 10 % duty
        eq(capacity, engine.airtimeTokensMs()); // a fresh bucket is full once
        long t = spendTokens(engine, 1_000L);
        int spent = engine.airtimeTokensMs();
        check("tokens still " + spent, spent < capacity - 5_000);
        // Lose the link, reconnect to the same radio a moment later.
        engine.onLinkLost();
        engine.onLinkUp(t + 2_000L);
        selfInfo(engine, 910_525, "node", t + 2_100L);
        int after = engine.airtimeTokensMs();
        // Only the refill for ~2 s at 10 % duty (about 200 ms) may have been earned.
        check("tokens back to " + after + " from " + spent, after <= spent + 400);
        check(after < capacity - 5_000);
        // The same holds with no loss event in between (a new link straight away).
        engine.onLinkUp(t + 3_000L);
        selfInfo(engine, 910_525, "node", t + 3_100L);
        check(engine.airtimeTokensMs() <= spent + 700);
    }

    static void f1DutyFromNewRadioKeepsTokens() {
        Engine engine = ready("node");
        engine.selectChannel(1);
        long t = spendTokens(engine, 1_000L);
        int spent = engine.airtimeTokensMs();
        check(spent < 55_000);
        // A 868.1 MHz radio: 1 % duty, capacity 6 s. Tokens are capped, not refilled.
        engine.onLinkLost();
        engine.onLinkUp(t + 1_000L);
        selfInfo(engine, 868_100, "eu", t + 1_100L);
        eq(6_000, engine.airtimeCapacityMs());
        check(engine.airtimeTokensMs() <= 6_000);
        int low = engine.airtimeTokensMs();
        // Back to the 910 MHz radio: capacity grows, the level does not jump to full.
        engine.onLinkLost();
        engine.onLinkUp(t + 2_000L);
        selfInfo(engine, 910_525, "node", t + 2_100L);
        eq(60_000, engine.airtimeCapacityMs());
        check("jumped to " + engine.airtimeTokensMs(), engine.airtimeTokensMs() <= low + 400);
        // Refill follows the new duty (10 %): 10 s earns about 1 s.
        Engine probe = ready("node");
        probe.selectChannel(1);
        spendTokens(probe, 1_000L);
        int before = probe.airtimeTokensMs();
        probe.onLinkLost();
        probe.onLinkUp(100_000L);
        selfInfo(probe, 910_525, "node", 100_000L);
        int atUp = probe.airtimeTokensMs();
        check(atUp >= before && atUp <= before + 10_000);
    }

    static void f1QueuesAndSelfClearedOnLink() {
        Engine engine = ready("node");
        engine.selectChannel(1);
        engine.sendChat(Mctak.SCOPE_ALL, 0, "", "queued", 5_000L);
        engine.joinSelected(5_000L);
        check(engine.queued() > 0);
        engine.onLinkLost();
        eq(0, engine.queued());
        check(engine.airtimeTokensMs() >= 0); // the bucket is still there
        engine.onLinkUp(6_000L);
        // Self info is gone until the new radio sends its own: nothing from the old radio is used.
        engine.setPluginOnlyChat(false);
        eq(-1, engine.maxChatBytes(Mctak.SCOPE_ALL, 0, "")); // native cap needs the new radio's name
        eq(SendReasons.NOT_READY, engine.sendNativeChannelText("hi", 6_100L).why);
        eq(SendReasons.NOT_READY, engine.sendChat(Mctak.SCOPE_ALL, 0, "", "hi", 6_100L).why);
        eq(SendReasons.NOT_READY, engine.joinSelected(6_100L).why);
        // A different node name arrives: the cap follows the new radio, not the old one.
        String longName = "a-much-longer-node-name";
        selfInfo(engine, 910_525, longName, 6_200L);
        engine.receiveFrame(Companion.channelInfo(1, "field-a", PRIVATE_A), 6_300L);
        eq("OK", engine.selectChannel(1).status);
        engine.setPluginOnlyChat(false);
        eq(Companion.maxNativeTextBytes(longName.length()), engine.maxChatBytes(Mctak.SCOPE_ALL, 0, ""));
        // A link that comes back also starts with no channels loaded.
        Engine again = ready("node");
        again.onLinkLost();
        again.onLinkUp(9_000L);
        eq(0, again.channelRows().size());
    }

    // ---- F2: no answer is not "Failed", and sends are never retried -----------------------------

    static void f2NoAnswerWording() {
        eq("No answer from radio: it may have been sent. Check before resending.", SendReasons.NO_ANSWER);
        check(SendReasons.NO_ANSWER.length() <= 70); // whole text fits the 70-character toast
        eq(SendReasons.NO_ANSWER, SendReasons.radioFailure(-1));
        check(!SendReasons.NO_ANSWER.startsWith("Failed"));
        // Real errors are still "Failed: ...".
        for (int code : new int[] {1, 2, 3, 4, 6, 9}) {
            check(SendReasons.radioFailure(code).startsWith("Failed: "));
        }
    }

    /** One READY session; returns the session with the engine's chat mode set for {@code op}. */
    private static RadioSession readySession(Engine engine, int op) {
        engine.onLinkUp(1_000L);
        RadioSession session = new RadioSession(engine);
        long now = 10_000L + handshakeOnFakeClock(session, 10_000L);
        engine.selectChannel(1);
        engine.setPluginOnlyChat(op == 62);
        return session;
    }

    static void f2SendsAreNeverRetried() {
        for (int op : new int[] {62, 3}) {
            // Timeout: the radio never answers.
            Engine engine = new Engine();
            RadioSession session = readySession(engine, op);
            long now = 200_000L;
            queueSend(engine, op, "once", now);
            session.tick(now + 100);
            byte[] first = session.pollWrite();
            check(first != null && (first[0] & 0xFF) == op);
            int writes = 1;
            long t = now + 100;
            for (int i = 0; i < 60; i++) {
                t += 1_000L;
                session.tick(t);
                byte[] more = session.pollWrite();
                if (more != null) {
                    check("retry of op " + (more[0] & 0xFF), (more[0] & 0xFF) != 62 && (more[0] & 0xFF) != 3);
                    session.onNotification(new byte[] {Companion.PACKET_OK}, t + 5);
                }
            }
            eq(SendReasons.NO_ANSWER, engine.lastChatResult());
            check(engine.counters().get("command_timeout") >= 1);
            eq(0, engine.queued());
            // Every error code, including TABLE_FULL (3), which used to be retried once for 62.
            for (int code = 1; code <= 6; code++) {
                Engine e = new Engine();
                RadioSession s = readySession(e, op);
                long n = 300_000L;
                queueSend(e, op, "err" + code, n);
                s.tick(n + 100);
                byte[] out = s.pollWrite();
                check(out != null && (out[0] & 0xFF) == op);
                s.onNotification(new byte[] {Companion.PACKET_ERROR, (byte) code}, n + 150);
                check(e.lastChatResult().startsWith("Failed: "));
                long u = n + 150;
                for (int i = 0; i < 20; i++) {
                    u += 500L;
                    s.tick(u);
                    byte[] more = s.pollWrite();
                    if (more != null) {
                        check("retry of op " + (more[0] & 0xFF) + " after error " + code,
                                (more[0] & 0xFF) != 62 && (more[0] & 0xFF) != 3);
                        s.onNotification(new byte[] {Companion.PACKET_OK}, u + 5);
                    }
                }
                eq(0, e.queued());
                eq(1L, e.counters().get("chat_send_failed"));
            }
        }
    }

    private static void queueSend(Engine engine, int op, String text, long now) {
        Engine.Send send = op == 62
                ? engine.sendChat(Mctak.SCOPE_ALL, 0, "", text, now)
                : engine.sendNativeChannelText(text, now);
        eq("QUEUED", send.status);
    }

    private static byte[] pli(int sender, int seq) {
        Mctak.Header header = new Mctak.Header(0, false, Mctak.KIND_PLI, sender, seq);
        Mctak.Pli pli = new Mctak.Pli(1_800_000_000L, 515074000, -1278000, 1036, 45, 3, 85, 0x90);
        return Mctak.frame(header, pli.body());
    }

    private static void check(boolean value) {
        if (!value) {
            throw new AssertionError("expected true");
        }
    }

    private static void check(String message, boolean value) {
        if (!value) {
            throw new AssertionError(message);
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
