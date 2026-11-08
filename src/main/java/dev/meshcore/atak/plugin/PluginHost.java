package dev.meshcore.atak.plugin;

import dev.meshcore.atak.cot.Cot;
import dev.meshcore.atak.engine.Engine;
import dev.meshcore.atak.mctak.Mctak;
import dev.meshcore.atak.transport.RadioSession;

import java.time.Instant;
import java.util.ArrayList;
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
    private Life life = Life.NEW;

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

    public void pump(long nowMs, long nowSeconds) {
        if (life != Life.STARTED) {
            return;
        }
        session.tick(nowMs);
        for (Engine.Inbound inbound : engine.drainInbound()) {
            ingest(inbound.cot, nowSeconds);
        }
        refreshStale(nowSeconds);
    }

    /**
     * Classify one outbound CoT string. Loopback traffic is dropped. PLI is not
     * copied onto the radio; the opt-in beacon is the only position path.
     * Waypoints are remembered and not transmitted.
     */
    public String offerOutboundCot(String cot, long nowMs) {
        if (life != Life.STARTED) {
            return "STOPPED";
        }
        if (cot == null || cot.length() > COT_CAP) {
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
            return offerChat(cot, nowMs);
        }
        return "UNSUPPORTED";
    }

    public List<Contact> contacts() {
        return List.copyOf(contacts.values());
    }

    public List<ChatLine> chats() {
        return List.copyOf(chats);
    }

    public List<Marker> markers() {
        return List.copyOf(markers);
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

    private String offerChat(String cot, long nowMs) {
        String text = remarks(cot);
        String room = attr(cot, "chatroom");
        int scope = Mctak.SCOPE_ROOM;
        int dest = 0;
        String roomName = room;
        if (room.isEmpty() || "All Chat Rooms".equals(room)) {
            scope = Mctak.SCOPE_ALL;
            roomName = "";
        } else if (room.startsWith("MCTAK-") && room.length() == "MCTAK-".length() + 8) {
            try {
                dest = (int) Long.parseLong(room.substring("MCTAK-".length()), 16);
                scope = Mctak.SCOPE_DIRECT;
                roomName = "";
            } catch (NumberFormatException ex) {
                return "REFUSED";
            }
        }
        return engine.sendChat(scope, dest, roomName, text, nowMs).status;
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
