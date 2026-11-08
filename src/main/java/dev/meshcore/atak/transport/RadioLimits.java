package dev.meshcore.atak.transport;

/**
 * Constants taken from the official MeshCore firmware and companion document.
 *
 * <p>Authority: {@code meshcore-dev/MeshCore} commit
 * {@code 81dbae592ffad40b4212bc0b37e82b38da08f4bc} (2026-10-15, the
 * {@code origin/main} tip read for this tree). The gridbasedotnet plugin and
 * the MCTAK specification are interoperability references. They do not override
 * these firmware checks.
 */
public final class RadioLimits {
    public static final String MESHCORE_REPO = "https://github.com/meshcore-dev/MeshCore";
    public static final String MESHCORE_COMMIT = "81dbae592ffad40b4212bc0b37e82b38da08f4bc";

    /** {@code src/helpers/BaseSerialInterface.h} line 5. */
    public static final int MAX_FRAME_SIZE = 176;
    /** {@code src/MeshCore.h} lines 20–21. {@code 184 - 16 - 3}. */
    public static final int MAX_GROUP_DATA_LENGTH = 165;
    /** {@code examples/companion_radio/MyMesh.cpp} line 106. {@code MAX_FRAME_SIZE - 9}. */
    public static final int MAX_CHANNEL_DATA_LENGTH = 167;
    /** {@code examples/companion_radio/MyMesh.cpp} line 64. */
    public static final int CMD_SEND_CHANNEL_DATA = 62;
    /** {@code examples/companion_radio/MyMesh.cpp} line 140. */
    public static final int ERR_ILLEGAL_ARG = 6;
    /** {@code examples/companion_radio/MyMesh.cpp} line 137. Queue full, not the oversize path. */
    public static final int ERR_TABLE_FULL = 3;

    public static final int ATT_OVERHEAD = 3;
    /**
     * ESP32 companion calls {@code BLEDevice::setMTU(MAX_FRAME_SIZE)} and then
     * notifies the whole frame. It does not apply the RP2040 drop. If the
     * negotiated ATT MTU stays 176, the notification payload is 173 bytes.
     * A channel-data host frame is 9 bytes plus the datagram, so a 165-byte
     * datagram is 174 bytes and does not fit that payload. A flood send of the
     * same datagram is 5 + 165 = 170 and does fit. Truncation was not observed
     * on a radio. This is not a new datagram size.
     */
    public static final int ESP32_LOCAL_MTU = MAX_FRAME_SIZE;
    public static final int ESP32_ATT_PAYLOAD = ESP32_LOCAL_MTU - ATT_OVERHEAD;
    public static final int CHANNEL_DATA_HEADER = 9;
    public static final int ESP32_NOTIFY_DATAGRAM_FIT = ESP32_ATT_PAYLOAD - CHANNEL_DATA_HEADER;
    /** Default {@code OFFLINE_QUEUE_SIZE} in companion {@code MyMesh.h}. Builds may override it. */
    public static final int DEFAULT_OFFLINE_QUEUE = 16;
    /**
     * RP2040 companion drops a notify when {@code frame + 3 > mtu} and comments
     * that the client must negotiate {@code MTU >= MAX_FRAME_SIZE + 3}
     * ({@code src/helpers/rp2040/SerialBLEInterface.cpp} lines 122–125).
     */
    public static final int MIN_MTU_FOR_MAX_FRAME = MAX_FRAME_SIZE + ATT_OVERHEAD;
    /** Requested in {@code docs/companion_protocol.md} (BLE MTU section). The peer may grant less. */
    public static final int REQUEST_MTU = 512;
    /** ESP32 {@code BLE_WRITE_MIN_INTERVAL} in {@code SerialBLEInterface.cpp}. */
    public static final int WRITE_GAP_MS = 60;
    /** Companion document command-response section. Not a firmware constant. */
    public static final int COMMAND_TIMEOUT_MS = 5_000;
    /** Local bound. Device info reports the real slot count; this walk will not follow a huge value. */
    public static final int CHANNEL_FETCH_CAP = 40;
    public static final int WRITE_QUEUE_CAP = 32;
    public static final int TRACE_CAP = 64;

    /**
     * Nordic UART UUIDs written in the ESP32 and RP2040 companion sources and in
     * {@code docs/companion_protocol.md}. The nRF52 companion uses Adafruit
     * {@code BLEUart} without repeating the literals in that file.
     */
    public static final String SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
    public static final String RX_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
    public static final String TX_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";
    public static final String CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb";

    private RadioLimits() {}

    /**
     * What this bridge will do with one companion frame before any byte is written.
     * Oversize group data is refused here. The firmware check that matches that
     * refusal is {@code payload_len > MAX_GROUP_DATA_LENGTH} returning
     * {@code ERR_CODE_ILLEGAL_ARG} ({@code MyMesh.cpp} lines 1230–1232). The
     * companion document in the same commit still says 166 and 167 come back as
     * {@code ERR_CODE_TABLE_FULL} and cites line 1265. The source is the authority.
     */
    public static String decision(byte[] frame, int mtu) {
        if (frame == null || frame.length == 0 || frame.length > MAX_FRAME_SIZE) {
            return "reject";
        }
        if (mtu <= 0 || frame.length + ATT_OVERHEAD > mtu) {
            return "mtu";
        }
        if ((frame[0] & 0xFF) == CMD_SEND_CHANNEL_DATA) {
            int payload = groupPayloadLength(frame);
            if (payload < 0 || payload > MAX_GROUP_DATA_LENGTH) {
                return "illegal_arg";
            }
        }
        return "ok";
    }

    /** Flood {@code CMD_SEND_CHANNEL_DATA} payload size, or -1 when this bridge will not send the frame. */
    public static int groupPayloadLength(byte[] frame) {
        if (frame == null || frame.length < 5 || (frame[2] & 0xFF) != 0xFF) {
            return -1;
        }
        return frame.length - 5;
    }

    /** True when one offline-queue drain returned at least the default capacity. */
    public static boolean backlogMayHaveOverflowed(int framesFromOneDrain) {
        return framesFromOneDrain >= DEFAULT_OFFLINE_QUEUE;
    }
}
