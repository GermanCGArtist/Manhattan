package dev.baritonequeue;

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

    static void bar(float w, float h, double frac, int rgb) {
        ImDrawList dl = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
        dl.addRectFilled(x, y, x + w, y + h, u32(TRACK), h / 2f);
        float f = (float) Math.max(0.0, Math.min(1.0, frac));
        if (f > 0) dl.addRectFilled(x, y, x + Math.max(h, w * f), y + h, u32(rgb), h / 2f);
        ImGui.dummy(w, h);
    }

    /** One cell per script step: green done, accent (or red when failing) partial fill for the running one. */
    static void segBar(ProgressModel.Snapshot snap, float w, float h, boolean failing) {
        ImDrawList dl = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
        List<ProgressModel.Segment> segs = snap.segments();
        int n = segs.size();
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
                    dl.addRectFilled(x0, y, Math.min(x1, fx), y + h, u32(failing ? ERR : ACCENT), gap > 0 ? 2f : 0f);
                    if (gap == 0f) dl.addRectFilled(x0, y - 2f, Math.max(x0 + 2f, x1), y, u32(failing ? ERR : ACCENT));
                }
            }
        }
        ImGui.dummy(w, h);
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
        if (button("Skip##skip", 0)) RunnerView.skip();
        ImGui.sameLine();
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
            ImGui.textUnformatted("Step " + snap.currentStep() + "/" + snap.totalSteps() + " · line " + snap.line());
            UiTheme.pushText(DIM);
            ImGui.textUnformatted(Alerts.shorten(snap.currentCommand().isEmpty() ? runningStepText(snap) : snap.currentCommand(), 64));
            ImGui.textUnformatted(Alerts.shorten(snap.stepTailText() + "  ·  elapsed " + snap.elapsedText(), 72));
            ImGui.popStyleColor();
        } else {
            ImGui.textUnformatted("Not running");
            UiTheme.pushText(DIM);
            ImGui.textUnformatted(snap.totalSteps() + " steps in the script. Run it from the editor or Ctrl+P.");
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
        label("Manhattan · " + DockedPanels.activeTabName());
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

        ImGui.sameLine(w - btnW);
        ImGui.setCursorPosY(y0 + 14f * s);
        runButtons(snap.running());
    }

    /** The steps around the running one (or the first ones when idle). */
    static void steps(ProgressModel.Snapshot snap, int maxRows) {
        List<ProgressModel.Segment> segs = snap.segments();
        if (segs.isEmpty()) {
            UiTheme.textColored(DIM, "The script is empty.");
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
            int maxChars = Math.max(6, (int) ((availW - 70f * s - rightW) / (7.2f * s)));
            UiTheme.pushText(GUTTER);
            ImGui.textUnformatted(String.format("%3d", seg.line()));
            ImGui.popStyleColor();
            ImGui.sameLine(0f, 8f * s);
            UiTheme.textColored(c, Alerts.shorten(seg.text(), maxChars));
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
        float nameW = Math.min(140f * s, w * 0.42f), numW = 44f * s;
        for (int i = 0; i < Math.min(maxRows, tools.size()); i++) {
            PlayerView.Slot t = tools.get(i);
            boolean low = t.durability() <= Math.max(worn * 2, 20);
            float y = ImGui.getCursorPosY();
            UiTheme.textColored(low ? WARN : FG, Alerts.shorten(PlayerView.pretty(t.id()), (int) (nameW / (7f * s))));
            ImGui.sameLine(nameW);
            ImGui.setCursorPosY(y + ImGui.getTextLineHeight() / 2f - 3f * s);
            float bw = Math.max(20f, w - nameW - numW);
            float bx = ImGui.getCursorScreenPosX(), by = ImGui.getCursorScreenPosY();
            bar(bw, 6f * s, t.durability() / (double) Math.max(1, t.maxDurability()), low ? WARN : OK);
            float tx = bx + bw * (worn / (float) Math.max(1, t.maxDurability()));
            ImGui.getWindowDrawList().addLine(tx, by - 2f, tx, by + 8f * s, u32(FG, 0.6f));
            ImGui.sameLine(w - numW + 6f * s);
            ImGui.setCursorPosY(y);
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
                int c = itemColor(sl.id());
                UiTheme.push(UiTheme.small);
                float tw = ImGui.calcTextSizeX(sl.label());
                dl.addText(x + (cell - tw) / 2f, y + (cell - ImGui.getFontSize()) / 2f - 2f, u32(c), sl.label());
                if (sl.count() > 1) {
                    String q = String.valueOf(sl.count());
                    dl.addText(x + cell - ImGui.calcTextSizeX(q) - 2f, y + cell - ImGui.getFontSize(), u32(FG), q);
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
            ImGui.textUnformatted(Alerts.shorten(a.title(), (int) ((w - 90f * s) / (7f * s))));
            if (two) UiTheme.textColored(DIM, Alerts.shorten(a.detail(), (int) ((w - 30f * s) / (7f * s))));
            ImGui.endGroup();
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
        ImGuiManager.pushMonoFont();
        for (Runner.HandlerInfo h : hs) {
            String when = h.active() ? "running" : h.fired() == 0 ? "armed" : h.fired() + "×, " + ago(h.ticksSinceEnd());
            UiTheme.textColored(0xe29ef0, "on");
            ImGui.sameLine(0f, 6f * s);
            UiTheme.textColored(0xecc77a, Alerts.shorten(h.condition(), 18));
            ImGui.sameLine(0f, 6f * s);
            UiTheme.textColored(DIM, "→");
            ImGui.sameLine(0f, 6f * s);
            UiTheme.textColored(0x7fc8f0, Alerts.shorten(h.body(), 16));
            ImGui.popFont();
            ImGui.sameLine(w - ImGui.calcTextSizeX(when));
            UiTheme.textColored(h.active() ? ACCENT : DIM, when);
            ImGuiManager.pushMonoFont();
        }
        ImGuiManager.popMonoFont();
    }

    static String ago(long ticks) {
        if (ticks < 0) return "never";
        long sec = ticks / 20;
        if (sec < 60) return sec + " s ago";
        if (sec < 3600) return (sec / 60) + " min ago";
        return (sec / 3600) + " h ago";
    }

    static void log(int n) {
        List<LogBuffer.Entry> all = LogBuffer.entries(LogBuffer.Filter.ALL);
        if (all.isEmpty()) {
            UiTheme.textColored(DIM, "No messages yet.");
            return;
        }
        float w = ImGui.getContentRegionAvailX();
        ImGuiManager.pushMonoFont();
        int maxChars = Math.max(10, (int) ((w - 80f * s()) / (8.4f * s())));
        for (int i = all.size() - 1; i >= Math.max(0, all.size() - n); i--) {
            LogBuffer.Entry e = all.get(i);
            UiTheme.textColored(GUTTER, e.time());
            ImGui.sameLine(0f, 10f * s());
            int c = e.kind() == LogBuffer.Kind.ERROR ? ERR : e.kind() == LogBuffer.Kind.BARITONE ? 0x7fc8f0 : FG;
            UiTheme.textColored(c, Alerts.shorten(e.text(), maxChars));
        }
        ImGuiManager.popMonoFont();
    }
}
