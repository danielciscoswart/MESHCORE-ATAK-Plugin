package dev.meshcore.atak.plugin;

import java.io.IOException;

/**
 * Small string storage the plugin writes through. On the phone it is
 * SharedPreferences obtained from ATAK's own context (E3); in tests it is a map.
 */
public interface KeyValueStore {
    /** The value, or null when absent. */
    String get(String key);

    void put(String key, String value) throws IOException;

    void remove(String key) throws IOException;
}
