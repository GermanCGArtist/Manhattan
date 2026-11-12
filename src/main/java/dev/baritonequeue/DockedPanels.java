package dev.baritonequeue;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.baritonequeue.Config.cfg;

/**
 * Stage 3 Docked UI Panels rendered inside {@link ImGuiManager}'s 2x2 DockSpace:
 * <ul>
 *   <li>Top-Left: {@code Script Editor} (file tabs, Run/Test/Pick block, syntax colours from {@link Syntax},
 *       Tab completion from {@link Suggest}, error hints from {@link ScriptCheck}, running-line highlight)
 *       and {@code Presets} (list, insert, save, share via {@link PresetShare}).</li>
 *   <li>Bottom-Left: {@code Chests} (searchable, sortable table over {@link ChestIndexView}, category filters,
 *       low-stock filter, detail pane with Show in world, Copy {@code pos(...)}, Go there),
 *       {@code Areas}, and {@code Capture}.</li>
 *   <li>Bottom-Right: {@code Log} ({@link LogBuffer} ring buffer with All / Errors / Baritone filters, Copy, Clear,
 *       and disabled Send error to Assistant button) and {@code Assistant} (empty tab stub).</li>
 * </ul>
 */
public final class DockedPanels {
    private DockedPanels() {}

    // ------------------------------------------------------------------ Script Editor state
    public static final class ScriptTab {
        public final String name;
        public final Path file;   // null for "main (config)"
        public String text;

        public ScriptTab(String name, Path file, String text) {
            this.name = name;
            this.file = file;
            this.text = text == null ? "" : text;
        }
    }

    private static final List<ScriptTab> tabs = new ArrayList<>();
    private static int activeTabIndex = 0;
    private static final ImString editorBuffer = new ImString(65536);
    private static final ImString newTabName = new ImString("job.txt", 64);
    private static String lastSyncedText = null;
    private static String cachedSyntaxSource = "";
    private static int[] cachedSyntaxColors = new int[0];
    private static List<Suggest.Item> suggestions = List.of();
    private static int suggestFrom = 0;
    private static int selectedSuggestion = 0;
    private static List<ScriptCheck.Entry> checkResults = null;
    private static String editorStatus = "";
    private static boolean imguiPicking = false;
    private static boolean editorInputActive = false;
    private static int editorCursorPos = 0;
    private static final imgui.callback.ImGuiInputTextCallback EDITOR_CALLBACK = new imgui.callback.ImGuiInputTextCallback() {
        @Override
        public void accept(imgui.ImGuiInputTextCallbackData data) {
            editorCursorPos = data.getCursorPos();
        }
    };

    // Recorded button rects for TestBridge verification
    public static float runBtnX, runBtnY, runBtnW, runBtnH;
    public static float testBtnX, testBtnY, testBtnW, testBtnH;
    public static float pickBtnX, pickBtnY, pickBtnW, pickBtnH;

    // ------------------------------------------------------------------ Presets state
    private static final ImString presetSearch = new ImString("", 128);
    private static final ImString newPresetName = new ImString("", 64);
    private static String lastInsertedPreset = "";

    // ------------------------------------------------------------------ Chests state
    private static final ImString chestSearch = new ImString("", 128);
    private static ChestIndexView.Category chestCategory = ChestIndexView.Category.ALL;
    private static final ImBoolean chestLowStockOnly = new ImBoolean(false);
    private static ChestIndexView.SortColumn chestSortCol = ChestIndexView.SortColumn.ITEM;
    private static boolean chestSortAsc = true;
    private static int selectedChestRow = 0;
    private static List<ChestIndexView.Row> lastChestRows = List.of();

    // ------------------------------------------------------------------ Areas & Capture state
    private static final ImString newAreaName = new ImString("", 64);
    private static final ImString captureNameBuf = new ImString("", 64);
    private static final ImString newCaptureSetBuf = new ImString("", 64);
    private static final ImString captureSheetBuf = new ImString(32768);
    private static String lastCaptureNotice = "";
    private static String lastSyncedCaptureSheet = null;

    // ------------------------------------------------------------------ Log state
    private static LogBuffer.Filter logFilter = LogBuffer.Filter.ALL;
    private static final Set<Long> selectedLogIds = new LinkedHashSet<>();
    private static long lastClickedLogId = -1;
    private static String lastCopiedLog = "";

    // ------------------------------------------------------------------ Public API for TestBridge & QueueMod

    public static boolean isImGuiPicking() {
        return imguiPicking;
    }

    public static void handleImGuiPick(Places.Target t) {
        imguiPicking = false;
        if (t == null) {
            editorStatus = "Pick block cancelled (looked at sky).";
            return;
        }
        Suggest.target = t;
        Values.Pos p = t.pos();
        String pos = "pos(" + p.x() + ", " + p.y() + ", " + p.z() + ")";
        String cur = currentScriptText();
        String name = Places.varName(t.name());
        var taken = Suggest.definedNames(cur);
        String unique = name;
        for (int i = 2; taken.contains(unique) || Builtins.all().containsKey(unique); i++) unique = name + i;
        String line = "let " + unique + " = " + pos + "   // " + t.name();
        String updated = cur.isBlank() ? line : (cur.endsWith("\n") ? cur + line : cur + "\n" + line);
        setCurrentScriptText(updated);
        editorStatus = "Picked " + t.name() + " at " + p + " -> " + unique;
        Util.msg("Put in the " + t.name() + " at " + p + ".");
    }

    public static void ensureTabsLoaded() {
        String cfgJoined = String.join("\n", cfg.script);
        if (tabs.isEmpty()) {
            tabs.add(new ScriptTab("main", null, cfgJoined));
            loadDiskScripts();
            activeTabIndex = 0;
            editorBuffer.set(cfgJoined);
            lastSyncedText = cfgJoined;
            recomputeSyntaxAndSuggestions(cfgJoined);
        } else if (activeTabIndex == 0 && !cfgJoined.equals(lastSyncedText)) {
            tabs.get(0).text = cfgJoined;
            if (!editorInputActive) {
                editorBuffer.set(cfgJoined);
            }
            lastSyncedText = cfgJoined;
            recomputeSyntaxAndSuggestions(cfgJoined);
        }
    }

    private static void loadDiskScripts() {
        try {
            Path dir = scriptDir();
            if (dir != null && Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    stream.filter(p -> p.getFileName().toString().endsWith(".txt"))
                            .sorted()
                            .limit(8)
                            .forEach(p -> {
                                String fn = p.getFileName().toString();
                                try {
                                    String content = Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
                                    tabs.add(new ScriptTab(fn, p, content));
                                } catch (Exception ignored) {
                                }
                            });
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static Path scriptDir() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                return mc.gameDirectory.toPath().resolve("scripts");
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static int tabCount() {
        ensureTabsLoaded();
        return tabs.size();
    }

    public static String activeTabName() {
        ensureTabsLoaded();
        return tabs.get(Math.max(0, Math.min(activeTabIndex, tabs.size() - 1))).name;
    }

    public static void selectTab(int idx) {
        ensureTabsLoaded();
        if (idx < 0 || idx >= tabs.size()) return;
        activeTabIndex = idx;
        ScriptTab t = tabs.get(activeTabIndex);
        editorBuffer.set(t.text);
        syncToConfig(t.text);
        recomputeSyntaxAndSuggestions(t.text);
    }

    public static ScriptTab addScriptTab(String rawName, String initialContent) {
        ensureTabsLoaded();
        String clean = (rawName == null || rawName.isBlank()) ? "script-" + (tabs.size() + 1) + ".txt" : rawName.trim();
        if (!clean.endsWith(".txt")) clean += ".txt";
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).name.equalsIgnoreCase(clean)) {
                selectTab(i);
                if (initialContent != null) setCurrentScriptText(initialContent);
                return tabs.get(i);
            }
        }
        Path file = null;
        try {
            Path dir = scriptDir();
            if (dir != null) {
                Files.createDirectories(dir);
                file = dir.resolve(clean);
                if (initialContent != null) {
                    Files.writeString(file, initialContent, StandardCharsets.UTF_8);
                }
            }
        } catch (Throwable ignored) {
        }
        ScriptTab tab = new ScriptTab(clean, file, initialContent == null ? "" : initialContent);
        tabs.add(tab);
        selectTab(tabs.size() - 1);
        return tab;
    }

    public static String currentScriptText() {
        ensureTabsLoaded();
        return tabs.get(activeTabIndex).text;
    }

    public static void setCurrentScriptText(String text) {
        ensureTabsLoaded();
        if (text == null) text = "";
        ScriptTab t = tabs.get(activeTabIndex);
        t.text = text;
        if (!editorBuffer.get().equals(text)) {
            editorBuffer.set(text);
        }
        syncToConfig(text);
        if (t.file != null) {
            try { Files.writeString(t.file, text, StandardCharsets.UTF_8); } catch (Throwable ignored) {}
        }
        checkResults = null;
        recomputeSyntaxAndSuggestions(text);
    }

    private static void syncToConfig(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
        cfg.script = lines;
        lastSyncedText = String.join("\n", cfg.script);
        Config.save();
    }

    private static void recomputeSyntaxAndSuggestions(String text) {
        cachedSyntaxSource = text;
        try {
            cachedSyntaxColors = Syntax.colors(text);
        } catch (Throwable t) {
            cachedSyntaxColors = new int[text.length()];
        }
        try {
            Suggest.Result r = Suggest.complete(text, text.length(), true);
            suggestions = r.items();
            suggestFrom = r.from();
            selectedSuggestion = 0;
        } catch (Throwable t) {
            suggestions = List.of();
        }
    }

    public static List<ScriptCheck.Entry> runTest() {
        ensureTabsLoaded();
        syncToConfig(currentScriptText());
        checkResults = ScriptCheck.run(cfg.script);
        for (ScriptCheck.Entry e : checkResults) {
            if (e.level() != ScriptCheck.Level.OK) {
                Util.msg((e.line() > 0 ? "Line " + e.line() + ": " : "") + e.text());
            }
        }
        if (checkResults.size() == 1) {
            Util.msg(checkResults.get(0).text());
        }
        editorStatus = checkResults.get(checkResults.size() - 1).text();
        return checkResults;
    }

    public static List<ScriptCheck.Entry> lastCheckResults() {
        return checkResults == null ? List.of() : checkResults;
    }

    public static int lastErrorCount() {
        if (checkResults == null) return 0;
        int n = 0;
        for (ScriptCheck.Entry e : checkResults) {
            if (e.level() == ScriptCheck.Level.ERROR && e.line() > 0) n++;
        }
        return n;
    }

    public static List<Suggest.Item> currentSuggestions() {
        ensureTabsLoaded();
        return suggestions;
    }

    public static String acceptTopSuggestion() {
        ensureTabsLoaded();
        String text = currentScriptText();
        Suggest.Result r = Suggest.complete(text, text.length(), true);
        suggestions = r.items();
        suggestFrom = r.from();
        if (suggestions.isEmpty()) return text;
        int idx = Math.max(0, Math.min(selectedSuggestion, suggestions.size() - 1));
        String s = suggestions.get(idx).text();
        int start = Math.max(0, Math.min(suggestFrom, text.length()));
        String before = text.substring(0, start);
        String line = before.substring(before.lastIndexOf('\n') + 1).stripLeading();
        String insert = s;
        if (line.startsWith("#") || Lexer.KEYWORDS.contains(s)) insert += " ";
        String updated = text.substring(0, start) + insert;
        setCurrentScriptText(updated);
        editorStatus = "Completed: " + s;
        return updated;
    }

    public static void insertPresetByName(String name) {
        List<String> lines = cfg.presets.get(name);
        if (lines == null || lines.isEmpty()) return;
        String cur = currentScriptText();
        String block = String.join("\n", lines);
        String updated = cur.isBlank() ? block : cur + "\n" + block;
        setCurrentScriptText(updated);
        lastInsertedPreset = name;
        editorStatus = "Inserted preset '" + name + "' (" + lines.size() + " lines).";
        Util.msg(editorStatus);
    }

    public static String lastInsertedPreset() {
        return lastInsertedPreset;
    }

    public static List<ChestIndexView.Row> currentChestRows() {
        lastChestRows = ChestIndexView.query(chestSearch.get(), chestCategory, chestLowStockOnly.get(), chestSortCol, chestSortAsc);
        return lastChestRows;
    }

    public static void setChestFilter(String search, ChestIndexView.Category cat, boolean lowOnly) {
        if (search != null) chestSearch.set(search);
        if (cat != null) chestCategory = cat;
        chestLowStockOnly.set(lowOnly);
    }

    public static void setChestSort(ChestIndexView.SortColumn col, boolean asc) {
        if (col != null) chestSortCol = col;
        chestSortAsc = asc;
    }

    public static void setLogFilter(LogBuffer.Filter filter) {
        if (filter != null) logFilter = filter;
    }

    public static LogBuffer.Filter getLogFilter() {
        return logFilter;
    }

    public static void selectLogEntry(long id, boolean toggle, boolean range) {
        List<LogBuffer.Entry> entries = LogBuffer.entries(logFilter);
        if (range && lastClickedLogId >= 0) {
            int idx1 = -1, idx2 = -1;
            for (int i = 0; i < entries.size(); i++) {
                long eid = entries.get(i).id();
                if (eid == lastClickedLogId) idx1 = i;
                if (eid == id) idx2 = i;
            }
            if (idx1 >= 0 && idx2 >= 0) {
                int start = Math.min(idx1, idx2);
                int end = Math.max(idx1, idx2);
                for (int i = start; i <= end; i++) {
                    selectedLogIds.add(entries.get(i).id());
                }
                lastClickedLogId = id;
                return;
            }
        }
        if (toggle) {
            if (selectedLogIds.contains(id)) {
                selectedLogIds.remove(id);
            } else {
                selectedLogIds.add(id);
            }
            lastClickedLogId = id;
        } else {
            selectedLogIds.clear();
            selectedLogIds.add(id);
            lastClickedLogId = id;
        }
    }

    public static int selectedLogCount() {
        return selectedLogIds.size();
    }

    public static String copySelectedLogs() {
        if (selectedLogIds.isEmpty()) {
            String text = LogBuffer.copyToClipboard(logFilter);
            lastCopiedLog = text;
            return text;
        }
        StringBuilder sb = new StringBuilder();
        List<LogBuffer.Entry> all = LogBuffer.entries(LogBuffer.Filter.ALL);
        for (LogBuffer.Entry e : all) {
            if (selectedLogIds.contains(e.id())) {
                sb.append('[').append(e.time()).append("] [").append(e.kind().name()).append("] ").append(e.text()).append('\n');
            }
        }
        String text = sb.toString();
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.keyboardHandler != null) {
                mc.keyboardHandler.setClipboard(text);
            }
        } catch (Throwable ignored) {
        }
        lastCopiedLog = text;
        return text;
    }

    public static void clearLogSelection() {
        selectedLogIds.clear();
        lastClickedLogId = -1;
    }

    public static String lastCopiedLog() {
        return lastCopiedLog;
    }

    public static void setLastCopiedLog(String s) {
        lastCopiedLog = s == null ? "" : s;
    }

    // ------------------------------------------------------------------ ImGui rendering for all 6 panels

    /** 1. Top-Left Tab 1: Script Editor + Dashboard telemetry. */
    public static void renderScriptEditorPanel(ProgressModel.Snapshot snap) {
        ensureTabsLoaded();

        // File tabs bar + New tab
        if (ImGui.beginTabBar("##ScriptFilesTabBar")) {
            for (int i = 0; i < tabs.size(); i++) {
                ScriptTab t = tabs.get(i);
                if (ImGui.beginTabItem(t.name + "##tab" + i)) {
                    if (activeTabIndex != i) {
                        selectTab(i);
                    }
                    ImGui.endTabItem();
                }
            }
            ImGui.endTabBar();
        }

        // Toolbar: Run, Test, Pick block, Comment, Tab complete, + New tab
        if (ImGui.button("Run##EditorRun")) {
            syncToConfig(currentScriptText());
            String err = QueueMod.runner.start(cfg.script);
            if (err != null) {
                editorStatus = err;
                Util.msg(err);
            } else {
                editorStatus = "Running script (" + cfg.script.size() + " lines).";
            }
        }
        runBtnX = ImGui.getItemRectMinX();
        runBtnY = ImGui.getItemRectMinY();
        runBtnW = ImGui.getItemRectSizeX();
        runBtnH = ImGui.getItemRectSizeY();

        ImGui.sameLine(0f, 4f);
        if (ImGui.button("Test##EditorTest")) {
            runTest();
        }
        testBtnX = ImGui.getItemRectMinX();
        testBtnY = ImGui.getItemRectMinY();
        testBtnW = ImGui.getItemRectSizeX();
        testBtnH = ImGui.getItemRectSizeY();

        ImGui.sameLine(0f, 4f);
        if (ImGui.button("Pick block##EditorPick")) {
            Places.Target t = Places.target();
            if (t != null) {
                handleImGuiPick(t);
            } else {
                imguiPicking = true;
                QueueMod.picking = true;
                editorStatus = "Aim at a block in the Game Viewport and press J.";
            }
        }
        pickBtnX = ImGui.getItemRectMinX();
        pickBtnY = ImGui.getItemRectMinY();
        pickBtnW = ImGui.getItemRectSizeX();
        pickBtnH = ImGui.getItemRectSizeY();

        ImGui.sameLine(0f, 4f);
        if (ImGui.button("Tab (" + suggestions.size() + ")##EditorTabComplete")) {
            acceptTopSuggestion();
        }
        ImGui.sameLine(0f, 6f);
        ImGui.setNextItemWidth(76f);
        ImGui.inputText("##NewTabName", newTabName);
        ImGui.sameLine(0f, 4f);
        if (ImGui.button("+ Tab##AddScriptTab")) {
            addScriptTab(newTabName.get(), "// " + newTabName.get() + "\n");
        }

        // Map of error/warning lines from ScriptCheck
        Map<Integer, ScriptCheck.Entry> lineIssues = new LinkedHashMap<>();
        if (checkResults != null) {
            for (ScriptCheck.Entry e : checkResults) {
                if (e.line() > 0 && !lineIssues.containsKey(e.line())) {
                    lineIssues.put(e.line(), e);
                }
            }
        }

        // Single unified full-height editor (##UnifiedScriptEditor)
        float editorH = Math.max(80f, ImGui.getContentRegionAvailY() - 48f);
        ImGuiManager.pushMonoFont();
        if (ImGui.beginChild("##UnifiedCodeScroll", 0f, editorH, true)) {
            String src = currentScriptText();
            if (!src.equals(cachedSyntaxSource)) {
                recomputeSyntaxAndSuggestions(src);
            }
            String[] lines = src.split("\n", -1);
            ImDrawList dl = ImGui.getWindowDrawList();
            float startX = ImGui.getCursorScreenPosX();
            float startY = ImGui.getCursorScreenPosY();
            var style = ImGui.getStyle();
            float padX = style.getFramePaddingX();
            float padY = style.getFramePaddingY();
            float lineHeight = ImGui.getTextLineHeight();
            float codeStartX = startX + 38f;
            float availW = Math.max(120f, ImGui.getContentRegionAvailX() - 38f);
            float contentH = Math.max(editorH - 12f, (lines.length + 2) * ImGui.getTextLineHeightWithSpacing());
            float fullW = Math.max(160f, ImGui.getContentRegionAvailX());

            for (int i = 0; i < lines.length; i++) {
                int ln = i + 1;
                float rowY = startY + padY + i * lineHeight;
                boolean isRunningLine = snap.running() && snap.line() == ln;
                ScriptCheck.Entry issue = lineIssues.get(ln);
                if (isRunningLine) {
                    dl.addRectFilled(startX, rowY, startX + fullW, rowY + lineHeight, ImGui.colorConvertFloat4ToU32(0.290f, 0.620f, 0.878f, 0.22f));
                    dl.addRectFilled(startX, rowY, startX + 3f, rowY + lineHeight, ImGui.colorConvertFloat4ToU32(0.290f, 0.620f, 0.878f, 1.0f));
                    dl.addText(startX + 2f, rowY, ImGui.colorConvertFloat4ToU32(0.290f, 0.620f, 0.878f, 1.0f), String.format("%2d \u25b6", ln));
                } else if (issue != null) {
                    boolean err = issue.level() == ScriptCheck.Level.ERROR;
                    int bgCol = err ? ImGui.colorConvertFloat4ToU32(0.878f, 0.333f, 0.333f, 0.20f) : ImGui.colorConvertFloat4ToU32(0.878f, 0.659f, 0.290f, 0.16f);
                    dl.addRectFilled(startX, rowY, startX + fullW, rowY + lineHeight, bgCol);
                    int textCol = err ? ImGui.colorConvertFloat4ToU32(0.95f, 0.38f, 0.38f, 1.0f) : ImGui.colorConvertFloat4ToU32(0.95f, 0.78f, 0.35f, 1.0f);
                    dl.addText(startX + 2f, rowY, textCol, String.format("%2d !", ln));
                } else {
                    dl.addText(startX + 2f, rowY, ImGui.colorConvertFloat4ToU32(0.50f, 0.50f, 0.55f, 0.60f), String.format("%2d  ", ln));
                }
            }

            ImGui.setCursorScreenPos(codeStartX, startY);
            ImGui.pushStyleColor(ImGuiCol.FrameBg, 0f, 0f, 0f, 0f);
            ImGui.pushStyleColor(ImGuiCol.Border, 0f, 0f, 0f, 0f);
            ImGui.pushStyleColor(ImGuiCol.Text, 0f, 0f, 0f, 0f);
            if (ImGui.inputTextMultiline("##ScriptEditorInput", editorBuffer, availW, contentH,
                    ImGuiInputTextFlags.AllowTabInput | ImGuiInputTextFlags.CallbackAlways, EDITOR_CALLBACK)) {
                setCurrentScriptText(editorBuffer.get());
            }
            ImGui.popStyleColor(3);

            editorInputActive = ImGui.isItemActive();
            if (!editorInputActive) {
                ScriptTab t = tabs.get(activeTabIndex);
                if (!t.text.equals(editorBuffer.get())) {
                    editorBuffer.set(t.text);
                }
            }

            String renderSrc = editorBuffer.get();
            if (!renderSrc.equals(cachedSyntaxSource)) {
                recomputeSyntaxAndSuggestions(renderSrc);
            }
            String[] renderLines = renderSrc.split("\n", -1);
            int charOffset = 0;
            for (int i = 0; i < renderLines.length; i++) {
                float lineY = startY + padY + i * lineHeight;
                drawSyntaxLine(dl, codeStartX + padX, lineY, renderLines[i], charOffset, cachedSyntaxColors);
                charOffset += renderLines[i].length() + 1;
            }

            if (editorInputActive && ((System.currentTimeMillis() / 500L) % 2L == 0L)) {
                int cpos = Math.max(0, Math.min(editorCursorPos, renderSrc.length()));
                int rem = cpos;
                int cLine = 0;
                int cCol = 0;
                for (int i = 0; i < renderLines.length; i++) {
                    int lLen = renderLines[i].length();
                    if (rem <= lLen || i == renderLines.length - 1) {
                        cLine = i;
                        cCol = Math.min(rem, lLen);
                        break;
                    }
                    rem -= (lLen + 1);
                }
                String prefix = renderLines[cLine].substring(0, cCol);
                float caretX = codeStartX + padX + ImGui.calcTextSizeX(prefix);
                float caretY = startY + padY + cLine * lineHeight;
                int caretCol = ImGui.colorConvertFloat4ToU32(0.92f, 0.95f, 1.0f, 0.95f);
                dl.addRectFilled(caretX, caretY, caretX + 2f, caretY + lineHeight, caretCol);
            }
        }
        ImGui.endChild();
        ImGuiManager.popMonoFont();

        // Footer: Test results / Error hint line OR Suggest completions
        if (checkResults != null && !checkResults.isEmpty()) {
            ScriptCheck.Entry summary = checkResults.get(checkResults.size() - 1);
            for (int i = 0; i < checkResults.size() - 1 && i < 2; i++) {
                ScriptCheck.Entry e = checkResults.get(i);
                if (e.level() == ScriptCheck.Level.ERROR) {
                    ImGui.textColored(0.95f, 0.38f, 0.38f, 1.0f, "Line " + e.line() + ": " + e.text());
                } else {
                    ImGui.textColored(0.95f, 0.78f, 0.35f, 1.0f, "Line " + e.line() + ": " + e.text());
                }
            }
            if (summary.level() == ScriptCheck.Level.ERROR) {
                ImGui.textColored(0.95f, 0.38f, 0.38f, 1.0f, summary.text());
            } else if (summary.level() == ScriptCheck.Level.WARN) {
                ImGui.textColored(0.95f, 0.78f, 0.35f, 1.0f, summary.text());
            } else {
                ImGui.textColored(0.247f, 0.698f, 0.498f, 1.0f, summary.text());
            }
        } else if (!suggestions.isEmpty()) {
            Suggest.Item top = suggestions.get(Math.max(0, Math.min(selectedSuggestion, suggestions.size() - 1)));
            ImGui.textColored(0.37f, 0.76f, 1.0f, 1.0f, "Suggest: " + top.text());
            if (!top.desc().isEmpty()) {
                ImGui.sameLine(0f, 8f);
                ImGui.textDisabled(top.desc());
            }
        } else if (!editorStatus.isEmpty()) {
            ImGui.textDisabled(editorStatus);
        }
    }

    private static void drawSyntaxLine(ImDrawList dl, float x, float y, String line, int startOffset, int[] colors) {
        if (line.isEmpty()) {
            return;
        }
        int len = line.length();
        int runStart = 0;
        for (int i = 0; i <= len; i++) {
            int curColor = (i < len && startOffset + i < colors.length) ? colors[startOffset + i] : Syntax.DEFAULT;
            int startColor = (startOffset + runStart < colors.length) ? colors[startOffset + runStart] : Syntax.DEFAULT;
            if (i == len || curColor != startColor) {
                if (i > runStart) {
                    String seg = line.substring(runStart, i);
                    float r = ((startColor >> 16) & 0xFF) / 255f;
                    float g = ((startColor >> 8) & 0xFF) / 255f;
                    float b = (startColor & 0xFF) / 255f;
                    int colU32 = ImGui.colorConvertFloat4ToU32(r, g, b, 1.0f);
                    float segX = x + (runStart == 0 ? 0f : ImGui.calcTextSizeX(line.substring(0, runStart)));
                    dl.addText(segX, y, colU32, seg);
                }
                runStart = i;
            }
        }
    }

    /** 2. Top-Left Tab 2: Presets (list, insert, save, share). */
    public static void renderPresetsPanel() {
        ImGui.setNextItemWidth(140f);
        ImGui.inputTextWithHint("##PresetSearch", "Filter presets...", presetSearch);
        ImGui.sameLine(0f, 6f);
        if (ImGui.button("Copy all##PresetsCopyAll")) PresetShare.copy(cfg.presets);
        ImGui.sameLine(0f, 4f);
        if (ImGui.button("Paste##PresetsPaste")) PresetShare.paste();
        ImGui.sameLine(0f, 4f);
        if (ImGui.button("Export##PresetsExport")) PresetShare.export();
        ImGui.sameLine(0f, 4f);
        if (ImGui.button("Import##PresetsImport")) PresetShare.importFile();

        ImGui.setNextItemWidth(160f);
        ImGui.inputTextWithHint("##NewPresetName", "New preset name...", newPresetName);
        ImGui.sameLine(0f, 6f);
        if (ImGui.button("Save script as preset##SavePresetBtn")) {
            String nm = newPresetName.get().trim();
            if (!nm.isEmpty() && !cfg.script.isEmpty()) {
                cfg.presets.put(nm, new ArrayList<>(cfg.script));
                Config.save();
                Util.msg("Saved preset '" + nm + "' (" + cfg.script.size() + " lines).");
            }
        }

        ImGui.separator();
        String q = presetSearch.get().trim().toLowerCase();
        if (ImGui.beginChild("##PresetsListScroll", 0f, 0f, false)) {
            List<String> names = new ArrayList<>(cfg.presets.keySet());
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                if (!q.isEmpty() && !name.toLowerCase().contains(q)) continue;
                List<String> lines = cfg.presets.get(name);
                if (lines == null) continue;

                if (ImGui.button("Insert##ins_" + i)) {
                    insertPresetByName(name);
                }
                ImGui.sameLine(0f, 4f);
                if (ImGui.button("Copy##cpy_" + i)) {
                    PresetShare.copy(Map.of(name, lines));
                }
                ImGui.sameLine(0f, 4f);
                if (ImGui.button("X##del_" + i)) {
                    cfg.presets.remove(name);
                    Config.save();
                    continue;
                }
                ImGui.sameLine(0f, 8f);
                ImGui.text(name + " (" + lines.size() + " lines)");
                if (ImGui.isItemHovered()) {
                    StringBuilder tip = new StringBuilder();
                    for (int k = 0; k < Math.min(lines.size(), 8); k++) {
                        tip.append(lines.get(k)).append('\n');
                    }
                    if (lines.size() > 8) tip.append("... +").append(lines.size() - 8).append(" more");
                    ImGui.setTooltip(tip.toString().trim());
                }
            }
        }
        ImGui.endChild();
    }

    /** 3. Bottom-Left Tab 1: Chests (searchable, sortable table over ChestIndex, category filters, low-stock, detail pane). */
    public static void renderChestsPanel() {
        ImGui.setNextItemWidth(130f);
        ImGui.inputTextWithHint("##ChestSearch", "Search item/chest...", chestSearch);
        ImGui.sameLine(0f, 6f);
        ImGui.checkbox("Low stock only", chestLowStockOnly);

        // Category filter buttons
        for (ChestIndexView.Category cat : ChestIndexView.Category.values()) {
            if (cat != ChestIndexView.Category.ALL) ImGui.sameLine(0f, 4f);
            boolean active = (chestCategory == cat);
            if (active) ImGui.pushStyleColor(ImGuiCol.Button, 0.290f, 0.620f, 0.878f, 0.85f);
            if (ImGui.button(cat.label + "##cat_" + cat.name())) {
                chestCategory = cat;
            }
            if (active) ImGui.popStyleColor();
        }

        List<ChestIndexView.Row> rows = currentChestRows();
        float availH = ImGui.getContentRegionAvailY();
        float detailH = 74f;
        float tableH = Math.max(48f, availH - detailH - 6f);

        int flags = ImGuiTableFlags.RowBg | ImGuiTableFlags.BordersInnerV | ImGuiTableFlags.ScrollY | ImGuiTableFlags.SizingStretchProp;
        if (ImGui.beginTable("##ChestIndexTable", 4, flags, 0f, tableH)) {
            ImGui.tableSetupColumn("Item", ImGuiTableColumnFlags.WidthStretch, 0.32f);
            ImGui.tableSetupColumn("Count", ImGuiTableColumnFlags.WidthStretch, 0.16f);
            ImGui.tableSetupColumn("Chest", ImGuiTableColumnFlags.WidthStretch, 0.32f);
            ImGui.tableSetupColumn("Status", ImGuiTableColumnFlags.WidthStretch, 0.20f);

            // Clickable sort headers
            ImGui.tableNextRow();
            sortHeaderCell(0, "Item", ChestIndexView.SortColumn.ITEM);
            sortHeaderCell(1, "Count", ChestIndexView.SortColumn.COUNT);
            sortHeaderCell(2, "Chest", ChestIndexView.SortColumn.CHEST);
            sortHeaderCell(3, "Status", ChestIndexView.SortColumn.STATUS);

            for (int i = 0; i < rows.size(); i++) {
                ChestIndexView.Row r = rows.get(i);
                ImGui.tableNextRow();
                ImGui.tableSetColumnIndex(0);
                boolean isSel = (selectedChestRow == i);
                if (ImGui.selectable(r.itemName() + "##chestrow_" + i, isSel)) {
                    selectedChestRow = i;
                }
                ImGui.tableSetColumnIndex(1);
                ImGui.text(String.valueOf(r.count()));
                ImGui.tableSetColumnIndex(2);
                ImGui.text(r.chestName());
                ImGui.tableSetColumnIndex(3);
                if ("Blocked".equals(r.status())) {
                    ImGui.textColored(0.95f, 0.38f, 0.38f, 1.0f, r.status());
                } else if (r.lowStock()) {
                    ImGui.textColored(0.95f, 0.78f, 0.35f, 1.0f, r.status());
                } else {
                    ImGui.textColored(0.247f, 0.698f, 0.498f, 1.0f, r.status());
                }
            }
            ImGui.endTable();
        }

        // Detail pane
        ImGui.separator();
        if (rows.isEmpty()) {
            ImGui.textDisabled("No matching chest items indexed yet (open a chest in game to index it).");
        } else {
            int idx = Math.max(0, Math.min(selectedChestRow, rows.size() - 1));
            ChestIndexView.Row sel = rows.get(idx);
            ImGui.text("Selected: " + sel.itemName() + " x" + sel.count() + " in " + sel.chestName() + " (" + sel.age() + ")");
            if (ImGui.button("Show in world##ChestShowWorld")) {
                ChestIndexView.showInWorld(sel.x(), sel.y(), sel.z());
            }
            ImGui.sameLine(0f, 6f);
            if (ImGui.button("Copy position as pos(...)##ChestCopyPos")) {
                ChestIndexView.copyPos(sel.x(), sel.y(), sel.z());
            }
            ImGui.sameLine(0f, 6f);
            if (ImGui.button("Go there (Baritone)##ChestGoThere")) {
                ChestIndexView.goThere(sel.x(), sel.y(), sel.z());
            }
        }
    }

    private static void sortHeaderCell(int colIdx, String title, ChestIndexView.SortColumn col) {
        ImGui.tableSetColumnIndex(colIdx);
        String arrow = (chestSortCol == col) ? (chestSortAsc ? " ^" : " v") : "";
        if (ImGui.smallButton(title + arrow + "##sort_" + col.name())) {
            if (chestSortCol == col) chestSortAsc = !chestSortAsc;
            else {
                chestSortCol = col;
                chestSortAsc = true;
            }
        }
    }

    /** 4. Bottom-Left Tab 2: Areas (saved areas, chests, beds, Litematica placements). */
    public static void renderAreasPanel() {
        ImGui.text("Current #sel: " + AreasView.currentSelectionSummary());
        ImGui.setNextItemWidth(120f);
        ImGui.inputTextWithHint("##NewAreaName", "Area name...", newAreaName);
        ImGui.sameLine(0f, 6f);
        if (ImGui.button("Save #sel as area##SaveAreaBtn")) {
            String err = AreasView.saveCurrent(newAreaName.get());
            if (err != null) Util.msg(err);
        }

        ImGui.separator();
        ImGui.textColored(0.290f, 0.620f, 0.878f, 1.0f, "Saved Areas:");
        List<AreasView.AreaEntry> areas = AreasView.list();
        if (areas.isEmpty()) {
            ImGui.textDisabled("No saved areas.");
        } else {
            for (int i = 0; i < areas.size(); i++) {
                AreasView.AreaEntry a = areas.get(i);
                if (ImGui.smallButton("Select##selarea_" + i)) {
                    AreasView.select(a.name());
                }
                ImGui.sameLine(0f, 4f);
                if (ImGui.smallButton("Copy##cpyarea_" + i)) {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null && mc.keyboardHandler != null) mc.keyboardHandler.setClipboard(a.scriptCall());
                    Util.msg("Copied " + a.scriptCall());
                }
                if (!a.isLast()) {
                    ImGui.sameLine(0f, 4f);
                    if (ImGui.smallButton("X##delarea_" + i)) {
                        AreasView.delete(a.name());
                        continue;
                    }
                }
                ImGui.sameLine(0f, 6f);
                ImGui.text(a.name() + ": " + a.summary());
            }
        }

        ImGui.separator();
        ImGui.textColored(0.290f, 0.620f, 0.878f, 1.0f, "Saved Chests & Spots:");
        List<PlacesView.SavedSpot> spots = PlacesView.spots();
        if (spots.isEmpty()) {
            ImGui.textDisabled("No saved chests or spots.");
        } else {
            for (int i = 0; i < spots.size(); i++) {
                PlacesView.SavedSpot sp = spots.get(i);
                if (ImGui.smallButton("Go##gospot_" + i)) {
                    ChestIndexView.goThere(sp.x(), sp.y(), sp.z());
                }
                ImGui.sameLine(0f, 4f);
                if (ImGui.smallButton("Copy##cpyspot_" + i)) {
                    ChestIndexView.copyPos(sp.x(), sp.y(), sp.z());
                }
                ImGui.sameLine(0f, 6f);
                ImGui.text(sp.name() + " [" + sp.kind() + "] at " + sp.x() + " " + sp.y() + " " + sp.z());
            }
        }

        ImGui.separator();
        ImGui.textColored(0.290f, 0.620f, 0.878f, 1.0f, "Litematica Placements:");
        List<PlacesView.SchematicEntry> schems = PlacesView.schematics();
        if (schems.isEmpty()) {
            ImGui.textDisabled("No Litematica placements in this world.");
        } else {
            for (PlacesView.SchematicEntry sc : schems) {
                ImGui.text(sc.index() + ". " + sc.name() + " at " + sc.x() + " " + sc.y() + " " + sc.z()
                        + " (" + sc.regions() + " region" + (sc.regions() == 1 ? "" : "s") + (sc.enabled() ? "" : ", disabled") + ")");
            }
        }
    }

    /** 5. Bottom-Left Tab 3: Capture (roles chest/bed/area/schematic, Fetch looked-at, Aim in world, sheet). */
    public static void renderCapturePanel() {
        ImGui.text("Set: " + cfg.captureSet + " (" + Capture.world() + ")");
        ImGui.sameLine(0f, 8f);
        ImGui.setNextItemWidth(90f);
        ImGui.inputTextWithHint("##CaptureSetInput", "set name", newCaptureSetBuf);
        ImGui.sameLine(0f, 4f);
        if (ImGui.smallButton("Switch set")) {
            if (!newCaptureSetBuf.get().isBlank()) {
                lastCaptureNotice = Capture.openSet(newCaptureSetBuf.get().trim());
            }
        }

        // Role selector buttons
        Capture.Type curType = Capture.type();
        ImGui.text("Role: " + curType.label + (curType.hint.isEmpty() ? "" : " (" + curType.hint + ")"));
        Capture.Type[] allTypes = Capture.Type.values();
        for (int i = 0; i < allTypes.length; i++) {
            Capture.Type ty = allTypes[i];
            if (i > 0 && i % 5 != 0) ImGui.sameLine(0f, 4f);
            boolean active = (curType == ty);
            if (active) ImGui.pushStyleColor(ImGuiCol.Button, 0.290f, 0.620f, 0.878f, 0.85f);
            if (ImGui.smallButton(ty.id + "##role_" + ty.id)) {
                cfg.captureType = ty.id;
                Config.save();
            }
            if (active) ImGui.popStyleColor();
        }

        ImGui.setNextItemWidth(120f);
        if (ImGui.inputTextWithHint("##CaptureVarName", "Optional name...", captureNameBuf)) {
            Capture.name = captureNameBuf.get();
        }
        ImGui.sameLine(0f, 6f);
        ImGui.textDisabled("Looked at: " + PlacesView.lookedAtSummary());

        // Action buttons: Fetch looked-at, Aim in world, Use #sel, Import saved, Copy full context
        if (ImGui.button("Fetch looked-at##CapFetch")) {
            Capture.name = captureNameBuf.get();
            lastCaptureNotice = PlacesView.fetchLookedAt();
        }
        ImGui.sameLine(0f, 6f);
        if (ImGui.button("Aim in world##CapAim")) {
            Capture.name = captureNameBuf.get();
            lastCaptureNotice = PlacesView.startAimingInWorld();
        }
        if (curType.kind == Capture.Kind.AREA) {
            ImGui.sameLine(0f, 6f);
            if (ImGui.button("Use #sel##CapUseSel")) {
                Capture.name = captureNameBuf.get();
                lastCaptureNotice = Capture.useSelection();
                Util.msg(lastCaptureNotice);
            }
        }
        ImGui.sameLine(0f, 6f);
        if (ImGui.button("Import saved##CapImport")) {
            lastCaptureNotice = Capture.importSaved();
            Util.msg(lastCaptureNotice);
        }
        ImGui.sameLine(0f, 6f);
        if (ImGui.button("Copy context##CapContext")) {
            String ctx = Capture.fullContext();
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.keyboardHandler != null) mc.keyboardHandler.setClipboard(ctx);
            lastCaptureNotice = "Copied full context (" + ctx.length() + " chars).";
            Util.msg(lastCaptureNotice);
        }

        if (!lastCaptureNotice.isEmpty()) {
            ImGui.textColored(0.247f, 0.698f, 0.498f, 1.0f, lastCaptureNotice);
        }

        String sheet = Capture.text();
        if (!sheet.equals(lastSyncedCaptureSheet)) {
            captureSheetBuf.set(sheet);
            lastSyncedCaptureSheet = sheet;
        }
        float availH = Math.max(40f, ImGui.getContentRegionAvailY());
        if (ImGui.inputTextMultiline("##CaptureSheetEdit", captureSheetBuf, -1f, availH)) {
            lastSyncedCaptureSheet = captureSheetBuf.get();
            Capture.setText(lastSyncedCaptureSheet);
        }
    }

    /** 6. Bottom-Right Tab 1: Log (LogBuffer ring buffer, filters All/Errors/Baritone, Copy, Clear, disabled Send error to Assistant). */
    public static void renderLogPanel() {
        for (LogBuffer.Filter f : LogBuffer.Filter.values()) {
            if (f != LogBuffer.Filter.ALL) ImGui.sameLine(0f, 4f);
            boolean active = (logFilter == f);
            if (active) ImGui.pushStyleColor(ImGuiCol.Button, 0.290f, 0.620f, 0.878f, 0.85f);
            if (ImGui.button(f.label + " (" + LogBuffer.count(f) + ")##logf_" + f.name())) {
                logFilter = f;
            }
            if (active) ImGui.popStyleColor();
        }

        ImGui.sameLine(0f, 10f);
        if (!selectedLogIds.isEmpty()) {
            if (ImGui.button("Copy selected (" + selectedLogIds.size() + ")##LogCopy")) {
                copySelectedLogs();
            }
            ImGui.sameLine(0f, 4f);
            if (ImGui.button("Clear selection##LogUnselect")) {
                clearLogSelection();
            }
        } else {
            if (ImGui.button("Copy all##LogCopy")) {
                copySelectedLogs();
            }
        }
        ImGui.sameLine(0f, 4f);
        if (ImGui.button("Clear##LogClear")) {
            LogBuffer.clear();
            clearLogSelection();
        }
        ImGui.sameLine(0f, 8f);
        ImGui.beginDisabled(true);
        ImGui.button("Send error to Assistant##LogSendAssistant");
        ImGui.endDisabled();
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Assistant is not configured yet (Stage 6).");
        }

        ImGui.separator();
        List<LogBuffer.Entry> entries = LogBuffer.entries(logFilter);
        if (ImGui.beginChild("##LogEntriesScroll", 0f, 0f, false)) {
            if (entries.isEmpty()) {
                ImGui.textDisabled("No log entries matching filter '" + logFilter.label + "'.");
            } else {
                for (LogBuffer.Entry e : entries) {
                    String lineText = "[" + e.time() + "] " + e.text();
                    if (e.kind() == LogBuffer.Kind.ERROR) {
                        ImGui.pushStyleColor(ImGuiCol.Text, 0.95f, 0.38f, 0.38f, 1.0f);
                    } else if (e.kind() == LogBuffer.Kind.BARITONE) {
                        ImGui.pushStyleColor(ImGuiCol.Text, 0.34f, 0.68f, 0.92f, 1.0f);
                    } else {
                        ImGui.pushStyleColor(ImGuiCol.Text, 0.88f, 0.89f, 0.92f, 1.0f);
                    }
                    if (ImGui.selectable(lineText + "##log_" + e.id(), selectedLogIds.contains(e.id()))) {
                        boolean ctrl = ImGui.getIO().getKeyCtrl();
                        boolean shift = ImGui.getIO().getKeyShift();
                        if (shift && lastClickedLogId >= 0) {
                            int idx1 = -1, idx2 = -1;
                            for (int i = 0; i < entries.size(); i++) {
                                long eid = entries.get(i).id();
                                if (eid == lastClickedLogId) idx1 = i;
                                if (eid == e.id()) idx2 = i;
                            }
                            if (idx1 >= 0 && idx2 >= 0) {
                                int start = Math.min(idx1, idx2);
                                int end = Math.max(idx1, idx2);
                                for (int i = start; i <= end; i++) {
                                    selectedLogIds.add(entries.get(i).id());
                                }
                                lastClickedLogId = e.id();
                            } else {
                                selectedLogIds.add(e.id());
                                lastClickedLogId = e.id();
                            }
                        } else if (ctrl) {
                            if (selectedLogIds.contains(e.id())) {
                                selectedLogIds.remove(e.id());
                            } else {
                                selectedLogIds.add(e.id());
                            }
                            lastClickedLogId = e.id();
                        } else {
                            if (selectedLogIds.size() == 1 && selectedLogIds.contains(e.id())) {
                                clearLogSelection();
                            } else {
                                selectedLogIds.clear();
                                selectedLogIds.add(e.id());
                                lastClickedLogId = e.id();
                            }
                        }
                    }
                    ImGui.popStyleColor();
                }
            }
            if (ImGui.isWindowFocused() && ImGui.getIO().getKeyCtrl() && ImGui.isKeyPressed(GLFW.GLFW_KEY_C) && !selectedLogIds.isEmpty()) {
                copySelectedLogs();
            }
        }
        ImGui.endChild();
    }

    /** 7. Bottom-Right Tab 2: Assistant (empty tab stub only, as required by Stage 3 prompt). */
    public static void renderAssistantPanel() {
        ImGui.textDisabled("Assistant (Stage 6)");
        ImGui.separator();
        ImGui.textWrapped("The LLM Assistant tab is a stub in Stage 3. Use the Capture panel's 'Copy context' button to export world and script context for an external LLM.");
    }
}
