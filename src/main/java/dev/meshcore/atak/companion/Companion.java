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
    public static final int CMD_SEND_CHANNEL_TXT = 0x03;
    public static final int CMD_SYNC_NEXT = 0x0A;
    /** Official {@code CMD_DEVICE_QUERY}. The following byte is {@code app_target_ver}. */
    public static final int CMD_DEVICE_QUERY = 0x16;
    public static final int CMD_GET_CHANNEL = 0x1F;
    public static final int CMD_SEND_CHANNEL_DATA = 0x3E;

    public static final int PACKET_OK = 0x00;
    public static final int PACKET_ERROR = 0x01;
    public static final int PACKET_SELF_INFO = 0x05;
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

        SelfInfo(byte[] publicKey, int freqKhz, int bwHz, int sf, int cr, String name) {
            this.publicKey = publicKey;
            this.sender = Bytes.raw4(publicKey, 0);
            this.freqKhz = freqKhz;
            this.bwHz = bwHz;
            this.sf = sf;
            this.cr = cr;
            this.name = name;
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
        return new SelfInfo(key, freqKhz, bwHz, sf, cr, name);
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
