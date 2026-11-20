package dev.loadchest;

import dev.midtown.api.RuntimeBus;

/**
 * Tracks pause state, delegated to {@link RuntimeBus}.
 */
final class PauseWatch {
    private PauseWatch() {}

    static boolean isPaused() {
        return RuntimeBus.isPaused();
    }

    public static void setPaused(boolean p) {
        RuntimeBus.setPaused(p);
    }
}
