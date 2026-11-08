package dev.meshcore.atak.mctak;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Every drop, skip, and refusal increments a named counter. Nothing is silent. */
public final class Counters {
    private final Map<String, Long> values = new LinkedHashMap<>();

    public void inc(String name) {
        values.merge(name, 1L, Long::sum);
    }

    public long get(String name) {
        return values.getOrDefault(name, 0L);
    }

    public Map<String, Long> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
