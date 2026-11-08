package dev.meshcore.atak.host;

import com.atakmap.comms.CommsLogger;
import com.atakmap.coremap.cot.event.CotEvent;

import java.util.ArrayDeque;

/**
 * Optional observe-only copy of outbound GeoChat. Default off.
 *
 * <p>ATAK-CIV 5.6.0 javadoc (API Documentation 5.6.0, VERSION.txt 47de477)
 * documents {@code CommsLogger.logSend} as a metrics and debug callback and
 * says other uses are not recommended. It does not say the callback can
 * replace or delay ATAK's own send. This class does not change the event and
 * does not transmit on the callback thread. {@code PreSendProcessor} is not
 * used: that slot is single-plugin, has no documented unregister, and the
 * javadoc says not to send from inside it.
 */
final class ExperimentalGeoChatWatch implements CommsLogger {
    static final int MAX_PENDING = 8;
    static final int MAX_XML_CHARS = 8192;

    private final ArrayDeque<String> pending = new ArrayDeque<String>();
    private int dropped;

    @Override
    public void logSend(CotEvent message, String destination) {
        hold(message);
    }

    @Override
    public void logSend(CotEvent message, String[] toUids) {
        hold(message);
    }

    @Override
    public void logReceive(CotEvent message, String rxid, String server) {
        // Incoming ATAK traffic is not rebroadcast. Radio inbound has its own path.
    }

    @Override
    public void dispose() {
        synchronized (pending) {
            pending.clear();
        }
    }

    int dropped() {
        synchronized (pending) {
            return dropped;
        }
    }

    String poll() {
        synchronized (pending) {
            return pending.pollFirst();
        }
    }

    private void hold(CotEvent message) {
        if (message == null) {
            return;
        }
        String type = message.getType();
        if (type == null || !"b-t-f".equals(type)) {
            return;
        }
        String xml = message.toString();
        if (xml == null || xml.indexOf("__meshcore") >= 0 || xml.length() > MAX_XML_CHARS) {
            return;
        }
        synchronized (pending) {
            if (pending.size() >= MAX_PENDING) {
                pending.removeFirst();
                dropped++;
            }
            pending.addLast(xml);
        }
    }
}
