package dev.loadchest;

/**
 * Tracks pause state.
 */
final class PauseWatch {
    private PauseWatch() {}

    static volatile boolean paused = false;

    public static void setPaused(boolean p) {
        paused = p;
    }
}
