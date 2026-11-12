package dev.baritonequeue;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/**
 * The progress display: one bar cut into a piece per step (green = done, blue = the running one, filled by its own
 * progress), the share of the whole script, and a second bar for the running step. Used in the editor and as an
 * overlay while the queue runs. Reads all state through {@link ProgressModel}.
 */
final class ProgressView {
    private ProgressView() {}

    static final int HEIGHT = 40;

    private static final int BACK = 0xFF2A2A2A, DONE = 0xFF3FB27F, NOW = 0xFF4A9EE0, EDGE = 0xFF000000;

    static void draw(GuiGraphicsExtractor g, Font font, int x, int y, int w) {
        ProgressModel.Snapshot snap = ProgressModel.snapshot();
        if (!snap.running()) {
            g.text(font, "Progress shows here while the script runs.", x, y + 10, 0xFF707070);
            return;
        }

        String head = "Line " + snap.line() + "  -  " + snap.actionsDone() + " done  -  " + snap.elapsedText();
        g.text(font, head, x, y, 0xFFFFFFFF, false);
        String who = snap.controller();
        if (!who.isEmpty()) {
            String shownWho = font.plainSubstrByWidth(who, Math.max(20, w - font.width(head) - 12));
            g.text(font, shownWho, x + w - font.width(shownWho), y, 0xFF8FB4D8, false);
        }

        String tail = snap.stepTailText();
        int tailW = font.width(tail);
        String cmd = font.plainSubstrByWidth(snap.currentCommand(), Math.max(20, w - tailW - 10));
        g.text(font, cmd, x, y + 12, 0xFFDDDDDD, false);
        g.text(font, tail, x + w - tailW, y + 12, 0xFFAAAAAA, false);

        int by = y + 23;
        List<ProgressModel.Segment> segs = snap.segments();
        if (!segs.isEmpty()) {
            int n = segs.size();
            int gap = n > 1 ? 1 : 0;
            g.fill(x - 1, by - 1, x + w + 1, by + 5, EDGE);
            for (int i = 0; i < n; i++) {
                int sx0 = x + (i * w) / n;
                int sx1 = x + ((i + 1) * w) / n - (i < n - 1 ? gap : 0);
                if (sx1 <= sx0) sx1 = sx0 + 1;
                g.fill(sx0, by, sx1, by + 4, BACK);
                ProgressModel.Segment seg = segs.get(i);
                if (seg.state() == ProgressModel.State.DONE) {
                    g.fill(sx0, by, sx1, by + 4, DONE);
                } else if (seg.state() == ProgressModel.State.RUNNING) {
                    int fillW = Math.max(1, (int) Math.round((sx1 - sx0) * seg.fraction()));
                    g.fill(sx0, by, sx0 + fillW, by + 4, NOW);
                }
            }
        } else {
            g.fill(x - 1, by - 1, x + w + 1, by + 5, EDGE);
            g.fill(x, by, x + w, by + 4, BACK);
            if (snap.stepKnown()) {
                g.fill(x, by, x + (int) (w * snap.stepFraction()), by + 4, NOW);
            } else {
                int span = Math.max(8, w / 6);
                long t = System.currentTimeMillis() / 12;
                int pos = (int) (t % (2L * (w - span)));
                if (pos > w - span) pos = 2 * (w - span) - pos;
                g.fill(x + pos, by, x + pos + span, by + 4, NOW);
            }
        }

        if (!snap.stallWarning().isEmpty()) {
            g.text(font, font.plainSubstrByWidth(snap.stallWarning(), w), x, y + 31, 0xFFFFA040, false);
        }
    }
}
