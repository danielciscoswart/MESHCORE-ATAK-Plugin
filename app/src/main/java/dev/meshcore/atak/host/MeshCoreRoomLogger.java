package dev.meshcore.atak.host;

import com.atakmap.comms.CommsLogger;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;

import java.util.ArrayDeque;

import dev.meshcore.atak.plugin.PluginHost;

/**
 * Observe-only comms logger for the MeshCore chat room (G2, G4). It replaces the v7.2
 * {@code ExperimentalGeoChatWatch}, which held every outbound GeoChat of every destination.
 *
 * <p>It never changes an event and never transmits. The callbacks run on ATAK's comms thread, so
 * they only do a cheap type check and a short hand-off; the plugin's one-second tick drains the
 * queues on the main thread.
 * <ul>
 *   <li>{@code logSend(event, String[] toUids)}: a chat event is held only when ATAK addressed it
 *       to exactly one uid and that uid is the MeshCore room's. Every other chat (all chat
 *       rooms, a TAK-server group, a person) is counted and its text is not read.</li>
 *   <li>{@code logSend(event, String destination)}: counted only. The room has no endpoint, so a
 *       send to an address is never the room.</li>
 *   <li>{@code logReceive}: a chat that ATAK received from the network is reduced to the sender
 *       callsign and the text, so the engine can recognise the same message when it arrives over
 *       the radio as well (G4). Nothing is kept beyond a hash.</li>
 * </ul>
 * A message that carries the plugin's own {@code __meshcore} marker is a copy the plugin injected;
 * the engine side drops it (loop guard).
 */
final class MeshCoreRoomLogger implements CommsLogger {
    static final int MAX_PENDING = 8;
    static final int MAX_NETWORK = 16;
    static final int MAX_XML_CHARS = 8192;

    static final class Outbound {
        final String xml;
        final String[] toUids;

        Outbound(String xml, String[] toUids) {
            this.xml = xml;
            this.toUids = toUids;
        }
    }

    static final class NetworkChat {
        final String callsign;
        final String text;

        NetworkChat(String callsign, String text) {
            this.callsign = callsign;
            this.text = text;
        }
    }

    private final ArrayDeque<Outbound> pending = new ArrayDeque<Outbound>();
    private final ArrayDeque<NetworkChat> network = new ArrayDeque<NetworkChat>();
    private int ignoredOther;
    private int dropped;

    @Override
    public void logSend(CotEvent message, String[] toUids) {
        if (!isChat(message)) {
            return;
        }
        if (toUids == null || toUids.length != 1 || toUids[0] == null
                || !toUids[0].startsWith(PluginHost.ROOM_UID_PREFIX)) {
            synchronized (pending) {
                ignoredOther++;
            }
            return;
        }
        String xml = message.toString();
        if (xml == null || xml.length() > MAX_XML_CHARS) {
            synchronized (pending) {
                ignoredOther++;
            }
            return;
        }
        synchronized (pending) {
            if (pending.size() >= MAX_PENDING) {
                pending.removeFirst();
                dropped++;
            }
            pending.addLast(new Outbound(xml, new String[] {toUids[0]}));
        }
    }

    @Override
    public void logSend(CotEvent message, String destination) {
        if (isChat(message)) {
            synchronized (pending) {
                ignoredOther++;
            }
        }
    }

    @Override
    public void logReceive(CotEvent message, String rxid, String server) {
        if (!isChat(message)) {
            return;
        }
        try {
            CotDetail chat = message.findDetail("__chat");
            CotDetail remarks = message.findDetail("remarks");
            if (chat == null || remarks == null) {
                return;
            }
            String callsign = chat.getAttribute("senderCallsign");
            String text = remarks.getInnerText();
            if (callsign == null || text == null || message.findDetail("__meshcore") != null) {
                return;
            }
            synchronized (network) {
                if (network.size() >= MAX_NETWORK) {
                    network.removeFirst();
                }
                network.addLast(new NetworkChat(callsign, text));
            }
        } catch (RuntimeException ex) {
            // A malformed network event must never reach ATAK's comms thread as an exception.
        }
    }

    @Override
    public void dispose() {
        synchronized (pending) {
            pending.clear();
        }
        synchronized (network) {
            network.clear();
        }
    }

    Outbound pollOutbound() {
        synchronized (pending) {
            return pending.pollFirst();
        }
    }

    NetworkChat pollNetwork() {
        synchronized (network) {
            return network.pollFirst();
        }
    }

    /** Chats seen with some other destination since the last call. */
    int takeIgnored() {
        synchronized (pending) {
            int n = ignoredOther;
            ignoredOther = 0;
            return n;
        }
    }

    int dropped() {
        synchronized (pending) {
            return dropped;
        }
    }

    private static boolean isChat(CotEvent message) {
        if (message == null) {
            return false;
        }
        String type = message.getType();
        return type != null && "b-t-f".equals(type);
    }
}
