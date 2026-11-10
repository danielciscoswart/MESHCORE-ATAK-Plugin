package dev.meshcore.atak.companion;

import dev.meshcore.atak.util.Bytes;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * MeshCore companion frames used by this plugin. Channel secrets are classified
 * and then wiped; they are not retained and must not be logged.
 */
public final class Companion {
    public static final int CMD_APP_START = 0x01;
    /**
     * {@code CMD_SEND_CHANNEL_TXT_MSG}. Built only by {@link #sendChannelText} and
     * only for the opt-in "MeshCore channel text" mode of the chat button (E9).
     */
    public static final int CMD_SEND_CHANNEL_TXT = 0x03;
    public static final int CMD_SYNC_NEXT = 0x0A;
    /** Official {@code CMD_DEVICE_QUERY}. The following byte is {@code app_target_ver}. */
    public static final int CMD_DEVICE_QUERY = 0x16;
    public static final int CMD_GET_CHANNEL = 0x1F;
    /** Read-only. {@code CMD_GET_STATS}; the second byte is the stats type (0 = core). */
    public static final int CMD_GET_STATS = 0x38;
    public static final int CMD_SEND_CHANNEL_DATA = 0x3E;

    /** {@code STATS_TYPE_CORE}: battery, uptime, error flags, queue length. */
    public static final int STATS_TYPE_CORE = 0;

    public static final int PACKET_OK = 0x00;
    public static final int PACKET_ERROR = 0x01;
    public static final int PACKET_SELF_INFO = 0x05;
    /** Reply to {@code CMD_SYNC_NEXT_MESSAGE} when the radio's queue is empty. */
    public static final int PACKET_NO_MORE_MESSAGES = 0x0A;
    /** {@code RESP_CODE_STATS}. */
    public static final int PACKET_STATS = 0x18;
    public static final int PACKET_DEVICE_INFO = 0x0D;
    public static final int PACKET_CHANNEL_INFO = 0x12;
    public static final int PACKET_CHANNEL_DATA = 0x1B;
    /** Native companion text replies. Not MCTAK, and not turned into GeoChat here. */
    public static final int PACKET_CONTACT_MSG = 0x07;
    public static final int PACKET_CHANNEL_MSG = 0x08;
    public static final int PACKET_CONTACT_MSG_V3 = 0x10;
    public static final int PACKET_CHANNEL_MSG_V3 = 0x11;
    public static final int PACKET_MESSAGES_WAITING = 0x83;

    /** Firmware PUBLIC_GROUP_PSK, base64 "izOH6cXN6mrJ5e26oRXNcg==". Well-known, not a private key. */
    public static final byte[] PUBLIC_CHANNEL_SECRET =
            Base64.getDecoder().decode("izOH6cXN6mrJ5e26oRXNcg==");

    private Companion() {}

    public static byte[] appStart(String appName) {
        byte[] name = appName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] frame = new byte[8 + name.length];
        frame[0] = CMD_APP_START;
        System.arraycopy(name, 0, frame, 8, name.length);
        return frame;
    }

    public static byte[] getChannel(int index) {
        return new byte[] {CMD_GET_CHANNEL, (byte) index};
    }

    public static byte[] syncNext() {
        return new byte[] {CMD_SYNC_NEXT};
    }

    /** {@code TXT_TYPE_PLAIN}. The only text type the firmware accepts on this path. */
    public static final int TXT_TYPE_PLAIN = 0;
    /**
     * Firmware {@code MAX_TEXT_LEN = 10 * CIPHER_BLOCK_SIZE = 160} ({@code BaseChatMesh.h}
     * line 8). {@code BaseChatMesh::sendGroupMessage} writes {@code "<node_name>: <text>"}
     * and silently truncates when {@code text_len + prefix_len > MAX_TEXT_LEN}, where
     * {@code prefix_len = strlen(node_name) + 2}.
     */
    public static final int MAX_TEXT_LEN = 160;
    /**
     * Send cap for every transmission (Claude, v0.2 section 4.3): 176 - 3 - 9 = 164 bytes.
     * For native text the group payload is {@code 5 + prefix_len + text_len}, so the
     * cap leaves {@code 164 - 5 - prefix_len} bytes of text.
     */
    public static final int SAFE_PAYLOAD = 164;
    /** Group-text payload bytes before the node-name prefix: timestamp u32 + txt_type. */
    public static final int GROUP_TEXT_HEADER = 5;

    /**
     * Most UTF-8 bytes of text one native channel message may carry, given the length
     * in bytes of the radio's node name: the smaller of the firmware's own limit
     * ({@code 160 - (name + 2)}) and the 164-byte payload cap ({@code 164 - 5 - (name + 2)}).
     * The firmware truncates silently; the plugin refuses instead.
     */
    public static int maxNativeTextBytes(int nodeNameBytes) {
        int prefix = Math.max(0, nodeNameBytes) + 2;
        int byFirmware = MAX_TEXT_LEN - prefix;
        int byPayloadCap = SAFE_PAYLOAD - GROUP_TEXT_HEADER - prefix;
        return Math.max(0, Math.min(byFirmware, byPayloadCap));
    }

    /**
     * {@code CMD_SEND_CHANNEL_TXT_MSG}: {@code {3, TXT_TYPE_PLAIN, channel_idx, timestamp u32le,
     * text}} (firmware {@code MyMesh.cpp} lines 1178-1197). Reply: OK (0) means the radio took
     * it, not that anyone received it; NOT_FOUND (2) means a bad channel slot (or no packet buffer).
     */
    public static byte[] sendChannelText(int channelIdx, long timestampSecs, byte[] textUtf8) {
        byte[] frame = new byte[7 + textUtf8.length];
        frame[0] = (byte) CMD_SEND_CHANNEL_TXT;
        frame[1] = (byte) TXT_TYPE_PLAIN;
        frame[2] = (byte) channelIdx;
        Bytes.putU32le(frame, 3, timestampSecs);
        System.arraycopy(textUtf8, 0, frame, 7, textUtf8.length);
        return frame;
    }

    /** Core stats request: {@code {56, 0}}. Read-only on the radio. */
    public static byte[] getStatsCore() {
        return new byte[] {(byte) CMD_GET_STATS, (byte) STATS_TYPE_CORE};
    }

    /**
     * Core stats reply, official layout {@code {24, 0, battery_mv u16, uptime_s u32,
     * err_flags u16, queue_len u8}} (firmware {@code MyMesh.cpp}, CMD_GET_STATS handler).
     * Little-endian. Returns null for any other frame.
     */
    public static final class Stats {
        public final int batteryMv;
        public final long uptimeSecs;
        public final int errFlags;
        public final int queueLen;

        Stats(int batteryMv, long uptimeSecs, int errFlags, int queueLen) {
            this.batteryMv = batteryMv;
            this.uptimeSecs = uptimeSecs;
            this.errFlags = errFlags;
            this.queueLen = queueLen;
        }
    }

    public static Stats parseStatsCore(byte[] frame) {
        if (frame == null || frame.length < 11 || (frame[0] & 0xFF) != PACKET_STATS
                || (frame[1] & 0xFF) != STATS_TYPE_CORE) {
            return null;
        }
        int battery = (frame[2] & 0xFF) | ((frame[3] & 0xFF) << 8);
        long uptime = Bytes.u32le(frame, 4);
        int flags = (frame[8] & 0xFF) | ((frame[9] & 0xFF) << 8);
        return new Stats(battery, uptime, flags, frame[10] & 0xFF);
    }

    /** Test helper: build a core stats reply the way the firmware does. */
    public static byte[] statsCore(int batteryMv, long uptimeSecs, int errFlags, int queueLen) {
        byte[] frame = new byte[11];
        frame[0] = PACKET_STATS;
        frame[1] = STATS_TYPE_CORE;
        frame[2] = (byte) batteryMv;
        frame[3] = (byte) (batteryMv >>> 8);
        Bytes.putU32le(frame, 4, uptimeSecs);
        frame[8] = (byte) errFlags;
        frame[9] = (byte) (errFlags >>> 8);
        frame[10] = (byte) queueLen;
        return frame;
    }

    /**
     * Device query as documented and as implemented. Firmware requires at least
     * two bytes and stores byte 1 as the protocol version the app understands.
     * The published example is {@code 16 03}.
     */
    public static byte[] deviceQuery() {
        return new byte[] {(byte) CMD_DEVICE_QUERY, 0x03};
    }

    public static boolean isNativeText(int packetType) {
        return packetType == PACKET_CONTACT_MSG || packetType == PACKET_CHANNEL_MSG
                || packetType == PACKET_CONTACT_MSG_V3 || packetType == PACKET_CHANNEL_MSG_V3;
    }

    /**
     * Native MeshCore text pulled from the radio's queue by CMD_SYNC_NEXT. Layouts
     * (firmware {@code MyMesh.cpp} {@code queueMessage} and {@code onChannelMessageRecv}):
     * <pre>
     * 16 {16, snr, 0, 0, pubkey[6], path_len, txt_type, ts u32le, [signer[4] if txt_type 2], text}
     *  7 same as 16 without the three bytes after the code
     * 17 {17, snr, 0, 0, channel_idx, path_len, txt_type, ts u32le, text}
     *  8 same as 17 without the three bytes after the code
     * </pre>
     * The text is UTF-8 and the firmware truncates by byte count, so it is decoded
     * leniently. It is never trusted: every use of it is labelled unverified.
     */
    public static final class NativeText {
        public final boolean direct;
        /** Channel slot for channel text, or -1 for a direct message. */
        public final int channel;
        /** First six public-key bytes of a direct sender, hex, or "". */
        public final String senderPrefixHex;
        public final int pathLen;
        public final int txtType;
        public final long senderTimestamp;
        public final String text;

        NativeText(boolean direct, int channel, String senderPrefixHex, int pathLen, int txtType,
                   long senderTimestamp, String text) {
            this.direct = direct;
            this.channel = channel;
            this.senderPrefixHex = senderPrefixHex;
            this.pathLen = pathLen;
            this.txtType = txtType;
            this.senderTimestamp = senderTimestamp;
            this.text = text;
        }
    }

    /** Parse frames 7, 8, 16 and 17. Returns null for any other or a short frame. */
    public static NativeText parseNativeText(byte[] frame) {
        if (frame == null || frame.length < 1) {
            return null;
        }
        int code = frame[0] & 0xFF;
        if (!isNativeText(code)) {
            return null;
        }
        boolean v3 = code == PACKET_CONTACT_MSG_V3 || code == PACKET_CHANNEL_MSG_V3;
        boolean direct = code == PACKET_CONTACT_MSG || code == PACKET_CONTACT_MSG_V3;
        int i = v3 ? 4 : 1;
        int idBytes = direct ? 6 : 1;
        // id, path_len, txt_type, timestamp
        if (frame.length < i + idBytes + 1 + 1 + 4) {
            return null;
        }
        int channel = -1;
        String prefix = "";
        if (direct) {
            prefix = Bytes.hex(Arrays.copyOfRange(frame, i, i + 6));
        } else {
            channel = frame[i] & 0xFF;
        }
        i += idBytes;
        int pathLen = frame[i++] & 0xFF;
        int txtType = frame[i++] & 0xFF;
        long ts = Bytes.u32le(frame, i);
        i += 4;
        if (direct && txtType == 2) {
            // TXT_TYPE_SIGNED_PLAIN carries a 4-byte signer prefix before the text.
            if (frame.length < i + 4) {
                return null;
            }
            i += 4;
        }
        int end = frame.length;
        // The firmware null-terminates its text in some paths; stop at the first NUL.
        for (int k = i; k < end; k++) {
            if (frame[k] == 0) {
                end = k;
                break;
            }
        }
        String text = new String(frame, i, end - i, java.nio.charset.StandardCharsets.UTF_8);
        return new NativeText(direct, channel, prefix, pathLen, txtType, ts, text);
    }

    /** Test helper: a V3 channel text frame as the firmware queues it. */
    public static byte[] channelTextV3(int snrQuarterDb, int channel, int pathLen, long timestamp, String text) {
        byte[] body = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] frame = new byte[4 + 1 + 1 + 1 + 4 + body.length];
        frame[0] = PACKET_CHANNEL_MSG_V3;
        frame[1] = (byte) snrQuarterDb;
        frame[4] = (byte) channel;
        frame[5] = (byte) pathLen;
        frame[6] = 0;
        Bytes.putU32le(frame, 7, timestamp);
        System.arraycopy(body, 0, frame, 11, body.length);
        return frame;
    }

    public static byte[] sendChannelData(int channel, byte[] datagram) {
        if (datagram.length > dev.meshcore.atak.mctak.Mctak.RADIO_MAX_PAYLOAD) {
            throw new IllegalArgumentException("datagram above MeshCore group payload limit");
        }
        byte[] frame = new byte[3 + 2 + datagram.length];
        frame[0] = CMD_SEND_CHANNEL_DATA;
        frame[1] = (byte) channel;
        frame[2] = (byte) 0xFF;
        frame[3] = (byte) 0xFF;
        frame[4] = (byte) 0xFF;
        System.arraycopy(datagram, 0, frame, 5, datagram.length);
        return frame;
    }

    public static byte[] channelDataRecv(int snrQuarterDb, int channel, int pathLen, int dataType, byte[] payload) {
        byte[] frame = new byte[9 + payload.length];
        frame[0] = PACKET_CHANNEL_DATA;
        frame[1] = (byte) snrQuarterDb;
        frame[4] = (byte) channel;
        frame[5] = (byte) pathLen;
        frame[6] = (byte) dataType;
        frame[7] = (byte) (dataType >>> 8);
        frame[8] = (byte) payload.length;
        System.arraycopy(payload, 0, frame, 9, payload.length);
        return frame;
    }

    /** Turn our own flood send into the frame a peer radio would notify. */
    public static byte[] txDataToPeerRecv(byte[] tx, int snrQuarterDb) {
        if (tx == null || tx.length < 5 || (tx[0] & 0xFF) != CMD_SEND_CHANNEL_DATA) {
            return null;
        }
        if ((tx[2] & 0xFF) != 0xFF) {
            return null;
        }
        int channel = tx[1] & 0xFF;
        int dataType = (tx[3] & 0xFF) | ((tx[4] & 0xFF) << 8);
        byte[] payload = Arrays.copyOfRange(tx, 5, tx.length);
        return channelDataRecv(snrQuarterDb, channel, 0x01, dataType, payload);
    }

    public static final class DataRecv {
        public final double snrDb;
        public final int channel;
        public final int pathLen;
        public final int dataType;
        public final byte[] payload;

        DataRecv(double snrDb, int channel, int pathLen, int dataType, byte[] payload) {
            this.snrDb = snrDb;
            this.channel = channel;
            this.pathLen = pathLen;
            this.dataType = dataType;
            this.payload = payload;
        }
    }

    public static DataRecv parseChannelData(byte[] frame) {
        if (frame == null || frame.length < 9 || (frame[0] & 0xFF) != PACKET_CHANNEL_DATA) {
            return null;
        }
        int snr = frame[1];
        int dataLen = frame[8] & 0xFF;
        if (9 + dataLen > frame.length) {
            return null;
        }
        return new DataRecv(snr / 4.0, frame[4] & 0xFF, frame[5] & 0xFF,
                (frame[6] & 0xFF) | ((frame[7] & 0xFF) << 8),
                Arrays.copyOfRange(frame, 9, 9 + dataLen));
    }

    public static final class SelfInfo {
        public final byte[] publicKey;
        public final int sender;
        public final int freqKhz;
        public final int bwHz;
        public final int sf;
        public final int cr;
        public final String name;
        /** Length of the node name in bytes as the firmware sends it, without trailing NULs. */
        public final int nameBytes;

        SelfInfo(byte[] publicKey, int freqKhz, int bwHz, int sf, int cr, String name, int nameBytes) {
            this.publicKey = publicKey;
            this.sender = Bytes.raw4(publicKey, 0);
            this.freqKhz = freqKhz;
            this.bwHz = bwHz;
            this.sf = sf;
            this.cr = cr;
            this.name = name;
            this.nameBytes = nameBytes;
        }
    }

    public static SelfInfo parseSelfInfo(byte[] frame) {
        if (frame == null || frame.length < 58 || (frame[0] & 0xFF) != PACKET_SELF_INFO) {
            return null;
        }
        byte[] key = Arrays.copyOfRange(frame, 4, 36);
        int freqKhz = (int) Bytes.u32le(frame, 48);
        int bwHz = (int) Bytes.u32le(frame, 52);
        int sf = frame[56] & 0xFF;
        int cr = frame[57] & 0xFF;
        String name = frame.length > 58 ? Bytes.utf8(frame, 58, frame.length - 58).replace("\u0000", "").trim() : "";
        int rawName = frame.length - 58;
        while (rawName > 0 && frame[58 + rawName - 1] == 0) {
            rawName--;
        }
        return new SelfInfo(key, freqKhz, bwHz, sf, cr, name, rawName);
    }

    public static byte[] selfInfo(byte[] publicKey32, int freqKhz, int bwHz, int sf, int cr, String name) {
        byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] frame = new byte[58 + nameBytes.length];
        frame[0] = PACKET_SELF_INFO;
        frame[1] = 1;
        System.arraycopy(publicKey32, 0, frame, 4, 32);
        Bytes.putU32le(frame, 48, freqKhz);
        Bytes.putU32le(frame, 52, bwHz);
        frame[56] = (byte) sf;
        frame[57] = (byte) cr;
        System.arraycopy(nameBytes, 0, frame, 58, nameBytes.length);
        return frame;
    }

    /**
     * Channel view safe to retain. The 16-byte secret is not a field.
     * {@code publicChannel} is true for the firmware Public slot key or the name "Public".
     * {@code hashtag} is true when the name starts with '#'; that key is derivable from the name.
     */
    public static final class ChannelView {
        public final int index;
        public final String name;
        public final String fingerprint;
        public final boolean publicChannel;
        public final boolean hashtag;
        public final boolean zeroKey;
        public final boolean empty;

        ChannelView(int index, String name, String fingerprint, boolean publicChannel, boolean hashtag,
                    boolean zeroKey, boolean empty) {
            this.index = index;
            this.name = name;
            this.fingerprint = fingerprint;
            this.publicChannel = publicChannel;
            this.hashtag = hashtag;
            this.zeroKey = zeroKey;
            this.empty = empty;
        }

        public boolean unsafeForTeamTraffic() {
            return publicChannel || hashtag || zeroKey || empty;
        }

        /** Why this slot may not carry team traffic, or "" when it may. No secret in the text. */
        public String refusalReason() {
            if (empty) {
                return "empty slot";
            }
            if (publicChannel) {
                return "public channel";
            }
            if (hashtag) {
                return "hashtag channel, key is derived from the name";
            }
            if (zeroKey) {
                return "all-zero key";
            }
            return "";
        }

        @Override
        public String toString() {
            return "channel " + index + " name=" + name + " fp=" + fingerprint
                    + (publicChannel ? " public" : "") + (hashtag ? " hashtag" : "")
                    + (zeroKey ? " zero-key" : "");
        }
    }

    public static ChannelView parseChannelInfo(byte[] frame) {
        if (frame == null || frame.length < 50 || (frame[0] & 0xFF) != PACKET_CHANNEL_INFO) {
            return null;
        }
        int index = frame[1] & 0xFF;
        String name = cString(frame, 2, 32);
        byte[] secret = Arrays.copyOfRange(frame, 34, 50);
        boolean allZero = true;
        for (byte b : secret) {
            if (b != 0) {
                allZero = false;
                break;
            }
        }
        boolean wellKnown = Bytes.constantTimeEquals(secret, PUBLIC_CHANNEL_SECRET);
        boolean derivedHashtag = derivedHashtagKey(name, secret);
        String fingerprint = fingerprint(secret);
        Arrays.fill(secret, (byte) 0);
        boolean namedPublic = "Public".equals(name);
        return new ChannelView(index, name, fingerprint, wellKnown || namedPublic,
                name.startsWith("#") || derivedHashtag, allZero, name.isEmpty() && allZero);
    }

    /**
     * Official companion doc: a hashtag key is the first 16 bytes of SHA-256 of the
     * name, including the leading '#'. A slot whose secret matches that derivation
     * is public even when the stored name omitted the '#'.
     */
    private static boolean derivedHashtagKey(String name, byte[] secret) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (matchesSha256Prefix(secret, "#" + name)) {
            return true;
        }
        return name.startsWith("#") && matchesSha256Prefix(secret, name);
    }

    private static boolean matchesSha256Prefix(byte[] secret, String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] prefix = Arrays.copyOf(digest, 16);
            boolean same = Bytes.constantTimeEquals(secret, prefix);
            Arrays.fill(digest, (byte) 0);
            Arrays.fill(prefix, (byte) 0);
            return same;
        } catch (Exception ex) {
            return false;
        }
    }

    public static byte[] channelInfo(int index, String name, byte[] secret16) {
        byte[] frame = new byte[50];
        frame[0] = PACKET_CHANNEL_INFO;
        frame[1] = (byte) index;
        byte[] nameBytes = Bytes.utf8Clamp(name, 32);
        System.arraycopy(nameBytes, 0, frame, 2, nameBytes.length);
        System.arraycopy(secret16, 0, frame, 34, 16);
        return frame;
    }

    /**
     * Device-info parse. The BLE PIN bytes are skipped and not returned.
     */
    public static final class DeviceInfo {
        public final int firmwareCode;
        public final int maxChannels;
        public final String model;

        DeviceInfo(int firmwareCode, int maxChannels, String model) {
            this.firmwareCode = firmwareCode;
            this.maxChannels = maxChannels;
            this.model = model;
        }

        @Override
        public String toString() {
            return "fw=" + firmwareCode + " channels=" + maxChannels + " model=" + model;
        }
    }

    public static DeviceInfo parseDeviceInfo(byte[] frame) {
        if (frame == null || frame.length < 2 || (frame[0] & 0xFF) != PACKET_DEVICE_INFO) {
            return null;
        }
        int fw = frame[1] & 0xFF;
        if (fw < 3 || frame.length < 80) {
            return new DeviceInfo(fw, 0, "");
        }
        int maxChannels = frame[3] & 0xFF;
        String model = Bytes.utf8(frame, 20, 40).replace("\u0000", "").trim();
        return new DeviceInfo(fw, maxChannels, model);
    }

    private static String cString(byte[] raw, int off, int len) {
        int n = 0;
        while (n < len && raw[off + n] != 0) {
            n++;
        }
        return Bytes.utf8(raw, off, n);
    }

    private static String fingerprint(byte[] secret) {
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256").digest(secret);
            return Bytes.hex(Arrays.copyOf(dig, 4));
        } catch (Exception ex) {
            return "--------";
        }
    }
}
