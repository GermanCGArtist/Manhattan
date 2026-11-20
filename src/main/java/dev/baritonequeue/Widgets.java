package dev.baritonequeue;

import dev.baritonequeue.project.GameProjectRuntime;
import dev.baritonequeue.project.Project;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.util.List;

import static dev.baritonequeue.UiTheme.*;

/** The workspace tiles (Hybrid design): run status, steps, vitals, tools, inventory, alerts, handlers, log. */
final class Widgets {
    private Widgets() {}

    static float s() {
        return ImGuiManager.getUiScale();
    }

    // ------------------------------------------------------------------ primitives

    static void label(String text) {
        UiTheme.push(UiTheme.small);
        UiTheme.pushText(DIM);
        ImGui.textUnformatted(text.toUpperCase());
        ImGui.popStyleColor();
        ImGui.popFont();
    }

    /** kind: 0 normal, 1 primary (accent), 2 danger (red text), 3 ghost. */
    static boolean button(String text, int kind) {
        return button(text, kind, 0f);
    }

    static boolean button(String text, int kind, float w) {
        int pushed = 0;
        if (kind == 1) {
            ImGui.pushStyleColor(ImGuiCol.Button, r(ACCENT), g(ACCENT), b(ACCENT), 1f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, r(ACCENT), g(ACCENT), b(ACCENT), 0.85f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive, r(ACCENT), g(ACCENT), b(ACCENT), 0.7f);
            ImGui.pushStyleColor(ImGuiCol.Text, r(ON_ACCENT), g(ON_ACCENT), b(ON_ACCENT), 1f);
            pushed = 4;
        } else if (kind == 2) {
            ImGui.pushStyleColor(ImGuiCol.Text, r(ERR), g(ERR), b(ERR), 1f);
            pushed = 1;
        } else if (kind == 3) {
            ImGui.pushStyleColor(ImGuiCol.Button, 0f, 0f, 0f, 0f);
            ImGui.pushStyleColor(ImGuiCol.Border, 0f, 0f, 0f, 0f);
            ImGui.pushStyleColor(ImGuiCol.Text, r(DIM), g(DIM), b(DIM), 1f);
            pushed = 3;
        }
        boolean clicked = w > 0 ? ImGui.button(text, w, 0f) : ImGui.button(text);
        ImGui.popStyleColor(pushed);
        return clicked;
    }

    /** {@code text} cut with "…" so it is at most {@code maxW} pixels wide in the current font. */
    static String fit(String text, float maxW) {
        if (text == null) return "";
        text = text.replace('\n', ' ');
        if (maxW <= 0) return "";
        if (ImGui.calcTextSizeX(text) <= maxW) return text;
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (ImGui.calcTextSizeX(text.substring(0, mid) + "…") <= maxW) lo = mid;
            else hi = mid - 1;
        }
        return lo == 0 ? "…" : text.substring(0, lo) + "…";
    }

    /** Text that wraps at the right edge of the current window (never treated as a format string). */
    static void wrapped(int rgb, String text) {
        ImGui.pushTextWrapPos(0f);
        UiTheme.textColored(rgb, text);
        ImGui.popTextWrapPos();
    }

    static void bar(float w, float h, double frac, int rgb) {
        ImDrawList dl = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
        dl.addRectFilled(x, y, x + w, y + h, u32(TRACK), h / 2f);
        float f = (float) Math.max(0.0, Math.min(1.0, frac));
        if (f > 0) dl.addRectFilled(x, y, x + Math.max(h, w * f), y + h, u32(rgb), h / 2f);
        ImGui.dummy(w, h);
    }

    /**
     * The script's steps as one bar: a cell per step line, green when done, the running one filled in accent (red
     * when failing) with a "you are here" tick; grey for steps still to come. Hover shows which line a cell is.
     */
    static void segBar(ProgressModel.Snapshot snap, float w, float h, boolean failing) {
        ImDrawList dl = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
        List<ProgressModel.Segment> segs = snap.segments();
        int n = segs.size();
        int run = failing ? ERR : ACCENT;
        float hereX = -1;
        if (n == 0) {
            dl.addRectFilled(x, y, x + w, y + h, u32(TRACK), 2f);
        } else {
            float gap = n <= 40 ? 3f * s() : n <= 90 ? 1f : 0f;
            float cw = (w - gap * (n - 1)) / n;
            for (int i = 0; i < n; i++) {
                ProgressModel.Segment seg = segs.get(i);
                float x0 = x + i * (cw + gap), x1 = i == n - 1 ? x + w : x0 + cw;
                dl.addRectFilled(x0, y, x1, y + h, u32(TRACK), gap > 0 ? 2f : 0f);
                if (seg.state() == ProgressModel.State.DONE) {
                    dl.addRectFilled(x0, y, x1, y + h, u32(OK, 0.85f), gap > 0 ? 2f : 0f);
                } else if (seg.state() == ProgressModel.State.RUNNING) {
                    float fx = x0 + Math.max(2f, (float) ((x1 - x0) * Math.max(0.0, seg.fraction())));
                    dl.addRectFilled(x0, y, Math.min(x1, fx), y + h, u32(run, 0.85f), gap > 0 ? 2f : 0f);
                    hereX = Math.min(x1, fx);
                }
            }
        }
        if (hereX >= 0) dl.addRectFilled(hereX - 1f * s(), y - 3f * s(), hereX + 1f * s(), y + h + 3f * s(), u32(FG));
        ImGui.invisibleButton("##segbar", Math.max(1f, w), Math.max(1f, h));
        if (n > 0 && ImGui.isItemHovered()) {
            int i = (int) Math.max(0, Math.min(n - 1, (ImGui.getMousePosX() - x) / w * n));
            ProgressModel.Segment seg = segs.get(i);
            String st = seg.state() == ProgressModel.State.DONE ? "done" : seg.state() == ProgressModel.State.RUNNING ? "running now" : "to come";
            ImGui.setTooltip("Step " + (i + 1) + "/" + n + " (" + st + "), line " + seg.line() + ":\n" + seg.text()
                    + "\n\nGreen = done, white tick = where the script is, grey = still to come.");
        }
    }

    static boolean failing() {
        for (Alerts.Alert a : Alerts.list(ProgressModel.snapshot())) if (a.level() == Alerts.Level.ERROR) return true;
        return false;
    }

    static String runningStepText(ProgressModel.Snapshot snap) {
        for (ProgressModel.Segment seg : snap.segments()) {
            if (seg.state() == ProgressModel.State.RUNNING) return seg.text();
        }
        return "";
    }

    static String percentText(ProgressModel.Snapshot snap) {
        return snap.running() ? snap.overallPercent() + "%" : "–";
    }

    /** Skip and Stop; Stop's rectangle is recorded for the in-game tests. */
    static void runButtons(boolean running) {
        if (!running) ImGui.beginDisabled();
        float x = ImGui.getCursorPosX(), y = ImGui.getCursorPosY();
        if (button("Skip##skip", 0)) RunnerView.skip();
        float skipW = ImGui.getItemRectSizeX();
        ImGui.setCursorPos(x + skipW + ImGui.getStyle().getItemSpacingX(), y);   // same row, same top edge
        if (button("Stop##stop", 2)) ImGuiManager.triggerStop();
        ImGuiManager.recordStopRect(ImGui.getItemRectMinX(), ImGui.getItemRectMinY(), ImGui.getItemRectSizeX(), ImGui.getItemRectSizeY());
        if (!running) ImGui.endDisabled();
    }

    static void statusPill(ProgressModel.Snapshot snap, boolean failing) {
        int c = !snap.running() ? DIM : failing ? ERR : OK;
        String t = !snap.running() ? "Idle" : failing ? "Error" : "Running";
        ImDrawList dl = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
        float h = ImGui.getTextLineHeight() + 6f * s();
        float w = ImGui.calcTextSizeX(t) + 26f * s();
        dl.addRectFilled(x, y, x + w, y + h, u32(CHIP), h / 2f);
        dl.addCircleFilled(x + 10f * s(), y + h / 2f, 4f * s(), u32(c));
        dl.addText(x + 18f * s(), y + 3f * s(), u32(c == DIM ? DIM : FG), t);
        ImGui.dummy(w, h);
    }

    // ------------------------------------------------------------------ tiles

    static boolean beginTile(String id, float w, float h) {
        ImGui.pushStyleColor(ImGuiCol.ChildBg, r(PANEL), g(PANEL), b(PANEL), 1f);
        ImGui.pushStyleColor(ImGuiCol.Border, r(LINE), g(LINE), b(LINE), 1f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 14f * s(), 12f * s());
        boolean open = ImGui.beginChild(id, w, h, true, ImGuiWindowFlags.AlwaysUseWindowPadding);
        return open;
    }

    static void endTile() {
        ImGui.endChild();
        ImGui.popStyleVar();
        ImGui.popStyleColor(2);
    }

    /** Big percentage, the running step, its estimate, the step bar, Skip/Stop. */
    static void nowRunning(ProgressModel.Snapshot snap) {
        boolean failing = failing();
        label("Now running");
        ImGui.sameLine(ImGui.getContentRegionMaxX() - 90f * s());
        statusPill(snap, failing);
        UiTheme.push(UiTheme.numL);
        UiTheme.pushText(!snap.running() ? DIM : failing ? ERR : ACCENT);
        ImGui.textUnformatted(percentText(snap));
        ImGui.popStyleColor();
        ImGui.popFont();
        ImGui.sameLine(0f, 14f * s());
        ImGui.beginGroup();
        if (snap.running()) {
            ImGui.textUnformatted("Step " + snap.currentStep() + "/" + snap.totalSteps()
                    + (snap.line() > 0 ? " · line " + snap.line() : ""));
            UiTheme.pushText(DIM);
            ImGui.textUnformatted(Alerts.shorten(snap.currentCommand().isEmpty() ? runningStepText(snap) : snap.currentCommand(), 64));
            ImGui.textUnformatted(Alerts.shorten(snap.stepTailText() + "  ·  elapsed " + snap.elapsedText(), 72));
            ImGui.popStyleColor();
        } else {
            ImGui.textUnformatted("Not running");
            UiTheme.pushText(DIM);
            ImGui.textUnformatted(snap.totalSteps() + " steps in the script. Run it from the editor or " + Platform.shortcutName() + "+P.");
            ImGui.popStyleColor();
        }
        ImGui.endGroup();
        segBar(snap, ImGui.getContentRegionAvailX(), 8f * s(), failing);
        runButtons(snap.running());
    }

    /** Monitor's top strip: status, step, step bar, big percent, step estimate, elapsed, Skip/Stop. */
    static void topStrip(ProgressModel.Snapshot snap, float w, float h) {
        boolean failing = failing();
        float s = s();
        float leftW = 280f * s, pctW = 120f * s, etaW = 190f * s, btnW = 150f * s;
        float midW = Math.max(120f, w - leftW - pctW - etaW - btnW - 60f * s);
        float y0 = ImGui.getCursorPosY();

        ImGui.beginGroup();
        label("Manhattan · " + activeSourceTitle());
        statusPill(snap, failing);
        ImGui.endGroup();

        ImGui.sameLine(leftW);
        ImGui.beginGroup();
        UiTheme.push(UiTheme.numM);
        if (snap.running()) {
            ImGui.textUnformatted("STEP " + snap.currentStep() + "/" + snap.totalSteps());
            ImGui.sameLine(0f, 12f * s);
            UiTheme.pushText(DIM);
            ImGui.textUnformatted(Alerts.shorten(runningStepText(snap).toUpperCase(), Math.max(8, (int) (midW / (14f * s)))));
            ImGui.popStyleColor();
        } else {
            UiTheme.pushText(DIM);
            ImGui.textUnformatted("NOT RUNNING");
            ImGui.popStyleColor();
        }
        ImGui.popFont();
        segBar(snap, midW, 8f * s, failing);
        ImGui.endGroup();

        ImGui.sameLine(leftW + midW + 24f * s);
        ImGui.setCursorPosY(y0 - 2f * s);
        UiTheme.push(UiTheme.numL);
        UiTheme.pushText(!snap.running() ? DIM : failing ? ERR : ACCENT);
        ImGui.textUnformatted(percentText(snap));
        ImGui.popStyleColor();
        ImGui.popFont();

        ImGui.sameLine(leftW + midW + 24f * s + pctW);
        ImGui.setCursorPosY(y0);
        ImGui.beginGroup();
        label("Step estimate");
        UiTheme.push(UiTheme.numM);
        String eta = snap.stepEtaText().isEmpty() ? (snap.running() ? snap.stepPercentText() : "–") : snap.stepEtaText().replace("about ", "").replace(" left", "");
        ImGui.textUnformatted(eta);
        ImGui.popFont();
        UiTheme.pushText(DIM);
        ImGui.textUnformatted(snap.running() ? "elapsed " + snap.elapsedText() : "");
        ImGui.popStyleColor();
        ImGui.endGroup();

        float btnRowW = ImGui.calcTextSizeX("Skip") + ImGui.calcTextSizeX("Stop") + 4f * ImGui.getStyle().getFramePaddingX()
                + ImGui.getStyle().getItemSpacingX();
        ImGui.setCursorPos(ImGui.getCursorStartPosX() + w - btnRowW, y0 + 16f * s);
        runButtons(snap.running());
    }

    private static boolean projectRunning() {
        try {
            GameProjectRuntime rt = GameProjectRuntime.instance();
            return rt != null && rt.running();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String activeSourceTitle() {
        if (projectRunning()) {
            Project p = GameProjectRuntime.instance().activeProject();
            if (p != null) {
                String name = (p.name != null && !p.name.isBlank()) ? p.name : p.id;
                if (name != null && !name.isBlank()) return "Project: " + name;
            }
            return "Project Queue";
        }
        return DockedPanels.activeTabName();
    }

    /** The steps around the running one (or the first ones when idle). */
    static void steps(ProgressModel.Snapshot snap, int maxRows) {
        boolean projRun = projectRunning();
        List<ProgressModel.Segment> segs = snap.segments();
        if (segs.isEmpty()) {
            UiTheme.textColored(DIM, projRun ? "No tasks in project." : "The script is empty.");
            return;
        }
        int run = 0;
        for (int i = 0; i < segs.size(); i++) if (segs.get(i).state() == ProgressModel.State.RUNNING) run = i;
        int from = Math.max(0, Math.min(run - 2, segs.size() - maxRows));
        int to = Math.min(segs.size(), from + maxRows);
        ImDrawList dl = ImGui.getWindowDrawList();
        float s = s();
        float availW = ImGui.getContentRegionAvailX();
        for (int i = from; i < to; i++) {
            ProgressModel.Segment seg = segs.get(i);
            float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
            float lh = ImGui.getTextLineHeight();
            float cx = x + 7f * s, cy = y + lh / 2f;
            switch (seg.state()) {
                case DONE -> dl.addCircleFilled(cx, cy, 6f * s, u32(OK));
                case RUNNING -> {
                    dl.addCircleFilled(cx, cy, 7f * s, u32(ACCENT, 0.25f));
                    dl.addCircleFilled(cx, cy, 4f * s, u32(ACCENT));
                }
                default -> dl.addCircle(cx, cy, 5.5f * s, u32(GUTTER), 0, 1.5f);
            }
            ImGui.setCursorScreenPos(x + 22f * s, y);
            int c = seg.state() == ProgressModel.State.PENDING ? DIM : FG;
            String right = seg.state() == ProgressModel.State.RUNNING ? snap.stepPercentText() : "";
            float rightW = right.isEmpty() ? 0 : ImGui.calcTextSizeX(right) + 8f * s;
            String num = String.format("%3d", seg.line());
            float textW = availW - 22f * s - ImGui.calcTextSizeX(num) - 8f * s - rightW - 4f * s;
            UiTheme.pushText(GUTTER);
            ImGui.textUnformatted(num);
            ImGui.popStyleColor();
            ImGui.sameLine(0f, 8f * s);
            UiTheme.textColored(c, fit(seg.text(), textW));
            if (ImGui.isItemHovered()) ImGui.setTooltip((projRun ? "Task " : "Line ") + seg.line() + ": " + seg.text());
            if (!right.isEmpty()) {
                ImGui.sameLine(availW - rightW + 8f * s);
                UiTheme.textColored(ACCENT, right);
            }
        }
        if (to < segs.size()) UiTheme.textColored(DIM, "   + " + (segs.size() - to) + " more steps");
    }

    static void vitals(ProgressModel.Snapshot snap) {
        float s = s();
        float w = ImGui.getContentRegionAvailX();
        float labelW = 64f * s, numW = 52f * s;
        row("Health", ERR, snap.health() / Math.max(1f, snap.maxHealth()),
                Math.round(snap.health()) + "/" + Math.round(snap.maxHealth()), w, labelW, numW);
        row("Food", WARN, snap.food() / 20.0, snap.food() + "/20", w, labelW, numW);
        PlayerView.Snapshot inv = PlayerView.get();
        UiTheme.textColored(DIM, "Inventory " + (36 - inv.freeSlots()) + "/36 · " + inv.freeSlots() + " free");
        ImGui.sameLine(w - ImGui.calcTextSizeX(snap.posText()));
        UiTheme.textColored(DIM, snap.posText());
    }

    private static void row(String name, int rgb, double frac, String num, float w, float labelW, float numW) {
        float y = ImGui.getCursorPosY();
        ImGui.textUnformatted(name);
        ImGui.sameLine(labelW);
        ImGui.setCursorPosY(y + ImGui.getTextLineHeight() / 2f - 3f * s());
        bar(Math.max(20f, w - labelW - numW), 6f * s(), frac, rgb);
        ImGui.sameLine(w - numW + 6f * s());
        ImGui.setCursorPosY(y);
        ImGui.textUnformatted(num);
    }

    static void tools(int maxRows) {
        List<PlayerView.Slot> tools = PlayerView.tools();
        if (tools.isEmpty()) {
            UiTheme.textColored(DIM, "No tools in the inventory.");
            return;
        }
        int worn = PlayerView.wornThreshold();
        float s = s();
        float w = ImGui.getContentRegionAvailX();
        float x0 = ImGui.getCursorPosX();
        float numW = ImGui.calcTextSizeX("0000") + 6f * s, iconW = ImGui.getTextLineHeight();
        float nameMax = 0;
        for (PlayerView.Slot t : tools) nameMax = Math.max(nameMax, ImGui.calcTextSizeX(PlayerView.pretty(t.id())));
        float nameW = Math.min(nameMax, w * 0.45f);
        for (int i = 0; i < Math.min(maxRows, tools.size()); i++) {
            PlayerView.Slot t = tools.get(i);
            boolean low = t.durability() <= Math.max(worn * 2, 20);
            float y = ImGui.getCursorPosY();
            float ix = ImGui.getCursorScreenPosX(), iy = ImGui.getCursorScreenPosY();
            ItemIcons.draw(ImGui.getWindowDrawList(), t.id(), ix, iy, ix + iconW, iy + iconW);
            ImGui.setCursorPos(x0 + iconW + 6f * s, y);
            UiTheme.textColored(low ? WARN : FG, fit(PlayerView.pretty(t.id()), nameW));
            if (ImGui.isItemHovered()) ImGui.setTooltip(PlayerView.pretty(t.id()) + ": " + t.durability() + "/" + t.maxDurability()
                    + (worn > 5 ? "  (worn out at " + worn + ")" : ""));
            float barX = x0 + iconW + 6f * s + nameW + 10f * s;
            float bw = Math.max(20f, x0 + w - numW - 8f * s - barX);
            ImGui.setCursorPos(barX, y + ImGui.getTextLineHeight() / 2f - 3f * s);
            bar(bw, 6f * s, t.durability() / (double) Math.max(1, t.maxDurability()), low ? WARN : OK);
            ImGui.setCursorPos(x0 + w - ImGui.calcTextSizeX(String.valueOf(t.durability())), y);
            UiTheme.textColored(DIM, String.valueOf(t.durability()));
        }
    }

    static void inventory() {
        PlayerView.Snapshot inv = PlayerView.get();
        float s = s();
        float w = ImGui.getContentRegionAvailX();
        float gap = 4f * s;
        float cell = (w - gap * 8) / 9f;
        ImDrawList dl = ImGui.getWindowDrawList();
        float x0 = ImGui.getCursorScreenPosX(), y0 = ImGui.getCursorScreenPosY();
        List<PlayerView.Slot> slots = inv.slots();
        String hover = null;
        float mx = ImGui.getMousePosX(), my = ImGui.getMousePosY();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 9; col++) {
                int idx = row == 3 ? col : 9 + (row * 9) + col;   // main inventory on top, hotbar last (like the game)
                float x = x0 + col * (cell + gap), y = y0 + row * (cell + gap) + (row == 3 ? gap : 0);
                dl.addRectFilled(x, y, x + cell, y + cell, u32(SLOT), 3f);
                boolean sel = row == 3 && col == inv.selected();
                dl.addRect(x, y, x + cell, y + cell, u32(sel ? ACCENT : LINE), 3f, 0, sel ? 2f : 1f);
                if (idx >= slots.size()) continue;
                PlayerView.Slot sl = slots.get(idx);
                if (sl.empty()) continue;
                float pad = Math.max(3f, cell * 0.14f);
                UiTheme.push(UiTheme.small);
                if (!ItemIcons.draw(dl, sl.id(), x + pad, y + pad, x + cell - pad, y + cell - pad)) {
                    float tw = ImGui.calcTextSizeX(sl.label());   // no texture found: two letters instead
                    dl.addText(x + (cell - tw) / 2f, y + (cell - ImGui.getFontSize()) / 2f - 2f, u32(itemColor(sl.id())), sl.label());
                }
                if (sl.count() > 1) {
                    String q = String.valueOf(sl.count());
                    float qx = x + cell - ImGui.calcTextSizeX(q) - 2f, qy = y + cell - ImGui.getFontSize();
                    dl.addText(qx + 1f, qy + 1f, u32(0x000000, 0.9f), q);   // shadow, like the game
                    dl.addText(qx, qy, u32(0xffffff), q);
                }
                ImGui.popFont();
                if (sl.damageable()) {
                    float f = sl.durability() / (float) Math.max(1, sl.maxDurability());
                    dl.addRectFilled(x + 3, y + cell - 4, x + cell - 3, y + cell - 2, u32(0x000000, 0.5f));
                    dl.addRectFilled(x + 3, y + cell - 4, x + 3 + (cell - 6) * f, y + cell - 2,
                            u32(sl.durability() <= Math.max(inv.wornThreshold() * 2, 20) ? WARN : OK));
                }
                if (mx >= x && mx <= x + cell && my >= y && my <= y + cell) {
                    hover = PlayerView.pretty(sl.id()) + (sl.count() > 1 ? " ×" + sl.count() : "")
                            + (sl.damageable() ? "  (" + sl.durability() + "/" + sl.maxDurability() + ")" : "");
                }
            }
        }
        float total = 4 * cell + 4 * gap;
        ImGui.invisibleButton("##inv", w, total);
        if (hover != null && ImGui.isItemHovered()) ImGui.setTooltip(hover);
    }

    static int itemColor(String id) {
        if (id.startsWith("diamond")) return 0x7fd7e6;
        if (id.startsWith("netherite")) return 0x8c7d86;
        if (id.startsWith("iron")) return 0xd8d8d8;
        if (id.startsWith("golden") || id.startsWith("gold")) return 0xe8c14a;
        if (id.contains("dirt") || id.contains("mud") || id.contains("grass")) return 0xa77a52;
        if (id.contains("cobble") || id.contains("stone") || id.contains("deepslate") || id.contains("andesite") || id.contains("gravel")) return 0x9a9da2;
        if (id.contains("log") || id.contains("planks") || id.contains("wood") || id.equals("stick")) return 0xc49a5c;
        if (id.contains("bread") || id.contains("cooked") || id.contains("apple") || id.contains("carrot") || id.contains("potato") || id.contains("beef") || id.contains("porkchop")) return 0xe0a35b;
        if (id.contains("torch") || id.contains("coal")) return 0xf0c060;
        if (id.contains("water") || id.contains("bucket")) return 0x6aa7e8;
        int h = id.hashCode();
        return 0x808080 | (h & 0x3f3f3f);
    }

    private static long incidentCopiedAtMs = 0L;

    static void alertsHeader() {
        float s = s();
        label("Alerts & Telemetry");
        boolean hasIncident = IncidentBundle.lastIncidentJson() != null;
        boolean justCopied = System.currentTimeMillis() - incidentCopiedAtMs < 2500L;
        String btnText = justCopied ? "Copied!" : (hasIncident ? "Copy Incident" : "Snapshot");
        float bw = ImGui.calcTextSizeX(btnText) + 2f * ImGui.getStyle().getFramePaddingX();
        ImGui.sameLine(ImGui.getContentRegionMaxX() - bw);
        ImGui.setCursorPosY(ImGui.getCursorPosY() - 4f * s);
        if (button(btnText + "##copyIncident", 3)) {
            if (!hasIncident) {
                IncidentBundle.captureAndWrite("manual_snapshot", "User requested snapshot from Monitor");
            }
            String json = IncidentBundle.lastIncidentJson();
            if (json != null) {
                ImGui.setClipboardText(json);
                incidentCopiedAtMs = System.currentTimeMillis();
            }
        }
        if (ImGui.isItemHovered()) {
            var path = IncidentBundle.lastIncidentPath();
            ImGui.setTooltip(path != null
                    ? "Copy latest incident bundle JSON to clipboard\n(" + path.getFileName() + ")"
                    : "Capture a diagnostic incident bundle JSON and copy to clipboard");
        }
    }

    /** Compact live time-budget bar (Work / Logistics / Survival / Stuck-Replan) + event counters. */
    static void bottlenecksBar() {
        var snap = dev.midtown.api.RuntimeBus.telemetrySnapshot();
        long total = snap.totalMs();
        boolean hasEvents = snap.replanCount() > 0 || snap.stuckCount() > 0 || snap.deathCount() > 0 || snap.interruptCount() > 0;
        if (total <= 0L && !hasEvents) return;

        float s = s();
        float w = ImGui.getContentRegionAvailX();
        float barH = 7f * s;
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImDrawList dl = ImGui.getWindowDrawList();

        if (total > 0L) {
            double work = snap.share(dev.midtown.api.RuntimeEvent.Category.WORK)
                    + snap.share(dev.midtown.api.RuntimeEvent.Category.WALK);
            double logi = snap.share(dev.midtown.api.RuntimeEvent.Category.LOGISTICS);
            double surv = snap.share(dev.midtown.api.RuntimeEvent.Category.SURVIVAL);
            double stuck = snap.share(dev.midtown.api.RuntimeEvent.Category.STUCK)
                    + snap.share(dev.midtown.api.RuntimeEvent.Category.REPLAN);

            dl.addRectFilled(x, y, x + w, y + barH, u32(SLOT), 3f);
            float cx = x;
            cx = drawSeg(dl, cx, y, barH, (float) (w * work), OK);
            cx = drawSeg(dl, cx, y, barH, (float) (w * logi), ACCENT);
            cx = drawSeg(dl, cx, y, barH, (float) (w * surv), WARN);
            drawSeg(dl, cx, y, barH, (float) (w * stuck), ERR);
            ImGui.dummy(w, barH + 3f * s);

            UiTheme.push(UiTheme.small);
            String legend = "Work " + Math.round(work * 100) + "%  ·  Base " + Math.round(logi * 100)
                    + "%  ·  Surv " + Math.round(surv * 100) + "%  ·  Stuck " + Math.round(stuck * 100) + "%";
            UiTheme.textColored(DIM, fit(legend, w));
            ImGui.popFont();
        }
        if (hasEvents) {
            UiTheme.push(UiTheme.small);
            String counts = "replans " + snap.replanCount() + " · stuck " + snap.stuckCount()
                    + " · interrupts " + snap.interruptCount()
                    + (snap.deathCount() > 0 ? " · deaths " + snap.deathCount() : "");
            UiTheme.textColored(snap.stuckCount() >= 4 || snap.deathCount() > 0 ? WARN : DIM, fit(counts, w));
            ImGui.popFont();
        }
        ImGui.dummy(1f, 3f * s);
    }

    private static float drawSeg(ImDrawList dl, float x, float y, float h, float segW, int col) {
        if (segW < 1f) return x;
        dl.addRectFilled(x, y, x + segW, y + h, u32(col), 2f);
        return x + segW;
    }

    static void alerts(ProgressModel.Snapshot snap, int max) {
        List<Alerts.Alert> list = Alerts.list(snap);
        if (list.isEmpty()) {
            UiTheme.textColored(OK, "Nothing needs attention.");
            return;
        }
        ImDrawList dl = ImGui.getWindowDrawList();
        float s = s();
        float w = ImGui.getContentRegionAvailX();
        for (int i = 0; i < Math.min(max, list.size()); i++) {
            Alerts.Alert a = list.get(i);
            int c = a.level() == Alerts.Level.ERROR ? ERR : a.level() == Alerts.Level.WARN ? WARN : DIM;
            float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
            boolean two = !a.detail().isEmpty();
            float h = ImGui.getTextLineHeight() * (two ? 2 : 1) + 12f * s + (two ? 2f * s : 0);
            dl.addRectFilled(x, y, x + w, y + h, u32(c, a.level() == Alerts.Level.INFO ? 0.06f : 0.10f), 5f);
            dl.addRect(x, y, x + w, y + h, u32(c, 0.45f), 5f);
            dl.addRectFilled(x + 6f * s, y + 6f * s, x + 9f * s, y + h - 6f * s, u32(c), 2f);
            ImGui.setCursorScreenPos(x + 18f * s, y + 6f * s);
            ImGui.beginGroup();
            float titleW = w - 28f * s - (a.level() == Alerts.Level.ERROR ? 80f * s : 0f);
            ImGui.textUnformatted(fit(a.title(), titleW));
            if (two) UiTheme.textColored(DIM, fit(a.detail(), w - 28f * s));
            ImGui.endGroup();
            if (ImGui.isItemHovered()) ImGui.setTooltip(a.title() + (two ? "\n" + a.detail() : ""));
            if (a.level() == Alerts.Level.ERROR) {
                ImGui.setCursorScreenPos(x + w - 70f * s, y + 5f * s);
                if (button("Dismiss##al" + i, 3)) Alerts.dismiss(a);
            }
            ImGui.setCursorScreenPos(x, y + h + 6f * s);
            ImGui.dummy(1, 0);
        }
    }

    static void handlers() {
        List<Runner.HandlerInfo> hs = QueueMod.runner.handlerInfos();
        if (hs.isEmpty()) {
            UiTheme.textColored(DIM, QueueMod.runner.running() ? "No on-blocks in this script." : "Shown while the script runs.");
            return;
        }
        float w = ImGui.getContentRegionAvailX();
        float s = s();
        float x0 = ImGui.getCursorPosX();
        // two lines per handler: "on <condition>" with its state on the right, then "→ <first body line>" indented
        for (Runner.HandlerInfo h : hs) {
            String when = h.active() ? "running" : h.fired() == 0 ? "armed" : h.fired() + "×, " + ago(h.ticksSinceEnd());
            float whenW = ImGui.calcTextSizeX(when);
            float y = ImGui.getCursorPosY();
            ImGuiManager.pushMonoFont();
            UiTheme.textColored(0xe29ef0, "on");
            ImGui.sameLine(0f, 6f * s);
            float condW = w - (ImGui.getCursorPosX() - x0) - whenW - 12f * s;
            UiTheme.textColored(0xecc77a, fit(h.condition(), condW));
            ImGui.popFont();
            if (ImGui.isItemHovered()) ImGui.setTooltip("line " + h.line() + ": on " + h.condition() + "\n  " + h.body());
            ImGui.setCursorPos(x0 + w - whenW, y);
            UiTheme.textColored(h.active() ? ACCENT : DIM, when);
            ImGuiManager.pushMonoFont();
            ImGui.setCursorPosX(x0 + 18f * s);
            UiTheme.textColored(DIM, "→");
            ImGui.sameLine(0f, 6f * s);
            UiTheme.textColored(0x7fc8f0, fit(h.body(), w - 18f * s - 20f * s));
            ImGui.popFont();
            ImGui.dummy(1, 2f * s);
        }
    }

    static String ago(long ticks) {
        if (ticks < 0) return "never";
        long sec = ticks / 20;
        if (sec < 60) return sec + " s ago";
        if (sec < 3600) return (sec / 60) + " min ago";
        return (sec / 3600) + " h ago";
    }

    /**
     * The log tile: newest first, scrollable. Click selects a line (Ctrl adds, Shift a range), Ctrl+C or Copy copies
     * the selection (everything shown when nothing is selected), hover shows the whole line, Open goes to the Log drawer.
     */
    static void logTile() {
        float s = s();
        label("Log");
        int sel = DockedPanels.selectedLogCount();
        String copyLabel = sel > 0 ? "Copy " + sel + "##logTileCopy" : "Copy all##logTileCopy";
        float bw = ImGui.calcTextSizeX(copyLabel.substring(0, copyLabel.indexOf('#'))) + ImGui.calcTextSizeX("Open")
                + 4f * ImGui.getStyle().getFramePaddingX() + ImGui.getStyle().getItemSpacingX();
        ImGui.sameLine(ImGui.getContentRegionMaxX() - bw);
        float by = ImGui.getCursorPosY() - 4f * s, bx = ImGui.getCursorPosX();
        ImGui.setCursorPosY(by);
        if (button(copyLabel, 3)) DockedPanels.copySelectedLogs();
        ImGui.setCursorPos(bx + ImGui.getItemRectSizeX() + ImGui.getStyle().getItemSpacingX(), by);
        if (button("Open##logTileOpen", 3)) Shell.drawer = Shell.Drawer.LOG;

        List<LogBuffer.Entry> all = LogBuffer.entries(LogBuffer.Filter.ALL);
        if (all.isEmpty()) {
            UiTheme.textColored(DIM, "No messages yet.");
            return;
        }
        ImGui.beginChild("##logTileList", 0f, 0f, false);
        float w = ImGui.getContentRegionAvailX();
        ImGuiManager.pushMonoFont();
        float timeW = ImGui.calcTextSizeX("00:00:00") + 10f * s;
        for (int i = all.size() - 1; i >= 0; i--) {
            LogBuffer.Entry e = all.get(i);
            float y = ImGui.getCursorPosY(), x = ImGui.getCursorPosX();
            boolean selected = DockedPanels.isLogSelected(e.id());
            if (ImGui.selectable("##logrow" + e.id(), selected, 0, w, ImGui.getTextLineHeight())) {
                DockedPanels.selectLogEntry(e.id(), ImGui.getIO().getKeyCtrl(), ImGui.getIO().getKeyShift());
            }
            if (ImGui.isItemHovered() && ImGui.calcTextSizeX(e.text()) > w - timeW) ImGui.setTooltip(e.text());
            ImGui.setCursorPos(x, y);
            UiTheme.textColored(GUTTER, e.time());
            ImGui.setCursorPos(x + timeW, y);
            int c = e.kind() == LogBuffer.Kind.ERROR ? ERR : e.kind() == LogBuffer.Kind.BARITONE ? 0x7fc8f0 : FG;
            UiTheme.textColored(c, fit(e.text(), w - timeW));
        }
        ImGuiManager.popMonoFont();
        if (ImGui.isWindowFocused() && ImGui.getIO().getKeyCtrl() && ImGui.isKeyPressed(imgui.flag.ImGuiKey.C)) {
            DockedPanels.copySelectedLogs();
        }
        ImGui.endChild();
    }
}
