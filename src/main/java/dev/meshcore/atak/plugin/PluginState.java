package dev.meshcore.atak.plugin;

import dev.meshcore.atak.engine.Engine;
import dev.meshcore.atak.transport.RadioChoice;

/**
 * E3: what the plugin remembers across restarts, and the code that remembers it.
 * Two things only: the last MCTAK sequence number and the address and name of
 * the radio the user chose. A channel choice is not saved. No key, PIN or message
 * text is stored.
 *
 * <p>v7.1 wrote the sequence to {@code pluginContext.getFilesDir()} every second.
 * The plugin runs in ATAK's process under ATAK's user id, so that directory was
 * not writable and the write failed 835 times. This class writes only when the
 * value changed, writes through a {@link KeyValueStore} backed by ATAK's own
 * context, and keeps the exception class and message for the diag.
 */
public final class PluginState {
    static final String KEY_SEQUENCE = "mctak_seq";
    static final String KEY_RADIO_ADDRESS = "radio_address";
    static final String KEY_RADIO_NAME = "radio_name";
    private static final int ERROR_CAP = 120;

    private final KeyValueStore store;
    private final Engine engine;
    private int lastSeen = -1;
    private int writes;
    private String lastError = "";

    public PluginState(KeyValueStore store, Engine engine) {
        this.store = store;
        this.engine = engine;
    }

    /** Continue the sequence after a restart. Failure is counted and kept for the diag. */
    public void restoreSequence() {
        try {
            String text = store.get(KEY_SEQUENCE);
            if (text != null && text.trim().length() > 0 && text.trim().length() <= 8) {
                int value = Integer.parseInt(text.trim());
                engine.resumeAfter(value);
            }
        } catch (Exception ex) {
            engine.counters().inc("sequence_restore_failed");
            lastError = describe(ex);
        }
        lastSeen = engine.lastIssuedSequence();
    }

    /**
     * Write the sequence only when it differs from the last value written or
     * seen. A failed write is counted once per value, not once per tick.
     */
    public void storeSequenceIfChanged() {
        int current = engine.lastIssuedSequence();
        if (current == lastSeen) {
            return;
        }
        lastSeen = current;
        try {
            store.put(KEY_SEQUENCE, Integer.toString(current));
            writes++;
        } catch (Exception ex) {
            engine.counters().inc("sequence_store_failed");
            lastError = describe(ex);
        }
    }

    /** Number of successful sequence writes. */
    public int writes() {
        return writes;
    }

    /** "ClassName: message" of the last storage failure, or "". */
    public String lastError() {
        return lastError;
    }

    /** The remembered radio address, or null when none is saved or the saved value is corrupt. */
    public String savedRadioAddress() {
        try {
            String address = store.get(KEY_RADIO_ADDRESS);
            return RadioChoice.looksLikeAddress(address) ? address.toUpperCase(java.util.Locale.US) : null;
        } catch (Exception ex) {
            lastError = describe(ex);
            return null;
        }
    }

    public String savedRadioName() {
        try {
            String name = store.get(KEY_RADIO_NAME);
            return name == null ? "" : name;
        } catch (Exception ex) {
            lastError = describe(ex);
            return "";
        }
    }

    /** Remember the radio the user chose. Returns false when it could not be stored. */
    public boolean saveRadio(String address, String name) {
        try {
            store.put(KEY_RADIO_ADDRESS, address);
            store.put(KEY_RADIO_NAME, name == null ? "" : name);
            return true;
        } catch (Exception ex) {
            engine.counters().inc("radio_save_failed");
            lastError = describe(ex);
            return false;
        }
    }

    /** "Forget radio": the next CONNECT asks again. */
    public void forgetRadio() {
        try {
            store.remove(KEY_RADIO_ADDRESS);
            store.remove(KEY_RADIO_NAME);
        } catch (Exception ex) {
            engine.counters().inc("radio_forget_failed");
            lastError = describe(ex);
        }
    }

    static String describe(Exception ex) {
        String message = ex.getMessage();
        String text = ex.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
        return text.length() > ERROR_CAP ? text.substring(0, ERROR_CAP) : text;
    }
}
