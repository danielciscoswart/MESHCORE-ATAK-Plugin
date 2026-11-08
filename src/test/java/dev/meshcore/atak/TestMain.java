package dev.meshcore.atak;

public final class TestMain {
    public static void main(String[] args) {
        int failed = AllTests.run();
        if (failed != 0) {
            System.exit(1);
        }
    }
}
