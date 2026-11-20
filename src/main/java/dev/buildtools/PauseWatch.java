package dev.buildtools;

import dev.midtown.api.RuntimeBus;

/**
 * Tracks pause state for buildtools helpers, delegated to {@link RuntimeBus}.
 */
final class PauseWatch {
    private PauseWatch() {}

    static boolean isPaused() {
        return RuntimeBus.isPaused();
    }

    static void setPaused(boolean p) {
        RuntimeBus.setPaused(p);
    }
}
