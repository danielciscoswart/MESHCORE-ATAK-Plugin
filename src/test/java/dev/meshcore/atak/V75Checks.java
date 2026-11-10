package dev.meshcore.atak;

import dev.meshcore.atak.companion.Companion;
import dev.meshcore.atak.engine.Engine;
import dev.meshcore.atak.engine.SeenLru;
import dev.meshcore.atak.engine.SendReasons;
import dev.meshcore.atak.plugin.PluginHost;
import dev.meshcore.atak.transport.RadioLimits;
import dev.meshcore.atak.transport.RadioSession;
import dev.meshcore.atak.util.Bytes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * v7.5 checks. The drain tests run a RadioSession against a small model of the firmware's offline
 * queue, written from MyMesh.cpp (companion-v1.18.0 = 81dbae5): each CMD_SYNC_NEXT_MESSAGE (10)
 * pops one queued frame, or answers RESP_CODE_NO_MORE_MESSAGES (10) when the queue is empty
 * (lines 1415-1423), and every queued message also sends a PUSH_CODE_MSG_WAITING (0x83) tickle
 * while a host is connected (lines 469-472, 585-588).
 */
final class V75Checks {
    private V75Checks() {}

    private static final byte[] SECRET = filled(0x5A);

    private static byte[] filled(int v) {
        byte[] b = new byte[16];
        Arrays.fill(b, (byte) v);
        return b;
    }

    /** A firmware offline queue behind a RadioSession, on a fake clock. */
    private static final class FakeRadio {
        final Engine engine = new Engine();
        final RadioSession session = new RadioSession(engine);
        final ArrayDeque<byte[]> queue = new ArrayDeque<>();
        final List<Integer> ops = new ArrayList<>();
        long now = 10_000L;
        int syncSent;
        int maxOutstanding;
        boolean endless;
        boolean answerSyncWithError;
        boolean silentSync;
        /** Called before the radio answers the n-th (1-based) cmd 10; may inject a tickle. */
        java.util.function.IntConsumer beforeSyncReply = n -> { };

        void connect() {
            session.onConnected(now);
            session.onMtu(512, now);
            session.onNotifications(true);
        }

        /** A message reaches the radio: it is queued and the host is tickled. */
        void arrive(byte[] frame) {
            queue.addLast(frame);
            session.onNotification(new byte[] {(byte) Companion.PACKET_MESSAGES_WAITING}, now);
        }

        /** A message queued while no host was connected: no tickle. */
        void arriveQuietly(byte[] frame) {
            queue.addLast(frame);
        }

        void pump() {
            int idle = 0;
            for (int guard = 0; guard < 20_000 && idle < 4; guard++) {
                long delay = session.nextActionDelayMs(now);
                if (delay > 0) {
                    now += delay;
                }
                session.tick(now);
                maxOutstanding = Math.max(maxOutstanding, session.queuedWrites());
                byte[] cmd = session.pollWrite();
                if (cmd == null) {
                    idle++;
                    now += 100L;
                    continue;
                }
                idle = 0;
                now += 40L;
                reply(cmd);
            }
        }

        private void reply(byte[] cmd) {
            int op = cmd[0] & 0xFF;
            ops.add(op);
            byte[] key = new byte[32];
            Bytes.putRaw4(key, 0, 0xCAFEBABE);
            if (op == Companion.CMD_APP_START) {
                session.onNotification(Companion.selfInfo(key, 910_525, 62_500, 8, 5, "node"), now);
            } else if (op == Companion.CMD_DEVICE_QUERY) {
                byte[] device = new byte[82];
                device[0] = Companion.PACKET_DEVICE_INFO;
                device[1] = 3;
                device[3] = 3;
                session.onNotification(device, now);
            } else if (op == Companion.CMD_GET_CHANNEL) {
                int idx = cmd[1] & 0xFF;
                session.onNotification(idx == 0
                        ? Companion.channelInfo(0, "Public", Companion.PUBLIC_CHANNEL_SECRET)
                        : idx == 1 ? Companion.channelInfo(1, "field-a", SECRET)
                        : Companion.channelInfo(idx, "", new byte[16]), now);
            } else if (op == Companion.CMD_GET_STATS) {
                session.onNotification(Companion.statsCore(3900, 1_000L, 0, 0), now);
            } else if (op == Companion.CMD_SYNC_NEXT) {
                syncSent++;
                beforeSyncReply.accept(syncSent);
                if (silentSync) {
                    return;
                }
                if (answerSyncWithError) {
                    session.onNotification(new byte[] {(byte) Companion.PACKET_ERROR, 6}, now);
                } else if (endless) {
                    session.onNotification(msg(syncSent, "Ana: endless " + syncSent), now);
                } else if (queue.isEmpty()) {
                    session.onNotification(new byte[] {(byte) Companion.PACKET_NO_MORE_MESSAGES}, now);
                } else {
                    session.onNotification(queue.pollFirst(), now);
                }
            } else {
                throw new AssertionError("unexpected command " + op);
            }
        }

        int count(int op) {
            int n = 0;
            for (int o : ops) {
                if (o == op) {
                    n++;
                }
            }
            return n;
        }
    }

    private static byte[] msg(int n, String text) {
        return Companion.channelTextV3(0, 1, 2, 1_800_000_000L + n, text);
    }

    /** A READY link with channel 1 chosen and nothing queued on the radio. */
    private static FakeRadio readyRadio() {
        FakeRadio radio = new FakeRadio();
        radio.connect();
        radio.pump();
        check(radio.session.ready());
        eq("OK", radio.engine.selectChannel(1).status);
        return radio;
    }

    // ---- item 1: drain the offline queue ------------------------------------------------------

    static void drainReadsEveryQueuedMessageAfterOneTickle() {
        FakeRadio radio = readyRadio();
        long syncBefore = radio.count(Companion.CMD_SYNC_NEXT);
        long noMoreBefore = radio.engine.counters().get("no_more_messages");
        long framesBefore = radio.engine.counters().get("drain_frames");
        // The v7.4 failure: several messages queued, one tickle. v7.4 read one and stopped.
        for (int i = 1; i <= 5; i++) {
            radio.queue.addLast(msg(i, "Ana: queued " + i));
        }
        radio.session.onNotification(new byte[] {(byte) Companion.PACKET_MESSAGES_WAITING}, radio.now);
        radio.pump();
        eq(0, radio.queue.size());
        // Five messages and then the firmware's NO_MORE_MESSAGES: six cmd 10, one per reply, never two at once.
        eq(6L, radio.count(Companion.CMD_SYNC_NEXT) - syncBefore);
        eq(5L, radio.engine.counters().get("native_slot_1"));
        eq(1L, radio.engine.counters().get("no_more_messages") - noMoreBefore);
        eq(5, radio.engine.drainRoomMessages().size());
        eq(5L, radio.engine.counters().get("drain_frames") - framesBefore);
        check(radio.maxOutstanding <= 1);
        // And it stays quiet afterwards: no cmd 10 without a tickle.
        long sent = radio.count(Companion.CMD_SYNC_NEXT);
        radio.pump();
        eq(sent, (long) radio.count(Companion.CMD_SYNC_NEXT));
    }

    static void drainOneMessagePerTickleStillWorks() {
        FakeRadio radio = readyRadio();
        for (int i = 1; i <= 3; i++) {
            radio.arrive(msg(i, "Ana: live " + i));
            radio.pump();
        }
        eq(3L, radio.engine.counters().get("native_slot_1"));
        eq(3, radio.engine.drainRoomMessages().size());
        eq(3L, radio.engine.counters().get("drain_complete") - 1L); // one more at READY
    }

    static void drainRunsAtReadyForMessagesQueuedBeforeTheLinkCameUp() {
        // The firmware tickles only while a host is connected, so these have no tickle at all.
        FakeRadio radio = new FakeRadio();
        for (int i = 1; i <= 4; i++) {
            radio.arriveQuietly(msg(i, "Ana: while away " + i));
        }
        radio.connect();
        radio.pump();
        check(radio.session.ready());
        eq(0, radio.queue.size());
        eq(4L, radio.engine.counters().get("drain_frames"));
        eq(1L, radio.engine.counters().get("no_more_messages"));
        // Order on the wire after READY: stats, then the drain.
        int stats = radio.ops.indexOf(Companion.CMD_GET_STATS);
        int firstSync = radio.ops.indexOf(Companion.CMD_SYNC_NEXT);
        check(stats >= 0 && firstSync > stats);
        // No channel was chosen yet, so nothing went to the room: unverified radio text never
        // reaches ATAK without the user choosing a channel (E2).
        eq(0, radio.engine.drainRoomMessages().size());
        eq(4L, radio.engine.counters().get("native_text_rx"));
    }

    static void drainRunsAgainOnReconnect() {
        FakeRadio radio = readyRadio();
        radio.session.onDisconnected(radio.now);
        radio.now += 40_000L;
        radio.arriveQuietly(msg(1, "Ana: after the drop"));
        radio.connect();
        radio.pump();
        check(radio.session.ready());
        eq(0, radio.queue.size());
        eq(1L, radio.engine.counters().get("drain_frames"));
        eq(2L, radio.engine.counters().get("drain_started"));
    }

    static void drainStopsAtTheSafetyCap() {
        FakeRadio radio = readyRadio();
        long before = radio.count(Companion.CMD_SYNC_NEXT);
        long noMoreAtReady = radio.engine.counters().get("no_more_messages");
        // A radio that never says "no more messages".
        radio.endless = true;
        radio.session.onNotification(new byte[] {(byte) Companion.PACKET_MESSAGES_WAITING}, radio.now);
        radio.pump();
        eq((long) RadioLimits.SYNC_DRAIN_CAP, radio.count(Companion.CMD_SYNC_NEXT) - before);
        eq(1L, radio.engine.counters().get("drain_cap_hit"));
        eq(0L, radio.engine.counters().get("no_more_messages") - noMoreAtReady);
        check(radio.session.trace().contains("drain_cap_hit"));
        // Nothing more is asked for until the next tickle.
        long sent = radio.count(Companion.CMD_SYNC_NEXT);
        radio.pump();
        eq(sent, (long) radio.count(Companion.CMD_SYNC_NEXT));
        // A finite backlog larger than the cap: the next tickle carries on where the cap stopped it.
        FakeRadio big = readyRadio();
        big.engine.counters();
        for (int i = 1; i <= RadioLimits.SYNC_DRAIN_CAP + 50; i++) {
            big.queue.addLast(msg(i, "Ana: big " + i));
        }
        long bigBefore = big.count(Companion.CMD_SYNC_NEXT);
        long bigNoMore = big.engine.counters().get("no_more_messages");
        big.session.onNotification(new byte[] {(byte) Companion.PACKET_MESSAGES_WAITING}, big.now);
        big.pump();
        eq((long) RadioLimits.SYNC_DRAIN_CAP, big.count(Companion.CMD_SYNC_NEXT) - bigBefore);
        eq(50, big.queue.size());
        eq(1L, big.engine.counters().get("drain_cap_hit"));
        big.session.onNotification(new byte[] {(byte) Companion.PACKET_MESSAGES_WAITING}, big.now);
        big.pump();
        eq(0, big.queue.size());
        eq(1L, big.engine.counters().get("no_more_messages") - bigNoMore);
    }

    static void drainCoalescesATickleThatArrivesMidDrain() {
        FakeRadio radio = readyRadio();
        long before = radio.count(Companion.CMD_SYNC_NEXT);
        long coalescedBefore = radio.engine.counters().get("sync_coalesced");
        long noMoreBefore = radio.engine.counters().get("no_more_messages");
        long extraBefore = radio.engine.counters().get("drain_extra_pass");
        for (int i = 1; i <= 2; i++) {
            radio.queue.addLast(msg(i, "Ana: m" + i));
        }
        // While the radio is answering the first cmd 10, three more tickles arrive.
        radio.beforeSyncReply = n -> {
            if (n == radio.syncSent && radio.syncSent == (int) before + 1) {
                for (int k = 0; k < 3; k++) {
                    radio.session.onNotification(new byte[] {(byte) Companion.PACKET_MESSAGES_WAITING}, radio.now);
                }
            }
        };
        radio.session.onNotification(new byte[] {(byte) Companion.PACKET_MESSAGES_WAITING}, radio.now);
        radio.pump();
        eq(0, radio.queue.size());
        eq(3L, radio.engine.counters().get("sync_coalesced") - coalescedBefore);
        // Two messages, NO_MORE, then exactly one extra pass (NO_MORE again), however many tickles came.
        eq(4L, radio.count(Companion.CMD_SYNC_NEXT) - before);
        eq(1L, radio.engine.counters().get("drain_extra_pass") - extraBefore);
        eq(2L, radio.engine.counters().get("no_more_messages") - noMoreBefore);
        check(radio.maxOutstanding <= 1);
        eq(2, radio.engine.drainRoomMessages().size());
    }

    static void drainEndsOnErrorOrNoAnswerAndRestartsOnTheNextTickle() {
        FakeRadio radio = readyRadio();
        long before = radio.count(Companion.CMD_SYNC_NEXT);
        radio.queue.addLast(msg(1, "Ana: x"));
        // An error reply to cmd 10 (never expected from the firmware): the pass ends, no loop.
        radio.answerSyncWithError = true;
        radio.session.onNotification(new byte[] {(byte) Companion.PACKET_MESSAGES_WAITING}, radio.now);
        radio.pump();
        eq(1L, radio.count(Companion.CMD_SYNC_NEXT) - before);
        eq(1L, radio.engine.counters().get("drain_stopped_unexpected"));
        // The next tickle starts a fresh pass and gets the message.
        radio.answerSyncWithError = false;
        radio.session.onNotification(new byte[] {(byte) Companion.PACKET_MESSAGES_WAITING}, radio.now);
        radio.pump();
        eq(0, radio.queue.size());
        eq(1L, radio.engine.counters().get("native_slot_1"));
        // No answer at all: the command times out, the pass ends, the next tickle restarts it.
        radio.silentSync = true;
        long timeoutsBefore = radio.engine.counters().get("drain_timeout");
        radio.session.onNotification(new byte[] {(byte) Companion.PACKET_MESSAGES_WAITING}, radio.now);
        radio.pump();
        radio.now += RadioLimits.COMMAND_TIMEOUT_MS + 1L;
        radio.session.tick(radio.now);
        eq(1L, radio.engine.counters().get("drain_timeout") - timeoutsBefore);
        long sent = radio.count(Companion.CMD_SYNC_NEXT);
        radio.pump();
        eq(sent, (long) radio.count(Companion.CMD_SYNC_NEXT));
        radio.silentSync = false;
        radio.queue.addLast(msg(2, "Ana: y"));
        radio.session.onNotification(new byte[] {(byte) Companion.PACKET_MESSAGES_WAITING}, radio.now);
        radio.pump();
        eq(0, radio.queue.size());
    }

    static void drainDoesNotBlockSendsForLong() {
        // A typed room message is queued while a short backlog drains: both finish.
        FakeRadio radio = readyRadio();
        for (int i = 1; i <= 3; i++) {
            radio.queue.addLast(msg(i, "Ana: backlog " + i));
        }
        radio.session.onNotification(new byte[] {(byte) Companion.PACKET_MESSAGES_WAITING}, radio.now);
        eq("QUEUED", radio.engine.sendFromRoom(radio.engine.roomSpec().uid, "id-1", "on my way", radio.now).status);
        // The radio acknowledges the send with OK whenever it is written; the test radio answers
        // unknown commands with an assertion, so give it the one reply it needs.
        for (int guard = 0; guard < 400; guard++) {
            long delay = radio.session.nextActionDelayMs(radio.now);
            if (delay > 0) {
                radio.now += delay;
            }
            radio.session.tick(radio.now);
            byte[] cmd = radio.session.pollWrite();
            if (cmd == null) {
                radio.now += 100L;
                if (guard > 40 && radio.queue.isEmpty()) {
                    break;
                }
                continue;
            }
            radio.now += 40L;
            int op = cmd[0] & 0xFF;
            if (op == Companion.CMD_SEND_CHANNEL_DATA || op == Companion.CMD_SEND_CHANNEL_TXT) {
                radio.ops.add(op);
                radio.session.onNotification(new byte[] {(byte) Companion.PACKET_OK}, radio.now);
            } else {
                radio.ops.add(op);
                radio.session.onNotification(radio.queue.isEmpty()
                        ? new byte[] {(byte) Companion.PACKET_NO_MORE_MESSAGES} : radio.queue.pollFirst(), radio.now);
            }
        }
        eq(0, radio.queue.size());
        check(radio.count(Companion.CMD_SEND_CHANNEL_DATA) == 1);
    }

    // ---- item 2: inbound dedup ----------------------------------------------------------------

    static void repeatedNativeMessageIsShownOnce() {
        PluginHost host = hostOnChannel1();
        Engine engine = host.engine();
        byte[] frame = Companion.channelTextV3(0, 1, 2, 1_800_000_100L, "CiscoKid: You read this?");
        // The 2026-11-10 report: one message, three copies.
        engine.receiveFrame(frame.clone(), 2_000L);
        engine.receiveFrame(frame.clone(), 3_000L);
        engine.receiveFrame(frame.clone(), 120_000L);
        eq(1, engine.drainRoomMessages().size());
        eq(1, engine.nativeInbox().size());
        eq(1, engine.takeNewNativeText().size());
        eq(2L, engine.counters().get("room_msg_dup_dropped"));
        // The frames still count as received from the radio.
        eq(3L, engine.counters().get("native_text_rx"));
        eq(3L, engine.counters().get("native_slot_1"));
    }

    static void dedupKeyIsChannelTimestampAndText() {
        PluginHost host = hostOnChannel1();
        Engine engine = host.engine();
        engine.receiveFrame(Companion.channelTextV3(0, 1, 2, 1_800_000_100L, "CiscoKid: hi"), 2_000L);
        // A new timestamp (the sender really sent it again) is shown.
        engine.receiveFrame(Companion.channelTextV3(0, 1, 2, 1_800_000_160L, "CiscoKid: hi"), 2_100L);
        // The same timestamp with other text is shown.
        engine.receiveFrame(Companion.channelTextV3(0, 1, 2, 1_800_000_100L, "CiscoKid: hello"), 2_200L);
        // The same text from another sender (the sender name is part of the radio text) is shown.
        engine.receiveFrame(Companion.channelTextV3(0, 1, 2, 1_800_000_100L, "Bo: hi"), 2_300L);
        // The same sender, text and timestamp on another channel slot is shown (and stays in the pane).
        engine.receiveFrame(Companion.channelTextV3(0, 0, 2, 1_800_000_100L, "CiscoKid: hi"), 2_400L);
        eq(4, engine.drainRoomMessages().size());
        eq(0L, engine.counters().get("room_msg_dup_dropped"));
        eq(5, engine.nativeInbox().size());
        // A repeat on Public is dropped from the pane too.
        engine.receiveFrame(Companion.channelTextV3(0, 0, 2, 1_800_000_100L, "CiscoKid: hi"), 2_500L);
        eq(5, engine.nativeInbox().size());
        eq(1L, engine.counters().get("room_msg_dup_dropped"));
        // A repeat of a direct message too.
        byte[] direct = direct16("hello there", 1_800_000_300L);
        engine.receiveFrame(direct.clone(), 3_000L);
        engine.receiveFrame(direct.clone(), 3_100L);
        eq(6, engine.nativeInbox().size());
        eq(2L, engine.counters().get("room_msg_dup_dropped"));
    }

    static void dedupWindowExpiresAndMemoryIsBounded() {
        // Window: first seen at t, shown again once more than 10 minutes have passed.
        PluginHost host = hostOnChannel1();
        Engine engine = host.engine();
        byte[] frame = Companion.channelTextV3(0, 1, 2, 1_800_000_100L, "CiscoKid: later");
        engine.receiveFrame(frame.clone(), 1_000L);
        engine.receiveFrame(frame.clone(), 1_000L + 600_000L);
        eq(1L, engine.counters().get("room_msg_dup_dropped"));
        engine.receiveFrame(frame.clone(), 1_000L + 600_001L);
        eq(1L, engine.counters().get("room_msg_dup_dropped"));
        // Repeats do not extend the window: it runs from the first sighting.
        SeenLru lru = new SeenLru(4, 1_000L);
        check(lru.firstTime("k", 0L));
        check(!lru.firstTime("k", 900L));
        check(lru.firstTime("k", 1_001L));
        // Bound: capacity 3, least recently SEEN goes first, and a repeat counts as a sighting.
        SeenLru small = new SeenLru(3, 1_000_000L);
        check(small.firstTime("a", 1L));
        check(small.firstTime("b", 2L));
        check(small.firstTime("c", 3L));
        check(!small.firstTime("a", 4L)); // a is now the newest
        check(small.firstTime("d", 5L)); // evicts b, the least recently seen
        eq(3, small.size());
        check(!small.firstTime("a", 6L));
        check(!small.firstTime("c", 6L));
        check(small.firstTime("b", 7L)); // b was evicted, so it is new again
        // Through the engine: a flood of distinct messages never grows the set past its cap.
        for (int i = 0; i < Engine.NATIVE_DEDUP_CAP * 3; i++) {
            engine.receiveFrame(Companion.channelTextV3(0, 1, 2, 1_800_100_000L + i, "Ana: flood " + i), 5_000L + i);
        }
        engine.drainRoomMessages();
        check(!engine.recentNativeNotes().isEmpty());
        check(engine.recentNativeNotes().size() <= Engine.NATIVE_RECENT_CAP);
    }

    static void recentNativeNotesShowTimestampAndRepeatButNeverText() {
        PluginHost host = hostOnChannel1();
        Engine engine = host.engine();
        byte[] frame = Companion.channelTextV3(0, 1, 2, 1_800_000_100L, "CiscoKid: You read this?");
        engine.receiveFrame(frame.clone(), 2_000L);
        engine.receiveFrame(frame.clone(), 2_100L);
        List<String> notes = engine.recentNativeNotes();
        eq(2, notes.size());
        check(notes.get(0).startsWith("ch1 ts=1800000100 path=2 bytes="));
        check(notes.get(0).contains(" txt16="));
        check(!notes.get(0).contains("DUP"));
        check(notes.get(1).endsWith(" DUP"));
        for (String line : notes) {
            check(!line.contains("read this") && !line.contains("CiscoKid"));
        }
    }

    // ---- item 3: a window left open after a mode switch -----------------------------------------

    static void oldWindowAfterAModeSwitchIsRefusedWithAToast() {
        PluginHost host = hostOnChannel1();
        Engine engine = host.engine();
        String pluginRoom = engine.roomSpec().uid;
        engine.setPluginOnlyChat(false);
        String textRoom = engine.roomSpec().uid;
        check(!pluginRoom.equals(textRoom));
        // ATAK's old chat window still names the old uid. The closed contact lets the Intent through
        // to the plugin, which refuses with a sentence the plugin toasts.
        eq("REFUSED", host.offerRoomChat(pluginRoom, new String[] {pluginRoom}, "ANDROID-self", "old-1", "typed late", 5_000L));
        eq(Engine.ROOM_CLOSED_WHY, host.lastWhy());
        eq(0, engine.queued());
        eq(1L, engine.counters().get("room_send_stale"));
        // The new window works, and switching back makes the first one live again.
        eq("QUEUED", host.offerRoomChat(textRoom, new String[] {textRoom}, "ANDROID-self", "new-1", "typed now", 5_100L));
        engine.setPluginOnlyChat(true);
        eq(pluginRoom, engine.roomSpec().uid);
        eq("QUEUED", host.offerRoomChat(pluginRoom, new String[] {pluginRoom}, "ANDROID-self", "old-2", "back again", 5_200L));
        // Source: every replaced room is kept as a "(closed)" contact (bounded) and removed when the
        // same uid comes back, on link loss and when the plugin stops.
        String room = read("app/src/main/java/dev/meshcore/atak/host/AtakChatRoom.java");
        check(room.contains("CLOSED_PREFIX = \"(closed) \""));
        check(room.contains("MAX_CLOSED = 6"));
        int close = room.indexOf("private void close()");
        int closeEnd = room.indexOf("private void removeClosedWithUid");
        check(close > 0 && closeEnd > close);
        String closeBody = room.substring(close, closeEnd);
        check(closeBody.contains("old.setName(CLOSED_PREFIX + name)"));
        check(closeBody.contains("closed.add(old)"));
        check(closeBody.contains("closed.size() > MAX_CLOSED"));
        // The closed contact keeps its PluginConnector: it is the same IndividualContact, renamed, never a new one.
        check(!closeBody.contains("new IndividualContact") && !closeBody.contains("new PluginConnector"));
        check(room.contains("removeClosedWithUid(spec.uid)"));
        int remove = room.indexOf("    void remove() {");
        check(remove > 0 && room.indexOf("removeAllClosed();", remove) > remove);
        // A null spec (link lost, channel unselected) removes them too.
        int reconcile = room.indexOf("boolean reconcile(Engine.RoomSpec spec)");
        check(room.indexOf("removeAllClosed();", reconcile) > reconcile && room.indexOf("removeAllClosed();", reconcile) < close);
    }

    // ---- item 4 and 5: dead hold gone; room offered on the send receiver alone ----------------------

    static void deadHoldIsGoneAndTheRoomDoesNotNeedTheLogger() {
        check(!Files.exists(root().resolve("src/main/java/dev/meshcore/atak/plugin/BoundedHold.java")));
        String logger = read("app/src/main/java/dev/meshcore/atak/host/MeshCoreRoomLogger.java");
        check(!logger.contains("BoundedHold") && !logger.contains("pollOutbound") && !logger.contains("takeOverflow"));
        String plugin = read("app/src/main/java/dev/meshcore/atak/host/MeshCoreAtakPlugin.java");
        int a = plugin.indexOf("private boolean setRoomListeners(boolean want)");
        int b = plugin.indexOf("private boolean registerSendReceiver()");
        check(a > 0 && b > a);
        String body = plugin.substring(a, b);
        // The receiver decides whether the room is offered...
        check(body.indexOf("registerSendReceiver()") < body.indexOf("registerCommsLogger"));
        // ...and neither a missing comms component nor a failing logger turns the room down.
        check(body.contains("return false;") && body.indexOf("return false;") < body.indexOf("registerCommsLogger"));
        int firstReturnFalse = body.indexOf("return false;");
        check(body.substring(Math.max(0, firstReturnFalse - 80), firstReturnFalse).contains("!registerSendReceiver()"));
        check(!body.substring(body.indexOf("registerCommsLogger")).contains("return false;"));
        check(plugin.contains("room_receiver_unavailable"));
        // reconcileRoom no longer needs the logger object to exist.
        int r = plugin.indexOf("private void reconcileRoom(long now)");
        check(!plugin.substring(r, r + 200).contains("roomLogger"));
    }

    // ---- item 6: the id is remembered only for a message that was queued ------------------------------

    static void refusedSendIsNotRememberedAndARetryGoesThrough() {
        PluginHost host = hostOnChannel1();
        Engine engine = host.engine();
        String room = engine.roomSpec().uid;
        String id = "7d1c0e3e-0001";
        // Refused: far too long for the radio.
        String tooLong = "x".repeat(400);
        eq("BLOCKED_RADIO_LIMIT", host.offerRoomChat(room, new String[] {room}, "ANDROID-self", id, tooLong, 5_000L));
        eq(0, engine.queued());
        // The same id with text that fits is a retry, not a duplicate: it is queued.
        eq("QUEUED", host.offerRoomChat(room, new String[] {room}, "ANDROID-self", id, "short", 5_100L));
        eq(1, engine.queued());
        // Now the id IS remembered: a third delivery of it is a visible DUPLICATE, not a silent drop.
        eq("DUPLICATE", host.offerRoomChat(room, new String[] {room}, "ANDROID-self", id, "short", 5_200L));
        eq(SendReasons.ALREADY_QUEUED, host.lastWhy());
        eq(1, engine.queued());
        eq(1L, engine.counters().get("room_dup_out"));
        // The refusal before it was counted as a refusal, the queue as one queued message.
        eq(1L, engine.counters().get("room_send_queued"));
        // The same holds with no id at all (text fallback, three seconds).
        eq("BLOCKED_RADIO_LIMIT", host.offerRoomChat(room, new String[] {room}, "ANDROID-self", null, tooLong, 9_000L));
        eq("QUEUED", host.offerRoomChat(room, new String[] {room}, "ANDROID-self", null, "ok", 9_100L));
    }

    // ---- firmware cross-checks -------------------------------------------------------------------------

    static void drainOpcodesAreTheFirmwaresNumbers() {
        eq(10, Companion.CMD_SYNC_NEXT);
        eq(10, Companion.PACKET_NO_MORE_MESSAGES);
        eq(0x83, Companion.PACKET_MESSAGES_WAITING);
        eq(0x11, Companion.PACKET_CHANNEL_MSG_V3);
        eq(0x10, Companion.PACKET_CONTACT_MSG_V3);
        eq(7, Companion.PACKET_CONTACT_MSG);
        eq(8, Companion.PACKET_CHANNEL_MSG);
        eq(27, Companion.PACKET_CHANNEL_DATA);
        eq(300, RadioLimits.SYNC_DRAIN_CAP);
        String env = System.getenv("MESHCORE_ROOT");
        if (env == null || env.isBlank()) {
            throw new Outcome.Skip("MESHCORE_ROOT unset; firmware opcodes were not read");
        }
        try {
            String fw = Files.readString(Path.of(env).resolve("examples/companion_radio/MyMesh.cpp"));
            check(fw.contains("#define CMD_SYNC_NEXT_MESSAGE         10"));
            check(fw.contains("#define RESP_CODE_NO_MORE_MESSAGES    10"));
            check(fw.contains("#define PUSH_CODE_MSG_WAITING           0x83"));
            check(fw.contains("#define RESP_CODE_CHANNEL_MSG_RECV_V3 17"));
            check(fw.contains("#define RESP_CODE_CONTACT_MSG_RECV_V3 16"));
            check(fw.contains("#define RESP_CODE_CONTACT_MSG_RECV    7"));
            check(fw.contains("#define RESP_CODE_CHANNEL_MSG_RECV    8"));
            check(fw.contains("#define RESP_CODE_CHANNEL_DATA_RECV   27"));
            // The handler: pop one frame, or answer NO_MORE_MESSAGES with a one-byte frame.
            int h = fw.indexOf("cmd_frame[0] == CMD_SYNC_NEXT_MESSAGE");
            check(h > 0);
            String handler = fw.substring(h, h + 400);
            check(handler.contains("getFromOfflineQueue(out_frame)"));
            check(handler.contains("out_frame[0] = RESP_CODE_NO_MORE_MESSAGES;"));
            check(handler.contains("_serial->writeFrame(out_frame, 1);"));
            // The tickle is sent only while a host is connected.
            check(fw.contains("if (_serial->isConnected()) {\n    uint8_t frame[1];\n    frame[0] = PUSH_CODE_MSG_WAITING;"));
            Path mesh = Path.of(env).resolve("examples/companion_radio/MyMesh.h");
            check(Files.readString(mesh).contains("#define OFFLINE_QUEUE_SIZE 16"));
            Path ble = Path.of(env).resolve("src/helpers/esp32/SerialBLEInterface.h");
            check(Files.readString(ble).contains("#define FRAME_QUEUE_SIZE  4"));
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private static PluginHost hostOnChannel1() {
        PluginHost host = new PluginHost();
        host.onCreate();
        host.onStart();
        Engine engine = host.engine();
        engine.onLinkUp(1_000L);
        engine.setNowSeconds(1_800_000_000L);
        byte[] key = new byte[32];
        Bytes.putRaw4(key, 0, 0x11111111);
        engine.receiveFrame(Companion.selfInfo(key, 910_525, 62_500, 8, 5, "Ciscokid-ATAK"), 1_000L);
        engine.receiveFrame(Companion.channelInfo(0, "Public", Companion.PUBLIC_CHANNEL_SECRET), 1_000L);
        engine.receiveFrame(Companion.channelInfo(1, "CombatClubMesh", SECRET), 1_000L);
        eq("OK", engine.selectChannel(1).status);
        return host;
    }

    /** A CONTACT_MSG_RECV_V3 frame (0x10) written byte by byte from MyMesh.cpp queueMessage. */
    private static byte[] direct16(String text, long ts) {
        byte[] body = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] f = new byte[4 + 6 + 1 + 1 + 4 + body.length];
        f[0] = 0x10;
        for (int i = 0; i < 6; i++) {
            f[4 + i] = (byte) (0xA0 + i);
        }
        f[10] = (byte) 0xFF;
        f[11] = 0;
        Bytes.putU32le(f, 12, ts);
        System.arraycopy(body, 0, f, 16, body.length);
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

    private static String read(String relative) {
        try {
            return Files.readString(root().resolve(relative));
        } catch (java.io.IOException ex) {
            throw new AssertionError("source not readable: " + relative);
        }
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
