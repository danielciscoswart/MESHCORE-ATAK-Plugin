package dev.meshcore.atak.engine;

/**
 * E10: the exact sentences shown when a send is refused. The pane toasts them and
 * puts them on the status line. None of them carries message text, a key or a PIN.
 */
public final class SendReasons {
    public static final String NOT_READY = "Not sent: radio not ready";
    public static final String NO_CHANNEL = "Not sent: choose a private channel first";
    public static final String POSITION_OFF = "Not sent: position sending is off";
    public static final String NO_FIX = "Not sent: no GPS fix yet";
    public static final String STOPPED = "Not sent: plugin is stopped";
    public static final String EMPTY_TEXT = "Not sent: type some text first";
    /** v7.5: ATAK handed over a message id that was already queued once. */
    public static final String ALREADY_QUEUED = "Not sent again: that message was already queued";

    /**
     * The radio accepted the frame. That is all it says: it is not a delivery
     * receipt, and the word "delivered" is never used for a channel send.
     */
    public static final String SENT_TO_RADIO = "Sent to radio";

    /**
     * F2: a send command that got no answer. The radio may well have transmitted, so this is
     * not "Failed": the user must look at the channel before sending again. 68 characters, so
     * it fits a 70-character toast whole.
     */
    public static final String NO_ANSWER = "No answer from radio: it may have been sent. Check before resending.";

    private SendReasons() {}

    /** "Failed: reason" for a radio error code from the companion protocol (firmware error codes). */
    public static String radioFailure(int code) {
        switch (code) {
            case -1: return NO_ANSWER;
            case 1: return "Failed: radio does not support this command";
            case 2: return "Failed: channel slot not found on the radio";
            case 3: return "Failed: radio queue is full";
            case 4: return "Failed: radio is busy";
            case 6: return "Failed: radio refused the frame (too big)";
            default: return "Failed: radio error " + code;
        }
    }

    /** "Not sent: text too long (have/max bytes)". */
    public static String tooLong(int haveBytes, int maxBytes) {
        return "Not sent: text too long (" + haveBytes + "/" + maxBytes + " bytes)";
    }
}
