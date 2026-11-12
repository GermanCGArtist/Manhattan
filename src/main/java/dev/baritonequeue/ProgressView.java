package dev.baritonequeue;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The progress display: one bar cut into a piece per step (green = done, blue = the running one, filled by its own
 * progress), the share of the whole script, and a second bar for the running step. Used in the editor and as an
 * overlay while the queue runs.
 */
final class ProgressView {
    private ProgressView() {}

    static final int HEIGHT = 40;

    private static final int BACK = 0xFF2A2A2A, NOW = 0xFF4A9EE0, EDGE = 0xFF000000;

    /** The Baritone process in control ("Builder", "SurvivalKit sleep", ...), or "idle". */
    private static String controller() {
        return RunnerView.controller();
    }

    static void draw(GuiGraphicsExtractor g, Font font, int x, int y, int w) {
        Runner r = QueueMod.runner;
        if (!r.running()) {
            g.text(font, "Progress shows here while the script runs.", x, y + 10, 0xFF707070);
            return;
        }
        Actions.Action a = r.action();
        Estimate e = a == null ? new Estimate() : a.estimate;
        double f = Math.max(0, e.fraction());

        String head = "Line " + r.line() + "  -  " + r.actionsDone() + " done  -  " + Estimate.duration(r.runningMillis() / 50);
        g.text(font, head, x, y, 0xFFFFFFFF, false);
        // who is steering Baritone right now: shows at a glance when another process (or nothing) holds it
        String who = controller();
        if (!who.isEmpty()) {
            String shownWho = font.plainSubstrByWidth(who, Math.max(20, w - font.width(head) - 12));
            g.text(font, shownWho, x + w - font.width(shownWho), y, 0xFF8FB4D8, false);
        }

        // the running action
        String pct = e.known() ? Math.round(f * 100) + "%" : "running";
        String left = e.left();
        long eta = e.etaTicks();
        StringBuilder sb = new StringBuilder(pct);
        if (!left.isEmpty()) sb.append("  -  ").append(left);
        if (eta >= 0) sb.append("  -  about ").append(Estimate.duration(eta)).append(" left");
        else if (!e.known() && a != null) sb.append("  -  ").append(Estimate.duration(a.ticks));
        String tail = sb.toString();
        int tailW = font.width(tail);
        String cmd = font.plainSubstrByWidth(r.current(), Math.max(20, w - tailW - 10));
        g.text(font, cmd, x, y + 12, 0xFFDDDDDD, false);
        g.text(font, tail, x + w - tailW, y + 12, 0xFFAAAAAA, false);

        int by = y + 23;
        g.fill(x - 1, by - 1, x + w + 1, by + 5, EDGE);
        g.fill(x, by, x + w, by + 4, BACK);
        if (a == null) {
            // the script itself is busy (a loop, wait until ...): nothing to fill
        } else if (e.known()) {
            g.fill(x, by, x + (int) (w * f), by + 4, NOW);
        } else {   // unknown length: a block that moves back and forth
            int span = Math.max(8, w / 6);
            long t = System.currentTimeMillis() / 12;
            int pos = (int) (t % (2L * (w - span)));
            if (pos > w - span) pos = 2 * (w - span) - pos;
            g.fill(x + pos, by, x + pos + span, by + 4, NOW);
        }

        int limit = Runner.stallLimit();
        if (a instanceof Actions.BaritoneCommand bc && limit > 0 && bc.noProgressTicks() > 1200) {   // no progress for over a minute: say so
            String warn = "No progress for " + Estimate.duration(bc.noProgressTicks()) + " - skipping after " + Estimate.duration(limit);
            g.text(font, font.plainSubstrByWidth(warn, w), x, y + 31, 0xFFFFA040, false);
        }
    }
}
