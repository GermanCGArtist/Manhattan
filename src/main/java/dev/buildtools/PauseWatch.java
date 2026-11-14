package dev.buildtools;

/**
 * Tracks pause state for buildtools helpers.
 */
final class PauseWatch {
    private PauseWatch() {}

    static volatile boolean paused = false;

    static void setPaused(boolean p) {
        paused = p;
    }
}
