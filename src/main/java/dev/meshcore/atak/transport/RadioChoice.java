package dev.meshcore.atak.transport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * E1: which radio the plugin talks to. No Android types, so a JVM test can check
 * the rules. The plugin never picks a radio by itself on the first connect: it
 * lists every match and the user chooses. A chosen radio is remembered by its
 * address and later connects go straight to it.
 */
public final class RadioChoice {
    /** What a CONNECT tap does, given what is remembered. */
    public enum Plan {
        /** A remembered radio that Android still has paired: connect to its address. */
        DIRECT_SAVED,
        /** A remembered radio that is not paired: scan, but only accept that address. */
        SCAN_FOR_SAVED,
        /** Nothing remembered: scan, then ask the user. */
        CHOOSE
    }

    /** One radio the scan found that looks like a MeshCore companion. */
    public static final class Candidate {
        public final String address;
        public final String name;
        public final int rssi;
        public final boolean paired;

        public Candidate(String address, String name, int rssi, boolean paired) {
            this.address = address;
            this.name = name == null || name.isEmpty() ? "(no-name)" : name;
            this.rssi = rssi;
            this.paired = paired;
        }

        /** The dialog row: name, signal strength, short address, paired yes/no. */
        public String line() {
            return name + "\n" + rssi + " dBm  " + shortAddress(address) + "  paired: " + (paired ? "yes" : "no");
        }
    }

    private RadioChoice() {}

    public static Plan plan(String savedAddress, boolean savedIsPaired) {
        if (savedAddress == null || savedAddress.isEmpty()) {
            return Plan.CHOOSE;
        }
        return savedIsPaired ? Plan.DIRECT_SAVED : Plan.SCAN_FOR_SAVED;
    }

    /** "...A2:95": the last two address bytes, enough to tell two radios apart. */
    public static String shortAddress(String address) {
        if (address == null || address.length() < 5) {
            return "";
        }
        return "..." + address.substring(address.length() - 5).toUpperCase(Locale.US);
    }

    /** Name plus short address, for the status line and the connect step. */
    public static String label(String name, String address) {
        String shown = name == null || name.isEmpty() ? "(no-name)" : name;
        String tail = shortAddress(address);
        return tail.isEmpty() ? shown : shown + " " + tail;
    }

    public static boolean sameAddress(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    /** A Bluetooth MAC: six hex pairs. Used to reject a corrupt saved value. */
    public static boolean looksLikeAddress(String address) {
        return address != null && address.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}");
    }

    /** Strongest signal first. The order is only for display; nothing is picked from it. */
    public static List<Candidate> byStrength(List<Candidate> in) {
        List<Candidate> out = new ArrayList<Candidate>(in);
        Collections.sort(out, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                return Integer.compare(b.rssi, a.rssi);
            }
        });
        return out;
    }
}
