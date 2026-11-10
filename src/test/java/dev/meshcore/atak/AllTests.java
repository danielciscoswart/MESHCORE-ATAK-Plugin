package dev.meshcore.atak;

import dev.meshcore.atak.companion.Companion;
import dev.meshcore.atak.cot.Cot;
import dev.meshcore.atak.engine.Engine;
import dev.meshcore.atak.engine.Governor;
import dev.meshcore.atak.mctak.Airtime;
import dev.meshcore.atak.mctak.Counters;
import dev.meshcore.atak.mctak.Fountain;
import dev.meshcore.atak.mctak.Mctak;
import dev.meshcore.atak.mctak.Reassembly;
import dev.meshcore.atak.util.Bytes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;

/** SDK-free checks. Positions are the published spec example or other fixed fixtures. */
public final class AllTests {
    private static int failed;
    private static int passed;
    private static int skipped;

    public static int run() {
        test("appendix A PLI bytes", AllTests::pliBytes);
        test("geochat broadcast, direct, room", AllTests::geochatBytes);
        test("receipt, ident, who, xfer", AllTests::otherKinds);
        test("malformed and encrypted drops", AllTests::malformed);
        test("xorshift, soliton, crc, airtime", AllTests::math);
        test("fountain reorder, duplicate, loss", AllTests::fountain);
        test("fuzz parser", AllTests::fuzz);
        test("governor, public channel, secrets, location", AllTests::policy);
        test("two simulated radios", AllTests::radios);
        test("PLI resync and self echo", AllTests::pliResync);
        test("official meshcore limits", TransportChecks::limits);
        test("channel privacy sequence and honest send status", TransportChecks::allegations);
        test("companion session loss duplicate bounds reconnect", TransportChecks::session);
        test("plugin lifecycle contacts chat markers", PluginChecks::lifecycle);
        test("B1 tick never closes a link in progress", LinkMachineChecks::tickNeverClosesLinkInProgress);
        test("stage time limits, status codes, auto-retry rules", LinkMachineChecks::stageTimeouts);
        test("late callbacks cannot revive a failed link", LinkMachineChecks::lateCallbacksCannotReviveALink);
        test("connect button labels", LinkMachineChecks::buttonLabels);
        test("B3 refused write keeps the frame", LinkMachineChecks::refusedWriteKeepsFrame);
        test("R5 handshake failures are visible", LinkMachineChecks::handshakeFailuresAreVisible);
        test("C1 radio held by another app", LinkRulesChecks::c1HeldByAnotherApp);
        test("C2 failed write ends the attempt", LinkRulesChecks::c2WriteFailureEndsAttempt);
        test("C3 GATT status hints", LinkRulesChecks::c3StatusHints);
        test("C4 createBond refusal re-check", LinkRulesChecks::c4CreateBondRefused);
        test("C5 MTU not negotiated", LinkRulesChecks::c5MtuNotNegotiated);
        test("C6 handshake progress", LinkRulesChecks::c6HandshakeProgress);
        test("scan start limit", LinkRulesChecks::scanLimit);
        test("optional mctak fixtures", AllTests::fixtures);
        test("optional official meshcore tree", TransportChecks::officialTree);
        System.out.println("PASSED " + passed + " FAILED " + failed + " SKIPPED " + skipped);
        System.out.println("meshcore " + dev.meshcore.atak.transport.RadioLimits.MESHCORE_COMMIT);
        return failed;
    }

    private static void pliBytes() {
        Mctak.Header header = new Mctak.Header(0, false, Mctak.KIND_PLI, 0xDEADBEEF, 0x0102);
        Mctak.Pli pli = new Mctak.Pli(1_800_000_000L, 515074000, -1278000, 1036, 45, 3, 85, 0x90);
        byte[] encoded = Mctak.frame(header, pli.body());
        String expect = "544b0001deadbeef020100d2496bd067b31ed07fecff0c042d035590";
        eq(expect, Bytes.hex(encoded));
        Mctak.Result parsed = Mctak.parse(encoded);
        check(parsed.ok());
        eq(515074000, parsed.pli.latE7);
        eq(-1278000, parsed.pli.lonE7);
        eq(36.0, parsed.pli.haeMeters());
        eq(90.0, parsed.pli.courseDegrees());
        eq(1.5, parsed.pli.speedMps());
        eq("Cyan", Mctak.teamName(parsed.pli.teamRole));
        eq("Team Member", Mctak.roleName(parsed.pli.teamRole));
        long rendered = Mctak.renderTime(1_800_000_000L, 1_800_000_100L);
        eq(1_800_000_000L, rendered);
        long insane = Mctak.renderTime(10L, 1_800_000_000L);
        eq(1_800_000_000L, insane);
        String cot = Cot.pli("MCTAK-deadbeef", parsed.pli, "VIPER-1", 1_800_000_100L);
        check(cot.contains("type=\"a-f-G-U-C\""));
        check(cot.contains("unverified=\"true\""));
        check(cot.contains("__meshcore"));
        check(cot.contains("callsign=\"VIPER-1\""));
        check(Cot.hasMeshcoreLoopMarker(cot));
    }

    private static void geochatBytes() {
        byte[] all = chat(0x0103, 0x0BADF00D, Mctak.SCOPE_ALL, 0, "", "hello mesh");
        eq("544b0002deadbeef030100d2496b0df0ad0b000a68656c6c6f206d657368", Bytes.hex(all));
        byte[] direct = chat(0x0104, 7, Mctak.SCOPE_DIRECT, 0xCAFEBABE, "", "psst");
        eq("544b0002deadbeef040100d2496b0700000001cafebabe0470737374", Bytes.hex(direct));
        byte[] room = chat(0x0105, 8, Mctak.SCOPE_ROOM, 0, "ops", "rally at CP1");
        eq("544b0002deadbeef050100d2496b0800000002036f70730c72616c6c7920617420435031", Bytes.hex(room));
        Mctak.Result parsed = Mctak.parse(direct);
        eq(0xCAFEBABE, parsed.chat.dest);
        eq("psst", parsed.chat.text);
        String cot = Cot.chat(parsed.chat, 0xDEADBEEF, "VIPER-1", "MCTAK-cafebabe", 1_800_000_000L);
        check(cot.contains("type=\"b-t-f\""));
        check(cot.contains("MCTAK.deadbeef.00000007"));
        check(cot.contains("unverified=\"true\""));
    }

    private static byte[] chat(int seq, int msgId, int scope, int dest, String room, String text) {
        Mctak.Header header = new Mctak.Header(0, false, Mctak.KIND_GEOCHAT, 0xDEADBEEF, seq);
        Mctak.GeoChat body = new Mctak.GeoChat(1_800_000_000L, msgId, scope, dest, room, text);
        return Mctak.frame(header, body.body());
    }

    private static void otherKinds() {
        Mctak.Header receiptH = new Mctak.Header(0, false, Mctak.KIND_RECEIPT, 0xCAFEBABE, 0x0200);
        byte[] receipt = Mctak.frame(receiptH, new Mctak.Receipt(1, 0xDEADBEEF, 7).body());
        eq("544b0003cafebabe000201deadbeef07000000", Bytes.hex(receipt));
        Mctak.Header identH = new Mctak.Header(0, false, Mctak.KIND_IDENT, 0xDEADBEEF, 0x0106);
        byte[] ident = Mctak.frame(identH, new Mctak.Ident(1_800_000_000L, 0x0009, 0x01, 0x90, "VIPER-1", "").body());
        eq("544b0006deadbeef060100d2496b090001900756495045522d3100", Bytes.hex(ident));
        Mctak.Result identParsed = Mctak.parse(ident);
        eq("VIPER-1", identParsed.ident.callsign);
        eq("", identParsed.ident.atakUid);
        byte[] who = Mctak.frame(new Mctak.Header(0, false, Mctak.KIND_WHO, 0xCAFEBABE, 0x0201),
                new Mctak.Who(0xDEADBEEF).body());
        eq("544b0007cafebabe0102deadbeef", Bytes.hex(who));
        byte[] xfer = Mctak.frame(new Mctak.Header(0, false, Mctak.KIND_XFER, 0xCAFEBABE, 0x0203),
                new Mctak.XferStatus(0xDEADBEEF, 0x1234, 0, false, 19).body());
        eq("544b0005cafebabe0302deadbeef341200000000001300", Bytes.hex(xfer));
        byte[] junk = Arrays.copyOf(receipt, receipt.length + 3);
        Mctak.Result extra = Mctak.parse(junk);
        check(extra.ok());
        eq(1, extra.receipt.rtype);
    }

    private static void malformed() {
        byte[] pli = Bytes.parseHex("544b0001deadbeef020100d2496bd067b31ed07fecff0c042d035590");
        pli[2] = 0x01;
        eq("enc_undecryptable", Mctak.parse(pli).drop);
        byte[] badMagic = Arrays.copyOf(pli, pli.length);
        badMagic[0] = 0;
        eq("magic", Mctak.parse(badMagic).drop);
        byte[] shortFrag = new byte[166];
        shortFrag[0] = 0x54;
        shortFrag[1] = 0x4B;
        shortFrag[3] = Mctak.KIND_FRAG;
        eq("frag_length", Mctak.parse(shortFrag).drop);
        byte[] frag = new byte[Mctak.MAX_DATAGRAM];
        frag[0] = 0x54;
        frag[1] = 0x4B;
        frag[3] = Mctak.KIND_FRAG;
        Bytes.putRaw4(frag, 4, 0xDEADBEEF);
        Bytes.putU16le(frag, Mctak.HDR_LEN, 0x1234);
        frag[Mctak.HDR_LEN + 2] = 0;
        Bytes.putU16le(frag, Mctak.HDR_LEN + 3, 4101);
        frag[Mctak.HDR_LEN + 5] = 27;
        Bytes.putU16le(frag, Mctak.HDR_LEN + 6, 0);
        eq("k_mismatch", Mctak.parse(frag).drop);
        frag[Mctak.HDR_LEN + 5] = 28;
        Bytes.putU16le(frag, Mctak.HDR_LEN + 6, 10_000);
        eq("sym_idx", Mctak.parse(frag).drop);
        frag[2] = 0x01;
        Bytes.putU16le(frag, Mctak.HDR_LEN + 6, 0);
        eq("enc_undecryptable", Mctak.parse(frag).drop);
        byte[] scope = chat(1, 1, 0, 0, "", "x");
        scope[Mctak.HDR_LEN + 8] = 9;
        eq("scope", Mctak.parse(scope).drop);
        byte[] truncated = Arrays.copyOf(scope, 12);
        check(Mctak.parse(truncated).drop != null);
    }

    private static void math() {
        Fountain.Xorshift32 rng = new Fountain.Xorshift32(1);
        eq(0x00042021, rng.next());
        double[] cdf = Fountain.cdf(2);
        near(0.5446372995361517, cdf[0]);
        near(1.0, cdf[1]);
        eq(2, Fountain.mStar(1));
        eq(3, Fountain.roundTarget(1, 1));
        eq(5, Fountain.roundTarget(1, 2));
        eq(6, Fountain.roundTarget(1, 3));
        eq(6, Fountain.mStar(4));
        eq(8, Fountain.roundTarget(4, 1));
        eq(13, Fountain.roundTarget(4, 2));
        eq(18, Fountain.roundTarget(4, 3));
        eq(38, Fountain.mStar(28));
        eq(48, Fountain.roundTarget(28, 1));
        CRC32 crc = new CRC32();
        crc.update("123456789".getBytes(StandardCharsets.US_ASCII));
        eq(0xCBF43926L, crc.getValue());
        eq(308, Airtime.packetMs(28, 8, 62_500, 5));
        eq(267, Airtime.packetMs(28, 10, 250_000, 5));
        eq(1025, Airtime.packetMs(167, 8, 62_500, 5));
        near(0.10, Airtime.duty(910_525));
        near(0.01, Airtime.duty(868_000));
        check(!Mctak.radioWillAccept(167));
        check(Mctak.radioWillAccept(28));
    }

    private static void fountain() {
        byte[] payload = new byte[180];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 3 + 1);
        }
        byte[] prefix = new byte[1 + payload.length];
        prefix[0] = 0;
        System.arraycopy(payload, 0, prefix, 1, payload.length);
        byte[] object = Fountain.withCrc(prefix);
        check(Fountain.crcOk(object));
        int k = Fountain.blocksFor(object.length);
        check(k >= 2);
        byte[][] blocks = Fountain.split(object);
        int transfer = 0x1234;
        List<Fountain.SymbolIn> symbols = new ArrayList<>();
        symbols.add(new Fountain.SymbolIn(1, Fountain.symbol(blocks, transfer, 1)));
        symbols.add(new Fountain.SymbolIn(1, Fountain.symbol(blocks, transfer, 1)));
        symbols.add(new Fountain.SymbolIn(0, Fountain.symbol(blocks, transfer, 0)));
        byte[] decoded = Fountain.decode(transfer, k, object.length, symbols);
        check(Arrays.equals(object, decoded));

        Counters counters = new Counters();
        List<byte[]> delivered = new ArrayList<>();
        Reassembly reassembly = new Reassembly(counters, (sender, content, obj, crc) -> delivered.add(obj));
        Mctak.Header header = new Mctak.Header(0, false, Mctak.KIND_FRAG, 0xCAFEBABE, 1);
        offer(reassembly, header, transfer, k, object, 1, 0);
        eq(0, delivered.size());
        eq(1L, counters.get("frag_held"));
        offer(reassembly, header, transfer, k, object, 1, 0);
        eq(1L, counters.get("dup_symbol"));
        offer(reassembly, header, transfer, k, object, 0, 1);
        eq(1, delivered.size());
        check(Arrays.equals(object, delivered.get(0)));

        byte[] voice = fragDatagram(header, transfer, Mctak.CONTENT_VOICE, object.length, k, 0,
                Fountain.symbol(blocks, transfer, 0));
        Mctak.Result voiceParsed = Mctak.parse(voice);
        check(voiceParsed.ok());
        reassembly.offer(voiceParsed.header, voiceParsed.frag, 0);
        eq(1L, counters.get("gated_voice_or_file"));

        Random random = new Random(7);
        byte[] lossy = Fountain.withCrc(new byte[] {0, 9, 8, 7});
        int lossK = Fountain.blocksFor(lossy.length);
        byte[][] lossBlocks = Fountain.split(lossy);
        List<Fountain.SymbolIn> arrived = new ArrayList<>();
        for (int sym = 0; sym < Fountain.roundTarget(lossK, 3); sym++) {
            if ((sym & 1) == 0) {
                continue;
            }
            arrived.add(new Fountain.SymbolIn(sym, Fountain.symbol(lossBlocks, 9, sym)));
        }
        byte[] recovered = Fountain.decode(9, lossK, lossy.length, arrived);
        check(Arrays.equals(lossy, recovered));
    }

    private static void offer(Reassembly reassembly, Mctak.Header header, int transfer, int k,
                              byte[] object, int sym, int seq) {
        byte[][] blocks = Fountain.split(object);
        byte[] datagram = fragDatagram(new Mctak.Header(0, false, Mctak.KIND_FRAG, header.sender, seq),
                transfer, Mctak.CONTENT_COT, object.length, k, sym, Fountain.symbol(blocks, transfer, sym));
        Mctak.Result parsed = Mctak.parse(datagram);
        check(parsed.ok());
        reassembly.offer(parsed.header, parsed.frag, 1_000L);
    }

    private static byte[] fragDatagram(Mctak.Header header, int transfer, int content, int objLen,
                                       int k, int sym, byte[] symbol) {
        Mctak.Frag frag = new Mctak.Frag(transfer, content, false, objLen, k, sym, symbol);
        return Mctak.frame(header, frag.body());
    }

    private static void fuzz() {
        Random random = new Random(99);
        for (int i = 0; i < 300; i++) {
            byte[] raw = new byte[random.nextInt(220)];
            random.nextBytes(raw);
            Mctak.Result parsed = Mctak.parse(raw);
            check(parsed != null);
            if (parsed.ok() && parsed.frag != null) {
                eq(Mctak.MAX_DATAGRAM, raw.length);
            }
        }
        byte[] empty = new byte[0];
        eq("short", Mctak.parse(empty).drop);
    }

    private static void policy() {
        Counters counters = new Counters();
        Governor governor = new Governor(counters, 910_525, 0);
        eq(60_000, governor.tokensMs());
        byte[] frame = new byte[] {1, 2, 3};
        check(governor.offer(Governor.Klass.P1, frame, 1000, 0, "chat", false));
        Governor.Item item = governor.poll(0);
        check(item != null);
        eq("chat", item.label);
        check(governor.poll(100) == null);
        byte[] again = new byte[] {4};
        check(governor.offer(Governor.Klass.P1, again, 1000, 250, "chat2", false));
        check(governor.poll(250) != null);

        for (int i = 0; i < 20; i++) {
            governor.offer(Governor.Klass.P1, frame, 50_000, 10_000, "big", false);
        }
        check(governor.queued() <= 8);
        check(counters.get("queue_drop") > 0);
        governor.refill(80_000);
        check(counters.get("queue_expired") > 0);

        Engine engine = readyEngine(0x11111111, "field-a", secret(0x42), 1_000L);
        String secretHex = "42424242424242424242424242424242";
        for (String line : engine.log()) {
            check(!line.toLowerCase().contains(secretHex));
        }
        Engine.Send blocked = engine.selectChannel(0);
        eq("REFUSED_PUBLIC", blocked.status);
        Engine.Send ok = engine.selectChannel(1);
        eq("OK", ok.status);
        Engine.Send off = engine.beacon(2_000L);
        eq("LOCATION_OFF", off.status);
        engine.setSelfPosition(51.5074, -0.1278, 36.0, 90.0, 1.5, 80);
        check(engine.counters().get("location_ignored") == 1);
        engine.setLocationOptIn(true);
        engine.setSelfPosition(51.5074, -0.1278, 36.0, 90.0, 1.5, 80);
        Engine.Send beacon = engine.beacon(2_000L);
        eq("QUEUED", beacon.status);
        eq("UNSUPPORTED", engine.sendVoice().status);
        eq("UNSUPPORTED", engine.sendImage().status);
        eq("NOT_TRANSMITTED", engine.sendMarker(null, 2_000L).status);
        String huge = "x".repeat(200);
        eq("BLOCKED_RADIO_LIMIT", engine.sendChat(Mctak.SCOPE_ALL, 0, "", huge, 3_000L).status);

        byte[] pin = new byte[80];
        pin[0] = Companion.PACKET_DEVICE_INFO;
        pin[1] = 3;
        pin[3] = 8;
        Bytes.putU32le(pin, 4, 0x13572468L);
        engine.receiveFrame(pin, 3_000L);
        for (String line : engine.log()) {
            check(!line.toLowerCase().contains("13572468"));
        }
        engine.onLinkLost();
        eq(Engine.Link.RECONNECTING, engine.link());
        eq(1, engine.reconnects());
    }

    private static void radios() {
        long t = 5_000L;
        Engine a = readyEngine(0xDEADBEEF, "field-a", secret(0x07), t);
        Engine b = readyEngine(0xCAFEBABE, "field-a", secret(0x07), t);
        eq("OK", a.selectChannel(1).status);
        eq("OK", b.selectChannel(1).status);
        eq("QUEUED", a.joinSelected(t).status);
        exchange(a, b, t);
        boolean sawIdent = false;
        for (Engine.Inbound in : b.drainInbound()) {
            if (in.summary.contains("VIPER")) {
                sawIdent = true;
            }
            if (in.cot != null) {
                check(in.cot.contains("unverified=\"true\""));
            }
        }
        check(sawIdent);

        a.setLocationOptIn(true);
        a.setSelfPosition(51.5074, -0.1278, 36.0, 90.0, 1.5, 85);
        eq("QUEUED", a.beacon(t + 1_000).status);
        exchange(a, b, t + 1_000);
        List<Engine.Inbound> plis = b.drainInbound();
        check(!plis.isEmpty());
        check(plis.get(0).cot.contains("type=\"a-f-G-U-C\""));
        check(plis.get(0).cot.contains("unverified=\"true\""));
        check(plis.get(0).unverified);
        holdUnknown(b, t + 2_000);

        eq("QUEUED", a.sendChat(Mctak.SCOPE_DIRECT, bSender(b), "", "psst", t + 400_000).status);
        exchange(a, b, t + 400_000);
        List<Engine.Inbound> chats = b.drainInbound();
        check(chats.get(0).cot.contains("psst"));
        check(chats.get(0).cot.contains("type=\"b-t-f\""));
        eq(1L, b.counters().get("receipt_delivered_queued"));
        eq(0L, b.counters().get("receipt_read_queued"));
        exchange(b, a, t + 400_000);
        List<Engine.Inbound> receipts = a.drainInbound();
        check(!receipts.isEmpty());
        check(receipts.get(0).cot.contains("b-t-f-d"));

        byte[] captured = null;
        eq("QUEUED", a.sendChat(Mctak.SCOPE_ALL, 0, "", "again", t + 401_000).status);
        List<byte[]> tx = a.drainTx(t + 401_000);
        check(tx.size() == 1);
        captured = Companion.txDataToPeerRecv(tx.get(0), 4);
        b.receiveFrame(captured, t + 401_000);
        b.receiveFrame(captured, t + 401_000);
        eq(1L, b.counters().get("dup_datagram"));
        long before = b.counters().get("receipt_suppressed");
        eq("QUEUED", a.sendChat(Mctak.SCOPE_ALL, 0, "", "roomless", t + 402_000).status);
        exchange(a, b, t + 402_000);
        check(b.counters().get("receipt_suppressed") > before);
    }

    private static void holdUnknown(Engine b, long now) {
        byte[] first = pliFrom(0x01020304, 1);
        byte[] second = pliFrom(0x01020304, 2);
        b.receiveFrame(Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV, first), now);
        eq(0, countPli(b.drainInbound()));
        b.receiveFrame(Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV, second), now + 1);
        eq(1, countPli(b.drainInbound()));
    }

    private static int countPli(List<Engine.Inbound> items) {
        int n = 0;
        for (Engine.Inbound item : items) {
            if (item.cot != null && item.cot.contains("a-f-G-U-C")) {
                n++;
            }
        }
        return n;
    }

    private static byte[] pliFrom(int sender, int seq) {
        Mctak.Header header = new Mctak.Header(0, false, Mctak.KIND_PLI, sender, seq);
        Mctak.Pli pli = new Mctak.Pli(1_800_000_000L, 515074000, -1278000, 1036, 45, 3, 85, 0x90);
        return Mctak.frame(header, pli.body());
    }

    private static int bSender(Engine b) {
        for (String line : b.log()) {
            int at = line.indexOf("ready sender=");
            if (at >= 0) {
                return (int) Long.parseLong(line.substring(at + "ready sender=".length(), at + "ready sender=".length() + 8), 16);
            }
        }
        throw new AssertionError("no sender");
    }

    private static void exchange(Engine from, Engine to, long now) {
        for (byte[] frame : from.drainTx(now)) {
            byte[] rx = Companion.txDataToPeerRecv(frame, 8);
            if (rx != null) {
                to.receiveFrame(rx, now);
            }
        }
    }

    private static Engine readyEngine(int senderPrefix, String privateName, byte[] privateSecret, long now) {
        Engine engine = new Engine();
        engine.setCallsign("VIPER-1");
        engine.onLinkUp(now);
        byte[] key = new byte[32];
        Bytes.putRaw4(key, 0, senderPrefix);
        for (int i = 4; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        engine.receiveFrame(Companion.selfInfo(key, 910_525, 62_500, 8, 5, "node"), now);
        engine.receiveFrame(Companion.channelInfo(0, "Public", Companion.PUBLIC_CHANNEL_SECRET), now);
        engine.receiveFrame(Companion.channelInfo(1, privateName, privateSecret), now);
        engine.receiveFrame(Companion.channelInfo(2, "#topic", secret(0x55)), now);
        eq(Engine.Link.READY, engine.link());
        eq("REFUSED_PUBLIC", engine.selectChannel(2).status);
        return engine;
    }

    private static byte[] secret(int fill) {
        byte[] secret = new byte[16];
        Arrays.fill(secret, (byte) fill);
        return secret;
    }

    private static void pliResync() {
        Engine b = readyEngine(0xCAFEBABE, "field-a", secret(0x07), 1_000L);
        eq("OK", b.selectChannel(1).status);
        int sender = 0x0A0B0C0D;
        feed(b, sender, 100, 1_000L);
        feed(b, sender, 101, 1_001L);
        eq(1, countPli(b.drainInbound()));
        feed(b, sender, 10, 1_002L);
        feed(b, sender, 11, 1_003L);
        eq(0, countPli(b.drainInbound()));
        check(b.counters().get("pli_window_drop") >= 2);
        feed(b, sender, 12, 1_004L);
        eq(1, countPli(b.drainInbound()));
        check(b.counters().get("pli_resync") >= 1);
        feed(b, sender, 10, 1_004L + 3_600_001L);
        eq(1, countPli(b.drainInbound()));
        feed(b, 0xCAFEBABE, 50, 2_000L);
        check(b.counters().get("self_echo") >= 1);
    }

    private static void feed(Engine engine, int sender, int seq, long now) {
        engine.receiveFrame(Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV, pliFrom(sender, seq)), now);
    }

    private static void fixtures() {
        String env = System.getenv("MESHCORE_TAK_FIXTURES");
        if (env == null || env.isBlank()) {
            throw new Outcome.Skip("MESHCORE_TAK_FIXTURES unset; byte compare was not executed");
        }
        Path root = Path.of(env);
        if (!Files.isDirectory(root)) {
            throw new AssertionError("MESHCORE_TAK_FIXTURES is not a directory: " + root);
        }
        try {
            byte[] pli = Files.readAllBytes(root.resolve("datagrams/pli_basic.hex"));
            eq("544b0001deadbeef020100d2496bd067b31ed07fecff0c042d035590",
                    Bytes.hex(Bytes.parseHex(new String(pli, StandardCharsets.US_ASCII))));
            Path symbols = root.resolve("symbols_K28/datagrams.hex");
            if (!Files.isRegularFile(symbols)) {
                throw new AssertionError("symbols_K28/datagrams.hex missing");
            }
            Counters counters = new Counters();
            List<byte[]> got = new ArrayList<>();
            Reassembly reassembly = new Reassembly(counters, (sender, content, object, crc) -> got.add(object));
            int offered = 0;
            for (String line : Files.readAllLines(symbols)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                Mctak.Result parsed = Mctak.parse(Bytes.parseHex(trimmed));
                check(parsed.ok());
                reassembly.offer(parsed.header, parsed.frag, 0);
                offered++;
            }
            check(offered > 0);
            check(!got.isEmpty());
            byte[] expect = Bytes.parseHex(Files.readString(root.resolve("symbols_K28/object.hex")));
            check(Arrays.equals(expect, got.get(0)));
            System.out.println("compared fixture symbols=" + offered);
        } catch (Outcome.Skip skip) {
            throw skip;
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static void test(String name, Runnable body) {
        try {
            body.run();
            passed++;
            System.out.println("ok " + name);
        } catch (Outcome.Skip skip) {
            skipped++;
            System.out.println("skipped " + name);
            if (skip.getMessage() != null) {
                System.out.println(skip.getMessage());
            }
        } catch (Throwable ex) {
            failed++;
            System.out.println("FAIL " + name);
            ex.printStackTrace(System.out);
        }
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

    private static void near(double expect, double actual) {
        if (Math.abs(expect - actual) > 1e-9) {
            throw new AssertionError("expected " + expect + " but was " + actual);
        }
    }
}
