package dev.meshcore.atak.cot;

import dev.meshcore.atak.mctak.Mctak;

import java.time.Instant;

/** Minimal Cursor-on-Target events for PLI, GeoChat, receipts, and waypoints. */
public final class Cot {
    private Cot() {}

    public static String pli(String uid, Mctak.Pli pli, String callsign, long nowSeconds) {
        long time = Mctak.renderTime(pli.ts, nowSeconds);
        long stale = time + Mctak.PLI_STALE_S;
        Double hae = pli.haeMeters();
        Double course = pli.courseDegrees();
        Double speed = pli.speedMps();
        StringBuilder detail = new StringBuilder();
        detail.append("<contact callsign=\"").append(esc(callsign)).append("\"/>");
        detail.append("<__group name=\"").append(esc(Mctak.teamName(pli.teamRole)))
                .append("\" role=\"").append(esc(Mctak.roleName(pli.teamRole))).append("\"/>");
        if (pli.battery != 0xFF) {
            detail.append("<status battery=\"").append(pli.battery).append("\"/>");
        }
        if (course != null || speed != null) {
            detail.append("<track");
            if (course != null) {
                detail.append(" course=\"").append(course).append("\"");
            }
            if (speed != null) {
                detail.append(" speed=\"").append(speed).append("\"");
            }
            detail.append("/>");
        }
        detail.append("<__meshcore unverified=\"true\"/>");
        return event(uid, "a-f-G-U-C", "m-g", time, stale, pli.latE7 / 1e7, pli.lonE7 / 1e7,
                hae == null ? 9999999.0 : hae, detail.toString());
    }

    public static String chat(Mctak.GeoChat chat, int sender, String senderCallsign, String destUid, long nowSeconds) {
        long time = Mctak.renderTime(chat.ts, nowSeconds);
        String uid = Mctak.messageUid(sender, chat.msgId);
        String room;
        String roomId;
        if (chat.scope == Mctak.SCOPE_ALL) {
            room = "All Chat Rooms";
            roomId = "All Chat Rooms";
        } else if (chat.scope == Mctak.SCOPE_DIRECT) {
            room = destUid;
            roomId = destUid;
        } else {
            room = chat.room;
            roomId = chat.room;
        }
        String senderUid = Mctak.derivedUid(sender);
        String detail = "<__chat id=\"" + esc(roomId) + "\" chatroom=\"" + esc(room)
                + "\" senderCallsign=\"" + esc(senderCallsign) + "\" messageId=\""
                + BytesHex(chat.msgId) + "\"><chatgrp id=\"" + esc(roomId) + "\" uid0=\""
                + esc(senderUid) + "\" uid1=\"" + esc(destUid) + "\"/></__chat>"
                + "<link uid=\"" + esc(senderUid) + "\" type=\"a-f-G-U-C\" relation=\"p-p\"/>"
                + "<remarks source=\"" + esc(senderUid) + "\" time=\"" + iso(time) + "\">"
                + esc(chat.text) + "</remarks><__meshcore unverified=\"true\"/>";
        return event(uid, "b-t-f", "m-g", time, time + Mctak.PLI_STALE_S, 0, 0, 9999999.0, detail);
    }

    /**
     * G3: one incoming message for the dedicated MeshCore room, shaped like a one-to-one GeoChat
     * message from the room's contact to this device, so ATAK files it under that contact. The
     * room contact is the sender as far as ATAK is concerned; the real sender is written into the
     * text, always with the "(MeshCore, unverified)" label, so the label cannot be lost whatever
     * the chat window prints as a name. The event carries the {@code __meshcore} marker, is meant
     * for the internal dispatcher only, and is never copied out again.
     *
     * @param roomUid  uid of the room contact
     * @param roomName current title of the room contact
     * @param selfUid  this device's uid ({@code MapView.getDeviceUid()})
     * @param senderLabel sender with the label, for example "Ana (MeshCore, unverified)"
     */
    public static String roomChat(String roomUid, String roomName, String selfUid, String selfCallsign,
                                  String senderLabel, String text, String messageKey, long nowSeconds) {
        String uid = "MESHCORE-ROOM-MSG-" + Long.toHexString(messageKey == null ? 0L
                : (messageKey.hashCode() & 0xFFFFFFFFL)) + "-" + nowSeconds;
        String detail = "<__chat parent=\"RootContactGroup\" groupOwner=\"false\" messageId=\"" + esc(uid)
                + "\" chatroom=\"" + esc(selfCallsign) + "\" id=\"" + esc(selfUid)
                + "\" senderCallsign=\"" + esc(roomName) + "\"><chatgrp uid0=\"" + esc(roomUid)
                + "\" uid1=\"" + esc(selfUid) + "\" id=\"" + esc(selfUid) + "\"/></__chat>"
                + "<link uid=\"" + esc(roomUid) + "\" type=\"a-f-G-U-C\" relation=\"p-p\"/>"
                + "<remarks source=\"" + esc(roomUid) + "\" to=\"" + esc(selfUid) + "\" time=\""
                + iso(nowSeconds) + "\">" + esc(senderLabel + ": " + text) + "</remarks>"
                + "<__meshcore unverified=\"true\"/>";
        return event(uid, "b-t-f", "m-g", nowSeconds, nowSeconds + Mctak.PLI_STALE_S, 0, 0, 9999999.0, detail);
    }

    public static String receipt(int rtype, int sender, int msgId, long nowSeconds) {
        String type = rtype == 2 ? "b-t-f-r" : "b-t-f-d";
        String uid = "MCTAK.receipt." + dev.meshcore.atak.util.Bytes.hex8(sender) + "."
                + dev.meshcore.atak.util.Bytes.hex8(msgId) + "." + rtype;
        String detail = "<remarks>receipt</remarks><__meshcore unverified=\"true\"/>";
        return event(uid, type, "m-g", nowSeconds, nowSeconds + 120, 0, 0, 9999999.0, detail);
    }

    public static String waypoint(String name, double lat, double lon, long nowSeconds) {
        String uid = "MCTAK-wpt-" + Integer.toHexString(name.hashCode());
        String detail = "<contact callsign=\"" + esc(name) + "\"/><__meshcore unverified=\"true\"/>";
        return event(uid, "b-m-p-w", "h-g-i-g-o", nowSeconds, nowSeconds + 3600, lat, lon, 9999999.0, detail);
    }

    public static boolean hasMeshcoreLoopMarker(String cot) {
        return cot != null && cot.contains("__meshcore");
    }

    public static boolean allowedMarkerType(String cot) {
        return cot != null && (cot.contains("type=\"b-m-p-w\"") || cot.contains("type=\"b-m-p-s-m\""));
    }

    private static String event(String uid, String type, String how, long time, long stale,
                                double lat, double lon, double hae, String detail) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<event version=\"2.0\" uid=\"" + esc(uid) + "\" type=\"" + esc(type)
                + "\" time=\"" + iso(time) + "\" start=\"" + iso(time) + "\" stale=\"" + iso(stale)
                + "\" how=\"" + esc(how) + "\">"
                + "<point lat=\"" + lat + "\" lon=\"" + lon + "\" hae=\"" + hae
                + "\" ce=\"9999999.0\" le=\"9999999.0\"/>"
                + "<detail>" + detail + "</detail></event>";
    }

    private static String iso(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).toString();
    }

    private static String BytesHex(int value) {
        return dev.meshcore.atak.util.Bytes.hex8(value);
    }

    static String esc(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
