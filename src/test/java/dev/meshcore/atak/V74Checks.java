package dev.meshcore.atak;

import dev.meshcore.atak.companion.Companion;
import dev.meshcore.atak.engine.Engine;
import dev.meshcore.atak.plugin.PluginHost;
import dev.meshcore.atak.util.Bytes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * v7.4 checks. J1: a message typed into the room arrives the way ATAK 5.6 really delivers it (the
 * PluginConnector Intent Bundle), not as a comms-logger event. J2: the radio's channel slot of a
 * native text frame, read from bytes written out by hand from the firmware source.
 */
final class V74Checks {
    private V74Checks() {}

    private static final byte[] KEY_A = filled(0x5A);
    private static final byte[] KEY_B = filled(0x37);
    private static final long T0 = 1_000L;
    private static final String SELF = "ANDROID-self0001";

    private static byte[] filled(int v) {
        byte[] b = new byte[16];
        Arrays.fill(b, (byte) v);
        return b;
    }

    /** The Ciscokid-ATAK table from the 2026-11-10 phone test: Public (0) and the private channel (1). */
    private static PluginHost phoneHost() {
        PluginHost host = new PluginHost();
        host.onCreate();
        host.onStart();
        Engine engine = host.engine();
        engine.onLinkUp(T0);
        engine.setNowSeconds(1_800_000_000L);
        byte[] key = new byte[32];
        Bytes.putRaw4(key, 0, 0x11111111);
        engine.receiveFrame(Companion.selfInfo(key, 910_525, 62_500, 8, 5, "Ciscokid-ATAK"), T0);
        engine.receiveFrame(Companion.channelInfo(0, "Public", Companion.PUBLIC_CHANNEL_SECRET), T0);
        engine.receiveFrame(Companion.channelInfo(1, "CombatClubMesh", KEY_A), T0);
        engine.receiveFrame(Companion.channelInfo(3, "ops", KEY_B), T0);
        eq("OK", engine.selectChannel(1).status);
        return host;
    }

    private static List<byte[]> drainAll(Engine engine, long nowMs) {
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            List<byte[]> one = engine.drainTx(nowMs + i * 60_000L);
            if (one.isEmpty()) {
                break;
            }
            out.addAll(one);
        }
        return out;
    }

    // ---- J1 -----------------------------------------------------------------------------

    /** What ATAK 5.6 puts in the MESSAGE Bundle for a typed room message (read in the bytecode). */
    static void j1TypedRoomMessageArrivesAsAConnectorIntent() {
        PluginHost host = phoneHost();
        Engine engine = host.engine();
        String room = engine.roomSpec().uid;
        long now = 5_000L;
        // destinations as ATAK builds them for a one-contact conversation: the room, with or without this device.
        List<byte[]> tx = new ArrayList<>();
        eq("QUEUED", host.offerRoomChat(room, new String[] {room}, SELF, "7d1c0e3e-0001", "testing", now));
        eq("", host.lastWhy());
        tx.addAll(drainAll(engine, now));
        now += 120_000L;
        eq("QUEUED", host.offerRoomChat(room, new String[] {room, SELF}, SELF, "7d1c0e3e-0002", "not working", now));
        tx.addAll(drainAll(engine, now));
        now += 120_000L;
        eq("QUEUED", host.offerRoomChat(room, null, SELF, "7d1c0e3e-0003", "no destination list", now));
        tx.addAll(drainAll(engine, now));
        now += 120_000L;
        eq(3, tx.size());
        for (byte[] frame : tx) {
            eq(Companion.CMD_SEND_CHANNEL_DATA, frame[0] & 0xFF);
            eq(1, frame[1] & 0xFF);
        }
        eq(3L, engine.counters().get("room_send_intent"));
        eq(3L, engine.counters().get("room_send_queued"));
        // The same Bundle again (ATAK may report one send twice) is dropped.
        eq("DUPLICATE", host.offerRoomChat(room, new String[] {room}, SELF, "7d1c0e3e-0001", "testing", now));
        eq(0, drainAll(engine, now).size());
        // With the "Plugin users only" switch OFF the room is another room and sends native text (cmd 3).
        engine.setPluginOnlyChat(false);
        String textRoom = engine.roomSpec().uid;
        eq("QUEUED", host.offerRoomChat(textRoom, new String[] {textRoom}, SELF, "7d1c0e3e-0004", "native", now));
        tx = drainAll(engine, now);
        eq(1, tx.size());
        eq(3, tx.get(0)[0] & 0xFF);
        eq(1, tx.get(0)[2] & 0xFF);
    }

    static void j1UnreadableOrForeignBundlesNeverSend() {
        PluginHost host = phoneHost();
        Engine engine = host.engine();
        String room = engine.roomSpec().uid;
        long now = 5_000L;
        String sentence = "Not sent: message could not be read";
        eq("REFUSED", host.offerRoomChat(null, null, SELF, "id", "text", now));
        eq(sentence, host.lastWhy());
        eq("REFUSED", host.offerRoomChat("ANDROID-abc123", new String[] {"ANDROID-abc123"}, SELF, "id", "text", now));
        eq("REFUSED", host.offerRoomChat("All Chat Rooms", new String[] {"All Chat Rooms"}, SELF, "id", "text", now));
        eq("REFUSED", host.offerRoomChat(room, new String[] {room}, SELF, "id", null, now));
        eq("REFUSED", host.offerRoomChat(room, new String[] {room}, SELF, "id", "   ", now));
        eq("REFUSED", host.offerRoomChat(room, new String[] {room}, SELF, "id", "x".repeat(2_001), now));
        eq(0, engine.queued());
        eq(6L, engine.counters().get("room_send_bad_bundle"));
        // A Bundle that also names another contact would reach that contact through ATAK's chat
        // service (and the TAK server), so the radio copy is refused rather than half-sent.
        eq("REFUSED", host.offerRoomChat(room, new String[] {room, "ANDROID-abc123"}, SELF, "id2", "hi", now));
        eq("Not sent: this chat has other recipients", host.lastWhy());
        eq(1L, engine.counters().get("room_send_mixed_dest"));
        eq(0, engine.queued());
        eq(0, drainAll(engine, now).size());
        // The Bundle's destinations in any form still reveal another recipient.
        eq(2, PluginHost.destinationsOf(room + "," + "ANDROID-abc123").length);
        eq("REFUSED", host.offerRoomChat(room, PluginHost.destinationsOf(room + ",ANDROID-abc123"), SELF, "id5", "hi", now));
        eq(null, PluginHost.destinationsOf("null"));
        eq(null, PluginHost.destinationsOf(42));
        eq(null, PluginHost.destinationsOf(null));
    }

    static void j1StaleRoomClosedAndStoppedHostRefuse() {
        PluginHost host = phoneHost();
        Engine engine = host.engine();
        String oldRoom = engine.roomSpec().uid;
        eq("OK", engine.selectChannel(3).status);
        eq("REFUSED", host.offerRoomChat(oldRoom, new String[] {oldRoom}, SELF, "id", "late", 5_000L));
        eq(Engine.ROOM_CLOSED_WHY, host.lastWhy());
        eq(0, engine.queued());
        // A lost link removes the room: a typed message is refused with a sentence, never silent.
        engine.onLinkLost();
        eq("REFUSED", host.offerRoomChat(oldRoom, new String[] {oldRoom}, SELF, "id3", "gone", 6_000L));
        check(!host.lastWhy().isEmpty());
        eq(0, engine.queued());
        host.onStop();
        eq("STOPPED", host.offerRoomChat(oldRoom, new String[] {oldRoom}, SELF, "id4", "stopped", 7_000L));
        check(!host.lastWhy().isEmpty());
    }

    /**
     * The v7.3 bug: the room was wired to a comms-logger event, but ATAK sends a plugin contact's
     * message to its connector Intent. Source checks that the Android side is wired to the Intent.
     */
    static void j1AndroidSideIsWiredToTheConnectorIntent() {
        try {
            androidSideChecks();
        } catch (java.io.IOException ex) {
            throw new AssertionError("source not readable: " + ex.getMessage());
        }
    }

    private static void androidSideChecks() throws java.io.IOException {
        Path host = root().resolve("app/src/main/java/dev/meshcore/atak/host");
        String room = Files.readString(host.resolve("AtakChatRoom.java"));
        String plugin = Files.readString(host.resolve("MeshCoreAtakPlugin.java"));
        String receiver = Files.readString(host.resolve("RoomSendReceiver.java"));
        String logger = Files.readString(host.resolve("MeshCoreRoomLogger.java"));
        check(room.contains("new PluginConnector(CONNECTOR_ACTION)"));
        check(room.contains("static final String CONNECTOR_ACTION"));
        check(!room.contains("NOT_AN_ENDPOINT"));
        check(!room.toLowerCase().contains("no receiver is registered"));
        // The action the connector names is the action a receiver is registered for.
        check(plugin.contains("filter.addAction(AtakChatRoom.CONNECTOR_ACTION"));
        check(plugin.contains("AtakBroadcast.getInstance().registerReceiver(roomSendReceiver, filter)"));
        check(plugin.contains("AtakBroadcast.getInstance().unregisterReceiver(roomSendReceiver)"));
        check(plugin.contains("host.offerRoomChat("));
        // A room without its receiver must not be offered.
        check(plugin.indexOf("registerSendReceiver()") < plugin.indexOf("comms.registerCommsLogger(roomLogger)"));
        // ATAK's Bundle keys, as read in ChatManagerMapComponent / ChatLine.toBundle.
        check(receiver.contains("\"MESSAGE\""));
        for (String key : new String[] {"conversationId", "destinations", "senderUid", "messageId", "message"}) {
            check(receiver.contains("\"" + key + "\""));
        }
        // The comms logger can no longer queue a send of any kind.
        check(!logger.contains("pending.offer("));
        // Still no external dispatcher and no GeoChatService call anywhere in the host package.
        for (Path file : (Iterable<Path>) Files.list(host)::iterator) {
            String src = Files.readString(file);
            check(!src.contains("getExternalDispatcher"));
            check(!src.contains("import com.atakmap.android.chat.GeoChatService"));
            check(!src.contains("getChatService("));
        }
    }

    // ---- J2 -----------------------------------------------------------------------------

    /**
     * Frames written out byte by byte from firmware MyMesh.cpp onChannelMessageRecv (companion-v1.18.0,
     * 81dbae5, lines 558-583), not built with the plugin's own test helper.
     */
    static void j2NativeChannelFrameFromFirmwareBytes() {
        // v3 (app_target_ver >= 3): {0x11, snr*4, 0, 0, channel_idx, path_len, txt_type, ts u32le, text}
        byte[] text = "Ciscokid: Hello?".getBytes(StandardCharsets.UTF_8);
        byte[] v3 = new byte[11 + text.length];
        v3[0] = 0x11;
        v3[1] = (byte) 0x28;   // snr 10 dB * 4
        v3[2] = 0;             // reserved1
        v3[3] = 0;             // reserved2
        v3[4] = 0x01;          // channel_idx
        v3[5] = (byte) 0xFF;   // path_len 0xFF: not flood-routed
        v3[6] = 0;             // TXT_TYPE_PLAIN
        v3[7] = 0x10; v3[8] = 0x20; v3[9] = 0x30; v3[10] = 0x65; // timestamp 0x65302010 LE
        System.arraycopy(text, 0, v3, 11, text.length);
        Companion.NativeText parsed = Companion.parseNativeText(v3);
        check(parsed != null);
        check(!parsed.direct);
        eq(1, parsed.channel);
        eq(255, parsed.pathLen);
        eq(0, parsed.txtType);
        eq(0x65302010L, parsed.senderTimestamp);
        eq("Ciscokid: Hello?", parsed.text);
        // v1 (app_target_ver < 3): {0x08, channel_idx, path_len, txt_type, ts u32le, text}
        byte[] v1 = new byte[8 + text.length];
        v1[0] = 0x08;
        v1[1] = 0x01;
        v1[2] = (byte) 0xFF;
        v1[3] = 0;
        v1[4] = 0x10; v1[5] = 0x20; v1[6] = 0x30; v1[7] = 0x65;
        System.arraycopy(text, 0, v1, 8, text.length);
        Companion.NativeText old = Companion.parseNativeText(v1);
        check(old != null);
        eq(1, old.channel);
        eq("Ciscokid: Hello?", old.text);
        // The firmware reports 0xFF when it cannot find the slot (findChannelIdx returns -1).
        v3[4] = (byte) 0xFF;
        eq(255, Companion.parseNativeText(v3).channel);
    }

    static void j2SlotIsCountedAndExplainedWithoutText() {
        PluginHost host = phoneHost();
        Engine engine = host.engine();
        byte[] chosen = frameV3(1, "Ciscokid: Hello?");
        byte[] publicSlot = frameV3(0, "Ciscokid: Hello?");
        byte[] unknown = frameV3(255, "Ciscokid: Hello?");
        engine.receiveFrame(chosen, 2_000L);
        eq("frame=0x11 channel=1 selected=1 path=255 bytes=" + chosen.length, engine.lastNativeNote());
        eq(1, engine.drainRoomMessages().size());
        engine.receiveFrame(publicSlot, 2_100L);
        engine.receiveFrame(unknown, 2_200L);
        eq(0, engine.drainRoomMessages().size());
        eq(1L, engine.counters().get("native_slot_1"));
        eq(1L, engine.counters().get("native_slot_0"));
        eq(1L, engine.counters().get("native_slot_255"));
        eq(2L, engine.counters().get("native_other_channel_pane_only"));
        eq("frame=0x11 channel=255 selected=1 path=255 bytes=" + unknown.length, engine.lastNativeNote());
        check(!engine.lastNativeNote().contains("Hello"));
    }

    private static byte[] frameV3(int channel, String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] f = new byte[11 + body.length];
        f[0] = 0x11;
        f[4] = (byte) channel;
        f[5] = (byte) 0xFF;
        f[7] = 0x10; f[8] = 0x20; f[9] = 0x30; f[10] = 0x65;
        System.arraycopy(body, 0, f, 11, body.length);
        return f;
    }

    private static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("app/src/main/java/dev/meshcore/atak/host"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new AssertionError("repository root not found");
        }
        return p;
    }

    private static void check(boolean value) {
        if (!value) {
            throw new AssertionError("check failed");
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
