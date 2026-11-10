package dev.meshcore.atak.transport;

import dev.meshcore.atak.companion.Companion;
import dev.meshcore.atak.engine.Engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Companion command session for one radio link. One GATT write is one
 * companion frame, which is what the official ESP32, nRF52, and RP2040
 * companion paths and {@code docs/companion_protocol.md} describe. This class
 * does not open a socket and does not log frame bytes, channel secrets, or a
 * BLE PIN.
 *
 * <p>Inbound channel datagrams are not pushed whole. Official
 * {@code onChannelDataRecv} queues the frame and writes a one-byte
 * {@code PUSH_CODE_MSG_WAITING} ({@code MyMesh.cpp} lines 627–632). v7.5: this
 * session answers that tickle by draining the radio's offline queue: it sends
 * {@code CMD_SYNC_NEXT_MESSAGE} again after every message until the radio replies
 * {@code RESP_CODE_NO_MORE_MESSAGES} ({@code MyMesh.cpp} lines 1415-1423). v7.4 sent one
 * and stopped, so the queue was only ever read one message per tickle and any backlog
 * (a message queued before the link was up, a tickle the BLE send queue dropped) stayed
 * behind. One drain runs at a time; a tickle that arrives during it asks for one more pass.
 */
public final class RadioSession {
    private enum Phase { NEED_APP, NEED_DEVICE, NEED_CHANNELS, READY }

    private final Engine engine;
    private final Deque<byte[]> outbox = new ArrayDeque<>();
    private final List<String> trace = new ArrayList<>();

    private Phase phase = Phase.NEED_APP;
    private boolean connected;
    private boolean notifications;
    private boolean writesEnabled = true;
    private boolean handshakeFailed;
    private boolean syncWanted;
    /** v7.5: a drain pass is running: cmd 10 is sent after every message until "no more". */
    private boolean drainActive;
    /** v7.5: a tickle (0x83) arrived during the pass; run one more pass when this one ends. */
    private boolean drainAgain;
    /** v7.5: cmd 10 frames issued in the current pass, for the safety cap. */
    private int drainSent;
    /** L1: a drain was asked for while no channel was chosen; it starts once one is. */
    private boolean drainDeferred;
    /** R1: the sync count at which a queued send last got its turn, or -1. */
    private int lastYieldAt = -1;
    private boolean mtuLatched;
    private boolean statsWanted;
    private long lastNotificationMs;
    private int mtu;
    private int handshakeRetries;
    private int tableFullRetries;
    private int channelCount;
    private int channelCursor;
    private int backoffStep;
    private long lastWriteMs = Long.MIN_VALUE / 4;
    private long inflightDeadline;
    private long reconnectNotBefore;
    private byte[] inflight;
    private byte[] retryHold;
    private String handshakeFailedCommand = "";
    private boolean handshakeMtuBlocked;
    private int consecutiveWriteRefusals;

    public RadioSession(Engine engine) {
        this.engine = engine;
    }

    public Engine engine() {
        return engine;
    }

    public synchronized List<String> trace() {
        return Collections.unmodifiableList(new ArrayList<String>(trace));
    }

    public synchronized boolean connected() {
        return connected;
    }

    public synchronized boolean ready() {
        return connected && phase == Phase.READY && !handshakeFailed;
    }

    public synchronized int mtu() {
        return mtu;
    }

    public synchronized void setWritesEnabled(boolean enabled) {
        this.writesEnabled = enabled;
    }

    public synchronized boolean writesEnabled() {
        return writesEnabled;
    }

    /** True once a handshake command was given up on (after its one retry). */
    public synchronized boolean handshakeFailed() {
        return handshakeFailed;
    }

    /** Name of the handshake command that got no reply, or "" when none failed. */
    public synchronized String handshakeFailedCommand() {
        return handshakeFailedCommand;
    }

    /**
     * True when a handshake command could not be written because the negotiated
     * ATT MTU is too small for it. This used to be a hidden counter only.
     */
    public synchronized boolean handshakeMtuBlocked() {
        return handshakeMtuBlocked;
    }

    /**
     * C6: where the companion handshake is, for the status line. Channel numbers
     * are 1-based: "channel 7/40" means channel slot 7 of 40 is being read.
     */
    public synchronized String handshakeProgress() {
        switch (phase) {
            case NEED_APP:
                return "handshake: app start";
            case NEED_DEVICE:
                return "handshake: device query";
            case NEED_CHANNELS:
                int shown = Math.min(channelCursor + 1, Math.max(channelCount, 1));
                return "handshake: channel " + shown + "/" + channelCount;
            default:
                return "handshake: done";
        }
    }

    public synchronized int consecutiveWriteRefusals() {
        return consecutiveWriteRefusals;
    }

    public synchronized void onConnected(long nowMs) {
        if (connected) {
            return;
        }
        connected = true;
        phase = Phase.NEED_APP;
        handshakeFailed = false;
        handshakeFailedCommand = "";
        handshakeMtuBlocked = false;
        consecutiveWriteRefusals = 0;
        handshakeRetries = 0;
        tableFullRetries = 0;
        channelCursor = 0;
        inflight = null;
        retryHold = null;
        statsWanted = false;
        resetDrain();
        lastNotificationMs = 0L;
        engine.onLinkUp(nowMs);
        note("connected");
    }

    public synchronized void onDisconnected(long nowMs) {
        if (!connected && inflight == null) {
            return;
        }
        connected = false;
        notifications = false;
        mtu = 0;
        mtuLatched = false;
        inflight = null;
        retryHold = null;
        outbox.clear();
        phase = Phase.NEED_APP;
        handshakeFailed = false;
        handshakeRetries = 0;
        statsWanted = false;
        resetDrain();
        int shift = Math.min(5, Math.max(0, backoffStep));
        long delay = Math.min(30_000L, 1000L << shift);
        backoffStep = Math.min(5, backoffStep + 1);
        reconnectNotBefore = nowMs + delay;
        engine.onLinkLost();
        note("disconnected backoffMs=" + delay);
    }

    public synchronized boolean reconnectDue(long nowMs) {
        return writesEnabled && !connected && nowMs >= reconnectNotBefore;
    }

    public synchronized void onMtu(int negotiated, long nowMs) {
        this.mtu = negotiated;
        this.mtuLatched = false;
        note("mtu " + negotiated);
    }

    public synchronized void onNotifications(boolean enabled) {
        this.notifications = enabled;
        note(enabled ? "notify_on" : "notify_off");
    }

    public synchronized void onNotification(byte[] frame, long nowMs) {
        if (frame == null || frame.length == 0 || frame.length > RadioLimits.MAX_FRAME_SIZE) {
            engine.counters().inc("ble_frame_rejected");
            note("reject len=" + (frame == null ? -1 : frame.length));
            return;
        }
        lastNotificationMs = nowMs;
        int type = frame[0] & 0xFF;
        if (type == Companion.PACKET_DEVICE_INFO) {
            frame = wipePin(frame);
        }
        if (type >= 0x80) {
            if (type == Companion.PACKET_MESSAGES_WAITING) {
                requestDrain();
            }
            engine.receiveFrame(frame, nowMs);
            return;
        }
        if (inflight != null && matches(inflight[0] & 0xFF, type)) {
            byte[] cmd = inflight;
            inflight = null;
            tableFullRetries = 0;
            if ((cmd[0] & 0xFF) == Companion.CMD_SEND_CHANNEL_DATA
                    || (cmd[0] & 0xFF) == Companion.CMD_SEND_CHANNEL_TXT) {
                engine.onChannelDataReply(0);
            }
            noteSuccess(cmd, frame);
            if ((cmd[0] & 0xFF) == Companion.CMD_SYNC_NEXT) {
                onSyncReply(type);
            }
            engine.receiveFrame(frame, nowMs);
            return;
        }
        if (inflight != null && type == Companion.PACKET_ERROR) {
            int code = frame.length > 1 ? frame[1] & 0xFF : -1;
            byte[] cmd = inflight;
            inflight = null;
            engine.counters().inc("radio_error");
            note("error " + code);
            int cmdByte = cmd[0] & 0xFF;
            if (cmdByte == Companion.CMD_GET_STATS) {
                // Read-only diagnostics. An old firmware may refuse it. Not a transport failure.
                engine.counters().inc("stats_unavailable");
            } else if (code != RadioLimits.ERR_TABLE_FULL) {
                engine.noteTransportStatus("RADIO_ERROR_" + code);
            }
            // F2: a send (3 or 62) is never retried automatically after an error or a timeout. A
            // second copy could land on the channel twice. The user decides.
            tableFullRetries = 0;
            if (cmdByte == Companion.CMD_SEND_CHANNEL_DATA || cmdByte == Companion.CMD_SEND_CHANNEL_TXT) {
                engine.onChannelDataReply(code < 0 ? -2 : code);
            }
            if (cmdByte == Companion.CMD_SEND_CHANNEL_DATA && code == RadioLimits.ERR_TABLE_FULL) {
                engine.noteTransportStatus("RADIO_ERROR_" + code);
            }
            if (cmdByte == Companion.CMD_GET_CHANNEL) {
                advanceChannel();
            } else if (cmdByte == Companion.CMD_APP_START || cmdByte == Companion.CMD_DEVICE_QUERY) {
                retryHandshake(cmd);
            }
            engine.receiveFrame(frame, nowMs);
            return;
        }
        // Native text is kept and shown by the engine (E8); it is not an unexpected frame.
        if (!Companion.isNativeText(type)) {
            engine.counters().inc("unexpected_response");
        }
        engine.receiveFrame(frame, nowMs);
    }

    /** When the last frame arrived from the radio, or 0 when none has on this link (E5). */
    public synchronized long lastNotificationMs() {
        return lastNotificationMs;
    }

    /**
     * E4: how long until {@link #tick} could write the next command. 0 means now.
     * -1 means nothing can be written until something else happens: a reply is
     * awaited, a frame is already queued for the GATT write, or the link is not up.
     * The Android adapter calls this after every notification and every completed
     * write, so the next frame goes out as soon as the 60 ms gap allows. The 1 s
     * host tick stays as a fallback.
     */
    public synchronized long nextActionDelayMs(long nowMs) {
        if (!connected || !notifications || !writesEnabled || inflight != null || !outbox.isEmpty()) {
            return -1L;
        }
        long wait = lastWriteMs + RadioLimits.WRITE_GAP_MS - nowMs;
        return wait <= 0L ? 0L : wait;
    }

    public synchronized void tick(long nowMs) {
        if (!connected || !notifications || !writesEnabled) {
            return;
        }
        if (inflight != null) {
            if (nowMs >= inflightDeadline) {
                byte[] lost = inflight;
                inflight = null;
                engine.counters().inc("command_timeout");
                note("timeout " + (lost[0] & 0xFF));
                int cmd = lost[0] & 0xFF;
                if (cmd == Companion.CMD_SEND_CHANNEL_DATA || cmd == Companion.CMD_SEND_CHANNEL_TXT) {
                    engine.noteTransportStatus("SEND_TIMEOUT");
                    engine.onChannelDataReply(-1);
                }
                if (cmd == Companion.CMD_APP_START || cmd == Companion.CMD_DEVICE_QUERY
                        || cmd == Companion.CMD_GET_CHANNEL) {
                    retryHandshake(lost);
                }
                if (cmd == Companion.CMD_SYNC_NEXT) {
                    // No answer: end the pass. The next tickle or the next READY starts a new one.
                    engine.counters().inc("drain_timeout");
                    resetDrain();
                }
            }
            return;
        }
        if (nowMs - lastWriteMs < RadioLimits.WRITE_GAP_MS) {
            return;
        }
        if (outbox.size() >= RadioLimits.WRITE_QUEUE_CAP) {
            engine.counters().inc("write_queue_drop");
            note("write_queue_full");
            return;
        }
        byte[] cmd = nextCommand(nowMs);
        if (cmd == null) {
            return;
        }
        String decision = RadioLimits.decision(cmd, mtu);
        if (!"ok".equals(decision)) {
            int cmdByte = cmd[0] & 0xFF;
            if (cmdByte == Companion.CMD_APP_START || cmdByte == Companion.CMD_DEVICE_QUERY
                    || cmdByte == Companion.CMD_GET_CHANNEL || cmdByte == Companion.CMD_SYNC_NEXT) {
                retryHold = cmd;
            }
            engine.counters().inc("not_written_" + decision);
            note("not_written " + decision);
            if ("mtu".equals(decision) && (cmdByte == Companion.CMD_APP_START
                    || cmdByte == Companion.CMD_DEVICE_QUERY)) {
                handshakeMtuBlocked = true;
            }
            return;
        }
        byte[] copy = Arrays.copyOf(cmd, cmd.length);
        outbox.addLast(copy);
        inflight = copy;
        inflightDeadline = nowMs + RadioLimits.COMMAND_TIMEOUT_MS;
        lastWriteMs = nowMs;
        note("write " + (copy[0] & 0xFF) + " len=" + copy.length);
    }

    public synchronized byte[] pollWrite() {
        return outbox.pollFirst();
    }

    /**
     * The next frame to hand to the radio, left in the queue. The BLE adapter
     * calls {@link #pollWrite()} only after Android accepted the write, or
     * {@link #writeRefused()} when it did not. A refused write used to lose the
     * frame (B3).
     */
    public synchronized byte[] peekWrite() {
        return outbox.peekFirst();
    }

    /**
     * Android refused the write. The head frame goes back to be tried again on a
     * later tick instead of being lost; its in-flight timer is cleared.
     */
    public synchronized void writeRefused() {
        byte[] head = outbox.pollFirst();
        consecutiveWriteRefusals++;
        engine.counters().inc("write_refused");
        note("write_refused " + consecutiveWriteRefusals);
        if (head != null) {
            if (inflight == head) {
                inflight = null;
            }
            if (retryHold == null) {
                retryHold = head;
            }
        }
    }

    /** Android accepted the write. */
    public synchronized void writeAccepted() {
        consecutiveWriteRefusals = 0;
    }

    public synchronized int queuedWrites() {
        return outbox.size();
    }

    private byte[] nextCommand(long nowMs) {
        if (handshakeFailed && phase != Phase.READY) {
            return null;
        }
        if (retryHold != null) {
            byte[] held = retryHold;
            retryHold = null;
            return held;
        }
        if (phase == Phase.NEED_APP) {
            return engine.appStartFrame();
        }
        if (phase == Phase.NEED_DEVICE) {
            return Companion.deviceQuery();
        }
        if (phase == Phase.NEED_CHANNELS) {
            if (channelCursor < channelCount) {
                return Companion.getChannel(channelCursor);
            }
            becomeReady();
        }
        if (statsWanted) {
            statsWanted = false;
            return Companion.getStatsCore();
        }
        if (drainDeferred && !drainActive && engine.hasSelectedChannel()) {
            // L1: a channel is chosen now. Start exactly one drain for everything that was deferred.
            engine.counters().inc("drain_deferred_started");
            requestDrain();
        }
        if (syncWanted) {
            if (drainSent > 0 && drainSent % RadioLimits.SYNC_SEND_INTERLEAVE == 0 && drainSent != lastYieldAt) {
                // R1: after every 8 sync requests one queued send goes first, so a typed message does
                // not wait behind a whole backlog. syncWanted stays set: the next call asks again.
                lastYieldAt = drainSent;
                byte[] send = nextSendFrame(nowMs);
                if (send != null) {
                    engine.counters().inc("drain_send_interleaved");
                    return send;
                }
            }
            syncWanted = false;
            drainSent++;
            return Companion.syncNext();
        }
        return nextSendFrame(nowMs);
    }

    /** The next queued send from the engine, or null when there is none or it cannot go out now. */
    private byte[] nextSendFrame(long nowMs) {
        byte[] preview = engine.previewTx(nowMs);
        if (preview == null) {
            return null;
        }
        if ("illegal_arg".equals(RadioLimits.decision(preview, Math.max(mtu, RadioLimits.MIN_MTU_FOR_MAX_FRAME)))
                || "reject".equals(RadioLimits.decision(preview, Math.max(mtu, RadioLimits.MIN_MTU_FOR_MAX_FRAME)))) {
            engine.drainTx(nowMs);
            engine.counters().inc("radio_payload_oversize");
            note("dropped_oversize");
            return null;
        }
        if (!"ok".equals(RadioLimits.decision(preview, mtu))) {
            if (!mtuLatched) {
                mtuLatched = true;
                engine.counters().inc("mtu_blocked");
                note("mtu_blocked");
            }
            return null;
        }
        mtuLatched = false;
        List<byte[]> drained = engine.drainTx(nowMs);
        return drained.isEmpty() ? null : drained.get(0);
    }

    private void noteSuccess(byte[] cmd, byte[] response) {
        int cmdByte = cmd[0] & 0xFF;
        if (cmdByte == Companion.CMD_APP_START) {
            phase = Phase.NEED_DEVICE;
            return;
        }
        if (cmdByte == Companion.CMD_DEVICE_QUERY) {
            Companion.DeviceInfo info = Companion.parseDeviceInfo(response);
            int n = info == null ? 0 : info.maxChannels;
            if (n < 0) {
                n = 0;
            }
            if (n > RadioLimits.CHANNEL_FETCH_CAP) {
                engine.counters().inc("channel_fetch_capped");
                n = RadioLimits.CHANNEL_FETCH_CAP;
            }
            channelCount = n;
            channelCursor = 0;
            phase = n == 0 ? Phase.READY : Phase.NEED_CHANNELS;
            if (phase == Phase.READY) {
                becomeReady();
            }
            return;
        }
        if (cmdByte == Companion.CMD_GET_CHANNEL) {
            advanceChannel();
        }
    }

    private void advanceChannel() {
        channelCursor++;
        if (channelCursor >= channelCount) {
            becomeReady();
        }
    }

    private void becomeReady() {
        phase = Phase.READY;
        backoffStep = 0;
        // E6: read uptime, battery and error flags once per READY. Read-only.
        statsWanted = true;
        note("session_ready");
        // v7.5: the firmware sends a tickle only while a host is connected (MyMesh.cpp lines 469 and
        // 585), so anything queued before this link came up has no tickle. Read it now.
        requestDrain();
    }

    private void resetDrain() {
        syncWanted = false;
        drainActive = false;
        drainAgain = false;
        drainSent = 0;
        lastYieldAt = -1;
        drainDeferred = false;
    }

    /** L1/diag: "deferred" (waiting for a channel), "active" (a pass is running) or "idle". */
    public synchronized String drainState() {
        if (drainActive) {
            return "active";
        }
        return drainDeferred ? "deferred" : "idle";
    }

    /**
     * v7.5: something may be waiting in the radio's queue (a tickle, or the link just became ready).
     * Starts a drain pass, or, when one is already running, asks for one more pass after it.
     */
    private void requestDrain() {
        if (drainActive) {
            drainAgain = true;
            engine.counters().inc("sync_coalesced");
            return;
        }
        if (!engine.hasSelectedChannel()) {
            // L1: reading a message takes it off the radio for good. With no channel chosen it could
            // only reach the pane (last 10), never the room, so leave it queued on the radio
            // (256 messages on Heltec V4) and read it once a channel is chosen.
            drainDeferred = true;
            engine.counters().inc("drain_deferred");
            return;
        }
        drainDeferred = false;
        drainActive = true;
        drainAgain = false;
        drainSent = 0;
        lastYieldAt = -1;
        syncWanted = true;
        engine.counters().inc("drain_started");
    }

    /**
     * v7.5: the radio answered a CMD_SYNC_NEXT_MESSAGE. A message frame means "ask again"; the
     * firmware's NO_MORE_MESSAGES means the queue is empty. Anything else (an error, an unknown
     * code) ends the pass so a confused radio cannot keep this loop running.
     */
    private void onSyncReply(int type) {
        if (!drainActive) {
            return;
        }
        if (type == Companion.PACKET_NO_MORE_MESSAGES) {
            engine.counters().inc("drain_complete");
            boolean again = drainAgain;
            drainActive = false;
            drainAgain = false;
            drainSent = 0;
            lastYieldAt = -1;
            if (again) {
                engine.counters().inc("drain_extra_pass");
                requestDrain();
            }
            return;
        }
        if (Companion.isNativeText(type) || type == Companion.PACKET_CHANNEL_DATA) {
            engine.counters().inc("drain_frames");
            if (drainSent >= RadioLimits.SYNC_DRAIN_CAP) {
                engine.counters().inc("drain_cap_hit");
                note("drain_cap_hit");
                resetDrain();
                return;
            }
            syncWanted = true;
            return;
        }
        engine.counters().inc("drain_stopped_unexpected");
        note("drain_stopped " + type);
        resetDrain();
    }

    private void retryHandshake(byte[] cmd) {
        if (handshakeRetries < 1) {
            retryHold = cmd;
            handshakeRetries++;
            note("handshake_retry");
            return;
        }
        handshakeFailed = true;
        handshakeFailedCommand = commandName(cmd);
        note("handshake_stopped");
        note("handshake_no_reply " + handshakeFailedCommand);
    }

    private static String commandName(byte[] cmd) {
        if (cmd == null || cmd.length == 0) {
            return "unknown";
        }
        int c = cmd[0] & 0xFF;
        if (c == Companion.CMD_APP_START) {
            return "APP_START";
        }
        if (c == Companion.CMD_DEVICE_QUERY) {
            return "DEVICE_QUERY";
        }
        if (c == Companion.CMD_GET_CHANNEL) {
            return "GET_CHANNEL";
        }
        return "cmd" + c;
    }

    private static boolean matches(int cmd, int response) {
        if (cmd == Companion.CMD_APP_START) {
            return response == Companion.PACKET_SELF_INFO;
        }
        if (cmd == Companion.CMD_DEVICE_QUERY) {
            return response == Companion.PACKET_DEVICE_INFO;
        }
        if (cmd == Companion.CMD_GET_CHANNEL) {
            return response == Companion.PACKET_CHANNEL_INFO;
        }
        if (cmd == Companion.CMD_SEND_CHANNEL_DATA || cmd == Companion.CMD_SEND_CHANNEL_TXT) {
            return response == Companion.PACKET_OK;
        }
        if (cmd == Companion.CMD_SYNC_NEXT) {
            return response < 0x80;
        }
        if (cmd == Companion.CMD_GET_STATS) {
            return response == Companion.PACKET_STATS;
        }
        return false;
    }

    /** Device-info bytes 4–7 are the BLE PIN. They are not retained. */
    static byte[] wipePin(byte[] frame) {
        if (frame.length < 8) {
            return frame;
        }
        byte[] copy = Arrays.copyOf(frame, frame.length);
        copy[4] = 0;
        copy[5] = 0;
        copy[6] = 0;
        copy[7] = 0;
        frame[4] = 0;
        frame[5] = 0;
        frame[6] = 0;
        frame[7] = 0;
        return copy;
    }

    private void note(String line) {
        if (trace.size() >= RadioLimits.TRACE_CAP) {
            trace.remove(0);
            engine.counters().inc("trace_capped");
        }
        trace.add(line);
    }
}
