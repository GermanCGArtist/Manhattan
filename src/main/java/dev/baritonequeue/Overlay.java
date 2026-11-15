package dev.baritonequeue;

import imgui.ImDrawList;
import imgui.ImGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

import static dev.baritonequeue.Config.cfg;
import static dev.baritonequeue.UiTheme.*;

/**
 * Drawn over the full-size game while the workspace is closed, never taking input: a glass status pill while the
 * script runs, the aiming hints for Pick block and Capture, and error toasts (8 s). Toasts also show inside the
 * game view while the workspace is open.
 */
final class Overlay {
    private Overlay() {}

    private static final long TOAST_MS = 8000;
    private static long seenErrorId = Long.MIN_VALUE;
    private static long toastId = -1;
    private static String toastText = "";
    private static long toastAt;

    /** Whether ImGui has to run while the workspace is closed. */
    static boolean visible() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null || mc.options.hideGui) return false;
        if (mc.screen != null && !(mc.screen instanceof ChatScreen)) return false;
        pollErrors();
        return QueueMod.runner.running() || Capture.aiming || QueueMod.picking || toastActive();
    }

    private static void pollErrors() {
        LogBuffer.Entry e = LogBuffer.lastError();
        if (e == null) return;
        if (seenErrorId == Long.MIN_VALUE) {   // errors from before the game started showing the overlay: not new
            seenErrorId = e.id();
            return;
        }
        if (e.id() != seenErrorId) {
            seenErrorId = e.id();
            toastId = e.id();
            toastText = e.text();
            toastAt = System.currentTimeMillis();
        }
    }

    static boolean toastActive() {
        return toastId >= 0 && System.currentTimeMillis() - toastAt < TOAST_MS;
    }

    static void draw(ProgressModel.Snapshot snap, float W, float H) {
        float s = ImGuiManager.getUiScale();
        ImDrawList dl = ImGui.getForegroundDrawList();
        float y = 10f * s;
        if (Capture.aiming || QueueMod.picking) {
            y = hint(dl, W, y, s);
        } else if (snap.running()) {
            y = pill(dl, snap, W, y, s);
        }
        drawToasts(0, y, W, H - y, false);
    }

    private static float pill(ImDrawList dl, ProgressModel.Snapshot snap, float W, float y, float s) {
        boolean failing = Widgets.failing();
        String step = "Step " + snap.currentStep() + "/" + snap.totalSteps();
        String what = Alerts.shorten(Widgets.runningStepText(snap), 46);
        String pct = failing ? "error" : snap.overallPercent() + "%";
        String eta = snap.stepEtaText();
        float w = Math.min(W - 20f * s, 640f * s), h = 54f * s;
        float x = (W - w) / 2f;
        dl.addRectFilled(x, y, x + w, y + h, u32(0x14161a, 0.82f), h / 2f);
        dl.addRect(x, y, x + w, y + h, u32(0xffffff, 0.10f), h / 2f);
        dl.addCircleFilled(x + 22f * s, y + h / 2f, 5f * s, u32(failing ? ERR : OK));
        float tx = x + 38f * s, ty = y + 9f * s;
        dl.addText(tx, ty, u32(FG), step);
        float sw = ImGui.calcTextSizeX(step);
        dl.addText(tx + sw + 10f * s, ty, u32(DIM), what);
        String right = pct + (eta.isEmpty() ? "" : "  ·  " + eta);
        dl.addText(x + w - 26f * s - ImGui.calcTextSizeX(right), ty, u32(failing ? ERR : FG), right);
        // step bar
        float bx = tx, bw = x + w - 26f * s - tx, by = y + h - 16f * s;
        dl.addRectFilled(bx, by, bx + bw, by + 5f * s, u32(0xffffff, 0.14f), 3f);
        dl.addRectFilled(bx, by, bx + bw * snap.overallPercent() / 100f, by + 5f * s, u32(failing ? ERR : ACCENT), 3f);
        return y + h + 8f * s;
    }

    private static float hint(ImDrawList dl, float W, float y, float s) {
        Places.Target t = Places.target();
        String key = QueueMod.open.getTranslatedKeyMessage().getString();
        String a, b;
        if (Capture.aiming) {
            Capture.Type ty = Capture.type();
            String step = ty.kind == Capture.Kind.AREA ? (Capture.corner1 == null ? " (corner 1)" : " (corner 2; corner 1 at " + Capture.corner1 + ")") : "";
            a = "Capture " + ty.label + step + ": look at a block and press " + key + ".  "
                    + QueueMod.capture.getTranslatedKeyMessage().getString() + " = back to the workspace";
            b = (t == null ? "Looking at nothing (" + key + " goes back without capturing)" : "Looking at: " + t.name() + " at " + t.pos())
                    + (cfg.captureRapid ? "   |   rapid: " + Capture.captured + " captured" : "");
        } else {
            a = "Look at a block and press " + key + " to put its position into the script.";
            b = t == null ? "(Press it looking at the sky to go back without one.)" : "Looking at: " + t.name() + " at " + t.pos();
        }
        float w = Math.max(ImGui.calcTextSizeX(a), ImGui.calcTextSizeX(b)) + 36f * s;
        float h = ImGui.getFontSize() * 2 + 22f * s;
        float x = (W - w) / 2f;
        dl.addRectFilled(x, y, x + w, y + h, u32(0x14161a, 0.86f), 12f * s);
        dl.addRect(x, y, x + w, y + h, u32(ACCENT, 0.55f), 12f * s);
        dl.addText(x + 18f * s, y + 8f * s, u32(ACCENT), a);
        dl.addText(x + 18f * s, y + 12f * s + ImGui.getFontSize(), u32(FG, 0.9f), b);
        return y + h + 8f * s;
    }

    /** The current error toast, centred at the top of the given rectangle. */
    static void drawToasts(float x0, float y0, float w0, float h0, boolean inShell) {
        pollErrors();
        if (!toastActive()) return;
        float s = ImGuiManager.getUiScale();
        ImDrawList dl = ImGui.getForegroundDrawList();
        String title = "Error";
        String text = Alerts.shorten(toastText, 110);
        float w = Math.min(w0 - 24f * s, Math.max(ImGui.calcTextSizeX(text), 200f * s) + 40f * s);
        float h = ImGui.getFontSize() * 2 + 20f * s;
        float x = x0 + (w0 - w) / 2f, y = y0 + (inShell ? 14f * s : 0f);
        long age = System.currentTimeMillis() - toastAt;
        float alpha = age > TOAST_MS - 600 ? Math.max(0f, (TOAST_MS - age) / 600f) : 1f;
        dl.addRectFilled(x, y, x + w, y + h, u32(0x14161a, 0.9f * alpha), 12f * s);
        dl.addRect(x, y, x + w, y + h, u32(ERR, 0.7f * alpha), 12f * s);
        dl.addRectFilled(x + 10f * s, y + 9f * s, x + 13f * s, y + h - 9f * s, u32(ERR, alpha), 2f);
        dl.addText(x + 22f * s, y + 8f * s, u32(ERR, alpha), title);
        dl.addText(x + 22f * s, y + 12f * s + ImGui.getFontSize(), u32(FG, alpha), text);
    }
}
