package dev.meshcore.atak.util;

import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** Little-endian packing and UTF-8 helpers. No secret material is formatted here. */
public final class Bytes {
    private Bytes() {}

    public static int u8(byte[] b, int off) {
        return b[off] & 0xFF;
    }

    public static int u16le(byte[] b, int off) {
        return u8(b, off) | (u8(b, off + 1) << 8);
    }

    public static long u32le(byte[] b, int off) {
        return (u8(b, off) | ((long) u8(b, off + 1) << 8)
                | ((long) u8(b, off + 2) << 16) | ((long) u8(b, off + 3) << 24)) & 0xFFFFFFFFL;
    }

    public static int i32le(byte[] b, int off) {
        return (int) u32le(b, off);
    }

    /** Four wire-order bytes as a signed int. Not a little-endian integer. */
    public static int raw4(byte[] b, int off) {
        return (u8(b, off) << 24) | (u8(b, off + 1) << 16) | (u8(b, off + 2) << 8) | u8(b, off + 3);
    }

    public static void putU8(byte[] b, int off, int v) {
        b[off] = (byte) v;
    }

    public static void putU16le(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
    }

    public static void putU32le(byte[] b, int off, long v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    public static void putRaw4(byte[] b, int off, int wire) {
        b[off] = (byte) (wire >>> 24);
        b[off + 1] = (byte) (wire >>> 16);
        b[off + 2] = (byte) (wire >>> 8);
        b[off + 3] = (byte) wire;
    }

    public static String hex8(int wire) {
        return String.format(Locale.ROOT, "%08x", wire);
    }

    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte value : data) {
            sb.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
        }
        return sb.toString();
    }

    public static byte[] parseHex(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '#' ) {
                while (i < text.length() && text.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (Character.isWhitespace(c)) {
                continue;
            }
            sb.append(c);
        }
        String hex = sb.toString();
        if ((hex.length() & 1) != 0) {
            throw new IllegalArgumentException("odd hex length");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static byte[] utf8Clamp(String text, int maxBytes) {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        if (raw.length <= maxBytes) {
            return raw;
        }
        int n = maxBytes;
        while (n > 0 && (raw[n] & 0xC0) == 0x80) {
            n--;
        }
        return Arrays.copyOf(raw, n);
    }

    public static String utf8(byte[] raw, int off, int len) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            return decoder.decode(java.nio.ByteBuffer.wrap(raw, off, len)).toString();
        } catch (java.nio.charset.CharacterCodingException ex) {
            return "\uFFFD";
        }
    }

    public static void xor(byte[] into, byte[] other) {
        for (int i = 0; i < into.length; i++) {
            into[i] ^= other[i];
        }
    }

    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
