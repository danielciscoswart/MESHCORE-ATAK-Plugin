package dev.meshcore.atak.plugin;

import dev.meshcore.atak.cot.Cot;
import dev.meshcore.atak.engine.Engine;
import dev.meshcore.atak.engine.SendReasons;
import dev.meshcore.atak.transport.RadioSession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ATAK-shaped lifecycle and the contact, chat, and marker lists a plugin UI
 * would read. This class uses no {@code com.atakmap} type. Map placement,
 * preferences screens, and tool menus are not implemented. Nothing here is
 * loaded into ATAK.
 */
public final class PluginHost {
    public enum Life { NEW, CREATED, STARTED, STOPPED, DESTROYED }

    public static final class Contact {
        public final String uid;
        public String callsign = "";
        public double lat = Double.NaN;
        public double lon = Double.NaN;
        public long staleEpoch;
        public boolean stale;
        public boolean unverified = true;

        Contact(String uid) {
            this.uid = uid;
        }
    }

    public static final class ChatLine {
        public final String fromUid;
        public final String text;
        public final boolean unverified;

        ChatLine(String fromUid, String text, boolean unverified) {
            this.fromUid = fromUid;
            this.text = text;
            this.unverified = unverified;
        }
    }

    public static final class Marker {
        public final String name;
        public final double lat;
        public final double lon;
        public final boolean transmitted;

        Marker(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.transmitted = false;
        }
    }

    private static final int CONTACT_CAP = 256;
    private static final int CHAT_CAP = 64;
    private static final int MARKER_CAP = 64;
    private static final int COT_CAP = 65_536;

    private final Engine engine;
    private final RadioSession session;
    private final Map<String, Contact> contacts = new LinkedHashMap<>();
    private final List<ChatLine> chats = new ArrayList<>();
    private final List<Marker> markers = new ArrayList<>();
    private final List<Engine.RoomMessage> roomMessages = new ArrayList<>();
    private Life life = Life.NEW;
    private String lastWhy = "";

    public PluginHost() {
        this.engine = new Engine();
        this.session = new RadioSession(engine);
    }

    public PluginHost(Engine engine, RadioSession session) {
        this.engine = engine;
        this.session = session;
    }

    public Engine engine() {
        return engine;
    }

    public RadioSession session() {
        return session;
    }

    public Life life() {
        return life;
    }

    public void onCreate() {
        if (life == Life.DESTROYED) {
            return;
        }
        life = Life.CREATED;
    }

    public void onStart() {
        if (life == Life.DESTROYED || life == Life.NEW) {
            return;
        }
        life = Life.STARTED;
        session.setWritesEnabled(true);
    }

    public void onStop() {
        if (life != Life.STARTED) {
            return;
        }
        life = Life.STOPPED;
        session.setWritesEnabled(false);
    }

    public void onDestroy() {
        session.setWritesEnabled(false);
        life = Life.DESTROYED;
    }

    /** CoT strings accepted on this tick. The caller may hand them to ATAK. */
    public List<String> pump(long nowMs, long nowSeconds) {
        List<String> published = new ArrayList<String>();
        if (life != Life.STARTED) {
            return published;
        }
        session.tick(nowMs);
        for (Engine.Inbound inbound : engine.drainInbound()) {
            if (inbound.cot != null) {
                published.add(inbound.cot);
            }
            ingest(inbound.cot, nowSeconds);
        }
        for (Engine.RoomMessage message : engine.drainRoomMessages()) {
            if (chats.size() >= CHAT_CAP) {
                chats.remove(0);
            }
            chats.add(new ChatLine(message.senderLabel(), message.text, true));
            if (roomMessages.size() < CHAT_CAP) {
                roomMessages.add(message);
            }
        }
        refreshStale(nowSeconds);
        return published;
    }

    /**
     * Classify one outbound CoT string. Loopback traffic is dropped. PLI is not
     * copied onto the radio; the opt-in beacon is the only position path.
     * Waypoints are remembered and not transmitted.
     */
    public String offerOutboundCot(String cot, long nowMs) {
        lastWhy = "";
        if (life != Life.STARTED) {
            lastWhy = SendReasons.STOPPED;
            return "STOPPED";
        }
        if (cot == null || cot.length() > COT_CAP) {
            lastWhy = "Not sent: message could not be read";
            return "REFUSED";
        }
        if (Cot.hasMeshcoreLoopMarker(cot)) {
            engine.counters().inc("cot_loop_dropped");
            return "DROP_LOOP";
        }
        String type = attr(cot, "type");
        if ("a-f-G-U-C".equals(type)) {
            return "USE_OPT_IN_BEACON";
        }
        if ("b-m-p-w".equals(type) || "b-m-p-s-m".equals(type)) {
            rememberMarker(cot);
            return engine.sendMarker(cot, nowMs).status;
        }
        if ("b-t-f".equals(type)) {
            // G2: a chat event with no destination is never sent. Only offerOutboundChat, which
            // knows the destination uids, can reach the radio, and only for the MeshCore room.
            engine.counters().inc("chat_other_ignored");
            return "IGNORED";
        }
        return "UNSUPPORTED";
    }

    public List<Contact> contacts() {
        return Collections.unmodifiableList(new ArrayList<Contact>(contacts.values()));
    }

    public List<ChatLine> chats() {
        return Collections.unmodifiableList(new ArrayList<ChatLine>(chats));
    }

    public List<Marker> markers() {
        return Collections.unmodifiableList(new ArrayList<Marker>(markers));
    }

    public Contact contact(String uid) {
        return contacts.get(uid);
    }

    private void ingest(String cot, long nowSeconds) {
        if (cot == null || cot.length() > COT_CAP) {
            return;
        }
        String type = attr(cot, "type");
        if ("a-f-G-U-C".equals(type)) {
            String uid = attr(cot, "uid");
            if (uid.isEmpty()) {
                return;
            }
            Contact contact = contacts.computeIfAbsent(uid, Contact::new);
            trimContacts();
            contact.callsign = attr(cot, "callsign");
            contact.lat = parseDouble(attr(cot, "lat"));
            contact.lon = parseDouble(attr(cot, "lon"));
            contact.staleEpoch = parseEpoch(attr(cot, "stale"));
            contact.unverified = cot.contains("unverified=\"true\"");
            contact.stale = contact.staleEpoch > 0 && nowSeconds > contact.staleEpoch;
            return;
        }
        if ("b-t-f".equals(type)) {
            String text = remarks(cot);
            if (chats.size() >= CHAT_CAP) {
                chats.remove(0);
            }
            chats.add(new ChatLine(attr(cot, "uid"), text, cot.contains("unverified=\"true\"")));
        }
    }

    /**
     * G2: one chat message ATAK reported as sent, with the uids ATAK addressed it to. Only a
     * message addressed to exactly the MeshCore room's uid can transmit. Everything else (all
     * chat rooms, a TAK-server group, a person, a message with no destination list) is ignored
     * and counted, and its text is not read. A message that carries this plugin's own marker is
     * dropped as a loop. Returns the engine status ("QUEUED", ...), "IGNORED", "DROP_LOOP",
     * "DUPLICATE", "STOPPED" or "REFUSED".
     */
    public String offerOutboundChat(String cot, String[] toUids, long nowMs) {
        lastWhy = "";
        if (life != Life.STARTED) {
            lastWhy = SendReasons.STOPPED;
            return "STOPPED";
        }
        if (cot == null) {
            engine.counters().inc("chat_other_ignored");
            return "IGNORED";
        }
        if (Cot.hasMeshcoreLoopMarker(cot)) {
            engine.counters().inc("cot_loop_dropped");
            return "DROP_LOOP";
        }
        if (!"b-t-f".equals(attr(cot, "type"))) {
            engine.counters().inc("chat_other_ignored");
            return "IGNORED";
        }
        if (toUids == null || toUids.length != 1 || toUids[0] == null
                || !toUids[0].startsWith(ROOM_UID_PREFIX)) {
            engine.counters().inc("chat_other_ignored");
            return "IGNORED";
        }
        if (cot.length() > COT_CAP) {
            lastWhy = "Not sent: message could not be read";
            return "REFUSED";
        }
        Engine.Send send = engine.sendFromRoom(toUids[0], attr(cot, "uid"), remarks(cot), nowMs);
        lastWhy = send.why;
        return send.status;
    }

    /** Prefix of every room uid. Only a destination that starts with it is looked at. */
    public static final String ROOM_UID_PREFIX = "MESHCORE-ROOM-";

    /** G3: room messages the last {@link #pump} took from the engine. The caller shows them. */
    public List<Engine.RoomMessage> takeRoomMessages() {
        List<Engine.RoomMessage> out = new ArrayList<Engine.RoomMessage>(roomMessages);
        roomMessages.clear();
        return out;
    }

    /**
     * E10: why the last {@link #offerOutboundCot} sent nothing, or "" when it was
     * queued or was not a send at all. Never contains message text.
     */
    public String lastWhy() {
        return lastWhy;
    }

    private void rememberMarker(String cot) {
        if (markers.size() >= MARKER_CAP) {
            markers.remove(0);
        }
        markers.add(new Marker(attr(cot, "callsign"), parseDouble(attr(cot, "lat")), parseDouble(attr(cot, "lon"))));
    }

    private void refreshStale(long nowSeconds) {
        for (Contact contact : contacts.values()) {
            if (contact.staleEpoch > 0 && nowSeconds > contact.staleEpoch) {
                contact.stale = true;
            }
        }
    }

    private void trimContacts() {
        while (contacts.size() > CONTACT_CAP) {
            String first = contacts.keySet().iterator().next();
            contacts.remove(first);
        }
    }

    private static String remarks(String cot) {
        int start = cot.indexOf("<remarks");
        if (start < 0) {
            return "";
        }
        int open = cot.indexOf('>', start);
        int close = cot.indexOf("</remarks>", open + 1);
        if (open < 0 || close < 0) {
            return "";
        }
        return unescape(cot.substring(open + 1, close));
    }

    private static String attr(String xml, String name) {
        String key = name + "=\"";
        int at = xml.indexOf(key);
        if (at < 0) {
            return "";
        }
        int start = at + key.length();
        int end = xml.indexOf('"', start);
        if (end < 0) {
            return "";
        }
        return unescape(xml.substring(start, end));
    }

    private static String unescape(String text) {
        return text.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static double parseDouble(String text) {
        if (text.isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    private static long parseEpoch(String text) {
        if (text.isEmpty()) {
            return 0L;
        }
        try {
            return Instant.parse(text).getEpochSecond();
        } catch (RuntimeException ex) {
            return 0L;
        }
    }
}
