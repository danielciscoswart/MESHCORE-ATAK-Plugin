package dev.meshcore.atak;

/** A skipped optional check. It must not be counted as a passing test. */
final class Outcome {
    static final class Skip extends RuntimeException {
        Skip(String message) {
            super(message);
        }
    }

    private Outcome() {}
}
