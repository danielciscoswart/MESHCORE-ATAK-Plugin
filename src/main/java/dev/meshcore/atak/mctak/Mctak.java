package dev.meshcore.atak.mctak;

import dev.meshcore.atak.util.Bytes;

import java.util.Arrays;

/**
 * MCTAK v0.1 datagrams. Layout follows the published normative description
 * carried inside MeshCore channel datagrams. This file is an independent
 * implementation; it does not copy the reference Java sources.
 */
public final class Mctak {
    public static final int MAX_DATAGRAM = 167;
    /** MeshCore radio-side group data limit. 166 and 167 cannot be transmitted. */
    public static final int RADIO_MAX_PAYLOAD = 165;
    public static final int HDR_LEN = 10;
    public static final int BODY_MAX = MAX_DATAGRAM - HDR_LEN;
    public static final int FRAG_SUBHDR = 8;
    public static final int SYMBOL_LEN = MAX_DATAGRAM - HDR_LEN - FRAG_SUBHDR;
    public static final int OBJ_MAX = 32768;
    public static final int MAGIC0 = 0x54;
    public static final int MAGIC1 = 0x4B;
    public static final int DATA_TYPE_DEV = 0xFFFF;
    public static final int PLI_STALE_S = 600;
    public static final int CLOCK_SANE_S = 86400;

    public static final int KIND_PLI = 0x01;
    public static final int KIND_GEOCHAT = 0x02;
    public static final int KIND_RECEIPT = 0x03;
    public static final int KIND_FRAG = 0x04;
    public static final int KIND_XFER = 0x05;
    public static final int KIND_IDENT = 0x06;
    public static final int KIND_WHO = 0x07;

    public static final int SCOPE_ALL = 0;
    public static final int SCOPE_DIRECT = 1;
    public static final int SCOPE_ROOM = 2;

    public static final int CONTENT_COT = 0;
    public static final int CONTENT_VOICE = 1;
    public static final int CONTENT_FILE = 2;
    public static final int CONTENT_CHAT_OVERFLOW = 3;

    private Mctak() {}

    public static boolean radioWillAccept(int datagramLength) {
        return datagramLength >= HDR_LEN && datagramLength <= RADIO_MAX_PAYLOAD;
    }

    public static final class Header {
        public final int version;
        public final boolean enc;
        public final int kind;
        public final int sender;
        public final int seq;

        public Header(int version, boolean enc, int kind, int sender, int seq) {
            this.version = version;
            this.enc = enc;
            this.kind = kind;
            this.sender = sender;
            this.seq = seq & 0xFFFF;
        }

        public byte[] encode() {
            byte[] out = new byte[HDR_LEN];
            out[0] = (byte) MAGIC0;
            out[1] = (byte) MAGIC1;
            int flags = (version & 0x0F) << 4;
            if (enc) {
                flags |= 0x01;
            }
            out[2] = (byte) flags;
            out[3] = (byte) kind;
            Bytes.putRaw4(out, 4, sender);
            Bytes.putU16le(out, 8, seq);
            return out;
        }

        public static String check(byte[] raw) {
            if (raw == null || raw.length < HDR_LEN) {
                return "short";
            }
            if (Bytes.u8(raw, 0) != MAGIC0 || Bytes.u8(raw, 1) != MAGIC1) {
                return "magic";
            }
            int version = Bytes.u8(raw, 2) >>> 4;
            if (version > 0) {
                return "version";
            }
            int kind = Bytes.u8(raw, 3);
            if (kind == 0x00 || kind >= 0xF0) {
                return "kind";
            }
            return null;
        }

        public static Header decode(byte[] raw) {
            int flags = Bytes.u8(raw, 2);
            return new Header(flags >>> 4, (flags & 0x01) != 0, Bytes.u8(raw, 3),
                    Bytes.raw4(raw, 4), Bytes.u16le(raw, 8));
        }
    }

    public static byte[] frame(Header header, byte[] body) {
        byte[] h = header.encode();
        byte[] out = Arrays.copyOf(h, h.length + body.length);
        System.arraycopy(body, 0, out, h.length, body.length);
        return out;
    }

    public static final class Pli {
        public final long ts;
        public final int latE7;
        public final int lonE7;
        public final int altRaw;
        public final int courseRaw;
        public final int speedRaw;
        public final int battery;
        public final int teamRole;

        public Pli(long ts, int latE7, int lonE7, int altRaw, int courseRaw, int speedRaw,
                   int battery, int teamRole) {
            this.ts = ts & 0xFFFFFFFFL;
            this.latE7 = latE7;
            this.lonE7 = lonE7;
            this.altRaw = altRaw & 0xFFFF;
            this.courseRaw = courseRaw & 0xFF;
            this.speedRaw = speedRaw & 0xFF;
            this.battery = battery & 0xFF;
            this.teamRole = teamRole & 0xFF;
        }

        public static int altRaw(Double haeMeters) {
            if (haeMeters == null || haeMeters < -1000 || haeMeters > 64534) {
                return 0xFFFF;
            }
            return (int) Math.round(haeMeters + 1000.0);
        }

        public static int courseRaw(Double degrees) {
            if (degrees == null || degrees < 0 || degrees > 358) {
                return 0xFF;
            }
            return (int) Math.round(degrees / 2.0);
        }

        public static int speedRaw(Double metersPerSecond) {
            if (metersPerSecond == null || metersPerSecond < 0) {
                return 0xFF;
            }
            if (metersPerSecond >= 127.0) {
                return 254;
            }
            return (int) Math.round(metersPerSecond * 2.0);
        }

        public Double haeMeters() {
            return altRaw == 0xFFFF ? null : altRaw - 1000.0;
        }

        public Double courseDegrees() {
            return courseRaw == 0xFF || courseRaw > 179 ? null : courseRaw * 2.0;
        }

        public Double speedMps() {
            if (speedRaw == 0xFF) {
                return null;
            }
            if (speedRaw == 254) {
                return 127.0;
            }
            return speedRaw / 2.0;
        }

        public byte[] body() {
            byte[] b = new byte[18];
            Bytes.putU32le(b, 0, ts);
            Bytes.putU32le(b, 4, latE7);
            Bytes.putU32le(b, 8, lonE7);
            Bytes.putU16le(b, 12, altRaw);
            b[14] = (byte) courseRaw;
            b[15] = (byte) speedRaw;
            b[16] = (byte) battery;
            b[17] = (byte) teamRole;
            return b;
        }

        public static Pli decode(byte[] raw, int off, int len) {
            if (len < 18) {
                return null;
            }
            return new Pli(Bytes.u32le(raw, off), Bytes.i32le(raw, off + 4), Bytes.i32le(raw, off + 8),
                    Bytes.u16le(raw, off + 12), Bytes.u8(raw, off + 14), Bytes.u8(raw, off + 15),
                    Bytes.u8(raw, off + 16), Bytes.u8(raw, off + 17));
        }
    }

    public static final class GeoChat {
        public final long ts;
        public final int msgId;
        public final int scope;
        public final int dest;
        public final String room;
        public final String text;

        public GeoChat(long ts, int msgId, int scope, int dest, String room, String text) {
            this.ts = ts & 0xFFFFFFFFL;
            this.msgId = msgId;
            this.scope = scope;
            this.dest = dest;
            this.room = room == null ? "" : room;
            this.text = text == null ? "" : text;
        }

        public byte[] body() {
            byte[] roomBytes = scope == SCOPE_ROOM ? Bytes.utf8Clamp(room, 32) : new byte[0];
            byte[] textBytes = Bytes.utf8Clamp(text, 255);
            int n = 4 + 4 + 1 + textBytes.length + 1;
            if (scope == SCOPE_DIRECT) {
                n += 4;
            } else if (scope == SCOPE_ROOM) {
                n += 1 + roomBytes.length;
            }
            byte[] b = new byte[n];
            int i = 0;
            Bytes.putU32le(b, i, ts);
            i += 4;
            Bytes.putU32le(b, i, msgId);
            i += 4;
            b[i++] = (byte) scope;
            if (scope == SCOPE_DIRECT) {
                Bytes.putRaw4(b, i, dest);
                i += 4;
            } else if (scope == SCOPE_ROOM) {
                b[i++] = (byte) roomBytes.length;
                System.arraycopy(roomBytes, 0, b, i, roomBytes.length);
                i += roomBytes.length;
            }
            b[i++] = (byte) textBytes.length;
            System.arraycopy(textBytes, 0, b, i, textBytes.length);
            return b;
        }

        public static GeoChat decode(byte[] raw, int off, int len) {
            if (len < 10) {
                return null;
            }
            int i = off;
            int end = off + len;
            long ts = Bytes.u32le(raw, i);
            i += 4;
            int msgId = (int) Bytes.u32le(raw, i);
            i += 4;
            int scope = Bytes.u8(raw, i++);
            int dest = 0;
            String room = "";
            if (scope == SCOPE_DIRECT) {
                if (i + 4 > end) {
                    return null;
                }
                dest = Bytes.raw4(raw, i);
                i += 4;
            } else if (scope == SCOPE_ROOM) {
                if (i >= end) {
                    return null;
                }
                int roomLen = Bytes.u8(raw, i++);
                if (roomLen > 32 || i + roomLen > end) {
                    return null;
                }
                room = Bytes.utf8(raw, i, roomLen);
                i += roomLen;
            } else if (scope != SCOPE_ALL) {
                return null;
            }
            if (i >= end) {
                return null;
            }
            int textLen = Bytes.u8(raw, i++);
            if (i + textLen > end) {
                return null;
            }
            String text = Bytes.utf8(raw, i, textLen);
            return new GeoChat(ts, msgId, scope, dest, room, text);
        }
    }

    public static final class Receipt {
        public final int rtype;
        public final int origSender;
        public final int msgId;

        public Receipt(int rtype, int origSender, int msgId) {
            this.rtype = rtype;
            this.origSender = origSender;
            this.msgId = msgId;
        }

        public byte[] body() {
            byte[] b = new byte[9];
            b[0] = (byte) rtype;
            Bytes.putRaw4(b, 1, origSender);
            Bytes.putU32le(b, 5, msgId);
            return b;
        }

        public static Receipt decode(byte[] raw, int off, int len) {
            if (len < 9) {
                return null;
            }
            int rtype = Bytes.u8(raw, off);
            if (rtype != 1 && rtype != 2) {
                return null;
            }
            return new Receipt(rtype, Bytes.raw4(raw, off + 1), (int) Bytes.u32le(raw, off + 5));
        }
    }

    public static final class Ident {
        public final long ts;
        public final int caps;
        public final int appVer;
        public final int teamRole;
        public final String callsign;
        public final String atakUid;

        public Ident(long ts, int caps, int appVer, int teamRole, String callsign, String atakUid) {
            this.ts = ts & 0xFFFFFFFFL;
            this.caps = caps & 0xFFFF;
            this.appVer = appVer & 0xFF;
            this.teamRole = teamRole & 0xFF;
            this.callsign = callsign == null ? "" : callsign;
            this.atakUid = atakUid == null ? "" : atakUid;
        }

        public byte[] body() {
            byte[] cs = Bytes.utf8Clamp(callsign, 16);
            byte[] uid = Bytes.utf8Clamp(atakUid, 48);
            byte[] b = new byte[10 + cs.length + uid.length];
            Bytes.putU32le(b, 0, ts);
            Bytes.putU16le(b, 4, caps);
            b[6] = (byte) appVer;
            b[7] = (byte) teamRole;
            b[8] = (byte) cs.length;
            System.arraycopy(cs, 0, b, 9, cs.length);
            b[9 + cs.length] = (byte) uid.length;
            System.arraycopy(uid, 0, b, 10 + cs.length, uid.length);
            return b;
        }

        public static Ident decode(byte[] raw, int off, int len) {
            if (len < 10) {
                return null;
            }
            int i = off;
            int end = off + len;
            long ts = Bytes.u32le(raw, i);
            i += 4;
            int caps = Bytes.u16le(raw, i);
            i += 2;
            int appVer = Bytes.u8(raw, i++);
            int teamRole = Bytes.u8(raw, i++);
            int csLen = Bytes.u8(raw, i++);
            if (csLen > 16 || i + csLen >= end) {
                return null;
            }
            String callsign = Bytes.utf8(raw, i, csLen);
            i += csLen;
            int uidLen = Bytes.u8(raw, i++);
            if (uidLen > 48 || i + uidLen > end) {
                return null;
            }
            String uid = Bytes.utf8(raw, i, uidLen);
            return new Ident(ts, caps, appVer, teamRole, callsign, uid);
        }
    }

    public static final class Who {
        public final int target;

        public Who(int target) {
            this.target = target;
        }

        public byte[] body() {
            byte[] b = new byte[4];
            Bytes.putRaw4(b, 0, target);
            return b;
        }

        public static Who decode(byte[] raw, int off, int len) {
            if (len < 4) {
                return null;
            }
            return new Who(Bytes.raw4(raw, off));
        }
    }

    public static final class XferStatus {
        public final int origSender;
        public final int transferId;
        public final long crcEcho;
        public final boolean complete;
        public final int received;

        public XferStatus(int origSender, int transferId, long crcEcho, boolean complete, int received) {
            this.origSender = origSender;
            this.transferId = transferId & 0xFFFF;
            this.crcEcho = crcEcho & 0xFFFFFFFFL;
            this.complete = complete;
            this.received = received & 0xFFFF;
        }

        public byte[] body() {
            byte[] b = new byte[13];
            Bytes.putRaw4(b, 0, origSender);
            Bytes.putU16le(b, 4, transferId);
            Bytes.putU32le(b, 6, crcEcho);
            b[10] = (byte) (complete ? 1 : 0);
            Bytes.putU16le(b, 11, received);
            return b;
        }

        public static XferStatus decode(byte[] raw, int off, int len) {
            if (len < 13) {
                return null;
            }
            return new XferStatus(Bytes.raw4(raw, off), Bytes.u16le(raw, off + 4),
                    Bytes.u32le(raw, off + 6), (Bytes.u8(raw, off + 10) & 0x01) != 0,
                    Bytes.u16le(raw, off + 11));
        }
    }

    public static final class Frag {
        public final int transferId;
        public final int content;
        public final boolean objEnc;
        public final int objLen;
        public final int k;
        public final int symIdx;
        public final byte[] symbol;

        public Frag(int transferId, int content, boolean objEnc, int objLen, int k, int symIdx, byte[] symbol) {
            this.transferId = transferId & 0xFFFF;
            this.content = content & 0x0F;
            this.objEnc = objEnc;
            this.objLen = objLen & 0xFFFF;
            this.k = k & 0xFF;
            this.symIdx = symIdx & 0xFFFF;
            this.symbol = Arrays.copyOf(symbol, SYMBOL_LEN);
        }

        public byte[] body() {
            byte[] b = new byte[FRAG_SUBHDR + SYMBOL_LEN];
            Bytes.putU16le(b, 0, transferId);
            int contentByte = content & 0x0F;
            if (objEnc) {
                contentByte |= 0x10;
            }
            b[2] = (byte) contentByte;
            Bytes.putU16le(b, 3, objLen);
            b[5] = (byte) k;
            Bytes.putU16le(b, 6, symIdx);
            System.arraycopy(symbol, 0, b, 8, SYMBOL_LEN);
            return b;
        }
    }

    public static final class Result {
        public final Header header;
        public final String drop;
        public final Pli pli;
        public final GeoChat chat;
        public final Receipt receipt;
        public final Ident ident;
        public final Who who;
        public final XferStatus xfer;
        public final Frag frag;

        private Result(Header header, String drop, Pli pli, GeoChat chat, Receipt receipt,
                       Ident ident, Who who, XferStatus xfer, Frag frag) {
            this.header = header;
            this.drop = drop;
            this.pli = pli;
            this.chat = chat;
            this.receipt = receipt;
            this.ident = ident;
            this.who = who;
            this.xfer = xfer;
            this.frag = frag;
        }

        public static Result drop(String reason) {
            return new Result(null, reason, null, null, null, null, null, null, null);
        }

        public boolean ok() {
            return drop == null;
        }
    }

    public static Result parse(byte[] raw) {
        String headerDrop = Header.check(raw);
        if (headerDrop != null) {
            return Result.drop(headerDrop);
        }
        Header header = Header.decode(raw);
        if (header.enc) {
            return new Result(header, "enc_undecryptable", null, null, null, null, null, null, null);
        }
        int bodyLen = raw.length - HDR_LEN;
        if (header.kind == KIND_FRAG) {
            if (raw.length != MAX_DATAGRAM) {
                return Result.drop("frag_length");
            }
            int contentByte = Bytes.u8(raw, HDR_LEN + 2);
            Frag frag = new Frag(Bytes.u16le(raw, HDR_LEN), contentByte & 0x0F,
                    (contentByte & 0x10) != 0, Bytes.u16le(raw, HDR_LEN + 3),
                    Bytes.u8(raw, HDR_LEN + 5), Bytes.u16le(raw, HDR_LEN + 6),
                    Arrays.copyOfRange(raw, HDR_LEN + 8, raw.length));
            String why = validateFrag(header, frag);
            if (why != null) {
                return Result.drop(why);
            }
            return new Result(header, null, null, null, null, null, null, null, frag);
        }
        switch (header.kind) {
            case KIND_PLI: {
                Pli pli = Pli.decode(raw, HDR_LEN, bodyLen);
                return pli == null ? Result.drop("malformed")
                        : new Result(header, null, pli, null, null, null, null, null, null);
            }
            case KIND_GEOCHAT: {
                if (bodyLen < 9) {
                    return Result.drop("malformed");
                }
                int scope = Bytes.u8(raw, HDR_LEN + 8);
                if (scope > SCOPE_ROOM) {
                    return Result.drop("scope");
                }
                GeoChat chat = GeoChat.decode(raw, HDR_LEN, bodyLen);
                return chat == null ? Result.drop("malformed")
                        : new Result(header, null, null, chat, null, null, null, null, null);
            }
            case KIND_RECEIPT: {
                Receipt receipt = Receipt.decode(raw, HDR_LEN, bodyLen);
                return receipt == null ? Result.drop("rtype")
                        : new Result(header, null, null, null, receipt, null, null, null, null);
            }
            case KIND_XFER: {
                XferStatus xfer = XferStatus.decode(raw, HDR_LEN, bodyLen);
                return xfer == null ? Result.drop("malformed")
                        : new Result(header, null, null, null, null, null, null, xfer, null);
            }
            case KIND_IDENT: {
                Ident ident = Ident.decode(raw, HDR_LEN, bodyLen);
                return ident == null ? Result.drop("malformed")
                        : new Result(header, null, null, null, null, ident, null, null, null);
            }
            case KIND_WHO: {
                Who who = Who.decode(raw, HDR_LEN, bodyLen);
                return who == null ? Result.drop("malformed")
                        : new Result(header, null, null, null, null, null, who, null, null);
            }
            default:
                return new Result(header, "unknown_kind", null, null, null, null, null, null, null);
        }
    }

    public static String validateFrag(Header header, Frag frag) {
        if (header.enc) {
            return "frag_enc";
        }
        if (frag.objLen < 1 || frag.objLen > OBJ_MAX) {
            return "obj_len";
        }
        int expectK = (frag.objLen + SYMBOL_LEN - 1) / SYMBOL_LEN;
        if (frag.k != expectK || frag.k < 1 || frag.k > 220) {
            return "k_mismatch";
        }
        if (frag.content > CONTENT_CHAT_OVERFLOW) {
            return "content";
        }
        if (frag.symIdx >= Fountain.symIdxLimit(frag.k)) {
            return "sym_idx";
        }
        return null;
    }

    public static long renderTime(long ts, long nowSeconds) {
        long delta = nowSeconds - ts;
        if (delta < 0) {
            delta = -delta;
        }
        return delta <= CLOCK_SANE_S ? ts : nowSeconds;
    }

    public static String teamName(int teamRole) {
        switch ((teamRole >>> 4) & 0x0F) {
            case 0: return "White";
            case 1: return "Yellow";
            case 2: return "Orange";
            case 3: return "Magenta";
            case 4: return "Red";
            case 5: return "Maroon";
            case 6: return "Purple";
            case 7: return "Dark Blue";
            case 8: return "Blue";
            case 9: return "Cyan";
            case 10: return "Teal";
            case 11: return "Green";
            case 12: return "Dark Green";
            case 13: return "Brown";
            default: return "Unknown";
        }
    }

    /** Role labels from the MCTAK team/role table. Labels only; no other behavior. */
    public static String roleName(int teamRole) {
        switch (teamRole & 0x0F) {
            case 0: return "Team Member";
            case 1: return "Team Lead";
            case 2: return "HQ";
            case 3: return "Sniper";
            case 4: return "Medic";
            case 5: return "Forward Observer";
            case 6: return "RTO";
            case 7: return "K9";
            default: return "Unknown";
        }
    }

    public static String derivedUid(int sender) {
        return "MCTAK-" + Bytes.hex8(sender);
    }

    public static String messageUid(int sender, int msgId) {
        return "MCTAK." + Bytes.hex8(sender) + "." + Bytes.hex8(msgId);
    }
}
