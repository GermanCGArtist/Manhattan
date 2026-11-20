package dev.baritonequeue;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import static dev.baritonequeue.Config.cfg;
import static dev.baritonequeue.UiTheme.*;

/**
 * The workspace (key J): two fixed layouts around the live game view.
 * <ul>
 *   <li>Edit: activity bar, wide script editor, and on the right the game, a Now running card and vitals/tools/alerts.</li>
 *   <li>Monitor: a top strip (step, bar, percent, estimate, Skip/Stop), the game in the middle, steps/vitals/handlers on
 *       the left, map/inventory/tools on the right, alerts and log under the game.</li>
 * </ul>
 * Presets, Chests, Areas, Capture and Log open as a drawer from the activity bar. Closed, the game is full-size and
 * {@link Overlay} shows a small status pill.
 */
final class Shell {
    private Shell() {}

    enum Mode { CLOSED, EDIT, MONITOR }

    enum Drawer {
        NONE(""), PROJECTS("Projects"), PRESETS("Presets"), CHESTS("Chests"), AREAS("Areas"), CAPTURE("Capture"), LOG("Log");
        final String title;
        Drawer(String title) { this.title = title; }
    }

    static Mode mode = Mode.CLOSED;
    static Drawer drawer = Drawer.NONE;
    /** Set while the shell is closed for aiming in the world (Pick block, Capture): J reopens it. */
    private static Mode suspendedMode = null;
    private static Drawer suspendedDrawer = Drawer.NONE;

    /** x, y, w, h of the area the game is fitted into (screen coordinates). */
    static float gameX, gameY, gameW, gameH;

    static boolean isOpen() {
        return mode != Mode.CLOSED;
    }

    static Mode lastMode() {
        return "monitor".equals(cfg.uiLastMode) ? Mode.MONITOR : Mode.EDIT;
    }

    static void setMode(Mode m) {
        if (m == Mode.CLOSED) {
            mode = Mode.CLOSED;
            return;
        }
        mode = m;
        suspendedMode = null;
        String v = m == Mode.MONITOR ? "monitor" : "edit";
        if (!v.equals(cfg.uiLastMode)) {
            cfg.uiLastMode = v;
            Config.save();
        }
    }

    /** Close the workspace so the player can aim at a block; J brings it back as it was. */
    static void suspendForAiming() {
        if (!isOpen()) return;
        suspendedMode = mode;
        suspendedDrawer = drawer;
        ImGuiManager.closeShell();
    }

    static boolean suspended() {
        return suspendedMode != null;
    }

    /** Reopen after aiming (or open on the given drawer). */
    static void resume(Drawer d) {
        Mode m = suspendedMode != null ? suspendedMode : lastMode();
        Drawer dr = d != null ? d : suspendedDrawer;
        suspendedMode = null;
        ImGuiManager.openShell(m);
        drawer = dr;
    }

    static void toggleDrawer(Drawer d) {
        drawer = drawer == d ? Drawer.NONE : d;
    }

    // ------------------------------------------------------------------ layout

    private static final int FIXED = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
            | ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoBringToFrontOnFocus;

    static float activityW(float s) { return 48f * s; }
    static float statusH(float s) { return 24f * s; }
    static float topH(float s) { return 92f * s; }

    /** Computes the game area for the current mode (called whenever the window size or scale may have changed). */
    static void layout(float W, float H, float s) {
        float a = activityW(s), sb = statusH(s);
        if (mode == Mode.EDIT) {
            float rw = clamp(W * 0.3667f, Math.min(340f * s, W * 0.5f), W * 0.45f);
            gameX = W - rw;
            gameY = 0;
            gameW = rw;
            gameH = Math.min(rw * 9f / 16f, (H - sb) * 0.5f);
        } else if (mode == Mode.MONITOR) {
            float top = topH(s), lw = leftW(W, s), rw = rightW(W, s);
            gameX = a + lw;
            gameY = top;
            gameW = Math.max(1f, W - gameX - rw);
            gameH = Math.min(gameW * 9f / 16f, (H - top - sb) * 0.68f);
        } else {
            gameX = 0;
            gameY = 0;
            gameW = W;
            gameH = H;
        }
    }

    private static float leftW(float W, float s) { return clamp(W * 0.1875f, 220f * s, 380f * s); }
    private static float rightW(float W, float s) { return clamp(W * 0.198f, 230f * s, 400f * s); }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------------ render

    static void render(ProgressModel.Snapshot snap, float W, float H) {
        float s = ImGuiManager.getUiScale();
        layout(W, H, s);
        DockedPanels.flushConfig(false);   // the last typed text reaches the config file in every layout
        drawGameFrame();
        activityBar(H, s);
        statusBar(snap, W, H, s);
        if (mode == Mode.EDIT) renderEdit(snap, W, H, s);
        else renderMonitor(snap, W, H, s);
        if (drawer != Drawer.NONE) renderDrawer(W, H, s);
        CommandPalette.render(W, H, s);
        Overlay.drawToasts(gameX, gameY, gameW, gameH, true);
    }

    /** A thin frame around the game: accent while it has the keyboard, grey otherwise. */
    private static void drawGameFrame() {
        float[] fit = ImGuiManager.fittedViewportRect();
        ImDrawList dl = ImGui.getBackgroundDrawList();
        int c = ImGuiManager.isGameFocused() ? u32(ACCENT) : u32(LINE);
        dl.addRect(fit[0] - 1f, fit[1] - 1f, fit[0] + fit[2] + 1f, fit[1] + fit[3] + 1f, c, 0f, 0, 2f);
        if (!ImGuiManager.isGameFocused()) {
            UiTheme.push(UiTheme.small);
            String hint = "Click the game to play · Esc returns to the panels";
            dl.addRectFilled(fit[0] + 8, fit[1] + 8, fit[0] + 16 + ImGui.calcTextSizeX(hint), fit[1] + 12 + ImGui.getFontSize(), u32(0x000000, 0.55f), 4f);
            dl.addText(fit[0] + 12, fit[1] + 10, u32(FG, 0.9f), hint);
            ImGui.popFont();
        }
    }

    static boolean begin(String id, float x, float y, float w, float h, int bg, float padX, float padY, int extraFlags) {
        ImGui.setNextWindowPos(x, y);
        ImGui.setNextWindowSize(w, h);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, padX, padY);
        ImGui.pushStyleColor(ImGuiCol.WindowBg, r(bg), g(bg), b(bg), 1f);
        boolean open = ImGui.begin(id, FIXED | extraFlags);
        ImGui.popStyleColor();
        ImGui.popStyleVar();
        return open;
    }

    // ------------------------------------------------------------------ activity bar and status bar

    private enum Act { EDITOR, MONITOR, PROJECTS, PRESETS, CHESTS, AREAS, CAPTURE, LOG }

    private static void activityBar(float H, float s) {
        float w = activityW(s);
        begin("##activity", 0, 0, w, H - statusH(s), PANEL2, 0, 6f * s, ImGuiWindowFlags.NoScrollbar);
        ImDrawList dl = ImGui.getWindowDrawList();
        // logo
        float lx = w / 2f - 11f * s, ly = 8f * s;
        dl.addRectFilled(lx, ly, lx + 22f * s, ly + 22f * s, u32(ACCENT), 5f * s);
        dl.addPolyline(new imgui.ImVec2[]{
                new imgui.ImVec2(lx + 6f * s, ly + 16f * s), new imgui.ImVec2(lx + 6f * s, ly + 6f * s),
                new imgui.ImVec2(lx + 11f * s, ly + 12f * s), new imgui.ImVec2(lx + 16f * s, ly + 6f * s),
                new imgui.ImVec2(lx + 16f * s, ly + 16f * s)}, 5, u32(ON_ACCENT), 0, 2f * s);
        ImGui.setCursorPosY(40f * s);
        int errs = Widgets.failing() ? 1 : 0;
        for (Act a : Act.values()) {
            boolean on = switch (a) {
                case EDITOR -> mode == Mode.EDIT && drawer == Drawer.NONE;
                case MONITOR -> mode == Mode.MONITOR && drawer == Drawer.NONE;
                case PROJECTS -> drawer == Drawer.PROJECTS;
                case PRESETS -> drawer == Drawer.PRESETS;
                case CHESTS -> drawer == Drawer.CHESTS;
                case AREAS -> drawer == Drawer.AREAS;
                case CAPTURE -> drawer == Drawer.CAPTURE;
                case LOG -> drawer == Drawer.LOG;
            };
            float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
            if (ImGui.invisibleButton("##act" + a, w, 44f * s)) {
                switch (a) {
                    case EDITOR -> { ImGuiManager.openShell(Mode.EDIT); drawer = Drawer.NONE; }
                    case MONITOR -> { ImGuiManager.openShell(Mode.MONITOR); drawer = Drawer.NONE; }
                    case PROJECTS -> toggleDrawer(Drawer.PROJECTS);
                    case PRESETS -> toggleDrawer(Drawer.PRESETS);
                    case CHESTS -> toggleDrawer(Drawer.CHESTS);
                    case AREAS -> toggleDrawer(Drawer.AREAS);
                    case CAPTURE -> toggleDrawer(Drawer.CAPTURE);
                    case LOG -> toggleDrawer(Drawer.LOG);
                }
            }
            boolean hov = ImGui.isItemHovered();
            if (hov) ImGui.setTooltip(tooltip(a));
            int c = on ? FG : hov ? FG : DIM;
            if (on) dl.addRectFilled(x, y + 8f * s, x + 2f * s, y + 36f * s, u32(ACCENT));
            Icons.draw(dl, a.name(), x + w / 2f, y + 22f * s, 9f * s, u32(c));
            if (a == Act.LOG && errs > 0) dl.addCircleFilled(x + w - 13f * s, y + 11f * s, 4f * s, u32(ERR));
        }
        ImGui.end();
    }

    private static String tooltip(Act a) {
        return switch (a) {
            case EDITOR -> "Edit: script editor (J toggles the workspace)";
            case MONITOR -> "Monitor: run telemetry around the game";
            case PROJECTS -> "Projects & Task Queue";
            case PRESETS -> "Presets";
            case CHESTS -> "Chests";
            case AREAS -> "Areas";
            case CAPTURE -> "Capture (K)";
            case LOG -> "Log";
        };
    }

    private static void statusBar(ProgressModel.Snapshot snap, float W, float H, float s) {
        float h = statusH(s);
        begin("##status", 0, H - h, W, h, PANEL2, 0, 0, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoInputs);
        ImDrawList dl = ImGui.getWindowDrawList();
        boolean failing = Widgets.failing();
        String run = !snap.running() ? "Idle" : failing ? "Error" : "Running";
        int bg = !snap.running() ? CHIP : failing ? ERR : ACCENT;
        int fg = !snap.running() ? DIM : failing ? 0x1e0808 : ON_ACCENT;
        UiTheme.push(UiTheme.small);
        float y = H - h, ty = y + (h - ImGui.getFontSize()) / 2f;
        float rw = ImGui.calcTextSizeX(run) + 26f * s;
        dl.addRectFilled(0, y, rw, H, u32(bg));
        dl.addCircleFilled(9f * s, y + h / 2f, 3f * s, u32(fg));
        dl.addText(16f * s, ty, u32(fg), run);
        float x = rw + 12f * s;
        String mid = snap.running()
                ? (snap.line() > 0
                    ? "step " + snap.currentStep() + "/" + snap.totalSteps() + " · line " + snap.line() + " · " + snap.overallPercent() + "%   "
                      + snap.stepTailText() + "   elapsed " + snap.elapsedText()
                    : "task " + snap.currentStep() + "/" + snap.totalSteps() + " · " + snap.overallPercent() + "%   "
                      + snap.stepTailText() + "   elapsed " + snap.elapsedText())
                : DockedPanels.activeTabName() + " · " + snap.totalSteps() + " steps";
        dl.addText(x, ty, u32(DIM), Alerts.shorten(mid, (int) (W * 0.55f / (6.5f * s))));
        String right = "J: workspace   " + Platform.shortcutName() + "+P: commands   Esc: panels   " + Math.round(s * 100) + "%";
        dl.addText(W - ImGui.calcTextSizeX(right) - 12f * s, ty, u32(DIM), right);
        ImGui.popFont();
        ImGui.end();
    }

    // ------------------------------------------------------------------ Edit

    private static void renderEdit(ProgressModel.Snapshot snap, float W, float H, float s) {
        float a = activityW(s), sb = statusH(s);
        float edW = gameX - a;
        if (begin("##editor", a, 0, edW, H - sb, PANEL, 12f * s, 8f * s, 0)) {
            DockedPanels.renderScriptEditorPanel(snap);
        }
        ImGui.end();

        float colY = gameY + gameH, colH = H - sb - colY;
        if (begin("##editRight", gameX, colY, gameW, colH, BG, 12f * s, 12f * s, 0)) {
            float w = ImGui.getContentRegionAvailX();
            float gap = 10f * s;
            float nowH = 168f * s;
            if (Widgets.beginTile("##now", w, nowH)) Widgets.nowRunning(snap);
            Widgets.endTile();
            float half = (w - gap) / 2f;
            float rowH = 150f * s;
            if (Widgets.beginTile("##vit", half, rowH)) { Widgets.label("Vitals"); Widgets.vitals(snap); }
            Widgets.endTile();
            ImGui.sameLine(0f, gap);
            if (Widgets.beginTile("##tools", half, rowH)) { Widgets.label("Tools"); Widgets.tools(4); }
            Widgets.endTile();
            float restH = Math.max(80f * s, ImGui.getContentRegionAvailY());
            if (Widgets.beginTile("##alerts", w, restH)) { Widgets.label("Alerts"); Widgets.alerts(snap, 3); }
            Widgets.endTile();
        }
        ImGui.end();
    }

    // ------------------------------------------------------------------ Monitor

    private static void renderMonitor(ProgressModel.Snapshot snap, float W, float H, float s) {
        float a = activityW(s), sb = statusH(s), top = topH(s);
        float lw = leftW(W, s), rw = rightW(W, s);
        float gap = 10f * s;

        if (begin("##top", a, 0, W - a, top, PANEL, 22f * s, 12f * s, ImGuiWindowFlags.NoScrollbar)) {
            Widgets.topStrip(snap, ImGui.getContentRegionAvailX(), top);
        }
        ImGui.end();

        float bodyH = H - top - sb;
        if (begin("##left", a, top, lw, bodyH, BG, 12f * s, 12f * s, 0)) {
            float w = ImGui.getContentRegionAvailX();
            float avail = ImGui.getContentRegionAvailY();
            float stepsH = Math.max(160f * s, avail * 0.44f);
            if (Widgets.beginTile("##steps", w, stepsH)) {
                Widgets.label("Steps");
                int rows = Math.max(3, (int) ((stepsH - 46f * s) / ImGui.getTextLineHeightWithSpacing()));
                Widgets.steps(snap, rows);
            }
            Widgets.endTile();
            if (Widgets.beginTile("##vitals", w, 128f * s)) { Widgets.label("Vitals"); Widgets.vitals(snap); }
            Widgets.endTile();
            float hh = Math.max(90f * s, ImGui.getContentRegionAvailY());
            if (Widgets.beginTile("##handlers", w, hh)) { Widgets.label("Handlers"); Widgets.handlers(); }
            Widgets.endTile();
        }
        ImGui.end();

        float bx = gameX, by = gameY + gameH, bw = gameW, bh = H - sb - by;
        if (begin("##bottom", bx, by, bw, bh, BG, 12f * s, 12f * s, 0)) {
            float w = ImGui.getContentRegionAvailX();
            float h = ImGui.getContentRegionAvailY();
            float half = (w - gap) / 2f;
            if (Widgets.beginTile("##alertsM", half, h)) {
                Widgets.alertsHeader();
                Widgets.bottlenecksBar();
                Widgets.alerts(snap, 3);
            }
            Widgets.endTile();
            ImGui.sameLine(0f, gap);
            if (Widgets.beginTile("##logM", half, h)) Widgets.logTile();
            Widgets.endTile();
        }
        ImGui.end();

        if (begin("##right", W - rw, top, rw, bodyH, BG, 0f, 12f * s, 0)) {
            ImGui.setCursorPosX(0f);
            float w = rw - 12f * s;
            if (Widgets.beginTile("##map", w, w)) {
                Widgets.label("Map");
                String chunks = MapLayers.summary();
                if (!chunks.isEmpty()) {
                    ImGui.sameLine(ImGui.getContentRegionMaxX() - ImGui.calcTextSizeX(chunks));
                    UiTheme.textColored(ACCENT, chunks);
                }
                float mw = ImGui.getContentRegionAvailX();
                MapView.draw(ImGui.getCursorScreenPosX(), ImGui.getCursorScreenPosY(), mw, Math.max(40f, ImGui.getContentRegionAvailY()));
            }
            Widgets.endTile();
            if (Widgets.beginTile("##inv", w, 0f)) {
                Widgets.label("Inventory");
                Widgets.inventory();
            }
            Widgets.endTile();
        }
        ImGui.end();
    }

    // ------------------------------------------------------------------ drawer

    private static void renderDrawer(float W, float H, float s) {
        float a = activityW(s), sb = statusH(s);
        float y = mode == Mode.MONITOR ? topH(s) : 0f;
        float w = Math.min(Math.max(520f * s, W * 0.36f), W - a - 40f);
        if (mode == Mode.EDIT) w = Math.min(Math.max(w, (gameX - a) * 0.7f), gameX - a);
        ImGui.setNextWindowPos(a, y);
        ImGui.setNextWindowSize(w, H - y - sb);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 14f * s, 10f * s);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 1f);
        ImGui.pushStyleColor(ImGuiCol.WindowBg, r(PANEL2), g(PANEL2), b(PANEL2), 1f);
        int flags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoSavedSettings;
        if (ImGui.begin("##drawer", flags)) {
            ImGui.popStyleColor();
            ImGui.popStyleVar(2);
            UiTheme.push(UiTheme.numM);
            ImGui.textUnformatted(drawer.title.toUpperCase());
            ImGui.popFont();
            float closeW = ImGui.calcTextSizeX("Close") + 2f * ImGui.getStyle().getFramePaddingX();
            ImGui.sameLine(ImGui.getContentRegionMaxX() - closeW);
            ImGui.setCursorPosY(ImGui.getCursorPosY() + 6f * s);   // centred on the title's height
            if (Widgets.button("Close##drawerClose", 3)) drawer = Drawer.NONE;
            ImGui.separator();
            // the body scrolls both ways, so long lines are never just cut off
            if (ImGui.beginChild("##drawerBody", 0f, 0f, false, ImGuiWindowFlags.HorizontalScrollbar)) {
                switch (drawer) {
                    case PROJECTS -> ProjectPanel.render();
                    case PRESETS -> DockedPanels.renderPresetsPanel();
                    case CHESTS -> DockedPanels.renderChestsPanel();
                    case AREAS -> DockedPanels.renderAreasPanel();
                    case CAPTURE -> DockedPanels.renderCapturePanel();
                    case LOG -> DockedPanels.renderLogPanel();
                    default -> { }
                }
            }
            ImGui.endChild();
        } else {
            ImGui.popStyleColor();
            ImGui.popStyleVar(2);
        }
        ImGui.end();
    }
}
