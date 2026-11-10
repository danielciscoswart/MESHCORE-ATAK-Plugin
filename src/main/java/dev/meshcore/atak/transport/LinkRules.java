package dev.meshcore.atak.transport;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Small decisions and texts for the connect path, kept free of Android types so
 * a JVM test can check them. {@code MeshcoreGattLink} calls these; the startup
 * source check makes sure it does.
 */
public final class LinkRules {
    /** Android bond-state values (BluetoothDevice.BOND_*). Copied so this class needs no Android jar. */
    public static final int BOND_NONE = 10;
    public static final int BOND_BONDING = 11;
    public static final int BOND_BONDED = 12;

    /** Default ATT MTU. Staying here means the MTU request did nothing. */
    public static final int DEFAULT_MTU = 23;

    /** C1: short reason for the failure text and toast. */
    public static final String HELD_REASON = "radio held by another app: force-stop MeshCore";
    /** C1: the full sentence, shown in the scrolling status area. */
    public static final String HELD_ADVICE =
            "Radio already connected to this phone by another app. Force-stop MeshCore, then CONNECT.";

    /** Android allows an app 5 scan starts per 30 s; more silently return nothing. */
    public static final int SCAN_STARTS_PER_WINDOW = 5;
    public static final long SCAN_WINDOW_MS = 30_000L;
    public static final String SCAN_LIMIT_REASON = "Android scan limit, wait 30 s";

    /** C4 follow-up: still NONE this long after createBond() means pairing never started. */
    public static final long BOND_START_GRACE_MS = 10_000L;

    private LinkRules() {}

    /** C3: a short "likely" hint for a GATT status code, or "" when there is none. */
    public static String hint(int status) {
        switch (status) {
            case 5:
            case 15:
            case 137:
                return "likely pairing keys rejected";
            case 8:
                return "likely link timed out";
            case 19:
                return "likely radio closed the link";
            case 133:
            case 62:
                return "likely could not connect";
            default:
                return "";
        }
    }

    /** C3: longer advice for the scrolling area, or "" when there is none. */
    public static String advice(int status) {
        switch (status) {
            case 5:
            case 15:
            case 137:
                return "Pairing keys were probably rejected: remove the radio from Android's paired devices, then CONNECT.";
            case 8:
                return "The link probably timed out: is the radio on and in range?";
            default:
                return "";
        }
    }

    /** C1: true when the target address is already in the phone's GATT-connected list. */
    public static boolean heldByAnotherApp(Collection<String> connectedAddresses, String target) {
        if (connectedAddresses == null || target == null) {
            return false;
        }
        for (String address : connectedAddresses) {
            if (address != null && address.toUpperCase(Locale.US).equals(target.toUpperCase(Locale.US))) {
                return true;
            }
        }
        return false;
    }

    /**
     * C1 guard against a false alarm: right after this plugin closes its own GATT client, Android
     * may still list that connection for a moment. Within this window the held-by-another-app
     * check is skipped, so DISCONNECT followed quickly by CONNECT is not blamed on MeshCore.
     */
    public static final long OWN_CLOSE_SETTLE_MS = 5_000L;

    public static boolean ownCloseStillSettling(long lastCloseMs, long nowMs) {
        return lastCloseMs > 0L && nowMs - lastCloseMs < OWN_CLOSE_SETTLE_MS;
    }

    /** C2: a failed GATT write ends the attempt in these stages. */
    public static boolean writeFailureEndsAttempt(LinkMachine.State state) {
        return state == LinkMachine.State.HANDSHAKE || state == LinkMachine.State.READY;
    }

    /** C4: after createBond() returned false, only a bond state of NONE is a failure. */
    public static boolean bondRefusalIsFatal(int bondState) {
        return bondState != BOND_BONDING && bondState != BOND_BONDED;
    }

    /** C4 follow-up: bond still NONE, never seen BONDING, and the grace period passed. */
    public static boolean pairingNeverStarted(boolean sawBonding, int bondState, long sinceCreateBondMs) {
        return !sawBonding && bondState == BOND_NONE && sinceCreateBondMs >= BOND_START_GRACE_MS;
    }

    /** C5: failure text when the MTU is still the default, else null. */
    public static String mtuProblem(int mtu) {
        if (mtu <= DEFAULT_MTU) {
            return "MTU not negotiated (" + mtu + ")";
        }
        return null;
    }

    /** True when starting another scan now would exceed Android's 5-per-30 s limit. */
    public static boolean scanLimitHit(List<Long> recentStartsMs, long nowMs) {
        int inWindow = 0;
        for (Long at : recentStartsMs) {
            if (at != null && nowMs - at < SCAN_WINDOW_MS) {
                inWindow++;
            }
        }
        return inWindow >= SCAN_STARTS_PER_WINDOW;
    }
}
