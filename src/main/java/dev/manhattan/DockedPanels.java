package dev.manhattan;

import dev.manhattan.project.GameProjectRuntime;
import dev.manhattan.project.ProjectTask;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
import imgui.flag.ImGuiKey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.manhattan.Config.cfg;

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
    private static int editorSelStart = 0;
    private static int editorSelEnd = 0;
    /** Cursor (char index) when the editor was last active; -1 = never. Pick block and presets go in here. */
    private static int lastCursor = -1;
    // an edit to apply inside the input callback (completion, comment toggle, pick) while the editor is active
    private static String pendingText = null;
    private static int pendingCursor = 0;
    private static boolean refocusEditor = false;
    // completion state
    private static String suggestKey = "";
    private static boolean suggestForced = false;
    private static boolean popupDismissed = false;
    private static boolean popupNavigated = false;
    // live checks (debounced)
    private static List<ScriptCheck.Entry> liveChecks = List.of();
    private static String liveCheckedText = null;
    private static long lastEditAt = 0;
    // config writes are throttled to one per second while typing
    private static boolean configDirty = false;
    private static long lastConfigSave = 0;
    private static final imgui.callback.ImGuiInputTextCallback EDITOR_CALLBACK = new imgui.callback.ImGuiInputTextCallback() {
        @Override
        public void accept(imgui.ImGuiInputTextCallbackData data) {
            String buf = data.getBuf();
            if (pendingText != null) {
                String t = pendingText;
                pendingText = null;
                data.deleteChars(0, data.getBufTextLen());
                data.insertChars(0, t);
                int c = charToByte(t, pendingCursor);
                data.setCursorPos(c);
                data.setSelectionStart(c);
                data.setSelectionEnd(c);
                buf = t;
            }
            editorCursorPos = byteToChar(buf, data.getCursorPos());
            editorSelStart = byteToChar(buf, data.getSelectionStart());
            editorSelEnd = byteToChar(buf, data.getSelectionEnd());
            lastCursor = editorCursorPos;
        }
    };

    /** ImGui works in UTF-8 byte offsets; the script text in chars. */
    static int byteToChar(String s, int bytes) {
        int b = 0;
        for (int i = 0; i < s.length(); i++) {
            if (b >= bytes) return i;
            int cp = s.codePointAt(i);
            b += cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (Character.isSupplementaryCodePoint(cp)) i++;
        }
        return s.length();
    }

    static int charToByte(String s, int chars) {
        int n = Math.max(0, Math.min(chars, s.length()));
        return s.substring(0, n).getBytes(StandardCharsets.UTF_8).length;
    }

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
    private static String lastAreasNotice = "";

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
        int at = lastCursor < 0 ? cur.length() : Math.min(lastCursor, cur.length());
        int ls = at == 0 ? 0 : cur.lastIndexOf('\n', at - 1) + 1;
        int le = cur.indexOf('\n', at);
        if (le < 0) le = cur.length();
        String lineNow = cur.substring(ls, le);
        String updated;
        int cursorAfter;
        if (lineNow.isBlank() || lastCursor < 0) {
            // an empty line (or no cursor yet): a named position on its own line
            String name = Places.varName(t.name());
            var taken = Suggest.definedNames(cur);
            String unique = name;
            for (int i = 2; taken.contains(unique) || Builtins.all().containsKey(unique); i++) unique = name + i;
            String line = "let " + unique + " = " + pos + "   // " + t.name();
            if (lastCursor < 0) {
                updated = cur.isBlank() ? line : (cur.endsWith("\n") ? cur + line : cur + "\n" + line);
                cursorAfter = updated.length();
            } else {
                updated = cur.substring(0, ls) + line + cur.substring(le);
                cursorAfter = ls + line.length();
            }
            editorStatus = "Picked " + t.name() + " at " + p + " -> " + unique;
        } else {
            // inside a line: pos(...) at the cursor; bare coordinates in # and / lines
            String before = cur.substring(ls, at);
            boolean bare = before.stripLeading().startsWith("#") || before.stripLeading().startsWith("/");
            String insert = bare ? p.x() + " " + p.y() + " " + p.z() : before.endsWith("pos(") ? p.x() + ", " + p.y() + ", " + p.z() + ")" : pos;
            updated = cur.substring(0, at) + insert + cur.substring(at);
            cursorAfter = at + insert.length();
            editorStatus = "Picked " + t.name() + " at " + p;
        }
        replaceText(updated, cursorAfter);
        Util.msg("Put in the " + t.name() + " at " + p + ".");
    }

    /** Replaces the active tab's text and puts the cursor at {@code cursor} (also while the editor is being typed in). */
    static void replaceText(String updated, int cursor) {
        if (editorInputActive) {
            pendingText = updated;
            pendingCursor = cursor;
        }
        lastCursor = cursor;
        setCurrentScriptText(updated);
    }

    /** Pick block button: the looked-at block if there is one in the game view, else aim in the world (J). */
    static void pickBlock() {
        Places.Target t = Places.target();
        if (t != null) {
            handleImGuiPick(t);
        } else {
            imguiPicking = true;
            QueueMod.picking = true;
            editorStatus = "Aim at a block and press J.";
            Shell.suspendForAiming();
        }
    }

    /** Run button: runs the active tab. */
    static void runCurrent() {
        ensureTabsLoaded();
        flushConfig(true);
        String err = QueueMod.runner.start(cfg.script, activeTabIndex == 0 ? Jobs.SCRIPT : activeTabName());
        if (err != null) {
            editorStatus = err;
            Util.msg(err);
        } else {
            editorStatus = "Running " + activeTabName() + " (" + cfg.script.size() + " lines).";
        }
    }

    static String tabName(int i) {
        ensureTabsLoaded();
        return i >= 0 && i < tabs.size() ? tabs.get(i).name : "";
    }

    /** Writes the script to the config file if it changed (at most once a second unless {@code now}). */
    static void flushConfig(boolean now) {
        if (!configDirty) return;
        long t = System.currentTimeMillis();
        if (!now && t - lastConfigSave < 1000) return;
        configDirty = false;
        lastConfigSave = t;
        Config.save();
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

    /** Puts the (remembered) cursor at char index {@code c}, as if the editor had been clicked there (tests). */
    public static void setCursor(int c) {
        int n = Math.max(0, Math.min(c, currentScriptText().length()));
        lastCursor = n;
        editorCursorPos = n;
        editorSelStart = n;
        editorSelEnd = n;
    }

    /** Sets the text from outside the editor (tests, commands): the cursor is forgotten, inserts go to the end. */
    public static void setScriptText(String text) {
        lastCursor = -1;
        setCurrentScriptText(text);
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
        configDirty = true;
        lastEditAt = System.currentTimeMillis();
        flushConfig(false);
    }

    private static void recomputeSyntaxAndSuggestions(String text) {
        cachedSyntaxSource = text;
        try {
            cachedSyntaxColors = Syntax.colors(text);
        } catch (Throwable t) {
            cachedSyntaxColors = new int[text.length()];
        }
        updateSuggestions(text, true);
    }

    /** Completions at the cursor while typing (at the end of the text otherwise); recomputed when text or cursor move. */
    private static void updateSuggestions(String text, boolean textChanged) {
        int cur = editorInputActive ? Math.max(0, Math.min(editorCursorPos, text.length()))
                : lastCursor >= 0 ? Math.min(lastCursor, text.length()) : text.length();
        boolean force = suggestForced || !editorInputActive;
        String key = cur + ":" + force + ":" + text.hashCode() + ":" + text.length();
        if (key.equals(suggestKey)) return;
        suggestKey = key;
        if (textChanged) {
            popupDismissed = false;
        }
        popupNavigated = false;
        try {
            Suggest.Result r = Suggest.complete(text, cur, force);
            suggestions = r.items();
            suggestFrom = r.from();
            selectedSuggestion = 0;
        } catch (Throwable t) {
            suggestions = List.of();
        }
    }

    /** The word typed so far that the completions replace. */
    private static String typedPrefix(String text) {
        int cur = Math.max(0, Math.min(editorCursorPos, text.length()));
        int from = Math.max(0, Math.min(suggestFrom, cur));
        return text.substring(from, cur);
    }

    private static boolean popupVisible() {
        if (!editorInputActive || popupDismissed || suggestions.isEmpty()) return false;
        if (suggestForced) return true;
        String typed = typedPrefix(currentScriptText());
        return !typed.isEmpty() && (suggestions.size() > 1 || !suggestions.get(0).text().equals(typed));
    }

    /** Puts the chosen completion in at the cursor. */
    static void acceptSuggestion(int idx) {
        String text = currentScriptText();
        if (suggestions.isEmpty()) return;
        int cur = Math.max(0, Math.min(editorInputActive ? editorCursorPos : (lastCursor >= 0 ? lastCursor : text.length()), text.length()));
        int start = Math.max(0, Math.min(suggestFrom, cur));
        String s = suggestions.get(Math.max(0, Math.min(idx, suggestions.size() - 1))).text();
        String before = text.substring(0, start);
        String line = before.substring(before.lastIndexOf('\n') + 1).stripLeading();
        String insert = s;
        if (line.startsWith("#") || Lexer.KEYWORDS.contains(s)) insert += " ";
        String updated = text.substring(0, start) + insert + text.substring(cur);
        suggestForced = false;
        replaceText(updated, start + insert.length());
        editorStatus = "Completed: " + s;
    }

    /**
     * Keys the editor handles itself before ImGui sees them (called from ImGuiManager.onKey): Tab accepts the
     * completion, Up/Down/Enter/Esc drive the completion list, Ctrl+Space shows all completions, Ctrl+/ comments lines.
     */
    static boolean handleEditorKey(int key, int mods) {
        if (!editorInputActive) return false;
        boolean ctrl = (mods & Platform.MOD_CONTROL) != 0;   // Ctrl+Space stays Ctrl: Cmd+Space is Spotlight on macOS
        boolean shortcut = Platform.shortcutDown(mods);
        boolean popup = popupVisible();
        if (key == InputConstants.KEY_TAB && mods == 0 && !suggestions.isEmpty() && (popup || ghostSuffix() != null)) {
            acceptSuggestion(selectedSuggestion);
            return true;
        }
        if (ctrl && key == InputConstants.KEY_SPACE) {
            suggestForced = true;
            popupDismissed = false;
            suggestKey = "";
            updateSuggestions(currentScriptText(), false);
            return true;
        }
        if (shortcut && key == InputConstants.KEY_SLASH) {
            toggleComment();
            return true;
        }
        if (popup) {
            int n = suggestions.size();
            switch (key) {
                case InputConstants.KEY_DOWN -> { selectedSuggestion = (selectedSuggestion + 1) % n; popupNavigated = true; return true; }
                case InputConstants.KEY_UP -> { selectedSuggestion = (selectedSuggestion - 1 + n) % n; popupNavigated = true; return true; }
                case InputConstants.KEY_PAGEDOWN -> { selectedSuggestion = Math.min(n - 1, selectedSuggestion + 8); popupNavigated = true; return true; }
                case InputConstants.KEY_PAGEUP -> { selectedSuggestion = Math.max(0, selectedSuggestion - 8); popupNavigated = true; return true; }
                case InputConstants.KEY_ESCAPE -> { popupDismissed = true; suggestForced = false; return true; }
                case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                    if (popupNavigated) { acceptSuggestion(selectedSuggestion); return true; }
                    return false;
                }
                default -> { return false; }
            }
        }
        return false;
    }

    /** What the top completion would add after the cursor (shown greyed out), or null. */
    private static String ghostSuffix() {
        if (!editorInputActive || suggestions.isEmpty() || popupDismissed) return null;
        String text = currentScriptText();
        int cur = Math.max(0, Math.min(editorCursorPos, text.length()));
        if (cur < text.length() && (Character.isLetterOrDigit(text.charAt(cur)) || text.charAt(cur) == '_')) return null;
        String typed = typedPrefix(text);
        String s = suggestions.get(Math.max(0, Math.min(selectedSuggestion, suggestions.size() - 1))).text();
        if (typed.isEmpty() && !s.startsWith("pos(") && !s.startsWith("let ") && !suggestForced) return null;
        if (!s.startsWith(typed) || s.length() <= typed.length()) return null;
        return s.substring(typed.length());
    }

    /** Ctrl+/ : "// " in front of the selected lines (or the cursor line), or away again. */
    static void toggleComment() {
        String value = currentScriptText();
        int cursor = Math.max(0, Math.min(editorCursorPos, value.length()));
        int a = Math.min(editorSelStart, editorSelEnd), b = Math.max(editorSelStart, editorSelEnd);
        if (a == b) { a = cursor; b = cursor; }
        a = Math.max(0, Math.min(a, value.length()));
        b = Math.max(0, Math.min(b, value.length()));
        int ls = a == 0 ? 0 : value.lastIndexOf('\n', a - 1) + 1;
        int le = value.indexOf('\n', b);
        if (le < 0) le = value.length();
        String[] lines = value.substring(ls, le).split("\n", -1);
        boolean allCommented = true;
        for (String l : lines) if (!l.isBlank() && !l.stripLeading().startsWith("//")) allCommented = false;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (i > 0) out.append('\n');
            if (l.isBlank()) { out.append(l); continue; }
            if (allCommented) {
                int at = l.indexOf("//");
                String rest = l.substring(at + 2);
                out.append(l, 0, at).append(rest.startsWith(" ") ? rest.substring(1) : rest);
            } else {
                int indent = l.length() - l.stripLeading().length();
                out.append(l, 0, indent).append("// ").append(l.substring(indent));
            }
        }
        String updated = value.substring(0, ls) + out + value.substring(le);
        int delta = updated.length() - value.length();
        replaceText(updated, Math.max(0, Math.min(cursor + delta, updated.length())));
    }

    /** The selected whole lines, or the whole script when nothing is selected. */
    static List<String> selectedLines() {
        String value = currentScriptText();
        int a = Math.min(editorSelStart, editorSelEnd), b = Math.max(editorSelStart, editorSelEnd);
        if (a == b) { a = 0; b = value.length(); }
        a = Math.max(0, Math.min(a, value.length()));
        b = Math.max(0, Math.min(b, value.length()));
        int ls = a == 0 ? 0 : value.lastIndexOf('\n', a - 1) + 1;
        int le = value.indexOf('\n', b);
        if (le < 0) le = value.length();
        List<String> lines = new ArrayList<>(List.of(value.substring(ls, le).split("\n", -1)));
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
        while (!lines.isEmpty() && lines.get(0).isBlank()) lines.remove(0);
        return lines;
    }

    /** Saves the selected lines (or the whole script) as a preset. */
    static String savePreset(String name) {
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) return "Type a name for the preset first.";
        List<String> lines = selectedLines();
        if (lines.isEmpty()) return "Nothing to save: select some lines first (or write something).";
        boolean replaced = cfg.presets.containsKey(name);
        cfg.presets.put(name, lines);
        Config.save();
        return (replaced ? "Updated" : "Saved") + " preset '" + name + "' (" + lines.size() + " line" + (lines.size() == 1 ? "" : "s") + ").";
    }

    /** Live check while typing: ScriptCheck 400 ms after the last edit (no chat messages). */
    private static void liveCheck(String text) {
        if (text.equals(liveCheckedText)) return;
        if (System.currentTimeMillis() - lastEditAt < 400) return;
        liveCheckedText = text;
        try {
            liveChecks = ScriptCheck.run(List.of(text.split("\n", -1)));
        } catch (Throwable t) {
            liveChecks = List.of();
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
        suggestKey = "";
        updateSuggestions(text, false);
        if (suggestions.isEmpty()) return text;
        acceptSuggestion(selectedSuggestion);
        return currentScriptText();
    }

    /** Puts a preset in at the cursor line (what was there moves below; a blank cursor line is replaced), or at the end. */
    public static void insertPresetByName(String name) {
        List<String> lines = cfg.presets.get(name);
        if (lines == null || lines.isEmpty()) return;
        String cur = currentScriptText();
        String block = String.join("\n", lines);
        String updated;
        int cursorAfter;
        if (lastCursor < 0 || cur.isBlank()) {
            updated = cur.isBlank() ? block : cur + "\n" + block;
            cursorAfter = updated.length();
        } else {
            int at = Math.min(lastCursor, cur.length());
            int ls = at == 0 ? 0 : cur.lastIndexOf('\n', at - 1) + 1;
            int le = cur.indexOf('\n', at);
            if (le < 0) le = cur.length();
            if (cur.substring(ls, le).isBlank()) {
                updated = cur.substring(0, ls) + block + cur.substring(le);
            } else {
                updated = cur.substring(0, ls) + block + "\n" + cur.substring(ls);
            }
            cursorAfter = ls + block.length();
        }
        replaceText(updated, cursorAfter);
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

    public static boolean isLogSelected(long id) {
        return selectedLogIds.contains(id);
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

    /** The script editor: file tabs, toolbar, code with line gutter, live problems, completion (ghost text and list). */
    public static void renderScriptEditorPanel(ProgressModel.Snapshot snap) {
        ensureTabsLoaded();
        flushConfig(false);
        float s = ImGuiManager.getUiScale();

        // file tabs (+ opens a small "new tab" popup)
        if (ImGui.beginTabBar("##ScriptFilesTabBar", imgui.flag.ImGuiTabBarFlags.FittingPolicyScroll)) {
            for (int i = 0; i < tabs.size(); i++) {
                ScriptTab t = tabs.get(i);
                if (ImGui.beginTabItem(t.name + "##tab" + i)) {
                    if (activeTabIndex != i) selectTab(i);
                    ImGui.endTabItem();
                }
            }
            if (ImGui.tabItemButton("+##AddScriptTab", imgui.flag.ImGuiTabItemFlags.Trailing)) ImGui.openPopup("##newTabPopup");
            ImGui.endTabBar();
        }
        if (ImGui.beginPopup("##newTabPopup")) {
            ImGui.setNextItemWidth(180f * s);
            ImGui.inputText("##NewTabName", newTabName);
            ImGui.sameLine();
            if (Widgets.button("Create##newTabCreate", 1)) {
                addScriptTab(newTabName.get(), "// " + newTabName.get() + "\n");
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }

        // toolbar
        if (Widgets.button("Run##EditorRun", 1)) runCurrent();
        runBtnX = ImGui.getItemRectMinX();
        runBtnY = ImGui.getItemRectMinY();
        runBtnW = ImGui.getItemRectSizeX();
        runBtnH = ImGui.getItemRectSizeY();
        ImGui.sameLine();
        if (Widgets.button("Test##EditorTest", 0)) runTest();
        testBtnX = ImGui.getItemRectMinX();
        testBtnY = ImGui.getItemRectMinY();
        testBtnW = ImGui.getItemRectSizeX();
        testBtnH = ImGui.getItemRectSizeY();
        ImGui.sameLine();
        if (Widgets.button("Pick block##EditorPick", 0)) pickBlock();
        pickBtnX = ImGui.getItemRectMinX();
        pickBtnY = ImGui.getItemRectMinY();
        pickBtnW = ImGui.getItemRectSizeX();
        pickBtnH = ImGui.getItemRectSizeY();
        if (ImGui.isItemHovered()) ImGui.setTooltip("Puts in the position of the block you look at (at the cursor).");
        ImGui.sameLine();
        if (Widgets.button("Save preset##EditorSavePreset", 0)) ImGui.openPopup("##savePresetPopup");
        if (ImGui.isItemHovered()) ImGui.setTooltip("Saves the selected lines (or the whole script) as a preset.");
        if (ImGui.beginPopup("##savePresetPopup")) {
            ImGui.setNextItemWidth(200f * s);
            ImGui.inputTextWithHint("##EditorPresetName", "Preset name", newPresetName);
            ImGui.sameLine();
            if (Widgets.button("Save##EditorPresetSave", 1)) {
                editorStatus = savePreset(newPresetName.get());
                Util.msg(editorStatus);
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }

        String text = currentScriptText();
        liveCheck(text);
        List<ScriptCheck.Entry> issues = checkResults != null ? checkResults : (text.equals(liveCheckedText) ? liveChecks : List.of());
        Map<Integer, ScriptCheck.Entry> lineIssues = new LinkedHashMap<>();
        int nErr = 0, nWarn = 0;
        for (ScriptCheck.Entry e : issues) {
            if (e.line() <= 0) continue;
            if (e.level() == ScriptCheck.Level.ERROR) nErr++;
            else if (e.level() == ScriptCheck.Level.WARN) nWarn++;
            ScriptCheck.Entry prev = lineIssues.get(e.line());
            if (prev == null || (prev.level() != ScriptCheck.Level.ERROR && e.level() == ScriptCheck.Level.ERROR)) lineIssues.put(e.line(), e);
        }
        String probs = nErr + nWarn == 0 ? "No problems" : (nErr > 0 ? nErr + " error" + (nErr == 1 ? "" : "s") : "")
                + (nErr > 0 && nWarn > 0 ? ", " : "") + (nWarn > 0 ? nWarn + " warning" + (nWarn == 1 ? "" : "s") : "");
        ImGui.sameLine(ImGui.getContentRegionMaxX() - ImGui.calcTextSizeX(probs));
        UiTheme.textColored(nErr > 0 ? UiTheme.ERR : nWarn > 0 ? UiTheme.WARN : UiTheme.OK, probs);

        // code
        float footerH = ImGui.getTextLineHeightWithSpacing() + 6f * s;
        float editorH = Math.max(80f, ImGui.getContentRegionAvailY() - footerH);
        ImGuiManager.pushMonoFont();
        ImGui.pushStyleColor(ImGuiCol.ChildBg, UiTheme.r(UiTheme.PANEL), UiTheme.g(UiTheme.PANEL), UiTheme.b(UiTheme.PANEL), 1f);
        ImGui.pushStyleColor(ImGuiCol.Border, UiTheme.r(UiTheme.LINE2), UiTheme.g(UiTheme.LINE2), UiTheme.b(UiTheme.LINE2), 1f);
        float caretX = 0, caretY = 0, lineHeight = ImGui.getTextLineHeight();
        boolean showPopup = false;
        if (ImGui.beginChild("##UnifiedCodeScroll", 0f, editorH, true, imgui.flag.ImGuiWindowFlags.HorizontalScrollbar)) {
            String src = currentScriptText();
            if (!src.equals(cachedSyntaxSource)) recomputeSyntaxAndSuggestions(src);
            String[] lines = src.split("\n", -1);
            ImDrawList dl = ImGui.getWindowDrawList();
            float startX = ImGui.getCursorScreenPosX();
            float startY = ImGui.getCursorScreenPosY();
            var style = ImGui.getStyle();
            float padX = style.getFramePaddingX();
            float padY = style.getFramePaddingY();
            float gutterW = ImGui.calcTextSizeX("0000") + 18f * s;
            float codeStartX = startX + gutterW;
            float fullW = Math.max(160f, ImGui.getContentRegionAvailX());
            float textW = 0f;
            for (String l : lines) textW = Math.max(textW, ImGui.calcTextSizeX(l));
            float availW = Math.max(fullW - gutterW, textW + 400f * s);
            float contentH = Math.max(editorH - 12f, (lines.length + 3) * lineHeight + padY * 2);

            for (int i = 0; i < lines.length; i++) {
                int ln = i + 1;
                float rowY = startY + padY + i * lineHeight;
                boolean isRunningLine = snap.running() && snap.line() == ln;
                ScriptCheck.Entry issue = lineIssues.get(ln);
                String num = String.valueOf(ln);
                float numX = startX + gutterW - 12f * s - ImGui.calcTextSizeX(num);
                if (isRunningLine) {
                    dl.addRectFilled(startX, rowY, startX + fullW + availW, rowY + lineHeight, UiTheme.u32(UiTheme.RUNLINE));
                    dl.addRectFilled(startX, rowY, startX + 3f * s, rowY + lineHeight, UiTheme.u32(UiTheme.ACCENT));
                    dl.addText(numX, rowY, UiTheme.u32(UiTheme.ACCENT), num);
                } else {
                    dl.addText(numX, rowY, UiTheme.u32(issue != null ? (issue.level() == ScriptCheck.Level.ERROR ? UiTheme.ERR : UiTheme.WARN) : UiTheme.GUTTER), num);
                }
                if (issue != null && issue.level() != ScriptCheck.Level.OK) {
                    int col = issue.level() == ScriptCheck.Level.ERROR ? UiTheme.ERR : UiTheme.WARN;
                    String line = lines[i];
                    int indent = line.length() - line.stripLeading().length();
                    float x0 = codeStartX + padX + ImGui.calcTextSizeX(line.substring(0, indent));
                    float x1 = codeStartX + padX + Math.max(ImGui.calcTextSizeX(line), ImGui.calcTextSizeX(line.substring(0, indent)) + 12f * s);
                    float uy = rowY + lineHeight - 1.5f * s;
                    int n = Math.max(2, (int) ((x1 - x0) / (3f * s)));
                    imgui.ImVec2[] pts = new imgui.ImVec2[n + 1];
                    for (int k = 0; k <= n; k++) pts[k] = new imgui.ImVec2(x0 + (x1 - x0) * k / n, uy + ((k & 1) == 0 ? -1.2f * s : 1.2f * s));
                    dl.addPolyline(pts, pts.length, UiTheme.u32(col, 0.9f), 0, 1.2f * s);
                    UiTheme.push(UiTheme.small);
                    dl.addText(x1 + 24f * s, rowY + (lineHeight - ImGui.getFontSize()) / 2f, UiTheme.u32(col, 0.85f),
                            Alerts.shorten(issue.text(), 140));
                    ImGui.popFont();
                }
            }

            ImGui.setCursorScreenPos(codeStartX, startY);
            ImGui.pushStyleColor(ImGuiCol.FrameBg, 0f, 0f, 0f, 0f);
            ImGui.pushStyleColor(ImGuiCol.Border, 0f, 0f, 0f, 0f);
            ImGui.pushStyleColor(ImGuiCol.Text, 0f, 0f, 0f, 0f);
            ImGui.pushStyleColor(ImGuiCol.TextSelectedBg, 0f, 0f, 0f, 0f);
            if (refocusEditor) {
                ImGui.setKeyboardFocusHere();
                refocusEditor = false;
            }
            if (ImGui.inputTextMultiline("##ScriptEditorInput", editorBuffer, availW, contentH,
                    ImGuiInputTextFlags.AllowTabInput | ImGuiInputTextFlags.CallbackAlways | ImGuiInputTextFlags.NoHorizontalScroll, EDITOR_CALLBACK)) {
                setCurrentScriptText(editorBuffer.get());
            }
            ImGui.popStyleColor(4);

            editorInputActive = ImGui.isItemActive();
            if (!editorInputActive) {
                editorSelStart = 0;
                editorSelEnd = 0;
                ScriptTab t = tabs.get(activeTabIndex);
                if (!t.text.equals(editorBuffer.get())) editorBuffer.set(t.text);
                suggestForced = false;
            }

            String renderSrc = editorBuffer.get();
            if (!renderSrc.equals(cachedSyntaxSource)) recomputeSyntaxAndSuggestions(renderSrc);
            else updateSuggestions(renderSrc, false);
            String[] renderLines = renderSrc.split("\n", -1);
            int selMin = Math.min(editorSelStart, editorSelEnd);
            int selMax = Math.max(editorSelStart, editorSelEnd);
            int selBgCol = UiTheme.u32(UiTheme.ACCENT, 0.28f);
            int charOffset = 0;
            for (int i = 0; i < renderLines.length; i++) {
                String line = renderLines[i];
                float lineY = startY + padY + i * lineHeight;
                if (editorInputActive && selMin < selMax && selMax >= charOffset && selMin <= charOffset + line.length()) {
                    int a = Math.max(0, selMin - charOffset);
                    int e = Math.min(line.length(), selMax - charOffset);
                    if (a <= e) {
                        float x0 = codeStartX + padX + (a == 0 ? 0f : ImGui.calcTextSizeX(line.substring(0, a)));
                        float x1 = codeStartX + padX + (e == 0 ? 0f : ImGui.calcTextSizeX(line.substring(0, e))) + (selMax > charOffset + line.length() ? 5f : 0f);
                        if (x1 > x0) dl.addRectFilled(x0, lineY, x1, lineY + lineHeight, selBgCol);
                    }
                }
                drawSyntaxLine(dl, codeStartX + padX, lineY, line, charOffset, cachedSyntaxColors);
                charOffset += line.length() + 1;
            }

            if (editorInputActive) {
                int cpos = Math.max(0, Math.min(editorCursorPos, renderSrc.length()));
                int rem = cpos, cLine = 0, cCol = 0;
                for (int i = 0; i < renderLines.length; i++) {
                    int lLen = renderLines[i].length();
                    if (rem <= lLen || i == renderLines.length - 1) {
                        cLine = i;
                        cCol = Math.min(rem, lLen);
                        break;
                    }
                    rem -= (lLen + 1);
                }
                caretX = codeStartX + padX + ImGui.calcTextSizeX(renderLines[cLine].substring(0, cCol));
                caretY = startY + padY + cLine * lineHeight;
                dl.addRectFilled(caretX, caretY, caretX + 1f, caretY + lineHeight, UiTheme.u32(UiTheme.GUTTER, 0.0f));
                if ((System.currentTimeMillis() / 500L) % 2L == 0L) {
                    dl.addRectFilled(caretX, caretY, caretX + 2f * s, caretY + lineHeight, UiTheme.u32(UiTheme.ACCENT));
                }
                String ghost = ghostSuffix();
                if (ghost != null) {
                    dl.addText(caretX + 2f * s, caretY, UiTheme.u32(UiTheme.DIM, 0.65f), ghost);
                    float gx = caretX + 2f * s + ImGui.calcTextSizeX(ghost) + 14f * s;
                    UiTheme.push(UiTheme.small);
                    String tip = "Tab";
                    float tw = ImGui.calcTextSizeX(tip) + 8f * s;
                    dl.addRect(gx, caretY + 2f, gx + tw, caretY + lineHeight - 2f, UiTheme.u32(UiTheme.LINE), 3f);
                    dl.addText(gx + 4f * s, caretY + (lineHeight - ImGui.getFontSize()) / 2f, UiTheme.u32(UiTheme.DIM), tip);
                    ImGui.popFont();
                }
                showPopup = popupVisible();
            }
        }
        ImGui.endChild();
        ImGui.popStyleColor(2);
        ImGuiManager.popMonoFont();

        if (showPopup) renderCompletionPopup(caretX, caretY + lineHeight + 2f, s);

        // footer: the selected completion's description, or the check summary / status
        Suggest.Item top = suggestions.isEmpty() ? null : suggestions.get(Math.max(0, Math.min(selectedSuggestion, suggestions.size() - 1)));
        if (editorInputActive && top != null && (showPopup || ghostSuffix() != null) && !top.desc().isEmpty()) {
            UiTheme.textColored(UiTheme.ACCENT, top.text());
            ImGui.sameLine(0f, 8f * s);
            UiTheme.textColored(UiTheme.DIM, Alerts.shorten(top.desc(), 110));
        } else if (checkResults != null && !checkResults.isEmpty()) {
            ScriptCheck.Entry summary = checkResults.get(checkResults.size() - 1);
            int c = summary.level() == ScriptCheck.Level.ERROR ? UiTheme.ERR : summary.level() == ScriptCheck.Level.WARN ? UiTheme.WARN : UiTheme.OK;
            UiTheme.textColored(c, summary.text());
        } else if (!editorStatus.isEmpty()) {
            UiTheme.textColored(UiTheme.DIM, editorStatus);
        } else {
            UiTheme.textColored(UiTheme.DIM, "Tab completes · Ctrl+Space shows all · " + Platform.shortcutName() + "+/ comments · " + Platform.shortcutName() + "+P commands");
        }
    }

    private static void renderCompletionPopup(float x, float y, float s) {
        int rows = Math.min(8, suggestions.size());
        float lh = ImGui.getTextLineHeightWithSpacing();
        float w = 0f;
        for (int i = 0; i < Math.min(suggestions.size(), 40); i++) w = Math.max(w, ImGui.calcTextSizeX(suggestions.get(i).text()));
        w = Math.min(Math.max(w + 40f * s, 260f * s), 560f * s);
        ImGui.setNextWindowPos(x, y);
        ImGui.setNextWindowSize(w, rows * lh + 14f * s);
        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.WindowPadding, 6f * s, 6f * s);
        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.WindowBorderSize, 1f);
        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.WindowRounding, 6f * s);
        ImGui.pushStyleColor(ImGuiCol.WindowBg, UiTheme.r(UiTheme.PANEL2), UiTheme.g(UiTheme.PANEL2), UiTheme.b(UiTheme.PANEL2), 1f);
        int flags = imgui.flag.ImGuiWindowFlags.NoTitleBar | imgui.flag.ImGuiWindowFlags.NoResize | imgui.flag.ImGuiWindowFlags.NoMove
                | imgui.flag.ImGuiWindowFlags.NoSavedSettings | imgui.flag.ImGuiWindowFlags.NoFocusOnAppearing | imgui.flag.ImGuiWindowFlags.NoNav;
        boolean vis = ImGui.begin("##complPopup", flags);
        ImGui.popStyleColor();
        ImGui.popStyleVar(3);
        popupRect[0] = x;
        popupRect[1] = y;
        popupRect[2] = w;
        popupRect[3] = rows * lh + 14f * s;
        if (vis) {
            ImGuiManager.pushMonoFont();
            popupFirstRowY = ImGui.getCursorScreenPosY();
            popupRowH = ImGui.getTextLineHeightWithSpacing();
            for (int i = 0; i < suggestions.size(); i++) {
                boolean sel = i == selectedSuggestion;
                if (sel && popupNavigated) ImGui.setScrollHereY();
                ImGui.selectable(suggestions.get(i).text() + "##sug" + i, sel);
            }
            ImGuiManager.popMonoFont();
        }
        ImGui.end();
        popupShownFrame = ImGuiManager.framesRendered();
    }

    private static final float[] popupRect = new float[4];
    private static float popupFirstRowY, popupRowH = 1f;
    private static long popupShownFrame = -10;

    /** A click on the completion list (handled here so the editor keeps the keyboard): accepts that completion. */
    static boolean handlePopupClick(double mx, double my) {
        if (ImGuiManager.framesRendered() - popupShownFrame > 1 || suggestions.isEmpty()) return false;
        if (mx < popupRect[0] || mx > popupRect[0] + popupRect[2] || my < popupRect[1] || my > popupRect[1] + popupRect[3]) return false;
        int i = (int) Math.floor((my - popupFirstRowY) / Math.max(1f, popupRowH));
        if (i < 0 || i >= suggestions.size()) return true;
        selectedSuggestion = i;
        acceptSuggestion(i);
        return true;
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
        if (ImGui.button("Save selected lines##SavePresetBtn")) {
            Util.msg(savePreset(newPresetName.get()));
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("The lines selected in the editor, or the whole script when nothing is selected.");
        UiTheme.textColored(UiTheme.DIM, "Insert puts a preset in at the editor's cursor line.");

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
        Widgets.wrapped(UiTheme.FG, "Current #sel: " + AreasView.currentSelectionSummary());
        ImGui.setNextItemWidth(120f);
        ImGui.inputTextWithHint("##NewAreaName", "Area name...", newAreaName);
        ImGui.sameLine(0f, 6f);
        if (ImGui.button("Save #sel as area##SaveAreaBtn")) {
            String err = AreasView.saveCurrent(newAreaName.get());
            if (err != null) Util.msg(err);
        }

        ImGui.separator();
        ImGui.textColored(0.290f, 0.620f, 0.878f, 1.0f, "Saved Areas:");
        if (!lastAreasNotice.isEmpty()) {
            UiTheme.textColored(UiTheme.OK, lastAreasNotice);
        }
        List<AreasView.AreaEntry> areas = AreasView.list();
        if (areas.isEmpty()) {
            ImGui.textDisabled("No saved areas.");
        } else {
            for (int i = 0; i < areas.size(); i++) {
                AreasView.AreaEntry a = areas.get(i);
                if (!a.isLast()) {
                    ImBoolean vis = new ImBoolean(WorldOverlay.isAreaVisible(a.name()));
                    if (ImGui.checkbox("##visarea_" + i, vis)) {
                        WorldOverlay.setAreaVisible(a.name(), vis.get());
                    }
                    if (ImGui.isItemHovered()) ImGui.setTooltip("Show/hide in 3D World Overlay");
                    ImGui.sameLine(0f, 4f);
                }
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
                ImGui.sameLine(0f, 4f);
                if (ImGui.smallButton("+ Queue##qarea_" + i)) {
                    ProjectTask task = ProjectTask.forAreaRole(a.name(), a.role());
                    GameProjectRuntime.instance().enqueueTask(task);
                    lastAreasNotice = "Queued " + task.displayLabel();
                    Util.msg(lastAreasNotice);
                }
                ImGui.sameLine(0f, 6f);
                String tagPrefix = a.isLast() ? "" : a.tag() + " ";
                String roleSuffix = a.role().equalsIgnoreCase("custom") ? "" : " [" + a.role() + "]";
                Widgets.wrapped(UiTheme.FG, tagPrefix + a.name() + roleSuffix + ": " + a.summary());
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
                WorldOverlay.Category cat = "bed".equalsIgnoreCase(sp.kind()) ? WorldOverlay.Category.BED : WorldOverlay.Category.CHEST;
                ImBoolean vis = new ImBoolean(WorldOverlay.isPointVisible(cat, sp.x(), sp.y(), sp.z()));
                if (ImGui.checkbox("##visspot_" + i, vis)) {
                    WorldOverlay.setPointVisible(cat, sp.x(), sp.y(), sp.z(), vis.get());
                }
                if (ImGui.isItemHovered()) ImGui.setTooltip("Show/hide in 3D World Overlay");
                ImGui.sameLine(0f, 4f);
                if (ImGui.smallButton("Go##gospot_" + i)) {
                    ChestIndexView.goThere(sp.x(), sp.y(), sp.z());
                }
                ImGui.sameLine(0f, 4f);
                if (ImGui.smallButton("Copy##cpyspot_" + i)) {
                    ChestIndexView.copyPos(sp.x(), sp.y(), sp.z());
                }
                ImGui.sameLine(0f, 6f);
                Widgets.wrapped(UiTheme.FG, sp.name() + " [" + sp.kind() + "] at " + sp.x() + " " + sp.y() + " " + sp.z());
            }
        }

        ImGui.separator();
        ImGui.textColored(0.290f, 0.620f, 0.878f, 1.0f, "Litematica Placements:");
        List<PlacesView.SchematicEntry> schems = PlacesView.schematics();
        if (schems.isEmpty()) {
            ImGui.textDisabled("No Litematica placements in this world.");
        } else {
            for (PlacesView.SchematicEntry sc : schems) {
                Widgets.wrapped(UiTheme.FG, sc.index() + ". " + sc.name() + " at " + sc.x() + " " + sc.y() + " " + sc.z()
                        + " (" + sc.regions() + " region" + (sc.regions() == 1 ? "" : "s") + (sc.enabled() ? "" : ", disabled") + ")");
            }
        }
    }

    /** Capture: roles (chest, bed, area, schematic ...), Fetch looked-at, Aim in world, rapid mode, the sheet and its tools. */
    public static void renderCapturePanel() {
        float s = ImGuiManager.getUiScale();
        UiTheme.textColored(UiTheme.DIM, "Set '" + cfg.captureSet + "' in " + Capture.world());
        ImGui.sameLine();
        ImGui.setNextItemWidth(110f * s);
        ImGui.inputTextWithHint("##CaptureSetInput", "set name", newCaptureSetBuf);
        ImGui.sameLine();
        if (ImGui.button("Open set##CapOpenSet")) {
            if (!newCaptureSetBuf.get().isBlank()) lastCaptureNotice = Capture.openSet(newCaptureSetBuf.get().trim());
        }

        // role
        Capture.Type curType = Capture.type();
        ImGui.setNextItemWidth(170f * s);
        if (ImGui.beginCombo("##CapRole", "Role: " + curType.label)) {
            for (Capture.Type ty : Capture.Type.values()) {
                if (ImGui.selectable(ty.label + "##role_" + ty.id, ty == curType)) {
                    cfg.captureType = ty.id;
                    Config.save();
                    Capture.corner1 = null;   // a new role starts with corner 1 again
                }
                if (!ty.hint.isEmpty() && ImGui.isItemHovered()) ImGui.setTooltip(ty.hint);
            }
            ImGui.endCombo();
        }
        ImGui.sameLine();
        boolean named = curType == Capture.Type.SPOT || curType == Capture.Type.AREA;
        String nameHint = named ? "name" : curType.kind == Capture.Kind.AREA ? "name (same = more boxes)" : "name (optional)";
        ImGui.setNextItemWidth(150f * s);
        if (!captureNameBuf.get().equals(Capture.name) && !ImGui.isAnyItemActive()) captureNameBuf.set(Capture.name);
        if (ImGui.inputTextWithHint("##CaptureVarName", nameHint, captureNameBuf)) Capture.name = captureNameBuf.get();
        if (curType.kind == Capture.Kind.SCHEMATIC) {
            ImGui.sameLine();
            ImGui.setNextItemWidth(Math.max(120f * s, ImGui.getContentRegionAvailX()));
            if (ImGui.beginCombo("##CapPlacement", Capture.schematic == null ? "Litematica placement..." : Capture.schematic)) {
                List<String> files = Capture.placements();
                if (files.isEmpty()) ImGui.textDisabled("Litematica has no placements (load a schematic in Litematica first).");
                for (int i = 0; i < files.size(); i++) {
                    if (ImGui.selectable(files.get(i) + "##pl" + i, files.get(i).equals(Capture.schematic))) Capture.schematic = files.get(i);
                }
                ImGui.endCombo();
            }
        } else if (!curType.hint.isEmpty()) {
            ImGui.sameLine();
            UiTheme.textColored(UiTheme.DIM, curType.hint);
        }

        String looked = "Looking at: " + PlacesView.lookedAtSummary();
        if (curType.kind == Capture.Kind.AREA) {
            looked += Capture.corner1 == null ? "   |   next: corner 1" : "   |   corner 1 at " + Capture.corner1 + ", next: corner 2";
        }
        UiTheme.textColored(UiTheme.DIM, looked);

        // actions
        if (Widgets.button("Fetch looked-at##CapFetch", 1)) {
            Capture.name = captureNameBuf.get();
            lastCaptureNotice = PlacesView.fetchLookedAt();
        }
        ImGui.sameLine();
        if (ImGui.button("Aim in world (J)##CapAim")) {
            Capture.name = captureNameBuf.get();
            lastCaptureNotice = PlacesView.startAimingInWorld();
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Closes the workspace: look at blocks and press J to capture; K comes back.");
        ImGui.sameLine();
        imgui.type.ImBoolean rapid = new imgui.type.ImBoolean(cfg.captureRapid);
        if (ImGui.checkbox("Rapid##CapRapid", rapid)) {
            cfg.captureRapid = rapid.get();
            Config.save();
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Aim in world keeps capturing with every J press until K.");
        if (curType.kind == Capture.Kind.AREA) {
            ImGui.sameLine();
            if (ImGui.button("Use #sel##CapUseSel")) {
                Capture.name = captureNameBuf.get();
                lastCaptureNotice = Capture.useSelection();
                Util.msg(lastCaptureNotice);
            }
        }
        if (!lastCaptureNotice.isEmpty()) UiTheme.textColored(UiTheme.OK, Alerts.shorten(lastCaptureNotice, 160));

        // the sheet (re-read from its file about once a second, so outside edits show up)
        String sheet = Capture.text();
        if (!sheet.equals(lastSyncedCaptureSheet) && (captureSheetBuf.get().equals(lastSyncedCaptureSheet == null ? "" : lastSyncedCaptureSheet)
                || !ImGui.isAnyItemActive())) {
            captureSheetBuf.set(sheet);
            lastSyncedCaptureSheet = sheet;
        }
        float bottomH = ImGui.getFrameHeightWithSpacing() * 2 + 4f * s;
        float availH = Math.max(60f, ImGui.getContentRegionAvailY() - bottomH);
        ImGuiManager.pushMonoFont();
        if (ImGui.inputTextMultiline("##CaptureSheetEdit", captureSheetBuf, -1f, availH)) {
            lastSyncedCaptureSheet = captureSheetBuf.get();
            Capture.setText(lastSyncedCaptureSheet);
        }
        ImGuiManager.popMonoFont();

        if (ImGui.button("Import saved##CapImport")) {
            lastCaptureNotice = Capture.importSaved();
            Util.msg(lastCaptureNotice);
        }
        ImGui.sameLine();
        if (ImGui.button("Copy sheet##CapCopy")) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.keyboardHandler != null) mc.keyboardHandler.setClipboard(Capture.text());
            lastCaptureNotice = "Copied the sheet. It is also in " + Capture.file();
        }
        ImGui.sameLine();
        if (ImGui.button("Copy full context##CapContext")) {
            String ctx = Capture.fullContext();
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.keyboardHandler != null) mc.keyboardHandler.setClipboard(ctx);
            lastCaptureNotice = "Copied " + ctx.split("\n").length + " lines for the LLM; also in " + Capture.contextFile();
            Util.msg(lastCaptureNotice);
        }
        ImGui.sameLine();
        imgui.type.ImBoolean show = new imgui.type.ImBoolean(cfg.captureShow);
        if (ImGui.checkbox("Show in world##CapShow", show)) {
            cfg.captureShow = show.get();
            Config.save();
        }
        List<String> stale = Capture.stale();
        if (stale.isEmpty()) UiTheme.textColored(UiTheme.DIM, Alerts.shorten("Sheet: " + Capture.file(), 120));
        else UiTheme.textColored(UiTheme.WARN, Alerts.shorten("Check: " + String.join("; ", stale), 160));
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
            if (ImGui.isWindowFocused() && ImGui.getIO().getKeyCtrl() && ImGui.isKeyPressed(ImGuiKey.C) && !selectedLogIds.isEmpty()) {
                copySelectedLogs();
            }
        }
        ImGui.endChild();
    }
}
