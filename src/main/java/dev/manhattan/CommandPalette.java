package dev.manhattan;

import dev.manhattan.project.GameProjectRuntime;
import dev.manhattan.project.Project;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.manhattan.Config.cfg;
import static dev.manhattan.UiTheme.*;

/** Ctrl+P: type to find a command (run, test, skip, stop, switch layout, open a panel, insert a preset, go to a chest ...). */
final class CommandPalette {
    private CommandPalette() {}

    record Cmd(String group, String title, String hint, Runnable action) {}

    private static boolean open;
    private static boolean focusInput;
    private static int selected;
    private static final ImString query = new ImString(128);
    private static String lastRun = "";

    static boolean isOpen() { return open; }

    static void toggle() {
        if (open) close();
        else {
            open = true;
            focusInput = true;
            selected = 0;
            query.set("");
        }
    }

    static void close() {
        open = false;
    }

    static String lastRun() { return lastRun; }

    static List<Cmd> commands() {
        List<Cmd> out = new ArrayList<>();
        out.add(new Cmd("Script", "Run script", DockedPanels.activeTabName(), DockedPanels::runCurrent));
        out.add(new Cmd("Script", "Test script (check without running)", "", DockedPanels::runTest));
        out.add(new Cmd("Script", "Skip current step", "", RunnerView::skip));
        out.add(new Cmd("Script", "Stop", "", ImGuiManager::triggerStop));
        out.add(new Cmd("Projects", "Open Projects", "", () -> Shell.drawer = Shell.Drawer.PROJECTS));
        try {
            GameProjectRuntime rt = GameProjectRuntime.instance();
            if (rt != null) {
                Project active = rt.activeProject();
                String activeHint = active != null ? (active.name != null && !active.name.isBlank() ? active.name : active.id) : "";
                out.add(new Cmd("Projects", "Run project layers", rt.projectQueue().enabledCount() + " enabled", () -> {
                    String err = GameProjectRuntime.instance().startLayerQueue();
                    if (err != null && !err.isBlank()) Util.msg(err);
                }));
                out.add(new Cmd("Projects", "Run active project", activeHint, () -> {
                    String err = GameProjectRuntime.instance().startQueue();
                    if (err != null && !err.isBlank()) Util.msg(err);
                }));
                out.add(new Cmd("Projects", "Stop project queue", "", () ->
                        GameProjectRuntime.instance().stopQueue("Stopped from command palette.")));
                for (String pid : rt.projectIds()) {
                    out.add(new Cmd("Projects", "Switch project: " + pid, "", () ->
                            GameProjectRuntime.instance().selectProject(pid)));
                }
            }
        } catch (Throwable ignored) {
        }
        out.add(new Cmd("Workspace", "Edit layout", "", () -> { ImGuiManager.openShell(Shell.Mode.EDIT); Shell.drawer = Shell.Drawer.NONE; }));
        out.add(new Cmd("Workspace", "Monitor layout", "", () -> { ImGuiManager.openShell(Shell.Mode.MONITOR); Shell.drawer = Shell.Drawer.NONE; }));
        for (Shell.Drawer d : Shell.Drawer.values()) {
            if (d == Shell.Drawer.NONE || d == Shell.Drawer.PROJECTS) continue;
            out.add(new Cmd("Workspace", "Open " + d.title, "", () -> Shell.drawer = d));
        }
        out.add(new Cmd("Workspace", "Close workspace (play)", "J", ImGuiManager::closeShell));
        out.add(new Cmd("Overlay", "Toggle World Overlay (Chests, Beds & Safe Areas)",
                cfg.showWorldOverlay ? "currently ON" : "currently OFF", () -> {
                    cfg.showWorldOverlay = !cfg.showWorldOverlay;
                    Config.save();
                    Util.msg("World overlay " + (cfg.showWorldOverlay ? "ON" : "OFF") + ".");
                }));
        out.add(new Cmd("Overlay", "Check Base Places (Beds & Chests)", "live block check", () ->
                WorldOverlay.checkReport().forEach(Util::msg)));
        out.add(new Cmd("Workspace", "UI scale 100%", "", () -> ImGuiManager.setUiScale(1.0f)));
        out.add(new Cmd("Workspace", "UI scale 125%", "", () -> ImGuiManager.setUiScale(1.25f)));
        out.add(new Cmd("Workspace", "UI scale 150%", "", () -> ImGuiManager.setUiScale(1.5f)));
        out.add(new Cmd("Insert", "Pick block: position of the looked-at block", "", DockedPanels::pickBlock));
        for (String name : cfg.presets.keySet()) {
            out.add(new Cmd("Insert", "Insert preset: " + name, cfg.presets.get(name).size() + " lines", () -> DockedPanels.insertPresetByName(name)));
        }
        for (int i = 0; i < DockedPanels.tabCount(); i++) {
            int idx = i;
            out.add(new Cmd("Go", "Open script tab: " + DockedPanels.tabName(i), "", () -> {
                DockedPanels.selectTab(idx);
                ImGuiManager.openShell(Shell.Mode.EDIT);
            }));
        }
        for (PlacesView.SavedSpot sp : PlacesView.spots()) {
            out.add(new Cmd("Go", "Go to " + sp.kind() + ": " + sp.name(), sp.x() + " " + sp.y() + " " + sp.z(),
                    () -> ChestIndexView.goThere(sp.x(), sp.y(), sp.z())));
        }
        return out;
    }

    static List<Cmd> filter(List<Cmd> all, String q) {
        String[] words = q.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<Cmd> out = new ArrayList<>();
        for (Cmd c : all) {
            String hay = (c.group() + " " + c.title() + " " + c.hint()).toLowerCase(Locale.ROOT);
            boolean ok = true;
            for (String w : words) if (!w.isEmpty() && !hay.contains(w)) { ok = false; break; }
            if (ok) out.add(c);
        }
        return out;
    }

    /** Runs the first command matching {@code q} (used by the in-game tests). */
    static String runFirst(String q) {
        List<Cmd> m = filter(commands(), q);
        if (m.isEmpty()) return "";
        run(m.get(0));
        return m.get(0).title();
    }

    private static void run(Cmd c) {
        lastRun = c.title();
        close();
        try {
            c.action().run();
        } catch (Throwable t) {
            Util.msg("Command failed: " + t);
        }
    }

    static void render(float W, float H, float s) {
        if (!open) return;
        float w = Math.min(680f * s, W - 80f * s), h = Math.min(460f * s, H - 140f * s);
        float x = (W - w) / 2f, y = 70f * s;
        ImGui.setNextWindowPos(x, y);
        ImGui.setNextWindowSize(w, h);
        ImGui.setNextWindowFocus();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 1f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 8f * s);
        ImGui.pushStyleColor(ImGuiCol.WindowBg, r(PANEL2), g(PANEL2), b(PANEL2), 1f);
        int flags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoScrollbar;
        boolean vis = ImGui.begin("##palette", flags);
        ImGui.popStyleColor();
        ImGui.popStyleVar(3);
        if (vis) {
            ImGui.setCursorPos(16f * s, 12f * s);
            ImGui.setNextItemWidth(w - 32f * s);
            if (focusInput) {
                ImGui.setKeyboardFocusHere();
                focusInput = false;
            }
            ImGui.pushStyleColor(ImGuiCol.FrameBg, r(BG), g(BG), b(BG), 1f);
            ImGui.inputTextWithHint("##paletteQuery", "Type a command: run, test, monitor, preset, chest ...", query);
            ImGui.popStyleColor();
            List<Cmd> items = filter(commands(), query.get());
            if (ImGui.isKeyPressed(ImGuiKey.DownArrow)) selected++;
            if (ImGui.isKeyPressed(ImGuiKey.UpArrow)) selected--;
            selected = Math.max(0, Math.min(selected, items.size() - 1));
            boolean enter = ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter);
            if (ImGui.isKeyPressed(ImGuiKey.Escape)) close();
            ImGui.separator();
            ImGui.beginChild("##paletteList", 0f, 0f, false);
            String group = "";
            Cmd chosen = null;
            for (int i = 0; i < items.size(); i++) {
                Cmd c = items.get(i);
                if (!c.group().equals(group)) {
                    group = c.group();
                    ImGui.setCursorPosX(16f * s);
                    Widgets.label(group);
                }
                ImGui.setCursorPosX(8f * s);
                boolean sel = i == selected;
                if (sel) ImGui.setScrollHereY();
                if (ImGui.selectable(c.title() + "##pc" + i, sel, 0, w - 16f * s, 0f)) chosen = c;
                if (!c.hint().isEmpty()) {
                    ImGui.sameLine(w - 24f * s - ImGui.calcTextSizeX(c.hint()));
                    UiTheme.textColored(DIM, c.hint());
                }
            }
            if (items.isEmpty()) {
                ImGui.setCursorPosX(16f * s);
                UiTheme.textColored(DIM, "No command matches.");
            }
            ImGui.endChild();
            if (enter && !items.isEmpty()) chosen = items.get(selected);
            if (chosen != null) {
                ImGui.end();
                run(chosen);
                return;
            }
        }
        ImGui.end();
    }
}
