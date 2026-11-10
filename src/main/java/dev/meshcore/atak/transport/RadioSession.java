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
 * {@code PUSH_CODE_MSG_WAITING} ({@code MyMesh.cpp} lines 627–632). This
 * session answers that tickle with a single {@code CMD_SYNC_NEXT_MESSAGE}.
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
    private boolean mtuLatched;
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
        int type = frame[0] & 0xFF;
        if (type == Companion.PACKET_DEVICE_INFO) {
            frame = wipePin(frame);
        }
        if (type >= 0x80) {
            if (type == Companion.PACKET_MESSAGES_WAITING) {
                if (syncWanted) {
                    engine.counters().inc("sync_coalesced");
                }
                syncWanted = true;
            }
            engine.receiveFrame(frame, nowMs);
            return;
        }
        if (inflight != null && matches(inflight[0] & 0xFF, type)) {
            byte[] cmd = inflight;
            if ((cmd[0] & 0xFF) == Companion.CMD_SYNC_NEXT && Companion.isNativeText(type)) {
                engine.counters().inc("native_text_unsupported");
                engine.noteTransportStatus("NATIVE_TEXT_UNSUPPORTED");
            }
            inflight = null;
            tableFullRetries = 0;
            noteSuccess(cmd, frame);
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
            if (code != RadioLimits.ERR_TABLE_FULL) {
                engine.noteTransportStatus("RADIO_ERROR_" + code);
            }
            if (cmdByte == Companion.CMD_SEND_CHANNEL_DATA && code == RadioLimits.ERR_TABLE_FULL
                    && tableFullRetries < 1) {
                retryHold = cmd;
                tableFullRetries++;
                note("table_full_retry");
                return;
            }
            tableFullRetries = 0;
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
        if (Companion.isNativeText(type)) {
            engine.counters().inc("native_text_unsupported");
            engine.noteTransportStatus("NATIVE_TEXT_UNSUPPORTED");
        }
        engine.counters().inc("unexpected_response");
        engine.receiveFrame(frame, nowMs);
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
                if (cmd == Companion.CMD_SEND_CHANNEL_DATA) {
                    engine.noteTransportStatus("SEND_TIMEOUT");
                }
                if (cmd == Companion.CMD_APP_START || cmd == Companion.CMD_DEVICE_QUERY
                        || cmd == Companion.CMD_GET_CHANNEL) {
                    retryHandshake(lost);
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
        if (syncWanted) {
            syncWanted = false;
            return Companion.syncNext();
        }
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
        note("session_ready");
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
        if (cmd == Companion.CMD_SEND_CHANNEL_DATA) {
            return response == Companion.PACKET_OK;
        }
        if (cmd == Companion.CMD_SYNC_NEXT) {
            return response < 0x80;
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
