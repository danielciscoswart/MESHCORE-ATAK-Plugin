package dev.meshcore.atak;

import dev.meshcore.atak.companion.Companion;
import dev.meshcore.atak.cot.Cot;
import dev.meshcore.atak.engine.Engine;
import dev.meshcore.atak.engine.SendReasons;
import dev.meshcore.atak.mctak.Mctak;
import dev.meshcore.atak.plugin.PluginHost;
import dev.meshcore.atak.util.Bytes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** v7.3 checks, G1 to G5 and the radio push codes. No radio, phone or ATAK is used. */
final class V73Checks {
    private V73Checks() {}

    private static final byte[] KEY_A = filled(0x5A);
    private static final byte[] KEY_B = filled(0x37);
    private static final long T0 = 1_000L;

    private static byte[] filled(int v) {
        byte[] b = new byte[16];
        Arrays.fill(b, (byte) v);
        return b;
    }

    /** A started host on a READY radio: Public (0), private "field-a" (1), "#topic" (2), private "ops" (3). */
    private static PluginHost host(String nodeName) {
        PluginHost host = new PluginHost();
        host.onCreate();
        host.onStart();
        Engine engine = host.engine();
        engine.onLinkUp(T0);
        engine.setNowSeconds(1_800_000_000L);
        byte[] key = new byte[32];
        Bytes.putRaw4(key, 0, 0x11111111);
        engine.receiveFrame(Companion.selfInfo(key, 910_525, 62_500, 8, 5, nodeName), T0);
        engine.receiveFrame(Companion.channelInfo(0, "Public", Companion.PUBLIC_CHANNEL_SECRET), T0);
        engine.receiveFrame(Companion.channelInfo(1, "field-a", KEY_A), T0);
        engine.receiveFrame(Companion.channelInfo(2, "#topic", KEY_A), T0);
        engine.receiveFrame(Companion.channelInfo(3, "ops", KEY_B), T0);
        return host;
    }

    private static PluginHost hostOnFieldA(String nodeName) {
        PluginHost host = host(nodeName);
        eq("OK", host.engine().selectChannel(1).status);
        return host;
    }

    /** What ATAK's comms logger would hand over for a chat typed into some room. */
    private static String chatXml(String eventUid, String text) {
        return "<event version=\"2.0\" uid=\"" + eventUid + "\" type=\"b-t-f\"><detail>"
                + "<__chat id=\"x\" chatroom=\"x\" senderCallsign=\"ME\"/><remarks>" + text
                + "</remarks></detail></event>";
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

    private static byte[] mctakChat(int sender, int seq, int msgId, int scope, int dest, String text) {
        Mctak.Header header = new Mctak.Header(0, false, Mctak.KIND_GEOCHAT, sender, seq);
        Mctak.GeoChat chat = new Mctak.GeoChat(1_800_000_000L, msgId, scope, dest, "", text);
        return Mctak.frame(header, chat.body());
    }

    private static byte[] mctakIdent(int sender, int seq, String callsign) {
        Mctak.Header header = new Mctak.Header(0, false, Mctak.KIND_IDENT, sender, seq);
        Mctak.Ident ident = new Mctak.Ident(1_800_000_000L, 0, 1, 0x90, callsign, "");
        return Mctak.frame(header, ident.body());
    }

    // ---- G1 -----------------------------------------------------------------------------

    static void g1RoomFollowsChannelAndMode() {
        PluginHost host = host("node");
        Engine engine = host.engine();
        // No channel chosen: no room.
        eq(null, engine.roomSpec());
        // Public and hashtag channels are refused, so they never get a room.
        eq("REFUSED_PUBLIC", engine.selectChannel(0).status);
        eq("REFUSED_PUBLIC", engine.selectChannel(2).status);
        eq(null, engine.roomSpec());
        eq("OK", engine.selectChannel(1).status);
        Engine.RoomSpec spec = engine.roomSpec();
        check(spec != null);
        eq("MeshCore: field-a (plugin only)", spec.name);
        check(spec.uid.startsWith(PluginHost.ROOM_UID_PREFIX));
        check(spec.pluginOnly);
        // The uid carries no key and no fingerprint.
        String fp = engine.selectedChannelRow().fingerprint;
        check(!spec.uid.toLowerCase().contains(fp.toLowerCase()));
        check(!spec.uid.contains("5a5a5a"));
        check(spec.uid.endsWith("-P"));
        // H2: a mode switch replaces the room (the mode is part of the uid) and renames it.
        engine.setPluginOnlyChat(false);
        Engine.RoomSpec text = engine.roomSpec();
        check(!spec.uid.equals(text.uid));
        check(text.uid.endsWith("-T"));
        eq(spec.channelKey, text.channelKey);
        eq("MeshCore: field-a (MeshCore text)", text.name);
        engine.setPluginOnlyChat(true);
        eq("MeshCore: field-a (plugin only)", engine.roomSpec().name);
        eq(spec.uid, engine.roomSpec().uid);
        // Another channel replaces the room (new uid) and the switch is back ON.
        engine.setPluginOnlyChat(false);
        eq("OK", engine.selectChannel(3).status);
        Engine.RoomSpec other = engine.roomSpec();
        check(!other.uid.equals(spec.uid));
        eq("MeshCore: ops (plugin only)", other.name);
        // A lost link removes the room, and so does a new link.
        engine.onLinkLost();
        eq(null, engine.roomSpec());
        engine.onLinkUp(2_000L);
        eq(null, engine.roomSpec());
    }

    static void g1RoomNameIsCleanAndCapped() {
        PluginHost host = host("node");
        Engine engine = host.engine();
        engine.receiveFrame(Companion.channelInfo(5, "A\nB" + "x".repeat(80), KEY_B), 1_100L);
        eq("OK", engine.selectChannel(5).status);
        String name = engine.roomSpec().name;
        check(name.startsWith("MeshCore: A B"));
        check(name.endsWith(" (plugin only)"));
        eq("MeshCore: ".length() + 32 + " (plugin only)".length(), name.length());
        check(name.indexOf('\n') < 0);
    }

    // ---- G2 -----------------------------------------------------------------------------

    static void g2OnlyTheRoomTransmits() {
        PluginHost host = hostOnFieldA("node");
        Engine engine = host.engine();
        String room = engine.roomSpec().uid;
        long now = 5_000L;
        // Every other destination is ignored, counted, and nothing is queued.
        eq("IGNORED", host.offerOutboundChat(chatXml("GeoChat.1", "hello all"), new String[] {"All Chat Rooms"}, now));
        eq("IGNORED", host.offerOutboundChat(chatXml("GeoChat.2", "to a person"), new String[] {"ANDROID-abc123"}, now));
        eq("IGNORED", host.offerOutboundChat(chatXml("GeoChat.3", "to a group"), new String[] {"UserGroups"}, now));
        eq("IGNORED", host.offerOutboundChat(chatXml("GeoChat.4", "no list"), null, now));
        eq("IGNORED", host.offerOutboundChat(chatXml("GeoChat.5", "empty list"), new String[0], now));
        eq("IGNORED", host.offerOutboundChat(chatXml("GeoChat.6", "two"), new String[] {room, "ANDROID-abc123"}, now));
        eq("IGNORED", host.offerOutboundChat(chatXml("GeoChat.7", "room first"), new String[] {"ANDROID-abc123", room}, now));
        eq("IGNORED", host.offerOutboundChat(chatXml("GeoChat.8", "not a room"), new String[] {"MESHCORE-ROOM"}, now));
        eq(0, engine.queued());
        eq(8L, engine.counters().get("chat_other_ignored"));
        eq("", host.lastWhy());
        // The old copy-everything entry point cannot send a chat either.
        eq("IGNORED", host.offerOutboundCot(chatXml("GeoChat.9", "old path"), now));
        eq(0, engine.queued());
        // Not a chat at all.
        eq("IGNORED", host.offerOutboundChat("<event type=\"a-f-G-U-C\" uid=\"u\"/>", new String[] {room}, now));
        eq(0, engine.queued());
        // The room itself sends, as MCTAK channel data (cmd 62) while the switch is ON.
        eq("QUEUED", host.offerOutboundChat(chatXml("GeoChat.10", "from the room"), new String[] {room}, now));
        List<byte[]> tx = drainAll(engine, now);
        eq(1, tx.size());
        eq(Companion.CMD_SEND_CHANNEL_DATA, tx.get(0)[0] & 0xFF);
        eq(1, tx.get(0)[1] & 0xFF);
        // With the switch OFF the same room sends native channel text (cmd 3).
        engine.setPluginOnlyChat(false);
        // H2: the room for the other mode has another uid; the window for the old one is refused.
        eq("REFUSED", host.offerOutboundChat(chatXml("GeoChat.10b", "old window"), new String[] {room}, now + 600_000L));
        eq(0, engine.queued());
        room = engine.roomSpec().uid;
        eq("QUEUED", host.offerOutboundChat(chatXml("GeoChat.11", "native from the room"), new String[] {room}, now + 600_000L));
        tx = drainAll(engine, now + 600_000L);
        eq(1, tx.size());
        eq(3, tx.get(0)[0] & 0xFF);
        eq(1, tx.get(0)[2] & 0xFF);
    }

    static void g2StaleOrMissingRoomNeverSends() {
        PluginHost host = hostOnFieldA("node");
        Engine engine = host.engine();
        String oldRoom = engine.roomSpec().uid;
        eq("OK", engine.selectChannel(3).status);
        // A message typed into the room of the previous channel is not sent to the new channel.
        // H1: refused with a visible sentence, never silent and never sent.
        eq("REFUSED", host.offerOutboundChat(chatXml("GeoChat.a", "late"), new String[] {oldRoom}, 5_000L));
        eq(Engine.ROOM_CLOSED_WHY, host.lastWhy());
        eq("Not sent: this MeshCore chat is closed. Use OPEN CHAT for the current one.", host.lastWhy());
        eq(0, engine.queued());
        eq(1L, engine.counters().get("room_send_stale"));
        // After a lost link there is no room: a send is refused with the E10 sentence.
        String room = engine.roomSpec().uid;
        engine.onLinkLost();
        eq("REFUSED", host.offerOutboundChat(chatXml("GeoChat.b", "gone"), new String[] {room}, 6_000L));
        eq(SendReasons.NOT_READY, host.lastWhy());
        eq(0, engine.queued());
        // A stopped plugin sends nothing.
        host.onStop();
        eq("STOPPED", host.offerOutboundChat(chatXml("GeoChat.c", "stopped"), new String[] {room}, 7_000L));
        eq(0, engine.queued());
    }

    static void g2ByteCapsStillApply() {
        PluginHost host = hostOnFieldA("node");
        Engine engine = host.engine();
        String room = engine.roomSpec().uid;
        long now = 5_000L;
        // Plugin-only: the 164-byte datagram cap.
        int pluginMax = Engine.maxChatTextBytes(Mctak.SCOPE_ALL, 0, "");
        eq("QUEUED", host.offerOutboundChat(chatXml("c1", "a".repeat(pluginMax)), new String[] {room}, now));
        eq("BLOCKED_RADIO_LIMIT", host.offerOutboundChat(chatXml("c2", "a".repeat(pluginMax + 1)), new String[] {room}, now));
        check(host.lastWhy().startsWith("Not sent: text too long"));
        drainAll(engine, now);
        // Native: 159 - (name length + 2), and the 164-byte payload cap.
        engine.setPluginOnlyChat(false);
        room = engine.roomSpec().uid;
        int nativeMax = Companion.maxNativeTextBytes("node".length());
        eq(153, nativeMax);
        now += 600_000L;
        eq("QUEUED", host.offerOutboundChat(chatXml("c3", "b".repeat(nativeMax)), new String[] {room}, now));
        eq("BLOCKED_RADIO_LIMIT", host.offerOutboundChat(chatXml("c4", "b".repeat(nativeMax + 1)), new String[] {room}, now));
        eq(SendReasons.tooLong(nativeMax + 1, nativeMax), host.lastWhy());
        // A longer node name shortens the cap by exactly the extra bytes.
        PluginHost longName = hostOnFieldA("a-long-node-name");
        longName.engine().setPluginOnlyChat(false);
        String longRoom = longName.engine().roomSpec().uid;
        int max = Companion.maxNativeTextBytes("a-long-node-name".length());
        eq(159 - ("a-long-node-name".length() + 2), max);
        eq("QUEUED", longName.offerOutboundChat(chatXml("c5", "c".repeat(max)), new String[] {longRoom}, 5_000L));
        eq("BLOCKED_RADIO_LIMIT", longName.offerOutboundChat(chatXml("c6", "c".repeat(max + 1)), new String[] {longRoom}, 5_000L));
        // The opt-in is still needed: no room means no send, and nothing chooses a channel.
        PluginHost none = host("node");
        eq(null, none.engine().roomSpec());
    }

    static void g2PublicChannelNeverGetsARoom() {
        PluginHost host = host("node");
        Engine engine = host.engine();
        eq("REFUSED_PUBLIC", engine.selectChannel(0).status);
        eq(null, engine.roomSpec());
        eq("REFUSED", host.offerOutboundChat(chatXml("p1", "hi"), new String[] {PluginHost.ROOM_UID_PREFIX + "0-1"}, 5_000L));
        eq(0, engine.queued());
    }

    // ---- G3 -----------------------------------------------------------------------------

    static void g3InboundMctakChatGoesToTheRoom() {
        PluginHost host = hostOnFieldA("node");
        Engine engine = host.engine();
        // A callsign first, then chats of all three MCTAK scopes from the chosen channel.
        engine.receiveFrame(Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV, mctakIdent(0x01020304, 1, "VIPER-1")), 2_000L);
        engine.receiveFrame(Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV,
                mctakChat(0x01020304, 2, 0x1001, Mctak.SCOPE_ALL, 0, "to everyone")), 2_100L);
        engine.receiveFrame(Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV,
                mctakChat(0x01020304, 3, 0x1002, Mctak.SCOPE_DIRECT, 0x0A0B0C0D, "to someone else")), 2_200L);
        List<Engine.RoomMessage> messages = engine.drainRoomMessages();
        eq(2, messages.size());
        eq("to everyone", messages.get(0).text);
        eq("VIPER-1 (MeshCore, unverified)", messages.get(0).senderLabel());
        eq(Engine.RoomMessage.SOURCE_PLUGIN, messages.get(0).source);
        // Recommendation (a): a message for one person does not look like one for the channel.
        eq("[direct to MCTAK-0a0b0c0d] to someone else", messages.get(1).text);
        // No general GeoChat event is produced any more.
        for (Engine.Inbound inbound : engine.drainInbound()) {
            check(inbound.cot == null || !inbound.cot.contains("b-t-f"));
        }
        // Chat from another channel (slot 3) is not for this room.
        engine.receiveFrame(Companion.channelDataRecv(4, 3, 1, Mctak.DATA_TYPE_DEV,
                mctakChat(0x01020304, 4, 0x1003, Mctak.SCOPE_ALL, 0, "other channel")), 2_300L);
        eq(0, engine.drainRoomMessages().size());
        check(engine.counters().get("other_channel") >= 1);
        // The host keeps them as unverified chat lines and hands them out once.
        engine.receiveFrame(Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV,
                mctakChat(0x01020304, 5, 0x1004, Mctak.SCOPE_ALL, 0, "via host")), 2_400L);
        host.pump(2_500L, 1_800_000_000L);
        eq(1, host.takeRoomMessages().size());
        eq(0, host.takeRoomMessages().size());
        check(host.chats().get(host.chats().size() - 1).unverified);
    }

    static void g3NativeTextOnTheChosenChannelGoesToTheRoom() {
        PluginHost host = hostOnFieldA("node");
        Engine engine = host.engine();
        engine.receiveFrame(Companion.channelTextV3(0, 1, 0, 1_700_000_001L, "Ana: meet at the gate"), 2_000L);
        // Another channel, a direct message, and a text with no node-name prefix.
        engine.receiveFrame(Companion.channelTextV3(0, 3, 0, 1_700_000_002L, "Bo: other channel"), 2_100L);
        engine.receiveFrame(Companion.channelTextV3(0, 1, 0, 1_700_000_003L, "no prefix at all"), 2_200L);
        List<Engine.RoomMessage> messages = engine.drainRoomMessages();
        eq(2, messages.size());
        eq("Ana", messages.get(0).sender);
        eq("meet at the gate", messages.get(0).text);
        eq("Ana (MeshCore, unverified)", messages.get(0).senderLabel());
        eq(Engine.RoomMessage.SOURCE_NATIVE, messages.get(0).source);
        eq("unknown (MeshCore, unverified)", messages.get(1).senderLabel());
        eq("no prefix at all", messages.get(1).text);
        // All three are in the pane inbox; only the chosen channel went to the room.
        eq(3, engine.nativeInbox().size());
        eq(1L, engine.counters().get("native_other_channel_pane_only"));
        // Control characters are stripped before anything is shown.
        engine.receiveFrame(Companion.channelTextV3(0, 1, 0, 1_700_000_004L, "Cy: a\u0007b\nc"), 2_300L);
        eq("a b c", engine.drainRoomMessages().get(0).text);
        // The sender split follows the firmware's "name: text".
        eq("Ana", Engine.splitNativeSender("Ana: hi: there")[0]);
        eq("hi: there", Engine.splitNativeSender("Ana: hi: there")[1]);
        eq("unknown", Engine.splitNativeSender(": starts with colon")[0]);
        eq("unknown", Engine.splitNativeSender("x".repeat(40) + ": too long a name")[0]);
    }

    static void g3PendingRoomMessagesDoNotSurviveAChannelChange() {
        PluginHost host = hostOnFieldA("node");
        Engine engine = host.engine();
        engine.receiveFrame(Companion.channelTextV3(0, 1, 0, 1_700_000_001L, "Ana: for field-a"), 2_000L);
        eq("OK", engine.selectChannel(3).status);
        eq(0, engine.drainRoomMessages().size());
        engine.receiveFrame(Companion.channelTextV3(0, 3, 0, 1_700_000_002L, "Ana: for ops"), 2_100L);
        engine.onLinkLost();
        eq(0, engine.drainRoomMessages().size());
    }

    static void g3RoomEventShape() {
        String xml = Cot.roomChat("MESHCORE-ROOM-1-abc", "MeshCore: field-a (plugin only)", "ANDROID-self",
                "ME", "Ana (MeshCore, unverified)", "a <b> & \"c\"", "n:1:2:Ana:xx", 1_800_000_000L);
        check(xml.contains("type=\"b-t-f\""));
        check(Cot.hasMeshcoreLoopMarker(xml));
        check(xml.contains("unverified=\"true\""));
        check(xml.contains("uid0=\"MESHCORE-ROOM-1-abc\""));
        check(xml.contains("uid1=\"ANDROID-self\""));
        check(xml.contains("id=\"ANDROID-self\""));
        check(xml.contains("relation=\"p-p\""));
        // The label is part of the text, and the text is XML-escaped.
        check(xml.contains(">Ana (MeshCore, unverified): a &lt;b&gt; &amp; &quot;c&quot;</remarks>"));
        // The room's own copy is recognised as a loop if ATAK ever offers it back.
        PluginHost host = hostOnFieldA("node");
        eq("DROP_LOOP", host.offerOutboundChat(xml, new String[] {host.engine().roomSpec().uid}, 5_000L));
        eq(0, host.engine().queued());
        eq(1L, host.engine().counters().get("cot_loop_dropped"));
    }

    // ---- G4 -----------------------------------------------------------------------------

    static void g4SentTwiceIsSentOnce() {
        PluginHost host = hostOnFieldA("node");
        Engine engine = host.engine();
        String room = engine.roomSpec().uid;
        long now = 5_000L;
        String xml = chatXml("GeoChat.same", "once only");
        eq("QUEUED", host.offerOutboundChat(xml, new String[] {room}, now));
        eq("DUPLICATE", host.offerOutboundChat(xml, new String[] {room}, now + 50));
        eq("DUPLICATE", host.offerOutboundChat(xml, new String[] {room}, now + 60_000L));
        eq("", host.lastWhy());
        eq(1, drainAll(engine, now).size());
        eq(2L, engine.counters().get("room_dup_out"));
        // A different message with the same text is a different send.
        eq("QUEUED", host.offerOutboundChat(chatXml("GeoChat.other", "once only"), new String[] {room}, now + 70_000L));
        // With no id at all, the same text twice inside three seconds is one send.
        String noId = "<event type=\"b-t-f\"><detail><remarks>no id</remarks></detail></event>";
        eq("QUEUED", host.offerOutboundChat(noId, new String[] {room}, now + 200_000L));
        eq("DUPLICATE", host.offerOutboundChat(noId, new String[] {room}, now + 201_000L));
        eq("QUEUED", host.offerOutboundChat(noId, new String[] {room}, now + 210_000L));
    }

    static void g4MarkedEventIsCaughtOnTheWayOut() {
        PluginHost host = hostOnFieldA("node");
        Engine engine = host.engine();
        String room = engine.roomSpec().uid;
        String marked = "<event version=\"2.0\" uid=\"MESHCORE-ROOM-MSG-1\" type=\"b-t-f\"><detail>"
                + "<remarks>from radio</remarks><__meshcore unverified=\"true\"/></detail></event>";
        eq("DROP_LOOP", host.offerOutboundChat(marked, new String[] {room}, 5_000L));
        eq("DROP_LOOP", host.offerOutboundChat(marked, new String[] {"All Chat Rooms"}, 5_000L));
        eq(0, engine.queued());
        eq(2L, engine.counters().get("cot_loop_dropped"));
        // The same text without the marker is a normal send.
        eq("QUEUED", host.offerOutboundChat(marked.replace("__meshcore", "other"), new String[] {room}, 5_100L));
    }

    static void g4SameMessageOverNetworkAndRadioShowsOnce() {
        PluginHost host = hostOnFieldA("node");
        Engine engine = host.engine();
        // Ana's message reached ATAK over the network first (seen by the comms logger).
        engine.noteNetworkChat("Ana", "meet at the gate", 2_000L);
        engine.receiveFrame(Companion.channelTextV3(0, 1, 0, 1_700_000_001L, "ana: meet at the gate"), 2_500L);
        eq(0, engine.drainRoomMessages().size());
        eq(1L, engine.counters().get("room_dup_network"));
        // The same text from someone else is not a duplicate.
        engine.receiveFrame(Companion.channelTextV3(0, 1, 0, 1_700_000_002L, "Bo: meet at the gate"), 2_600L);
        eq(1, engine.drainRoomMessages().size());
        // An MCTAK copy from the same person (callsign known from IDENT) is a duplicate too.
        engine.receiveFrame(Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV, mctakIdent(0x0A0B0C0D, 1, "Cy")), 3_000L);
        engine.noteNetworkChat("Cy", "all clear", 3_100L);
        engine.receiveFrame(Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV,
                mctakChat(0x0A0B0C0D, 2, 0x1001, Mctak.SCOPE_ALL, 0, "all clear")), 3_200L);
        eq(0, engine.drainRoomMessages().size());
        eq(2L, engine.counters().get("room_dup_network"));
        // After the five-minute window the same text is shown again.
        engine.receiveFrame(Companion.channelTextV3(0, 1, 0, 1_700_000_900L, "Ana: meet at the gate"), 2_500L + 301_000L);
        eq(1, engine.drainRoomMessages().size());
        // Nothing but a hash is kept: an empty callsign or text is not recorded at all.
        engine.noteNetworkChat("", "x", 5_000L);
        engine.noteNetworkChat("Ana", null, 5_000L);
    }

    static void g4SameRadioMessageTwiceShowsOnce() {
        PluginHost host = hostOnFieldA("node");
        Engine engine = host.engine();
        byte[] frame = Companion.channelTextV3(0, 1, 0, 1_700_000_001L, "Ana: hello");
        engine.receiveFrame(frame.clone(), 2_000L);
        engine.receiveFrame(frame.clone(), 2_100L);
        eq(1, engine.drainRoomMessages().size());
        eq(1L, engine.counters().get("room_dup_in"));
        // The same plugin chat twice (same sender and message id) shows once.
        byte[] chat = mctakChat(0x01020304, 9, 0x1010, Mctak.SCOPE_ALL, 0, "twice");
        engine.receiveFrame(Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV, chat), 3_000L);
        engine.receiveFrame(Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV, chat), 3_100L);
        eq(1, engine.drainRoomMessages().size());
    }

    // ---- G5 -----------------------------------------------------------------------------

    static void g5ResultsAreVisibleAndNeverDelivered() {
        PluginHost host = hostOnFieldA("node");
        Engine engine = host.engine();
        String room = engine.roomSpec().uid;
        long now = 5_000L;
        // Queued, then the radio's answers.
        eq("QUEUED", host.offerOutboundChat(chatXml("r1", "hi"), new String[] {room}, now));
        eq(1, drainAll(engine, now).size());
        int before = engine.chatResultSeq();
        engine.onChannelDataReply(0);
        eq(SendReasons.SENT_TO_RADIO, engine.lastChatResult());
        check(engine.chatResultSeq() == before + 1);
        eq("QUEUED", host.offerOutboundChat(chatXml("r2", "hi again"), new String[] {room}, now + 60_000L));
        drainAll(engine, now + 60_000L);
        engine.onChannelDataReply(-1);
        eq(SendReasons.NO_ANSWER, engine.lastChatResult());
        eq("QUEUED", host.offerOutboundChat(chatXml("r3", "and again"), new String[] {room}, now + 120_000L));
        drainAll(engine, now + 120_000L);
        engine.onChannelDataReply(3);
        eq("Failed: radio queue is full", engine.lastChatResult());
        // Every refusal has a sentence for the toast and the status line; none says delivered.
        List<String> sentences = new ArrayList<>();
        sentences.add(engine.lastChatResult());
        eq("BLOCKED_RADIO_LIMIT", host.offerOutboundChat(chatXml("r4", "x".repeat(300)), new String[] {room}, now + 180_000L));
        sentences.add(host.lastWhy());
        engine.onLinkLost();
        eq("REFUSED", host.offerOutboundChat(chatXml("r5", "hi"), new String[] {room}, now + 190_000L));
        sentences.add(host.lastWhy());
        sentences.add(SendReasons.SENT_TO_RADIO);
        sentences.add(SendReasons.NO_ANSWER);
        for (String sentence : sentences) {
            check(!sentence.isEmpty());
            check(!sentence.toLowerCase().contains("deliver"));
        }
    }

    // ---- radio pushes ---------------------------------------------------------------------

    static void pushesAreCountedAndNeverLogged() {
        PluginHost host = hostOnFieldA("node");
        Engine engine = host.engine();
        int logBefore = engine.log().size();
        byte[] raw = new byte[40];
        raw[0] = (byte) 0x88;
        raw[1] = 0x12;
        raw[2] = (byte) 0xC8;
        for (int i = 3; i < raw.length; i++) {
            raw[i] = (byte) (0xA0 + i);
        }
        for (int i = 0; i < 21; i++) {
            engine.receiveFrame(raw.clone(), 2_000L + i);
            engine.receiveFrame(new byte[] {(byte) 0x88}, 2_000L + i);
        }
        byte[] advert = new byte[33];
        advert[0] = (byte) 0x80;
        engine.receiveFrame(advert, 3_000L);
        byte[] newAdvert = new byte[60];
        newAdvert[0] = (byte) 0x8A;
        engine.receiveFrame(newAdvert, 3_100L);
        eq(42L, engine.counters().get("push_log_rx_data"));
        eq(1L, engine.counters().get("push_advert"));
        eq(1L, engine.counters().get("push_new_advert"));
        eq(0L, engine.counters().get("unhandled_88"));
        eq(0L, engine.counters().get("unhandled_80"));
        eq(0L, engine.counters().get("unhandled_8a"));
        // No log line, so no bytes of radio traffic anywhere.
        eq(logBefore, engine.log().size());
        // Nothing from a push became chat, a contact, or a room message.
        eq(0, engine.drainRoomMessages().size());
        eq(0, engine.drainInbound().size());
        // A code that is not a push or a reply in v1.18.0 is still counted as unhandled.
        engine.receiveFrame(new byte[] {(byte) 0x99, 1, 2}, 4_000L);
        eq(1L, engine.counters().get("unhandled_99"));
        // Every push in the table has a name, and 0x91 and up are not pushes.
        for (int code = 0x80; code <= 0x90; code++) {
            check(Companion.pushName(code) != null);
        }
        eq(null, Companion.pushName(0x91));
        eq(null, Companion.pushName(0x7F));
        eq(null, Companion.pushName(0x11));
    }

    static void logIsBounded() {
        PluginHost host = hostOnFieldA("node");
        Engine engine = host.engine();
        for (int i = 0; i < 2_000; i++) {
            engine.selectChannel(i % 2 == 0 ? 1 : 3);
        }
        check(engine.log().size() <= 500);
        check(!engine.log().isEmpty());
    }

    /** Skipped unless MESHCORE_ROOT points at meshcore-dev/MeshCore at the companion-v1.18.0 tag. */
    static void pushCodesMatchTheOfficialFirmware() {
        String env = System.getenv("MESHCORE_ROOT");
        if (env == null || env.isBlank()) {
            throw new Outcome.Skip("MESHCORE_ROOT unset; push codes were not compared with the firmware");
        }
        try {
            String src = Files.readString(Path.of(env).resolve("examples/companion_radio/MyMesh.cpp"), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("#define PUSH_CODE_([A-Z_]+)\\s+0x([0-9A-Fa-f]+)").matcher(src);
            int seen = 0;
            while (m.find()) {
                int code = Integer.parseInt(m.group(2), 16);
                eq(m.group(1).toLowerCase(), Companion.pushName(code));
                seen++;
            }
            eq(17, seen);
            check(src.contains("out_frame[i++] = PUSH_CODE_LOG_RX_DATA;"));
            check(src.contains("out_frame[0] = PUSH_CODE_ADVERT;"));
            eq(0x88, Companion.PUSH_LOG_RX_DATA);
            eq(0x80, Companion.PUSH_ADVERT);
            eq(0x8A, Companion.PUSH_NEW_ADVERT);
            eq(0x90, Companion.PUSH_CONTACTS_FULL);
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
    }

    // ---- safety invariants carried from v7.2 ----------------------------------------------

    // ---- v7.3H: Claude's H1 to H4 and recommendations (a), (b) ----------------------------

    static void h1OldRoomIsRefusedVisibly() {
        PluginHost host = hostOnFieldA("node");
        Engine engine = host.engine();
        String oldRoom = engine.roomSpec().uid;
        // Channel change: the window opened for field-a is now old.
        eq("OK", engine.selectChannel(3).status);
        Engine.Send direct = engine.sendFromRoom(oldRoom, "id-1", "late", 5_000L);
        eq("REFUSED", direct.status);
        eq("Not sent: this MeshCore chat is closed. Use OPEN CHAT for the current one.", direct.why);
        eq(0, engine.queued());
        // Through the host: same status, same sentence, nothing sent, and a retry is refused again
        // (a refused message is not remembered as "sent once").
        eq("REFUSED", host.offerOutboundChat(chatXml("old-1", "late"), new String[] {oldRoom}, 5_100L));
        eq(Engine.ROOM_CLOSED_WHY, host.lastWhy());
        eq("REFUSED", host.offerOutboundChat(chatXml("old-1", "late"), new String[] {oldRoom}, 5_200L));
        eq(0, engine.queued());
        eq(3L, engine.counters().get("room_send_stale"));
        // A made-up room uid is refused the same way; the current room still sends.
        eq("REFUSED", engine.sendFromRoom(PluginHost.ROOM_UID_PREFIX + "9-deadbeef-P", "id-2", "x", 5_300L).status);
        eq("QUEUED", host.offerOutboundChat(chatXml("new-1", "now"), new String[] {engine.roomSpec().uid}, 5_400L));
        eq(1, engine.queued());
        // A destination that is not a MeshCore room at all is still silent and counted.
        long ignored = engine.counters().get("chat_other_ignored");
        eq("IGNORED", host.offerOutboundChat(chatXml("tak-1", "hi"), new String[] {"ANDROID-abc"}, 5_500L));
        eq(ignored + 1, engine.counters().get("chat_other_ignored"));
    }

    static void h2ModeIsPartOfTheRoomUid() {
        PluginHost host = hostOnFieldA("node");
        Engine engine = host.engine();
        Engine.RoomSpec plugin = engine.roomSpec();
        check(plugin.uid.matches("MESHCORE-ROOM-1-[0-9a-f]+-P"));
        engine.setPluginOnlyChat(false);
        Engine.RoomSpec text = engine.roomSpec();
        check(text.uid.matches("MESHCORE-ROOM-1-[0-9a-f]+-T"));
        check(!plugin.uid.equals(text.uid));
        // Same channel: only the suffix differs. No key or fingerprint in either.
        eq(plugin.uid.substring(0, plugin.uid.length() - 2), text.uid.substring(0, text.uid.length() - 2));
        // The window opened in plugin-only mode is refused after the switch; nothing goes out in the new mode.
        eq("REFUSED", host.offerOutboundChat(chatXml("m-1", "typed in the old window"), new String[] {plugin.uid}, 5_000L));
        eq(Engine.ROOM_CLOSED_WHY, host.lastWhy());
        eq(0, engine.queued());
        // And back: the text window is the old one now.
        engine.setPluginOnlyChat(true);
        eq(plugin.uid, engine.roomSpec().uid);
        eq("REFUSED", host.offerOutboundChat(chatXml("m-2", "typed in the text window"), new String[] {text.uid}, 5_100L));
        eq(0, engine.queued());
        // The toast on a switch names the audience.
        eq("Room now sends MeshCore text: anyone on field-a can read it", Engine.modeNotice(plugin, text));
        eq("Room now sends to ATAK plugin users only on field-a", Engine.modeNotice(text, plugin));
        // No notice when nothing changed, when there was no room, or when the channel changed too.
        eq(null, Engine.modeNotice(plugin, plugin));
        eq(null, Engine.modeNotice(null, plugin));
        eq(null, Engine.modeNotice(plugin, null));
        eq("OK", engine.selectChannel(3).status);
        eq(null, Engine.modeNotice(plugin, engine.roomSpec()));
    }

    static void h3FullHoldRefusesTheNewMessage() {
        dev.meshcore.atak.plugin.BoundedHold<String> hold = new dev.meshcore.atak.plugin.BoundedHold<>(8);
        for (int i = 1; i <= 8; i++) {
            check(hold.offer("m" + i));
        }
        eq(0, hold.takeRefused());
        // The ninth is refused and counted; nothing already held is dropped.
        eq(false, hold.offer("m9"));
        eq(false, hold.offer("m10"));
        eq(2, hold.takeRefused());
        eq(0, hold.takeRefused());
        eq(8, hold.size());
        eq("m1", hold.poll());
        // Room again after one was taken.
        check(hold.offer("m11"));
        for (int i = 2; i <= 8; i++) {
            eq("m" + i, hold.poll());
        }
        eq("m11", hold.poll());
        eq(null, hold.poll());
        // The host logger is the only user: it uses the hold, never removeFirst.
        try {
            String logger = new String(Files.readAllBytes(java.nio.file.Paths.get(
                    "app/src/main/java/dev/meshcore/atak/host/MeshCoreRoomLogger.java")), StandardCharsets.UTF_8);
            check(logger.contains("BoundedHold"));
            check(!logger.contains("pending.removeFirst"));
        } catch (java.io.IOException ex) {
            // Run from another directory: the source check script covers this too.
        }
    }

    static void h4ReceiptsAreNotChat() {
        check(PluginHost.isChatType("b-t-f"));
        eq(false, PluginHost.isChatType("b-t-f-d"));
        eq(false, PluginHost.isChatType("b-t-f-r"));
        eq(false, PluginHost.isChatType("b-t-f-x"));
        eq(false, PluginHost.isChatType("b-t"));
        eq(false, PluginHost.isChatType("a-f-G-U-C"));
        eq(false, PluginHost.isChatType(""));
        eq(false, PluginHost.isChatType(null));
        PluginHost host = hostOnFieldA("node");
        Engine engine = host.engine();
        String room = engine.roomSpec().uid;
        // A delivered or read receipt addressed to the room is ignored: nothing queued, no refusal text.
        String delivered = chatXml("rcpt-d", "x").replace("type=\"b-t-f\"", "type=\"b-t-f-d\"");
        String read = chatXml("rcpt-r", "x").replace("type=\"b-t-f\"", "type=\"b-t-f-r\"");
        check(delivered.contains("b-t-f-d") && read.contains("b-t-f-r"));
        eq("IGNORED", host.offerOutboundChat(delivered, new String[] {room}, 5_000L));
        eq("", host.lastWhy());
        eq("IGNORED", host.offerOutboundChat(read, new String[] {room}, 5_100L));
        eq(0, engine.queued());
        // The host logger uses the same check.
        try {
            String logger = new String(Files.readAllBytes(java.nio.file.Paths.get(
                    "app/src/main/java/dev/meshcore/atak/host/MeshCoreRoomLogger.java")), StandardCharsets.UTF_8);
            check(logger.contains("PluginHost.isChatType("));
            check(!logger.contains("\"b-t-f\".equals"));
        } catch (java.io.IOException ex) {
            // Run from another directory: the source check script covers this too.
        }
    }

    static void haScopePrefixOnInboundPluginChat() {
        PluginHost host = hostOnFieldA("node");
        Engine engine = host.engine();
        engine.receiveFrame(Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV, mctakIdent(0x01020304, 1, "VIPER-1")), 2_000L);
        engine.receiveFrame(Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV,
                mctakChat(0x01020304, 2, 0x2001, Mctak.SCOPE_ALL, 0, "all")), 2_100L);
        engine.receiveFrame(Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV,
                mctakChat(0x01020304, 3, 0x2002, Mctak.SCOPE_DIRECT, 0x11111111, "mine")), 2_200L);
        engine.receiveFrame(Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV,
                mctakChat(0x01020304, 4, 0x2003, Mctak.SCOPE_DIRECT, 0x0A0B0C0D, "theirs")), 2_300L);
        Mctak.Header header = new Mctak.Header(0, false, Mctak.KIND_GEOCHAT, 0x01020304, 5);
        Mctak.GeoChat roomChat = new Mctak.GeoChat(1_800_000_000L, 0x2004, Mctak.SCOPE_ROOM, 0, "Alpha\u0007team", "room text");
        engine.receiveFrame(Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV, Mctak.frame(header, roomChat.body())), 2_400L);
        List<Engine.RoomMessage> m = engine.drainRoomMessages();
        eq(4, m.size());
        eq("all", m.get(0).text);
        eq("[direct to you] mine", m.get(1).text);
        eq("[direct to MCTAK-0a0b0c0d] theirs", m.get(2).text);
        eq("[room Alpha team] room text", m.get(3).text);
        // Every one keeps the unverified label.
        for (Engine.RoomMessage one : m) {
            check(one.senderLabel().endsWith("(MeshCore, unverified)"));
        }
    }

    static void hbDeliveredReceiptIsOffByDefault() {
        PluginHost host = hostOnFieldA("node");
        Engine engine = host.engine();
        eq(false, engine.deliveredReceipts());
        engine.receiveFrame(Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV, mctakIdent(0x01020304, 1, "VIPER-1")), 2_000L);
        engine.receiveFrame(Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV,
                mctakChat(0x01020304, 2, 0x3001, Mctak.SCOPE_DIRECT, 0x11111111, "for me")), 2_100L);
        // The message still reaches the room, but the radio queues nothing in reply.
        eq(1, engine.drainRoomMessages().size());
        eq(0, engine.queued());
        eq(0L, engine.counters().get("receipt_delivered_queued"));
        eq(1L, engine.counters().get("receipt_off"));
        eq(0, drainAll(engine, 3_000L).size());
        // With the flag on (tests only; the plugin never sets it) the old behaviour is back.
        engine.setDeliveredReceipts(true);
        engine.receiveFrame(Companion.channelDataRecv(4, 1, 1, Mctak.DATA_TYPE_DEV,
                mctakChat(0x01020304, 3, 0x3002, Mctak.SCOPE_DIRECT, 0x11111111, "again")), 2_200L);
        eq(1L, engine.counters().get("receipt_delivered_queued"));
        // The flag is not touched by the plugin code.
        try {
            for (String f : new String[] {"app/src/main/java/dev/meshcore/atak/host/MeshCoreAtakPlugin.java",
                    "src/main/java/dev/meshcore/atak/plugin/PluginHost.java"}) {
                String src = new String(Files.readAllBytes(java.nio.file.Paths.get(f)), StandardCharsets.UTF_8);
                check(!src.contains("setDeliveredReceipts"));
            }
        } catch (java.io.IOException ex) {
            // Run from another directory: the source check script covers this too.
        }
    }

    static void v72SafetyStillHolds() {
        PluginHost host = host("node");
        Engine engine = host.engine();
        // Nothing is chosen for the user, and position sending needs a chosen channel.
        eq(false, engine.hasSelectedChannel());
        engine.setLocationOptIn(true);
        eq(false, engine.locationOptIn());
        // The switch defaults to ON and comes back to ON on a channel change.
        eq(true, engine.pluginOnlyChat());
        eq("OK", engine.selectChannel(1).status);
        engine.setPluginOnlyChat(false);
        eq("OK", engine.selectChannel(3).status);
        eq(true, engine.pluginOnlyChat());
        // The pane's own send path is unchanged and still refuses Public.
        eq("REFUSED_PUBLIC", engine.selectChannel(0).status);
        // PLI from ATAK is still never copied out.
        String pli = Cot.pli("MCTAK-1", new Mctak.Pli(1_800_000_000L, 1, 1, 0, 0, 0, 1, 0x90), "X", 1_800_000_000L);
        eq("DROP_LOOP", host.offerOutboundCot(pli, 5_000L));
        eq("USE_OPT_IN_BEACON", host.offerOutboundCot(pli.replace("__meshcore", "other"), 5_000L));
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
