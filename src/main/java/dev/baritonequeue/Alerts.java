package dev.baritonequeue;

import java.util.ArrayList;
import java.util.List;

/** What needs attention right now, most severe first: the latest error, a stalled step, worn tools, low health/food. */
final class Alerts {
    private Alerts() {}

    enum Level { ERROR, WARN, INFO }

    record Alert(Level level, String title, String detail, long key) {}

    /** Errors older than this stop being shown as alerts (they stay in the log). */
    private static final long ERROR_TTL_MS = 10 * 60_000L;
    private static long dismissedErrorId = -1;
    private static long lastErrorId = -1;
    private static long lastErrorSeenAt;

    static List<Alert> list(ProgressModel.Snapshot snap) {
        List<Alert> out = new ArrayList<>();
        LogBuffer.Entry err = LogBuffer.lastError();
        long now = System.currentTimeMillis();
        if (err != null) {
            if (err.id() != lastErrorId) {
                lastErrorId = err.id();
                lastErrorSeenAt = now;
            }
            if (err.id() != dismissedErrorId && now - lastErrorSeenAt < ERROR_TTL_MS) {
                out.add(new Alert(Level.ERROR, shorten(err.text(), 90), err.time(), err.id()));
            }
        }
        if (!snap.stallWarning().isEmpty()) {
            out.add(new Alert(Level.WARN, "Step is not making progress", snap.stallWarning(), -2));
        }
        int worn = PlayerView.wornThreshold();
        for (PlayerView.Slot s : PlayerView.tools()) {
            if (s.durability() <= Math.max(worn * 2, 20)) {
                out.add(new Alert(Level.WARN, PlayerView.pretty(s.id()) + " at " + s.durability() + " durability",
                        "Worn out at " + worn + "; restock() swaps it", -3));
                break;
            }
        }
        if (snap.health() > 0 && snap.health() <= snap.maxHealth() * 0.35f) {
            out.add(new Alert(Level.WARN, "Health low: " + Math.round(snap.health()) + "/" + Math.round(snap.maxHealth()), "", -4));
        }
        if (snap.food() <= 6) {
            out.add(new Alert(Level.WARN, "Food low: " + snap.food() + "/20", "", -5));
        }
        if (PlayerView.get().freeSlots() == 0) {
            out.add(new Alert(Level.INFO, "Inventory full", "", -6));
        }
        return out;
    }

    static void dismiss(Alert a) {
        if (a.level() == Level.ERROR) dismissedErrorId = a.key();
    }

    static String shorten(String s, int max) {
        s = s.replace('\n', ' ');
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
