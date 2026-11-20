package dev.baritonequeue;

import dev.baritonequeue.project.GameProjectRuntime;
import dev.midtown.api.RuntimeBus;

import java.util.Locale;

/**
 * Tracks pause state for script and project execution (#pause / #resume / #stop / #cancel),
 * backed by {@link RuntimeBus}.
 */
final class PauseWatch {
    private PauseWatch() {}

    static boolean isPaused() {
        return RuntimeBus.isPaused();
    }

    static void setPaused(boolean paused) {
        RuntimeBus.setPaused(paused);
    }

    static void handleCommand(String raw) {
        if (RuntimeBus.handlePauseCommand(raw)) {
            if (GameProjectRuntime.instance().running()) {
                if (RuntimeBus.isPaused()) GameProjectRuntime.instance().scheduler().pause();
                else GameProjectRuntime.instance().scheduler().resume();
            }
            return;
        }
        String m = raw == null ? "" : raw.trim();
        if (m.startsWith("#")) m = m.substring(1).trim();
        if (m.isEmpty()) return;
        String[] w = m.toLowerCase(Locale.ROOT).split("\\s+");
        switch (w[0]) {
            case "stop", "cancel" -> {
                if (QueueMod.runner.running()) {
                    QueueMod.runner.halt("You cancelled the task, so the queue stopped. /queue run starts it again.");
                }
                if (GameProjectRuntime.instance().running()) {
                    GameProjectRuntime.instance().stopQueue("Cancelled by user.");
                }
            }
            default -> {}
        }
    }
}
