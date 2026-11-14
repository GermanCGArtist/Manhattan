package dev.baritonequeue;

/**
 * Tracks pause state for script execution (#pause / #resume / #stop / #cancel).
 */
final class PauseWatch {
    private PauseWatch() {}

    static volatile boolean paused = false;

    static void handleCommand(String raw) {
        String m = raw == null ? "" : raw.trim();
        if (m.startsWith("#")) m = m.substring(1).trim();
        if (m.isEmpty()) return;
        String[] w = m.toLowerCase().split("\\s+");
        switch (w[0]) {
            case "p", "pause", "paws" -> paused = w.length > 1 && w[1].equals("toggle") ? !paused : true;
            case "r", "resume", "unpause", "unp" -> paused = false;
            case "stop", "cancel" -> QueueMod.runner.halt("You cancelled the task, so the queue stopped. /queue run starts it again.");
            default -> {}
        }
    }
}
