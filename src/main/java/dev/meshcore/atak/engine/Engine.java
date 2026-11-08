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

        Send(String status, String detail) {
            this.status = status;
            this.detail = detail;
        }
    }

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

    public void setLocationOptIn(boolean enabled) {
        this.locationOptIn = enabled;
        if (!enabled) {
            hasFix = false;
            log.add("location_opt_out");
        } else {
            log.add("location_opt_in");
        }
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
        if (governor == null) {
            governor = new Governor(counters, 910_525, nowMs);
        }
        log.add("link_connecting");
    }

    public void onLinkLost() {
        if (link == Link.DISCONNECTED) {
            return;
        }
        link = Link.RECONNECTING;
        reconnects++;
        counters.inc("reconnect");
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
        Companion.DataRecv recv = Companion.parseChannelData(frame);
        if (recv == null) {
            counters.inc("ignored_frame");
            return;
        }
        if (selected != null && recv.channel != selected) {
            counters.inc("other_channel");
            return;
        }
        if (recv.dataType != Mctak.DATA_TYPE_DEV) {
            counters.inc("other_data_type");
            return;
        }
        acceptDatagram(recv.payload, nowMs);
    }

    public Send selectChannel(int index) {
        Companion.ChannelView view = channels.get(index);
        if (view == null) {
            counters.inc("channel_missing");
            return new Send("REFUSED", "channel not loaded");
        }
        if (view.unsafeForTeamTraffic()) {
            counters.inc("public_channel_refused");
            log.add("refused_unsafe_channel index=" + index);
            return new Send("REFUSED_PUBLIC", "public, hashtag, and empty channels are not used");
        }
        selected = index;
        log.add("selected_channel " + index);
        return new Send("OK", view.toString());
    }

    public Send joinSelected(long nowMs) {
        if (self == null || selected == null) {
            return new Send("REFUSED", "radio or channel not ready");
        }
        Mctak.Ident ident = new Mctak.Ident(nowSeconds, ADVERTISED_CAPS, 0x01, teamRole, callsign, "");
        return queueDatagram(new Mctak.Header(0, false, Mctak.KIND_IDENT, self.sender, nextSeq()),
                ident.body(), Governor.Klass.P2, "ident", nowMs, false);
    }

    public Send beacon(long nowMs) {
        if (!locationOptIn) {
            counters.inc("location_suppressed");
            return new Send("LOCATION_OFF", "position sending is opt-in");
        }
        if (!hasFix) {
            counters.inc("no_fix");
            return new Send("NO_FIX", "no position to send");
        }
        if (self == null || selected == null || governor == null) {
            return new Send("REFUSED", "radio or channel not ready");
        }
        if (nowMs - lastBeaconMs < pliIntervalMs) {
            counters.inc("pli_rate_limited");
            return new Send("RATE_LIMIT", "pli interval");
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

    public Send sendChat(int scope, int dest, String room, String text, long nowMs) {
        if (self == null || selected == null) {
            return new Send("REFUSED", "radio or channel not ready");
        }
        if (text == null) {
            text = "";
        }
        if (scope == Mctak.SCOPE_DIRECT && dest == 0) {
            return new Send("REFUSED", "direct chat needs a peer id");
        }
        int msgId = 0x1000 + (seq & 0xFFF);
        Mctak.GeoChat chat = new Mctak.GeoChat(nowSeconds, msgId, scope, dest, room, text);
        byte[] body = chat.body();
        if (Mctak.HDR_LEN + body.length > Mctak.RADIO_MAX_PAYLOAD) {
            counters.inc("radio_payload_oversize");
            return new Send("BLOCKED_RADIO_LIMIT",
                    "encoded chat is above the 165-byte MeshCore radio limit; "
                            + "MCTAK fragments are 167 bytes and are not given a second format");
        }
        return queueDatagram(new Mctak.Header(0, false, Mctak.KIND_GEOCHAT, self.sender, nextSeq()),
                body, Governor.Klass.P1, "chat", nowMs, false);
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

    public Companion.ChannelView channel(int index) {
        return channels.get(index);
    }

    public java.util.List<Integer> channelIndexes() {
        return new java.util.ArrayList<>(channels.keySet());
    }

    /** Lowest loaded slot that is not public, hashtag, or empty. */
    public Send selectFirstTeamChannel() {
        for (java.util.Map.Entry<Integer, Companion.ChannelView> entry : channels.entrySet()) {
            if (!entry.getValue().unsafeForTeamTraffic()) {
                return selectChannel(entry.getKey());
            }
        }
        return new Send("REFUSED", "no private channel");
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
        if (!Mctak.radioWillAccept(datagram.length)) {
            counters.inc("radio_payload_oversize");
            return new Send("BLOCKED_RADIO_LIMIT", "datagram " + datagram.length + " bytes");
        }
        int cost = Airtime.packetMs(datagram.length, self.sf, self.bwHz, self.cr);
        byte[] frame = Companion.sendChannelData(selected, datagram);
        boolean accepted = governor.offer(klass, frame, cost, nowMs, label, skipNotQueue);
        if (!accepted) {
            return new Send("SKIPPED", label);
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
