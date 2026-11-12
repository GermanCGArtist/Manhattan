package dev.baritonequeue;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static dev.baritonequeue.Config.cfg;

/**
 * The script editor, coloured like a code editor. While you type, completions show at the cursor (Tab or a click
 * takes one; Tab with nothing shown asks for them, like chat). Where a position fits, the block you were looking at
 * when the editor opened is offered, like /setblock does; Pick block closes the editor so you can aim at any block.
 * Ctrl+/ comments lines in and out; Test checks the script without running it; presets can be saved and inserted.
 * Does not pause a singleplayer world.
 */
final class QueueScreen extends Screen {
    private static final int GLFW_KEY_TAB = 258;
    private static final int KEY_ENTER = 257, KEY_KP_ENTER = 335, KEY_ESC = 256, KEY_UP = 265, KEY_DOWN = 264;
    private static final int KEY_PGUP = 266, KEY_PGDN = 267, KEY_SPACE = 32;
    private static final int MAX_ROWS = 10;
    private static final int GLFW_KEY_SLASH = 47;
    private static final int PANEL_H = 58;
    private static final int GUTTER = 28;
    private static final int INNER = 4;   // the editor's inner padding

    // kept when the screen is rebuilt (after the preset list)
    private static int pendingCursor = -1;
    private static int rememberedCursorLine = 0;

    private MultiLineEditBox box;
    private MultilineTextField field;          // the editor's text and cursor (reached by reflection: it is private)
    private EditBox presetName;
    private int left, width0, boxY, boxH, panelY;

    private List<Suggest.Item> suggestions = List.of();
    private int suggestFrom;                       // where the word the suggestions replace starts
    private static Places.Target pendingPick;      // a block picked with Pick block, to insert when the editor is back
    private static boolean pickReturn;             // the editor reopens after Pick block (insert, or nothing when cancelled)
    private static int pickCursor = -1;
    private int sel, scroll;                       // the highlighted suggestion and the first one shown
    private boolean navigated, dismissed, forced;  // arrows used / Esc pressed / Ctrl+Space asked for the full list
    private int popX, popY, popW, popRows;         // where the list was drawn last (for clicks and the wheel)
    private String lastKey = "";
    private List<ScriptCheck.Entry> results = null;   // shown instead of suggestions after Test, until the next edit
    private String notice = "";
    private long noticeUntil;

    private String colorText = null;
    private int[] colors = new int[0];

    QueueScreen() {
        super(Component.literal("Manhattan script"));
        if (!pickReturn) Suggest.target = Places.target();   // like /setblock: what the crosshair was on
    }

    /** Called when the player pressed the key during Pick block (t is null when looking at nothing). */
    static void pickResult(Places.Target t) {
        pendingPick = t;
        pickReturn = true;
        if (t != null) Suggest.target = t;
    }

    @Override
    protected void init() {
        width0 = Math.min(width - 24, 460);
        left = (width - width0) / 2;
        boxY = 40 + ProgressView.HEIGHT + 6;
        boxH = Math.max(60, height - boxY - PANEL_H - 4 - 16 - 26 - 26 - 6);
        panelY = boxY + boxH + 4;

        // The editor draws its own text almost invisibly (alpha 4: 0 would count as opaque); the coloured copy is
        // painted over it in extractRenderState. Typing, cursor and selection all stay the editor's own.
        box = MultiLineEditBox.builder()
                .setX(left + GUTTER)
                .setY(boxY)
                .setTextColor(0x04FFFFFF)
                .setTextShadow(false)
                .setPlaceholder(Component.literal("For example:\nlet dirt = pos(100, 64, -20)\narea(\"pond\")\ntake_upto(dirt, \"dirt\", 512)\nfill_water(\"dirt\")\nput(dirt, \"dirt\")\n#sel ca"))
                .build(font, width0 - GUTTER, boxH, Component.literal("Commands"));
        box.setCharacterLimit(40000);
        box.setValue(String.join("\n", cfg.script));
        box.setValueListener(text -> {
            store(text);
            results = null;
        });
        addRenderableWidget(box);
        setFocused(box);
        field = textFieldOf(box);
        if (field != null && pendingCursor >= 0) {
            field.seekCursor(Whence.ABSOLUTE, Math.min(pendingCursor, field.value().length()));
        }
        pendingCursor = -1;
        if (pickReturn) {
            pickReturn = false;
            if (pendingPick != null && field != null) insertPick(pendingPick);
            pendingPick = null;
        }

        // preset row
        int py = panelY + PANEL_H + 16;
        presetName = new EditBox(font, left, py, width0 - 3 * 84 - 12, 20, Component.literal("Preset name"));
        presetName.setHint(Component.literal("preset name"));
        presetName.setMaxLength(40);
        addRenderableWidget(presetName);
        addRenderableWidget(Button.builder(Component.literal("Capture..."), b -> {
            store(box.getValue());
            Config.save();
            minecraft.setScreen(new CaptureScreen());
        }).bounds(left + width0 - 3 * 84 - 8, py, 84, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save preset"), b -> savePreset())
                .bounds(left + width0 - 2 * 84 - 4, py, 84, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Presets..."), b -> {
            if (field != null) rememberedCursorLine = lineOf(field.value(), Math.min(field.cursor(), field.value().length()));
            store(box.getValue());
            Config.save();
            minecraft.setScreen(new PresetScreen(this));
        }).bounds(left + width0 - 84, py, 84, 20).build());

        // main buttons
        int by = py + 26;
        int gap = 4;
        int bw = (width0 - 5 * gap) / 6;
        addRenderableWidget(Button.builder(Component.literal("Run"), b -> {
            store(box.getValue());
            Config.save();
            String err = QueueMod.runner.start(cfg.script);
            if (err != null) { say(err); Util.msg(err); }
        }).bounds(left, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Test"), b -> test())
                .bounds(left + (bw + gap), by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Stop"), b -> RunnerView.stop())
                .bounds(left + 2 * (bw + gap), by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Skip"), b -> RunnerView.skip())
                .bounds(left + 3 * (bw + gap), by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Pick block"), b -> {
            // close the editor; aiming at a block and pressing the key brings it back with the position put in
            store(box.getValue());
            Config.save();
            pickCursor = field == null ? -1 : Math.min(field.cursor(), field.value().length());
            QueueMod.picking = true;
            minecraft.setScreen(null);
        }).bounds(left + 4 * (bw + gap), by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(left + 5 * (bw + gap), by, bw, 20).build());
    }

    private static MultilineTextField textFieldOf(MultiLineEditBox box) {
        try {
            var f = MultiLineEditBox.class.getDeclaredField("textField");
            f.setAccessible(true);
            return (MultilineTextField) f.get(box);
        } catch (Throwable t) {
            t.printStackTrace();
            return null;   // no completion/presets from the cursor then; the editor itself still works
        }
    }

    private void store(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
        cfg.script = lines;
    }

    private static java.lang.reflect.Method viewBegin, viewEnd;

    /** {begin, end} of a text range of the editor (its StringView type is not accessible, so by reflection). */
    private static int[] range(Object view) {
        try {
            if (viewBegin == null) {
                Class<?> c = Class.forName("net.minecraft.client.gui.components.MultilineTextField$StringView");
                viewBegin = c.getDeclaredMethod("beginIndex");
                viewEnd = c.getDeclaredMethod("endIndex");
                viewBegin.setAccessible(true);
                viewEnd.setAccessible(true);
            }
            return new int[]{(int) viewBegin.invoke(view), (int) viewEnd.invoke(view)};
        } catch (Throwable t) {
            return new int[]{0, 0};
        }
    }

    private static int lineOf(String value, int index) {
        int n = 0;
        for (int i = 0; i < index && i < value.length(); i++) if (value.charAt(i) == '\n') n++;
        return n;
    }

    private void say(String text) {
        notice = text;
        noticeUntil = System.currentTimeMillis() + 5000;
    }

    // ------------------------------------------------------------------ test

    private void test() {
        store(box.getValue());
        Config.save();
        results = ScriptCheck.run(cfg.script);
        for (ScriptCheck.Entry e : results) {
            if (e.level() != ScriptCheck.Level.OK) Util.msg((e.line() > 0 ? "Line " + e.line() + ": " : "") + e.text());
        }
        if (results.size() == 1) Util.msg(results.get(0).text());
    }

    // ------------------------------------------------------------------ presets

    /** The selected lines (whole lines), or the whole script when nothing is selected. */
    private List<String> selectedLines() {
        String value = box.getValue();
        int a = 0, b = value.length();
        if (field != null && field.hasSelection()) {
            int[] r = range(field.getSelected());
            a = r[0];
            b = r[1];
        }
        int ls = value.lastIndexOf('\n', Math.max(0, a - 1));
        ls = (a == 0) ? 0 : ls + 1;
        int le = value.indexOf('\n', b);
        if (le < 0) le = value.length();
        List<String> lines = new ArrayList<>(List.of(value.substring(ls, le).split("\n", -1)));
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
        while (!lines.isEmpty() && lines.get(0).isBlank()) lines.remove(0);
        return lines;
    }

    private void savePreset() {
        String name = presetName.getValue().trim();
        if (name.isEmpty()) { say("Type a name for the preset first."); return; }
        List<String> lines = selectedLines();
        if (lines.isEmpty()) { say("Nothing to save: select some lines first (or write something)."); return; }
        boolean replaced = cfg.presets.containsKey(name);
        cfg.presets.put(name, lines);
        Config.save();
        say((replaced ? "Updated" : "Saved") + " preset '" + name + "' (" + lines.size() + " line" + (lines.size() == 1 ? "" : "s") + ").");
    }

    /** Called by the preset list: puts the preset in at the cursor line; what was there moves below it. */
    void insertPreset(List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        List<String> script = new ArrayList<>(cfg.script);
        int at = Math.min(rememberedCursorLine, script.size());
        if (at < script.size() && script.get(at).isBlank()) script.remove(at);   // a blank cursor line is replaced
        script.addAll(at, lines);
        cfg.script = script;
        Config.save();
        int index = 0;   // cursor goes to the start of the line after the inserted block
        for (int i = 0; i < at + lines.size() && i < script.size(); i++) index += script.get(i).length() + 1;
        pendingCursor = index;
    }

    // ------------------------------------------------------------------ completion and comments

    @Override
    public void tick() {
        super.tick();
        if (field == null) return;
        String value = field.value();
        int cursor = Math.min(field.cursor(), value.length());
        String key = cursor + ":" + value;
        if (key.equals(lastKey)) return;
        lastKey = key;
        Suggest.Result r = Suggest.complete(value, cursor, forced);
        suggestions = r.items();
        suggestFrom = r.from();
        forced = false;
        sel = 0;
        scroll = 0;
        navigated = false;
        dismissed = false;
    }

    private boolean popupOpen() {
        return results == null && !dismissed && !suggestions.isEmpty();
    }

    private void move(int delta) {
        int n = suggestions.size();
        sel = ((sel + delta) % n + n) % n;
        navigated = true;
        if (sel < scroll) scroll = sel;
        if (sel >= scroll + MAX_ROWS) scroll = sel - MAX_ROWS + 1;
    }

    private void accept(String suggestion) {
        if (field == null) return;
        String value = field.value();
        int cursor = Math.min(field.cursor(), value.length());
        int start = Math.max(0, Math.min(suggestFrom, cursor));
        String insert = suggestion;
        // a word in a # line, a keyword: a space after it; a function call "name(": the cursor stays inside
        String before = value.substring(0, start);
        String line = before.substring(before.lastIndexOf('\n') + 1).stripLeading();
        if (line.startsWith("#") || Lexer.KEYWORDS.contains(suggestion)) insert += " ";
        box.setValue(value.substring(0, start) + insert + value.substring(cursor));
        field.seekCursor(Whence.ABSOLUTE, start + insert.length());
        lastKey = "";   // recompute for the new position
        setFocused(box);
    }

    /** Puts a picked block in: on an empty line as "let chest = pos(...)", else pos(...) at the cursor. */
    private void insertPick(Places.Target t) {
        String value = field.value();
        int at = pickCursor < 0 ? value.length() : Math.min(pickCursor, value.length());
        pickCursor = -1;
        int ls = at == 0 ? 0 : value.lastIndexOf('\n', at - 1) + 1;
        int le = value.indexOf('\n', at);
        if (le < 0) le = value.length();
        String line = value.substring(ls, le);
        Values.Pos p = t.pos();
        String pos = "pos(" + p.x() + ", " + p.y() + ", " + p.z() + ")";
        String insert;
        if (line.isBlank()) {
            String name = Places.varName(t.name());
            java.util.Set<String> taken = Suggest.definedNames(value);
            String unique = name;
            for (int i = 2; taken.contains(unique) || Builtins.all().containsKey(unique); i++) unique = name + i;
            insert = "let " + unique + " = " + pos + "   // " + t.name();
            box.setValue(value.substring(0, ls) + insert + value.substring(le));
            field.seekCursor(Whence.ABSOLUTE, ls + insert.length());
        } else {
            String before = value.substring(ls, at);
            boolean bare = before.stripLeading().startsWith("#") || before.stripLeading().startsWith("/");
            insert = bare ? p.x() + " " + p.y() + " " + p.z() : before.endsWith("pos(") ? p.x() + ", " + p.y() + ", " + p.z() + ")" : pos;
            box.setValue(value.substring(0, at) + insert + value.substring(at));
            field.seekCursor(Whence.ABSOLUTE, at + insert.length());
        }
        say("Put in the " + t.name() + " at " + p + ".");
        lastKey = "";
    }

    /** Ctrl+/ : puts "// " in front of the selected lines (or the cursor line), or takes it away again. */
    private void toggleComment() {
        if (field == null) return;
        String value = field.value();
        int cursor = Math.min(field.cursor(), value.length());
        int a = cursor, b = cursor;
        if (field.hasSelection()) {
            int[] r = range(field.getSelected());
            a = r[0];
            b = r[1];
        }
        int ls = a == 0 ? 0 : value.lastIndexOf('\n', a - 1) + 1;
        int le = value.indexOf('\n', b);
        if (le < 0) le = value.length();
        String[] lines = value.substring(ls, le).split("\n", -1);
        boolean allCommented = true;
        for (String l : lines) {
            if (!l.isBlank() && !l.stripLeading().startsWith("//")) allCommented = false;
        }
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
        box.setValue(updated);
        field.seekCursor(Whence.ABSOLUTE, Math.max(0, Math.min(cursor + delta, updated.length())));
        lastKey = "";
        setFocused(box);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (getFocused() == box) {
            if (event.key() == GLFW_KEY_SLASH && event.hasControlDown()) {
                toggleComment();
                return true;
            }
            if (event.key() == KEY_SPACE && event.hasControlDown()) {   // like chat: show the choices on demand
                forced = true;
                lastKey = "";
                return true;
            }
            if (event.key() == GLFW_KEY_TAB && !popupOpen() && results == null) {
                forced = true;
                lastKey = "";
                tick();
                if (suggestions.size() == 1) accept(suggestions.get(0).text());   // only one choice: take it, like chat
                return true;
            }
            if (popupOpen()) {
                int k = event.key();
                if (k == KEY_DOWN) { move(1); return true; }
                if (k == KEY_UP) { move(-1); return true; }
                if (k == KEY_PGDN) { move(MAX_ROWS - 1); return true; }
                if (k == KEY_PGUP) { move(-(MAX_ROWS - 1)); return true; }
                if (k == GLFW_KEY_TAB || ((k == KEY_ENTER || k == KEY_KP_ENTER) && navigated)) {
                    accept(suggestions.get(sel).text());
                    return true;
                }
                if (k == KEY_ESC) { dismissed = true; return true; }
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (popupOpen() && mx >= popX && mx < popX + popW && my >= popY && my < popY + popRows * 12 + 4) {
            move(scrollY > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (popupOpen() && event.x() >= popX && event.x() < popX + popW && event.y() >= popY + 2 && event.y() < popY + 2 + popRows * 12) {
            accept(suggestions.get(scroll + (int) ((event.y() - popY - 2) / 12)).text());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClose() {
        if (box != null) store(box.getValue());
        Config.save();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
        g.centeredText(font, "#sel ca = Baritone command, /cmd = chat command, let x = 5, if/while/for/repeat ... end, // comment",
                width / 2, 24, 0xFF9A9A9A);

        ProgressView.draw(g, font, left, 38, width0);
        drawCode(g);

        g.fill(left, panelY, left + width0, panelY + PANEL_H, 0x80000000);
        if (results != null) {
            drawResults(g);
        } else if (popupOpen()) {
            Suggest.Item it = suggestions.get(sel);
            g.text(font, font.plainSubstrByWidth(it.text(), width0 - 8), left + 4, panelY + 4, 0xFFFFFFFF);
            if (!it.desc().isEmpty()) g.text(font, font.plainSubstrByWidth(it.desc(), width0 - 8), left + 4, panelY + 16, 0xFFB0B0B0);
            g.text(font, "Up/Down choose, Tab" + (navigated ? " or Enter" : "") + " accept, Esc closes the list   (" + (sel + 1) + "/" + suggestions.size() + ")",
                    left + 4, panelY + PANEL_H - 13, 0xFF707070);
        } else {
            g.text(font, "Choices appear while you type; Tab asks for them (Ctrl+Space: all). Where a position fits,",
                    left + 4, panelY + 4, 0xFF707070);
            String looked = Suggest.target == null ? "nothing (look at a block, or use Pick block)" : "the " + Suggest.target.name() + " at " + Suggest.target.pos();
            g.text(font, font.plainSubstrByWidth("Tab offers what you looked at: " + looked + ".", width0 - 8),
                    left + 4, panelY + 16, 0xFF707070);
            g.text(font, "take(chest, \"dirt\", 64)  fill_water(\"dirt\")  dump(chest)  sleep()  on food < 8 ... end",
                    left + 4, panelY + 28, 0xFF5A7A9A);
        }
        boolean showNotice = System.currentTimeMillis() < noticeUntil;
        String status = showNotice ? notice : QueueMod.runner.status();
        g.text(font, font.plainSubstrByWidth(status, width0), left, panelY + PANEL_H + 4, showNotice ? 0xFF60E060 : 0xFFE0E0E0);
        if (popupOpen()) drawPopup(g, mouseX, mouseY);
    }

    /** The choices as a list at the cursor, like the chat's: highlighted row, description next to the name. */
    private void drawPopup(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (field == null) return;
        String value = field.value();
        int cursor = Math.min(field.cursor(), value.length());
        int lineH = font.lineHeight;
        int row = 0, cx = box.getX() + INNER;
        for (Object view : field.iterateLines()) {
            int[] vr = range(view);
            if (cursor >= vr[0] && cursor <= vr[1]) {
                cx += font.width(value.substring(Math.min(vr[0], value.length()), cursor));
                break;
            }
            row++;
        }
        int cy = box.getY() + INNER - (int) box.scrollAmount() + row * lineH;

        int rows = Math.min(MAX_ROWS, suggestions.size());
        int nameW = 0, descW = 0;
        for (Suggest.Item it : suggestions) {
            nameW = Math.max(nameW, font.width(it.text()));
            descW = Math.max(descW, font.width(it.desc()));
        }
        descW = Math.min(descW, 240);
        int w = Math.min(width - 8, nameW + (descW > 0 ? 14 + descW : 0) + 10);
        int h = rows * 12 + 4;
        int from = Math.max(0, Math.min(suggestFrom, cursor));
        int x = Math.max(4, Math.min(cx - font.width(value.substring(from, cursor)), width - w - 4));
        int y = cy + lineH + 2;
        if (y + h > height - 4) y = cy - h - 2;   // no room below: above the line

        popX = x;
        popY = y;
        popW = w;
        popRows = rows;
        g.fill(x, y, x + w, y + h, 0xF0101010);
        for (int i = 0; i < rows; i++) {
            int idx = scroll + i;
            if (idx >= suggestions.size()) break;
            Suggest.Item it = suggestions.get(idx);
            int ry = y + 2 + i * 12;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + 12;
            if (idx == sel) g.fill(x + 1, ry, x + w - 1, ry + 12, 0xFF2F5F8F);
            else if (hover) g.fill(x + 1, ry, x + w - 1, ry + 12, 0xFF303030);
            g.text(font, it.text(), x + 5, ry + 2, idx == sel ? 0xFFFFFFFF : 0xFFE0E0E0, false);
            if (!it.desc().isEmpty()) {
                g.text(font, font.plainSubstrByWidth(it.desc(), descW), x + 9 + nameW, ry + 2, 0xFF909090, false);
            }
        }
        if (suggestions.size() > rows) {   // a thin scroll marker
            int th = Math.max(6, h * rows / suggestions.size());
            int ty = y + (h - th) * scroll / Math.max(1, suggestions.size() - rows);
            g.fill(x + w - 2, ty, x + w - 1, ty + th, 0xFF808080);
        }
    }

    /** The coloured text and the line numbers, painted over the editor's own (invisible) text. */
    private void drawCode(GuiGraphicsExtractor g) {
        if (field == null) return;
        String value = field.value();
        if (!value.equals(colorText)) {
            colorText = value;
            colors = Syntax.colors(value);
        }
        int x0 = box.getX() + INNER;
        int y0 = box.getY() + INNER - (int) box.scrollAmount();
        int lineH = font.lineHeight;
        int top = box.getY() + 1, bottom = box.getY() + boxH - 1;

        g.enableScissor(box.getX(), top, box.getX() + box.getWidth(), bottom);
        int row = 0, logical = 0;
        for (Object view : field.iterateLines()) {
            int[] vr = range(view);
            int y = y0 + row * lineH;
            int begin = Math.min(vr[0], value.length());
            int end = Math.min(vr[1], value.length());
            if (y + lineH >= top && y <= bottom) {
                int x = x0;
                int runStart = begin;
                for (int i = begin; i <= end; i++) {
                    if (i == end || colors[i] != colors[runStart]) {
                        if (i > runStart) {
                            String run = value.substring(runStart, i);
                            g.text(font, run, x, y, colors[runStart], false);
                            x += font.width(run);
                        }
                        runStart = i;
                    }
                }
            }
            if (vr[0] == 0 || (vr[0] > 0 && value.charAt(vr[0] - 1) == '\n')) {
                logical++;
                if (y + lineH >= top && y <= bottom) {
                    String n = String.valueOf(logical);
                    boolean now = QueueMod.runner.running() && QueueMod.runner.line() == logical;   // blue = running now
                    int state = now ? 2 : 0;
                    int color = now ? 0xFF4A9EE0 : 0xFF6E7681;
                    if (state == 2) g.fill(left, y - 1, left + 2, y + lineH, color);
                    g.text(font, n, left + GUTTER - 6 - font.width(n), y, color, false);
                }
            }
            row++;
        }
        if (value.isEmpty()) logical = 1;
        g.disableScissor();
    }

    private void drawResults(GuiGraphicsExtractor g) {
        int y = panelY + 4;
        int shown = 0, rows = 0;
        // the summary (last entry) always shows; problems before it, long ones wrapped onto a second line
        for (int i = 0; i < results.size() - 1 && rows < 4; i++, shown++) {
            ScriptCheck.Entry e = results.get(i);
            int color = e.level() == ScriptCheck.Level.ERROR ? 0xFFFF6060 : 0xFFFFD060;
            String text = "Line " + e.line() + ": " + e.text();
            for (int part = 0; part < 2 && !text.isEmpty() && rows < 4; part++, rows++) {
                String cut = font.plainSubstrByWidth(text, width0 - 8);
                if (cut.length() < text.length()) {   // break at the last space that fits
                    int sp = cut.lastIndexOf(' ');
                    if (sp > 10) cut = cut.substring(0, sp);
                }
                g.text(font, cut, left + 4, y, color);
                text = text.substring(cut.length()).stripLeading();
                y += 11;
            }
        }
        int more = results.size() - 1 - shown;
        ScriptCheck.Entry sum = results.get(results.size() - 1);
        int color = sum.level() == ScriptCheck.Level.ERROR ? 0xFFFF6060 : sum.level() == ScriptCheck.Level.WARN ? 0xFFFFD060 : 0xFF60E060;
        g.text(font, font.plainSubstrByWidth(sum.text() + (more > 0 ? "  (+" + more + " more in chat)" : ""), width0 - 8),
                left + 4, panelY + PANEL_H - 13, color);
    }
}
