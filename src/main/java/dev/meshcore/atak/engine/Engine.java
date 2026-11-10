package dev.meshcore.atak.engine;

import dev.meshcore.atak.companion.Companion;
import dev.meshcore.atak.cot.Cot;
import dev.meshcore.atak.mctak.Airtime;
import dev.meshcore.atak.mctak.Counters;
import dev.meshcore.atak.mctak.Mctak;
import dev.meshcore.atak.mctak.Reassembly;
import dev.meshcore.atak.util.Bytes;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SDK-independent bridge: companion radio frames in, CoT events out, and the
 * reverse. Location is opt-in. The built-in Public channel and hashtag
 * channels are refused. A channel key is not treated as sender authentication;
 * every inbound CoT is marked unverified.
 */
public final class Engine {
    public enum Link { DISCONNECTED, CONNECTING, READY, RECONNECTING }

    public static final class Inbound {
        public final String cot;
        public final boolean unverified = true;
        public final String summary;

        Inbound(String cot, String summary) {
            this.cot = cot;
            this.summary = summary;
        }
    }

    public static final class Send {
        public final String status;
        public final String detail;
        /**
         * E10: a short sentence that says why nothing was sent, such as
         * "Not sent: choose a private channel first". Empty when the send was
         * queued. It never contains message text, a key or a PIN.
         */
        public final String why;

        Send(String status, String detail) {
            this(status, detail, "");
        }

        Send(String status, String detail, String why) {
            this.status = status;
            this.detail = detail;
            this.why = why;
        }
    }

    /** E8: one native MeshCore text taken from the radio queue. Always unverified. */
    public static final class NativeMessage {
        public static final String LABEL = "MeshCore, unverified";

        public final boolean direct;
        public final int channel;
        public final String senderPrefixHex;
        public final String text;
        public final long receivedMs;

        NativeMessage(boolean direct, int channel, String senderPrefixHex, String text, long receivedMs) {
            this.direct = direct;
            this.channel = channel;
            this.senderPrefixHex = senderPrefixHex;
            this.text = text;
            this.receivedMs = receivedMs;
        }

        /** The only way a message is shown: the label always comes first. */
        public String display() {
            String from = direct ? "direct from " + senderPrefixHex : "channel " + channel;
            return "[" + LABEL + "] " + from + ": " + text;
        }
    }

    /** E2: one slot of the radio's channel table, no secret. */
    public static final class ChannelRow {
        public final int index;
        public final String name;
        public final String fingerprint;
        public final boolean safe;
        public final String reason;

        ChannelRow(int index, String name, String fingerprint, boolean safe, String reason) {
            this.index = index;
            this.name = name;
            this.fingerprint = fingerprint;
            this.safe = safe;
            this.reason = reason;
        }
    }

    /** E6: the last core stats reading from the radio. */
    public static final class RadioStats {
        public final int batteryMv;
        public final long uptimeSecs;
        public final int errFlags;
        public final int queueLen;
        public final long atMs;

        RadioStats(int batteryMv, long uptimeSecs, int errFlags, int queueLen, long atMs) {
            this.batteryMv = batteryMv;
            this.uptimeSecs = uptimeSecs;
            this.errFlags = errFlags;
            this.queueLen = queueLen;
            this.atMs = atMs;
        }
    }

    /** How many native messages the pane keeps and shows. */
    public static final int NATIVE_INBOX_CAP = 10;
    public static final int NATIVE_TEXT_SHOWN_CHARS = 160;

    private final Counters counters = new Counters();
    private final List<String> log = new ArrayList<>();
    private final List<Inbound> inbound = new ArrayList<>();
    private final Map<String, Long> seen = new LinkedHashMap<>();
    private final Map<Integer, Integer> pliLastSeq = new LinkedHashMap<>();
    private final Map<Integer, Long> pliLastMs = new LinkedHashMap<>();
    private final Map<Integer, Integer> pliDrops = new LinkedHashMap<>();
    private final Map<Integer, Integer> hits = new LinkedHashMap<>();
    private final Map<Integer, String> callsigns = new LinkedHashMap<>();
    private final Map<String, Long> chatDedup = new LinkedHashMap<>();
    private final Map<String, Boolean> deliveredOnce = new LinkedHashMap<>();
    private final Reassembly reassembly = new Reassembly(counters, (sender, content, object, crc) -> {
        if (content == Mctak.CONTENT_COT || content == Mctak.CONTENT_CHAT_OVERFLOW) {
            inbound.add(new Inbound(null, "transfer sender=" + Bytes.hex8(sender) + " bytes=" + object.length));
            counters.inc("transfer_delivered");
        }
    });

    private Link link = Link.DISCONNECTED;
    private Governor governor;
    private Companion.SelfInfo self;
    private final Map<Integer, Companion.ChannelView> channels = new LinkedHashMap<>();
    private Integer selected;
    private boolean locationOptIn;
    private boolean geoChatCopyOptIn;
    private final List<NativeMessage> nativeInbox = new ArrayList<>();
    private final List<NativeMessage> nativeNew = new ArrayList<>();
    private RadioStats lastStats;
    private final List<RadioStats> statsHistory = new ArrayList<>();
    private boolean rebootSeen;
    /** E9 toggle: true = plugin-only MCTAK channel data (default), false = native MeshCore channel text. */
    private boolean pluginOnlyChat = true;
    private String lastTxLabel = "";
    private String lastChatResult = "";
    private int chatResultSeq;
    private boolean hasFix;
    private double lat = Double.NaN;
    private double lon = Double.NaN;
    private Double hae;
    private Double course;
    private Double speed;
    private int battery = 0xFF;
    private int teamRole = 0x90;
    private String callsign = "LEARNER";
    private boolean readReceipts;
    private int seq;
    private String transportStatus;
    private long lastBeaconMs = Long.MIN_VALUE / 4;
    private long pliIntervalMs = 300_000L;
    private int reconnects;
    private long nowSeconds = 1_800_000_000L;

    public Counters counters() {
        return counters;
    }

    public List<String> log() {
        return log;
    }

    public Link link() {
        return link;
    }

    /**
     * IDENT capability bits this bridge advertises. Bit 0 is FRAG-rx in MCTAK.
     * It stays clear: official MeshCore group payloads stop at 165 bytes
     * ({@code MAX_GROUP_DATA_LENGTH}) and an MCTAK fragment is 167 bytes, so
     * this bridge cannot receive one over current companion firmware.
     */
    public static final int ADVERTISED_CAPS = 0;

    public int reconnects() {
        return reconnects;
    }

    public void setNowSeconds(long seconds) {
        this.nowSeconds = seconds;
    }

    public void setCallsign(String callsign) {
        this.callsign = callsign == null || callsign.trim().isEmpty() ? "LEARNER" : callsign.trim();
    }

    public void setTeamRole(int team, int role) {
        this.teamRole = ((team & 0x0F) << 4) | (role & 0x0F);
    }

    public void setReadReceipts(boolean enabled) {
        this.readReceipts = enabled;
    }

    /**
     * Position sending is opt-in and needs an explicitly chosen channel (E2).
     * Turning it on with no selected channel does nothing and is counted.
     */
    public void setLocationOptIn(boolean enabled) {
        if (enabled && (selected == null || self == null)) {
            counters.inc("location_optin_refused");
            log.add("location_optin_refused");
            this.locationOptIn = false;
            return;
        }
        this.locationOptIn = enabled;
        if (!enabled) {
            hasFix = false;
            log.add("location_opt_out");
        } else {
            log.add("location_opt_in");
        }
    }

    public boolean locationOptIn() {
        return locationOptIn;
    }

    /**
     * Copying ATAK GeoChat onto the radio is opt-in too, and also needs a chosen
     * channel. Returns false when the request was refused.
     */
    public boolean setGeoChatCopy(boolean enabled) {
        if (enabled && (selected == null || self == null)) {
            counters.inc("geochat_copy_refused");
            log.add("geochat_copy_refused");
            geoChatCopyOptIn = false;
            return false;
        }
        geoChatCopyOptIn = enabled;
        log.add(enabled ? "geochat_copy_on" : "geochat_copy_off");
        return true;
    }

    public boolean geoChatCopyOptIn() {
        return geoChatCopyOptIn;
    }

    /**
     * E2: changing the radio or the channel turns every outgoing path off. The
     * user has to opt in again, with a confirmation naming the channel.
     */
    public void clearSendOptIns(String why) {
        boolean any = locationOptIn || geoChatCopyOptIn;
        // E9: the "Plugin users only" switch is never remembered; it is back ON whenever
        // the radio or the channel changes.
        pluginOnlyChat = true;
        locationOptIn = false;
        geoChatCopyOptIn = false;
        hasFix = false;
        if (any) {
            counters.inc("send_opt_ins_cleared");
            log.add("send_opt_ins_cleared " + why);
        }
    }

    /** Forget the chosen channel. Position and GeoChat copying go off with it. */
    public void clearChannelSelection(String why) {
        if (selected != null) {
            log.add("channel_selection_cleared " + why);
        }
        selected = null;
        clearSendOptIns(why);
    }

    /** Test and operator positions. Ignored unless location opt-in is already on. */
    public void setSelfPosition(double lat, double lon, Double hae, Double course, Double speed, int battery) {
        if (!locationOptIn) {
            counters.inc("location_ignored");
            log.add("location_ignored");
            return;
        }
        this.lat = lat;
        this.lon = lon;
        this.hae = hae;
        this.course = course;
        this.speed = speed;
        this.battery = battery;
        this.hasFix = !Double.isNaN(lat) && !Double.isNaN(lon);
        log.add("position_set");
    }

    public void onLinkUp(long nowMs) {
        link = link == Link.RECONNECTING ? Link.CONNECTING : Link.CONNECTING;
        // E2: a new session starts with no channel chosen and nothing queued for
        // a channel slot that may now mean something else.
        channels.clear();
        clearChannelSelection("new session");
        governor = new Governor(counters, self == null ? 910_525 : self.freqKhz, nowMs);
        log.add("link_connecting");
    }

    public void onLinkLost() {
        if (link == Link.DISCONNECTED) {
            return;
        }
        link = Link.RECONNECTING;
        reconnects++;
        counters.inc("reconnect");
        clearChannelSelection("link lost");
        governor = null;
        log.add("link_lost");
    }

    public byte[] appStartFrame() {
        return Companion.appStart("mctak-learn");
    }

    public void receiveFrame(byte[] frame, long nowMs) {
        if (frame == null || frame.length == 0) {
            counters.inc("short_frame");
            return;
        }
        int type = frame[0] & 0xFF;
        // E6: one counter per frame type, instead of one lump for everything unhandled.
        counters.inc("rx_" + hex2(type));
        if (type == Companion.PACKET_SELF_INFO) {
            Companion.SelfInfo info = Companion.parseSelfInfo(frame);
            if (info == null) {
                counters.inc("bad_self_info");
                return;
            }
            self = info;
            if (governor == null) {
                governor = new Governor(counters, info.freqKhz, nowMs);
            }
            link = Link.READY;
            log.add("ready sender=" + Bytes.hex8(info.sender) + " radio=" + info.freqKhz
                    + "kHz sf" + info.sf);
            return;
        }
        if (type == Companion.PACKET_DEVICE_INFO) {
            Companion.DeviceInfo info = Companion.parseDeviceInfo(frame);
            log.add(info == null ? "device_info_bad" : info.toString());
            return;
        }
        if (type == Companion.PACKET_CHANNEL_INFO) {
            Companion.ChannelView view = Companion.parseChannelInfo(frame);
            // The secret travelled in this frame. Only a fingerprint is kept: wipe the bytes.
            if (frame.length >= 50) {
                java.util.Arrays.fill(frame, 34, 50, (byte) 0);
            }
            if (view == null) {
                counters.inc("bad_channel");
                return;
            }
            channels.put(view.index, view);
            log.add(view.toString());
            return;
        }
        if (type == Companion.PACKET_MESSAGES_WAITING) {
            log.add("messages_waiting");
            return;
        }
        if (type == Companion.PACKET_NO_MORE_MESSAGES) {
            counters.inc("no_more_messages");
            return;
        }
        if (type == Companion.PACKET_STATS) {
            Companion.Stats stats = Companion.parseStatsCore(frame);
            if (stats == null) {
                counters.inc("bad_stats");
                return;
            }
            recordStats(stats, nowMs);
            return;
        }
        if (Companion.isNativeText(type)) {
            acceptNativeText(frame, nowMs);
            return;
        }
        Companion.DataRecv recv = Companion.parseChannelData(frame);
        if (recv == null) {
            // OK and error replies are handled by the session; everything else is counted by type.
            if (type != Companion.PACKET_OK && type != Companion.PACKET_ERROR) {
                counters.inc("unhandled_" + hex2(type));
            }
            return;
        }
        if (selected == null) {
            // E2: with no channel chosen nothing from the air becomes CoT.
            counters.inc("data_no_channel");
            return;
        }
        if (recv.channel != selected) {
            counters.inc("other_channel");
            return;
        }
        if (recv.dataType != Mctak.DATA_TYPE_DEV) {
            counters.inc("other_data_type");
            return;
        }
        counters.inc("mctak_data_rx");
        acceptDatagram(recv.payload, nowMs);
    }

    private static String hex2(int value) {
        String h = Integer.toHexString(value & 0xFF);
        return h.length() == 1 ? "0" + h : h;
    }

    /** E8: keep and surface native text instead of discarding it. Text is never logged. */
    private void acceptNativeText(byte[] frame, long nowMs) {
        Companion.NativeText parsed = Companion.parseNativeText(frame);
        if (parsed == null) {
            counters.inc("native_text_bad");
            return;
        }
        counters.inc("native_text_rx");
        NativeMessage message = new NativeMessage(parsed.direct, parsed.channel,
                parsed.senderPrefixHex, displayText(parsed.text), nowMs);
        if (nativeInbox.size() >= NATIVE_INBOX_CAP) {
            nativeInbox.remove(0);
        }
        nativeInbox.add(message);
        if (nativeNew.size() >= NATIVE_INBOX_CAP) {
            nativeNew.remove(0);
        }
        nativeNew.add(message);
    }

    /** Control characters out, length capped. Radio text is untrusted display data. */
    static String displayText(String raw) {
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), NATIVE_TEXT_SHOWN_CHARS));
        for (int i = 0; i < raw.length() && sb.length() < NATIVE_TEXT_SHOWN_CHARS; i++) {
            char c = raw.charAt(i);
            sb.append(c < 0x20 || c == 0x7F ? ' ' : c);
        }
        return sb.toString();
    }

    /** The last {@link #NATIVE_INBOX_CAP} native messages, oldest first. */
    public List<NativeMessage> nativeInbox() {
        return new ArrayList<>(nativeInbox);
    }

    /** Messages that arrived since the last call. The pane toasts each one. */
    public List<NativeMessage> takeNewNativeText() {
        List<NativeMessage> out = new ArrayList<>(nativeNew);
        nativeNew.clear();
        return out;
    }

    private void recordStats(Companion.Stats stats, long nowMs) {
        RadioStats reading = new RadioStats(stats.batteryMv, stats.uptimeSecs, stats.errFlags,
                stats.queueLen, nowMs);
        RadioStats previous = lastStats;
        if (previous != null && reading.uptimeSecs < previous.uptimeSecs) {
            // Radio uptime went backwards between two readings: it rebooted.
            rebootSeen = true;
            counters.inc("radio_reboot_detected");
        }
        lastStats = reading;
        if (statsHistory.size() >= 8) {
            statsHistory.remove(0);
        }
        statsHistory.add(reading);
        counters.inc("stats_read");
    }

    public RadioStats radioStats() {
        return lastStats;
    }

    /** True once a stats reading showed lower uptime than the one before it. */
    public boolean radioRebootSeen() {
        return rebootSeen;
    }

    public List<RadioStats> radioStatsHistory() {
        return new ArrayList<>(statsHistory);
    }

    public Send selectChannel(int index) {
        Companion.ChannelView view = channels.get(index);
        if (view == null) {
            counters.inc("channel_missing");
            return new Send("REFUSED", "channel not loaded", "Not selected: channel not loaded");
        }
        if (view.unsafeForTeamTraffic()) {
            counters.inc("public_channel_refused");
            log.add("refused_unsafe_channel index=" + index);
            return new Send("REFUSED_PUBLIC", "public, hashtag, zero-key, and empty channels are not used",
                    "Not selected: " + view.refusalReason());
        }
        if (selected == null || selected != index) {
            // E2: a different channel means position and GeoChat copying start over.
            clearSendOptIns("channel changed");
        }
        selected = index;
        log.add("selected_channel " + index);
        return new Send("OK", view.toString());
    }

    public Send joinSelected(long nowMs) {
        if (self == null || selected == null) {
            return refusedNotReady();
        }
        Mctak.Ident ident = new Mctak.Ident(nowSeconds, ADVERTISED_CAPS, 0x01, teamRole, callsign, "");
        return queueDatagram(new Mctak.Header(0, false, Mctak.KIND_IDENT, self.sender, nextSeq()),
                ident.body(), Governor.Klass.P2, "ident", nowMs, false);
    }

    public Send beacon(long nowMs) {
        if (!locationOptIn) {
            counters.inc("location_suppressed");
            return new Send("LOCATION_OFF", "position sending is opt-in",
                    SendReasons.POSITION_OFF);
        }
        if (!hasFix) {
            counters.inc("no_fix");
            return new Send("NO_FIX", "no position to send", SendReasons.NO_FIX);
        }
        if (self == null || selected == null || governor == null) {
            return refusedNotReady();
        }
        if (nowMs - lastBeaconMs < pliIntervalMs) {
            counters.inc("pli_rate_limited");
            return new Send("RATE_LIMIT", "pli interval", "");
        }
        int latE7 = (int) Math.round(lat * 1e7);
        int lonE7 = (int) Math.round(lon * 1e7);
        Mctak.Pli pli = new Mctak.Pli(nowSeconds, latE7, lonE7, Mctak.Pli.altRaw(hae),
                Mctak.Pli.courseRaw(course), Mctak.Pli.speedRaw(speed), battery, teamRole);
        Send send = queueDatagram(new Mctak.Header(0, false, Mctak.KIND_PLI, self.sender, nextSeq()),
                pli.body(), Governor.Klass.P2, "pli", nowMs, true);
        if ("QUEUED".equals(send.status)) {
            lastBeaconMs = nowMs;
        }
        return send;
    }

    /** E10: the reason for a refusal that comes from missing radio or channel state. */
    private Send refusedNotReady() {
        String why = self == null || governor == null
                ? SendReasons.NOT_READY
                : SendReasons.NO_CHANNEL;
        return new Send("REFUSED", "radio or channel not ready", why);
    }

    /**
     * Most UTF-8 bytes of chat text that still fit one MeshCore group payload for this
     * scope and room (E10). Computed by encoding, not estimated.
     */
    public static int maxChatTextBytes(int scope, int dest, String room) {
        int best = 0;
        for (int n = 0; n <= 255; n++) {
            byte[] filler = new byte[n];
            java.util.Arrays.fill(filler, (byte) 'a');
            Mctak.GeoChat chat = new Mctak.GeoChat(0L, 0, scope, dest, room,
                    new String(filler, java.nio.charset.StandardCharsets.US_ASCII));
            if (Mctak.HDR_LEN + chat.body().length > Companion.SAFE_PAYLOAD) {
                break;
            }
            best = n;
        }
        return best;
    }

    public Send sendChat(int scope, int dest, String room, String text, long nowMs) {
        if (self == null || selected == null) {
            return refusedNotReady();
        }
        if (text == null) {
            text = "";
        }
        Send unsafe = refuseUnsafeChannel();
        if (unsafe != null) {
            return unsafe;
        }
        if (text.trim().isEmpty()) {
            return new Send("REFUSED", "empty text", SendReasons.EMPTY_TEXT);
        }
        if (scope == Mctak.SCOPE_DIRECT && dest == 0) {
            return new Send("REFUSED", "direct chat needs a peer id", "Not sent: direct chat needs a peer id");
        }
        int msgId = 0x1000 + (seq & 0xFFF);
        Mctak.GeoChat chat = new Mctak.GeoChat(nowSeconds, msgId, scope, dest, room, text);
        byte[] body = chat.body();
        if (Mctak.HDR_LEN + body.length > Companion.SAFE_PAYLOAD) {
            counters.inc("radio_payload_oversize");
            int have = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            return new Send("BLOCKED_RADIO_LIMIT",
                    "encoded chat is above the 164-byte plugin payload cap (MeshCore limit 165); "
                            + "MCTAK fragments are 167 bytes and are not given a second format",
                    SendReasons.tooLong(have, maxChatTextBytes(scope, dest, room)));
        }
        return queueDatagram(new Mctak.Header(0, false, Mctak.KIND_GEOCHAT, self.sender, nextSeq()),
                body, Governor.Klass.P1, "chat", nowMs, false);
    }

    /** E9: true (the default) sends plugin chat as MCTAK data; false sends native MeshCore channel text. */
    public boolean pluginOnlyChat() {
        return pluginOnlyChat;
    }

    public void setPluginOnlyChat(boolean pluginOnly) {
        this.pluginOnlyChat = pluginOnly;
        log.add(pluginOnly ? "chat_mode plugin_only" : "chat_mode meshcore_text");
    }

    /** What the chat button is labelled for the current mode. */
    public String chatModeLabel() {
        return pluginOnlyChat ? "ATAK plugin users only" : "MeshCore channel text (anyone on channel)";
    }

    /**
     * Most UTF-8 bytes the current mode can send, or -1 when the radio is not known yet. For
     * native text it depends on the radio's node name: the firmware writes
     * "&lt;node_name&gt;: &lt;text&gt;" and the plugin refuses what would be truncated.
     */
    public int maxChatBytes(int scope, int dest, String room) {
        if (pluginOnlyChat) {
            return maxChatTextBytes(scope, dest, room);
        }
        return self == null ? -1 : Companion.maxNativeTextBytes(self.nameBytes);
    }

    /**
     * E9, opt-in: send {@code text} as native MeshCore channel text on the chosen private
     * channel. The radio encrypts it with that channel's stored key, adds
     * "node_name: " in front, and the official MeshCore app shows it. The refusals match
     * the plugin-data path (E10) and the cap is the smaller of the firmware limit and the
     * 164-byte payload cap. The plugin refuses an oversize text; it never truncates.
     */
    public Send sendNativeChannelText(String text, long nowMs) {
        if (self == null || selected == null || governor == null) {
            return refusedNotReady();
        }
        Send unsafe = refuseUnsafeChannel();
        if (unsafe != null) {
            return unsafe;
        }
        String body = text == null ? "" : text;
        if (body.trim().isEmpty()) {
            return new Send("REFUSED", "empty text", SendReasons.EMPTY_TEXT);
        }
        if (body.indexOf('\u0000') >= 0) {
            return new Send("REFUSED", "text contains NUL", "Not sent: text contains an invalid character");
        }
        byte[] utf8 = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int max = Companion.maxNativeTextBytes(self.nameBytes);
        if (utf8.length > max) {
            counters.inc("radio_payload_oversize");
            return new Send("BLOCKED_RADIO_LIMIT", "native text above the cap",
                    SendReasons.tooLong(utf8.length, max));
        }
        byte[] frame = Companion.sendChannelText(selected, nowSeconds, utf8);
        int payload = Companion.GROUP_TEXT_HEADER + self.nameBytes + 2 + utf8.length;
        int cost = Airtime.packetMs(payload, self.sf, self.bwHz, self.cr);
        boolean accepted = governor.offer(Governor.Klass.P1, frame, cost, nowMs, "chat-native", false);
        if (!accepted) {
            return new Send("SKIPPED", "chat-native", "Not sent: skipped by the airtime limit");
        }
        return new Send("QUEUED", "chat-native costMs=" + cost);
    }

    /**
     * E10, both chat modes: a Public, hashtag, zero-key or empty slot is never a send
     * target, even if one somehow became "selected". Returns the refusal, or null.
     */
    private Send refuseUnsafeChannel() {
        Companion.ChannelView view = selected == null ? null : channels.get(selected);
        if (view == null) {
            return new Send("REFUSED", "channel not loaded", SendReasons.NO_CHANNEL);
        }
        if (view.unsafeForTeamTraffic()) {
            counters.inc("public_channel_refused");
            return new Send("REFUSED_PUBLIC", "unsafe channel",
                    "Not sent: " + view.refusalReason() + ", choose a private channel");
        }
        return null;
    }

    public Send sendVoice() {
        counters.inc("gated_voice_or_file");
        return new Send("UNSUPPORTED", "voice is not implemented");
    }

    public Send sendImage() {
        counters.inc("gated_voice_or_file");
        return new Send("UNSUPPORTED", "imagery is not implemented");
    }

    public Send sendMarker(String cotOrNull, long nowMs) {
        counters.inc("marker_not_transmitted");
        return new Send("NOT_TRANSMITTED",
                "waypoint and other CoT objects use MCTAK FRAG symbols of 167 bytes; "
                        + "current MeshCore companion firmware rejects payloads above 165");
    }

    public boolean hasSelectedChannel() {
        return selected != null;
    }

    /** Last issued MCTAK sequence, including zero before any send. */
    public int lastIssuedSequence() {
        return seq & 0xFFFF;
    }

    /**
     * Continue after a previous process. {@code lastIssued} is the last sequence
     * that process handed out, not a public key or a channel secret. One integer.
     */
    public void resumeAfter(int lastIssued) {
        this.seq = lastIssued & 0xFFFF;
    }

    /**
     * E9 (declined, button relabelled): the radio's answer to a channel-data send.
     * {@code code} 0 is the radio's OK, -1 is no answer in time, anything else is its
     * error code. Only a plugin chat sets {@link #lastChatResult()}. The text is
     * "Sent to radio" or "Failed: reason". It is never "delivered".
     */
    public void onChannelDataReply(int code) {
        boolean plugin = "chat".equals(lastTxLabel);
        boolean nativeText = "chat-native".equals(lastTxLabel);
        if (!plugin && !nativeText) {
            return;
        }
        lastChatResult = code == 0 ? SendReasons.SENT_TO_RADIO
                : (nativeText && code == 2 ? "Failed: channel slot not found on the radio, or radio out of buffers"
                        : SendReasons.radioFailure(code));
        chatResultSeq++;
        counters.inc(code == 0 ? "chat_sent_to_radio" : "chat_send_failed");
    }

    /** Increases each time the radio answers a chat send, so the pane can toast every result once. */
    public int chatResultSeq() {
        return chatResultSeq;
    }

    /** Last visible result of a plugin chat, or "". */
    public String lastChatResult() {
        return lastChatResult;
    }

    /** Radio or unsupported-traffic status for the pane. Not a delivery receipt. */
    public void noteTransportStatus(String status) {
        this.transportStatus = status;
    }

    public String transportStatus() {
        return transportStatus;
    }

    public Companion.ChannelView channel(int index) {
        return channels.get(index);
    }

    public java.util.List<Integer> channelIndexes() {
        return new java.util.ArrayList<>(channels.keySet());
    }

    /**
     * E2: the radio's channel table for the pane and the diag. Empty slots are
     * skipped here; {@link #emptyChannelSlots()} counts them. No secret is held.
     */
    public List<ChannelRow> channelRows() {
        List<ChannelRow> rows = new ArrayList<>();
        for (Companion.ChannelView view : channels.values()) {
            if (view.empty) {
                continue;
            }
            rows.add(new ChannelRow(view.index, view.name, view.fingerprint,
                    !view.unsafeForTeamTraffic(), view.refusalReason()));
        }
        return rows;
    }

    public int emptyChannelSlots() {
        int n = 0;
        for (Companion.ChannelView view : channels.values()) {
            if (view.empty) {
                n++;
            }
        }
        return n;
    }

    /** The selected channel, or null. */
    public ChannelRow selectedChannelRow() {
        if (selected == null) {
            return null;
        }
        Companion.ChannelView view = channels.get(selected);
        return view == null ? null : new ChannelRow(view.index, view.name, view.fingerprint,
                !view.unsafeForTeamTraffic(), view.refusalReason());
    }

    /** Next companion frame the governor would emit, left in the queue. */
    public byte[] previewTx(long nowMs) {
        if (governor == null) {
            return null;
        }
        Governor.Item item = governor.peek(nowMs);
        return item == null ? null : item.frame;
    }

    public List<byte[]> drainTx(long nowMs) {
        List<byte[]> out = new ArrayList<>();
        if (governor == null) {
            return out;
        }
        Governor.Item item = governor.poll(nowMs);
        if (item != null) {
            out.add(item.frame);
            lastTxLabel = item.label;
            log.add("tx " + item.label);
        }
        return out;
    }

    public List<Inbound> drainInbound() {
        List<Inbound> copy = new ArrayList<>(inbound);
        inbound.clear();
        return copy;
    }

    public int queued() {
        return governor == null ? 0 : governor.queued();
    }

    private Send queueDatagram(Mctak.Header header, byte[] body, Governor.Klass klass, String label,
                               long nowMs, boolean skipNotQueue) {
        byte[] datagram = Mctak.frame(header, body);
        if (!Mctak.radioWillAccept(datagram.length) || datagram.length > Companion.SAFE_PAYLOAD) {
            counters.inc("radio_payload_oversize");
            return new Send("BLOCKED_RADIO_LIMIT", "datagram " + datagram.length + " bytes",
                    "Not sent: message too big for the radio (" + datagram.length + "/"
                            + Companion.SAFE_PAYLOAD + " bytes)");
        }
        int cost = Airtime.packetMs(datagram.length, self.sf, self.bwHz, self.cr);
        byte[] frame = Companion.sendChannelData(selected, datagram);
        boolean accepted = governor.offer(klass, frame, cost, nowMs, label, skipNotQueue);
        if (!accepted) {
            return new Send("SKIPPED", label, "Not sent: skipped by the airtime limit");
        }
        return new Send("QUEUED", label + " costMs=" + cost);
    }

    private void acceptDatagram(byte[] datagram, long nowMs) {
        Mctak.Result parsed = Mctak.parse(datagram);
        if (!parsed.ok()) {
            counters.inc(parsed.drop == null ? "drop" : parsed.drop);
            return;
        }
        if (self != null && parsed.header.sender == self.sender) {
            counters.inc("self_echo");
            return;
        }
        String seenKey = Integer.toHexString(parsed.header.sender) + ":" + (parsed.header.seq & 0xFFFF);
        long now = nowMs;
        purge(seen, now, 600_000L);
        if (seen.containsKey(seenKey)) {
            counters.inc("dup_datagram");
            return;
        }
            if (seen.size() >= 512) {
            Iterator<String> it = seen.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        seen.put(seenKey, now);
        int sender = parsed.header.sender;
        if (parsed.pli != null) {
            if (!pliWindow(sender, parsed.header.seq, nowMs)) {
                counters.inc("pli_window_drop");
                return;
            }
            if (!secondDatagram(sender)) {
                counters.inc("held_first_contact");
                return;
            }
            String name = callsigns.getOrDefault(sender, Mctak.derivedUid(sender));
            inbound.add(new Inbound(Cot.pli(Mctak.derivedUid(sender), parsed.pli, name, nowSeconds), "pli"));
            return;
        }
        if (parsed.ident != null) {
            if (!parsed.ident.callsign.isEmpty()) {
                callsigns.put(sender, parsed.ident.callsign);
            }
            hits.put(sender, 2);
            inbound.add(new Inbound(null, "ident " + parsed.ident.callsign + " unverified"));
            counters.inc("ident");
            return;
        }
        if (parsed.chat != null) {
            String dedup = Integer.toHexString(sender) + ":" + parsed.chat.msgId;
            purge(chatDedup, now, 600_000L);
            if (chatDedup.containsKey(dedup)) {
                counters.inc("dup_chat");
                return;
            }
            chatDedup.put(dedup, now);
            String destUid = parsed.chat.scope == Mctak.SCOPE_DIRECT
                    ? Mctak.derivedUid(parsed.chat.dest) : "";
            String name = callsigns.getOrDefault(sender, Mctak.derivedUid(sender));
            inbound.add(new Inbound(Cot.chat(parsed.chat, sender, name, destUid, nowSeconds), "chat"));
            maybeReceipt(parsed.chat, sender, nowMs);
            return;
        }
        if (parsed.receipt != null) {
            inbound.add(new Inbound(Cot.receipt(parsed.receipt.rtype, parsed.receipt.origSender,
                    parsed.receipt.msgId, nowSeconds),
                    parsed.receipt.rtype == 2 ? "read" : "delivered"));
            return;
        }
        if (parsed.frag != null) {
            reassembly.offer(parsed.header, parsed.frag, nowMs);
            return;
        }
        if (parsed.who != null) {
            counters.inc("who");
            return;
        }
        if (parsed.xfer != null) {
            counters.inc("xfer_status");
        }
    }

    private void maybeReceipt(Mctak.GeoChat chat, int author, long nowMs) {
        if (chat.scope != Mctak.SCOPE_DIRECT || self == null || chat.dest != self.sender) {
            counters.inc("receipt_suppressed");
            return;
        }
        String key = Integer.toHexString(author) + ":" + chat.msgId;
        if (deliveredOnce.containsKey(key)) {
            counters.inc("receipt_duplicate_suppressed");
            return;
        }
        deliveredOnce.put(key, Boolean.TRUE);
        Mctak.Receipt receipt = new Mctak.Receipt(1, author, chat.msgId);
        queueDatagram(new Mctak.Header(0, false, Mctak.KIND_RECEIPT, self.sender, nextSeq()),
                receipt.body(), Governor.Klass.P0, "receipt", nowMs, false);
        counters.inc("receipt_delivered_queued");
        if (readReceipts) {
            Mctak.Receipt read = new Mctak.Receipt(2, author, chat.msgId);
            queueDatagram(new Mctak.Header(0, false, Mctak.KIND_RECEIPT, self.sender, nextSeq()),
                    read.body(), Governor.Klass.P0, "read", nowMs, false);
            counters.inc("receipt_read_queued");
        }
    }

    private boolean secondDatagram(int sender) {
        int n = hits.getOrDefault(sender, 0) + 1;
        hits.put(sender, n);
        return n >= 2 || callsigns.containsKey(sender);
    }

    private boolean pliWindow(int sender, int seq, long nowMs) {
        Long lastMs = pliLastMs.get(sender);
        Integer last = pliLastSeq.get(sender);
        if (lastMs != null && nowMs - lastMs > 3_600_000L) {
            last = null;
            pliDrops.put(sender, 0);
        }
        if (last == null) {
            pliLastSeq.put(sender, seq & 0xFFFF);
            pliLastMs.put(sender, nowMs);
            pliDrops.put(sender, 0);
            return true;
        }
        int delta = ((seq & 0xFFFF) - last) & 0xFFFF;
        if (delta >= 32768) {
            int drops = pliDrops.getOrDefault(sender, 0) + 1;
            if (drops >= 3) {
                pliLastSeq.put(sender, seq & 0xFFFF);
                pliLastMs.put(sender, nowMs);
                pliDrops.put(sender, 0);
                counters.inc("pli_resync");
                return true;
            }
            pliDrops.put(sender, drops);
            return false;
        }
        pliLastSeq.put(sender, seq & 0xFFFF);
        pliLastMs.put(sender, nowMs);
        pliDrops.put(sender, 0);
        return true;
    }

    private int nextSeq() {
        seq = (seq + 1) & 0xFFFF;
        return seq;
    }

    private static <K> void purge(Map<K, Long> map, long now, long ttl) {
        map.entrySet().removeIf(e -> now - e.getValue() > ttl);
    }
}
