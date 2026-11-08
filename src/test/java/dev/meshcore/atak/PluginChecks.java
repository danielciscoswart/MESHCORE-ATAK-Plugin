package dev.meshcore.atak;

import dev.meshcore.atak.companion.Companion;
import dev.meshcore.atak.cot.Cot;
import dev.meshcore.atak.engine.Engine;
import dev.meshcore.atak.mctak.Mctak;
import dev.meshcore.atak.plugin.PluginHost;
import dev.meshcore.atak.util.Bytes;

/** Lifecycle, contacts, chat, and markers without an ATAK process. No radio transmit. */
final class PluginChecks {
    private PluginChecks() {}

    static void lifecycle() {
        PluginHost host = new PluginHost();
        eq(PluginHost.Life.NEW, host.life());
        host.onStart();
        eq(PluginHost.Life.NEW, host.life());
        host.onCreate();
        host.onStart();
        eq(PluginHost.Life.STARTED, host.life());

        Engine engine = host.engine();
        long now = 20_000L;
        engine.onLinkUp(now);
        byte[] key = new byte[32];
        Bytes.putRaw4(key, 0, 0xA1B2C3D4);
        engine.receiveFrame(Companion.selfInfo(key, 910_525, 62_500, 8, 5, "node"), now);
        engine.setNowSeconds(1_800_000_000L);
        engine.receiveFrame(Companion.channelInfo(0, "Public", Companion.PUBLIC_CHANNEL_SECRET), now);
        byte[] secret = new byte[16];
        java.util.Arrays.fill(secret, (byte) 7);
        engine.receiveFrame(Companion.channelInfo(1, "field-a", secret), now);
        eq("OK", engine.selectFirstTeamChannel().status);

        byte[] first = datagram(0x01020304, 1);
        byte[] second = datagram(0x01020304, 2);
        engine.receiveFrame(Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV, first), now + 1);
        engine.receiveFrame(Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV, second), now + 2);
        host.pump(now + 2, 1_800_000_000L);
        PluginHost.Contact contact = host.contact(Mctak.derivedUid(0x01020304));
        check(contact != null);
        check(contact.unverified);
        check(!contact.stale);
        near(51.5074, contact.lat);
        host.pump(now + 3, 1_800_000_000L + 601L);
        check(host.contact(Mctak.derivedUid(0x01020304)).stale);

        String inbound = Cot.pli(Mctak.derivedUid(1), 
                new Mctak.Pli(1_800_000_000L, 1, 1, 0, 0, 0, 1, 0x90), "X", 1_800_000_000L);
        eq("DROP_LOOP", host.offerOutboundCot(inbound, now + 10));
        eq("USE_OPT_IN_BEACON", host.offerOutboundCot(inbound.replace("__meshcore", "other"), now + 10));

        String chat = "<event type=\"b-t-f\"><detail><__chat chatroom=\"All Chat Rooms\"/>"
                + "<remarks>hello</remarks></detail></event>";
        eq("QUEUED", host.offerOutboundCot(chat, now + 20));
        String direct = "<event type=\"b-t-f\"><detail><__chat chatroom=\"MCTAK-01020304\"/>"
                + "<remarks>psst</remarks></detail></event>";
        eq("QUEUED", host.offerOutboundCot(direct, now + 30));
        String huge = "<event type=\"b-t-f\"><detail><__chat chatroom=\"All Chat Rooms\"/>"
                + "<remarks>" + "x".repeat(200) + "</remarks></detail></event>";
        eq("BLOCKED_RADIO_LIMIT", host.offerOutboundCot(huge, now + 40));

        String marker = "<event type=\"b-m-p-w\"><point lat=\"1.5\" lon=\"2.5\"/>"
                + "<detail><contact callsign=\"cache\"/></detail></event>";
        eq("NOT_TRANSMITTED", host.offerOutboundCot(marker, now + 50));
        check(!host.markers().isEmpty());
        check(!host.markers().get(0).transmitted);
        eq("cache", host.markers().get(0).name);

        Mctak.Header header = new Mctak.Header(0, false, Mctak.KIND_GEOCHAT, 0x01020304, 3);
        Mctak.GeoChat inboundChat = new Mctak.GeoChat(1_800_000_000L, 7, Mctak.SCOPE_ALL, 0, "", "hello");
        engine.receiveFrame(Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV,
                Mctak.frame(header, inboundChat.body())), now + 55);
        host.pump(now + 60, 1_800_000_000L);
        check(!host.chats().isEmpty());
        check(host.chats().get(0).unverified);
        check(host.chats().get(0).text.contains("hello"));

        host.onStop();
        eq("STOPPED", host.offerOutboundCot(chat, now + 70));
        int writes = host.session().queuedWrites();
        host.pump(now + 80, 1_800_000_000L);
        eq(writes, host.session().queuedWrites());
        host.onDestroy();
        host.onStart();
        eq(PluginHost.Life.DESTROYED, host.life());
    }

    private static byte[] datagram(int sender, int seq) {
        Mctak.Header header = new Mctak.Header(0, false, Mctak.KIND_PLI, sender, seq);
        Mctak.Pli pli = new Mctak.Pli(1_800_000_000L, 515074000, -1278000, 1036, 0, 0, 80, 0x90);
        return Mctak.frame(header, pli.body());
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
        if (Math.abs(expect - actual) > 1e-6) {
            throw new AssertionError("expected " + expect + " but was " + actual);
        }
    }
}
