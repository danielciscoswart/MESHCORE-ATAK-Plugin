package dev.meshcore.atak;

import dev.meshcore.atak.companion.Companion;
import dev.meshcore.atak.engine.Engine;
import dev.meshcore.atak.mctak.Mctak;
import dev.meshcore.atak.transport.RadioLimits;
import dev.meshcore.atak.transport.RadioSession;
import dev.meshcore.atak.util.Bytes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Session checks against the pinned official MeshCore companion behavior. No radio is opened. */
final class TransportChecks {
    private TransportChecks() {}

    static void limits() {
        eq(165, RadioLimits.MAX_GROUP_DATA_LENGTH);
        eq(167, RadioLimits.MAX_CHANNEL_DATA_LENGTH);
        eq(176, RadioLimits.MAX_FRAME_SIZE);
        eq(62, RadioLimits.CMD_SEND_CHANNEL_DATA);
        eq(Mctak.RADIO_MAX_PAYLOAD, RadioLimits.MAX_GROUP_DATA_LENGTH);
        eq(Mctak.MAX_DATAGRAM, RadioLimits.MAX_CHANNEL_DATA_LENGTH);
        check("81dbae592ffad40b4212bc0b37e82b38da08f4bc".equals(RadioLimits.MESHCORE_COMMIT));
        check(!Mctak.radioWillAccept(166));
        check(!Mctak.radioWillAccept(167));
        check(Mctak.radioWillAccept(165));

        byte[] tooBig = floodFrame(167);
        eq("illegal_arg", RadioLimits.decision(tooBig, 512));
        boolean refused = false;
        try {
            Companion.sendChannelData(1, new byte[167]);
        } catch (IllegalArgumentException ex) {
            refused = true;
        }
        check(refused);
        byte[] exact = Companion.sendChannelData(1, new byte[165]);
        eq("ok", RadioLimits.decision(exact, 512));
        eq("mtu", RadioLimits.decision(exact, 23));
        eq(170, exact.length);
        check(exact.length <= RadioLimits.MAX_FRAME_SIZE);

        Engine engine = ready(1_000L);
        eq("OK", engine.selectChannel(1).status);
        eq(1, selectedFromLog(engine));
        eq("QUEUED", engine.joinSelected(1_000L).status);
        byte[] identFrame = engine.drainTx(1_000L).get(0);
        int payload = RadioLimits.groupPayloadLength(identFrame);
        check(payload > 14 && payload <= 165);
        byte[] datagram = Arrays.copyOfRange(identFrame, 5, identFrame.length);
        Mctak.Result parsed = Mctak.parse(datagram);
        check(parsed.ident != null);
        eq(Engine.ADVERTISED_CAPS, parsed.ident.caps);
        check((parsed.ident.caps & 0x0001) == 0);

        eq("BLOCKED_RADIO_LIMIT", engine.sendChat(Mctak.SCOPE_ALL, 0, "", "x".repeat(200), 2_000L).status);
        eq("NOT_TRANSMITTED", engine.sendMarker(null, 2_000L).status);
        byte[] over = floodFrame(167);
        eq("illegal_arg", RadioLimits.decision(over, RadioLimits.MIN_MTU_FOR_MAX_FRAME));

        eq(173, RadioLimits.ESP32_ATT_PAYLOAD);
        eq(164, RadioLimits.ESP32_NOTIFY_DATAGRAM_FIT);
        byte[] send165 = Companion.sendChannelData(1, new byte[165]);
        eq(170, send165.length);
        check(send165.length <= RadioLimits.ESP32_ATT_PAYLOAD);
        byte[] inbound = Companion.channelDataRecv(4, 1, 0, Mctak.DATA_TYPE_DEV, new byte[165]);
        eq(174, inbound.length);
        check(Mctak.parse(java.util.Arrays.copyOfRange(inbound, 9, inbound.length)) != null);
        byte[] truncated = java.util.Arrays.copyOf(inbound, RadioLimits.ESP32_ATT_PAYLOAD);
        check(Companion.parseChannelData(truncated) == null);
        check(!RadioLimits.backlogMayHaveOverflowed(15));
        check(RadioLimits.backlogMayHaveOverflowed(16));
    }

    static void session() {
        Engine engine = new Engine();
        RadioSession session = new RadioSession(engine);
        long t = 10_000L;
        session.onConnected(t);
        session.onMtu(23, t);
        session.onNotifications(true);
        session.tick(t);
        byte[] app = session.pollWrite();
        check(app != null && (app[0] & 0xFF) == Companion.CMD_APP_START);
        check(session.pollWrite() == null);
        long timedOut = t + RadioLimits.COMMAND_TIMEOUT_MS;
        session.tick(timedOut);
        check(session.pollWrite() == null);
        session.tick(timedOut + RadioLimits.WRITE_GAP_MS);
        byte[] retried = session.pollWrite();
        check(retried != null && (retried[0] & 0xFF) == Companion.CMD_APP_START);
        long second = timedOut + RadioLimits.WRITE_GAP_MS + RadioLimits.COMMAND_TIMEOUT_MS;
        session.tick(second);
        session.tick(second + RadioLimits.WRITE_GAP_MS);
        check(session.pollWrite() == null);
        check(engine.counters().get("command_timeout") >= 2);
        check(session.trace().contains("handshake_stopped"));

        Engine live = new Engine();
        RadioSession link = new RadioSession(live);
        long now = 50_000L;
        link.onConnected(now);
        link.onMtu(512, now);
        link.onNotifications(true);
        handshake(link, live, now);

        check(link.ready());
        eq("OK", live.selectChannel(1).status);
        String pin = "13572468";
        for (String line : link.trace()) {
            check(!line.toLowerCase().contains(pin));
            check(!line.toLowerCase().contains("42424242424242424242424242424242"));
        }
        for (String line : live.log()) {
            check(!line.toLowerCase().contains(pin));
        }

        live.setCallsign("VIPER-1");
        eq("QUEUED", live.joinSelected(now + 5_000L).status);
        link.tick(now + 5_000L);
        byte[] ident = link.pollWrite();
        check(ident != null && (ident[0] & 0xFF) == Companion.CMD_SEND_CHANNEL_DATA);
        link.onNotification(new byte[] {Companion.PACKET_OK}, now + 5_100L);

        byte[] waiting = new byte[] {(byte) Companion.PACKET_MESSAGES_WAITING};
        link.onNotification(waiting, now + 6_000L);
        link.onNotification(waiting, now + 6_001L);
        check(live.counters().get("sync_coalesced") >= 1);
        link.tick(now + 6_100L);
        byte[] sync = link.pollWrite();
        check(sync != null && (sync[0] & 0xFF) == Companion.CMD_SYNC_NEXT);
        byte[] pli = Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV, pliDatagram(0x01020304, 1));
        link.onNotification(Arrays.copyOfRange(pli, 0, pli.length), now + 6_200L);
        link.onNotification(waiting, now + 6_300L);
        link.tick(now + 6_400L);
        check(link.pollWrite() != null);
        link.onNotification(Arrays.copyOf(pli, pli.length), now + 6_500L);
        check(live.counters().get("dup_datagram") >= 1 || live.counters().get("held_first_contact") >= 1);

        byte[] junk = new byte[] {0x01, 0x02};
        link.onNotification(junk, now + 7_000L);
        check(live.counters().get("unexpected_response") >= 1);
        byte[] huge = new byte[200];
        for (int i = 0; i < 1000; i++) {
            link.onNotification(huge, now + 8_000L);
        }
        check(live.counters().get("ble_frame_rejected") >= 1000);
        check(link.trace().size() <= RadioLimits.TRACE_CAP);
        check(link.queuedWrites() <= RadioLimits.WRITE_QUEUE_CAP);

        live.setLocationOptIn(true);
        live.setSelfPosition(51.5074, -0.1278, 10.0, 0.0, 0.0, 50);
        eq("QUEUED", live.beacon(now + 400_000L).status);
        eq("mtu", RadioLimits.decision(ident, 23));

        link.onDisconnected(now + 500_000L);
        check(!link.reconnectDue(now + 500_000L));
        check(link.reconnectDue(now + 501_000L));
        link.onConnected(now + 501_000L);
        link.onDisconnected(now + 501_000L);
        check(!link.reconnectDue(now + 502_000L));
        check(link.reconnectDue(now + 503_000L));
        link.setWritesEnabled(false);
        check(!link.reconnectDue(now + 600_000L));
    }

    static void officialTree() {
        String env = System.getenv("MESHCORE_ROOT");
        if (env == null || env.isBlank()) {
            throw new Outcome.Skip("MESHCORE_ROOT unset; official tree was not read by this test");
        }
        try {
            officialTreeAt(Path.of(env));
        } catch (Outcome.Skip skip) {
            throw skip;
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static void officialTreeAt(Path root) throws Exception {
        String head = new String(Files.readAllBytes(root.resolve(".git/HEAD")), StandardCharsets.UTF_8).trim();
        if (head.startsWith("ref:")) {
            String ref = head.substring(4).trim();
            head = Files.readString(root.resolve(".git").resolve(ref)).trim();
        }
        eq(RadioLimits.MESHCORE_COMMIT, head);
        String mesh = Files.readString(root.resolve("src/MeshCore.h"));
        check(mesh.contains("#define MAX_PACKET_PAYLOAD  184"));
        check(mesh.contains("#define MAX_GROUP_DATA_LENGTH  (MAX_PACKET_PAYLOAD - CIPHER_BLOCK_SIZE - 3)"));
        String serial = Files.readString(root.resolve("src/helpers/BaseSerialInterface.h"));
        check(serial.contains("#define MAX_FRAME_SIZE  176"));
        String myMesh = Files.readString(root.resolve("examples/companion_radio/MyMesh.cpp"));
        check(myMesh.contains("#define CMD_SEND_CHANNEL_DATA         62"));
        check(myMesh.contains("#define MAX_CHANNEL_DATA_LENGTH       (MAX_FRAME_SIZE - 9)"));
        check(myMesh.contains("payload_len > MAX_GROUP_DATA_LENGTH"));
        // v7.2: opcodes and limits the native-text and stats paths rely on.
        check(myMesh.contains("#define CMD_SEND_CHANNEL_TXT_MSG      3"));
        check(myMesh.contains("#define CMD_GET_STATS                 56"));
        String chat = Files.readString(root.resolve("src/helpers/BaseChatMesh.h"));
        check(chat.contains("#define MAX_TEXT_LEN    (10*CIPHER_BLOCK_SIZE)"));
        String chatCpp = Files.readString(root.resolve("src/helpers/BaseChatMesh.cpp"));
        check(chatCpp.contains("createGroupDatagram(PAYLOAD_TYPE_GRP_TXT, channel, temp, 5 + prefix_len + text_len)"));
        check(chatCpp.contains("createGroupDatagram(PAYLOAD_TYPE_GRP_DATA"));
        String meshCpp = Files.readString(root.resolve("src/Mesh.cpp"));
        check(meshCpp.contains("Utils::encryptThenMAC(channel.secret"));
        check(myMesh.contains("writeErrFrame(ERR_CODE_ILLEGAL_ARG);"));
        String doc = Files.readString(root.resolve("docs/companion_protocol.md"));
        check(doc.contains("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"));
        check(doc.contains("one protocol frame per BLE write"));
        check(doc.contains("Hashtag channels should not be treated as private"));
        String esp = Files.readString(root.resolve("src/helpers/esp32/SerialBLEInterface.cpp"));
        check(esp.contains("BLEDevice::setMTU(MAX_FRAME_SIZE)"));
        String rp = Files.readString(root.resolve("src/helpers/rp2040/SerialBLEInterface.cpp"));
        check(rp.contains("client must negotiate MTU >= MAX_FRAME_SIZE+3"));
        System.out.println("official tree " + head + " matched pinned limits and framing");
    }

    private static void handshake(RadioSession link, Engine live, long now) {
        link.tick(now);
        byte[] app = link.pollWrite();
        check(app != null);
        byte[] key = new byte[32];
        Bytes.putRaw4(key, 0, 0xCAFEBABE);
        link.onNotification(Companion.selfInfo(key, 910_525, 62_500, 8, 5, "node"), now + 10);
        link.tick(now + 80);
        byte[] query = link.pollWrite();
        check(query != null && (query[0] & 0xFF) == Companion.CMD_DEVICE_QUERY);
        eq(3, query[1] & 0xFF);
        byte[] device = new byte[82];
        device[0] = Companion.PACKET_DEVICE_INFO;
        device[1] = 3;
        device[3] = 4;
        Bytes.putU32le(device, 4, 0x13572468L);
        link.onNotification(device, now + 100);
        eq(0, device[4] & 0xFF);
        eq(0, device[5] & 0xFF);
        eq(0, device[6] & 0xFF);
        eq(0, device[7] & 0xFF);
        for (int i = 0; i < 4; i++) {
            link.tick(now + 200L + i * 100L);
            byte[] get = link.pollWrite();
            check(get != null && (get[0] & 0xFF) == Companion.CMD_GET_CHANNEL);
            eq(i, get[1] & 0xFF);
            if (i == 0) {
                link.onNotification(Companion.channelInfo(0, "Public", Companion.PUBLIC_CHANNEL_SECRET), now + 250);
            } else if (i == 1) {
                link.onNotification(Companion.channelInfo(1, "field-a", secret()), now + 350);
            } else if (i == 2) {
                link.onNotification(Companion.channelInfo(2, "#topic", secret()), now + 450);
            } else {
                link.onNotification(Companion.channelInfo(3, "", new byte[16]), now + 550);
            }
        }
        check(live.channel(1) != null);
        check(live.channel(1).fingerprint != null);
        // E6: the first thing after READY is one read-only core stats request.
        link.tick(now + 700L);
        byte[] stats = link.pollWrite();
        check(stats != null && (stats[0] & 0xFF) == Companion.CMD_GET_STATS);
        link.onNotification(Companion.statsCore(3900, 1_000L, 0, 0), now + 720L);
        // v7.5: READY also starts one drain of the radio's offline queue; this radio has nothing.
        link.tick(now + 800L);
        byte[] drain = link.pollWrite();
        check(drain != null && (drain[0] & 0xFF) == Companion.CMD_SYNC_NEXT);
        link.onNotification(new byte[] {(byte) Companion.PACKET_NO_MORE_MESSAGES}, now + 820L);
    }

    private static Engine ready(long now) {
        Engine engine = new Engine();
        engine.onLinkUp(now);
        byte[] key = new byte[32];
        Bytes.putRaw4(key, 0, 0x11111111);
        engine.receiveFrame(Companion.selfInfo(key, 910_525, 62_500, 8, 5, "node"), now);
        engine.receiveFrame(Companion.channelInfo(0, "Public", Companion.PUBLIC_CHANNEL_SECRET), now);
        engine.receiveFrame(Companion.channelInfo(1, "field-a", secret()), now);
        engine.receiveFrame(Companion.channelInfo(2, "#topic", secret()), now);
        return engine;
    }

    private static int selectedFromLog(Engine engine) {
        for (String line : engine.log()) {
            if (line.startsWith("selected_channel ")) {
                return Integer.parseInt(line.substring("selected_channel ".length()));
            }
        }
        throw new AssertionError("no selection");
    }

    private static byte[] pliDatagram(int sender, int seq) {
        Mctak.Header header = new Mctak.Header(0, false, Mctak.KIND_PLI, sender, seq);
        Mctak.Pli pli = new Mctak.Pli(1_800_000_000L, 515074000, -1278000, 1036, 45, 3, 85, 0x90);
        return Mctak.frame(header, pli.body());
    }

    static void allegations() {
        Engine engine = new Engine();
        engine.onLinkUp(1_000L);
        byte[] key = new byte[32];
        Bytes.putRaw4(key, 0, 0x11111111);
        engine.receiveFrame(Companion.selfInfo(key, 910_525, 62_500, 8, 5, "node"), 1_000L);
        byte[] derived = sha256Prefix("#test");
        eq("9cd8fcf22a47333b591d96a2b848b73f", Bytes.hex(derived));
        engine.receiveFrame(Companion.channelInfo(3, "test", derived), 1_000L);
        eq("REFUSED_PUBLIC", engine.selectChannel(3).status);
        engine.receiveFrame(Companion.channelInfo(4, "named", new byte[16]), 1_000L);
        eq("REFUSED_PUBLIC", engine.selectChannel(4).status);
        byte[] random = new byte[16];
        Arrays.fill(random, (byte) 0x5A);
        engine.receiveFrame(Companion.channelInfo(5, "test", random), 1_000L);
        eq("OK", engine.selectChannel(5).status);
        for (String line : engine.log()) {
            check(!line.contains("9cd8fcf22a47333b591d96a2b848b73f"));
            check(!line.contains("00000000000000000000000000000000"));
        }

        engine.resumeAfter(40);
        eq(40, engine.lastIssuedSequence());
        eq("QUEUED", engine.joinSelected(2_000L).status);
        byte[] ident = engine.drainTx(2_000L).get(0);
        int seq = (ident[5 + 8] & 0xFF) | ((ident[5 + 9] & 0xFF) << 8);
        eq(41, seq);

        RadioSession session = new RadioSession(engine);
        long now = 8_000L;
        session.onConnected(now);
        session.onMtu(512, now);
        session.onNotifications(true);
        session.tick(now);
        byte[] app = session.pollWrite();
        check(app != null);
        // E8: native text is no longer discarded. A frame too short to parse is counted, not shown.
        session.onNotification(new byte[] {Companion.PACKET_CHANNEL_MSG, 0x00}, now + 10);
        check(engine.nativeInbox().isEmpty());
        check(engine.counters().get("native_text_bad") >= 1);
        session.onNotification(Companion.channelTextV3(20, 1, 2, 1_700_000_000L, "hello"), now + 20);
        eq(1, engine.nativeInbox().size());
        check(engine.nativeInbox().get(0).display().startsWith("[" + Engine.NativeMessage.LABEL + "]"));
        eq(0L, engine.counters().get("receipt_delivered_queued"));
        session.onNotification(new byte[] {Companion.PACKET_ERROR, RadioLimits.ERR_ILLEGAL_ARG}, now + 90);
        eq("RADIO_ERROR_6", engine.transportStatus());
        eq(0L, engine.counters().get("receipt_delivered_queued"));
    }

    private static byte[] floodFrame(int payloadLen) {
        byte[] frame = new byte[5 + payloadLen];
        frame[0] = (byte) Companion.CMD_SEND_CHANNEL_DATA;
        frame[1] = 1;
        frame[2] = (byte) 0xFF;
        frame[3] = (byte) 0xFF;
        frame[4] = (byte) 0xFF;
        return frame;
    }

    private static byte[] sha256Prefix(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Arrays.copyOf(digest, 16);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static byte[] secret() {
        byte[] secret = new byte[16];
        Arrays.fill(secret, (byte) 0x42);
        return secret;
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
