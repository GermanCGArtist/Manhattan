package dev.baritonequeue;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static dev.baritonequeue.Config.cfg;

/**
 * The capture menu (key K): choose a role, then take the block you are looking at (Fetch looked-at), or aim in the
 * world and press J (Aim in world; with Rapid on every press adds a line until K brings the menu back). Areas take
 * two corners, or the current #sel selection. The text below is the sheet: edit it, Copy it, or Copy full context
 * for an LLM. It is kept as a file per world and set.
 */
final class CaptureScreen extends Screen {
    private static final int ROW = 12;
    private static final int MAX_ROWS = 14;

    private MultiLineEditBox box;
    private EditBox nameBox, setBox;
    private Button typeButton, schemButton, rapidButton, showButton;
    private Places.Target target;
    private int left, width0, boxY, boxH;

    private String notice = "";
    private long noticeUntil;
    private boolean noticeBad;

    // a drop-down list: which one is open, its rows, where it is drawn
    private List<String> listItems = null;
    private java.util.function.Consumer<Integer> listPick;
    private int listX, listY, listW, listScroll;

    CaptureScreen() {
        super(Component.literal("Capture"));
        target = Places.target();
    }

    @Override
    protected void init() {
        width0 = Math.min(width - 24, 480);
        left = (width - width0) / 2;
        int gap = 4;
        int y = 22;

        // row 1: role, name, schematic
        int tw = 150;
        typeButton = addRenderableWidget(Button.builder(Component.literal(""), b -> openList(b, typeLabels(), i -> {
            cfg.captureType = Capture.Type.values()[i].id;
            Config.save();
            Capture.corner1 = null;
            refresh();
        })).bounds(left, y, tw, 20).build());
        nameBox = new EditBox(font, left + tw + gap, y + 1, 120, 18, Component.literal("Name"));
        nameBox.setMaxLength(40);
        nameBox.setValue(Capture.name);
        nameBox.setResponder(v -> Capture.name = v);
        addRenderableWidget(nameBox);
        schemButton = addRenderableWidget(Button.builder(Component.literal(""), b -> {
            List<String> files = Capture.schematics();
            if (files.isEmpty()) { say("No schematics in " + Capture.schematicsDir() + ".", true); return; }
            openList(b, files, i -> { Capture.schematic = files.get(i); refresh(); });
        }).bounds(left + tw + 120 + 2 * gap, y, width0 - tw - 120 - 2 * gap, 20).build());

        // row 2: fetch
        y += 24;
        int bw = (width0 - 3 * gap) / 4;
        addRenderableWidget(Button.builder(Component.literal("Fetch looked-at"), b -> {
            String r = Capture.capture(target);
            say(r, r.startsWith("Look") || r.startsWith("Pick"));
            syncBox();
        }).bounds(left, y, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Aim in world (J)"), b -> {
            saveBox();
            Capture.aiming = true;
            Capture.captured = 0;
            minecraft.setScreen(null);
        }).bounds(left + bw + gap, y, bw, 20).build());
        rapidButton = addRenderableWidget(Button.builder(Component.literal(""), b -> {
            cfg.captureRapid = !cfg.captureRapid;
            Config.save();
            refresh();
        }).bounds(left + 2 * (bw + gap), y, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Use selection"), b -> {
            String r = Capture.useSelection();
            say(r, !r.startsWith("Captured"));
            syncBox();
        }).bounds(left + 3 * (bw + gap), y, bw, 20).build());

        // the sheet
        boxY = y + 24 + ROW;
        boxH = Math.max(60, height - boxY - 24 - 24 - 20);
        box = MultiLineEditBox.builder()
                .setX(left)
                .setY(boxY)
                .setPlaceholder(Component.literal("Nothing captured yet. Choose a role above, look at a chest and press Fetch looked-at."))
                .build(font, width0, boxH, Component.literal("Sheet"));
        box.setCharacterLimit(60000);
        syncBox();
        addRenderableWidget(box);

        // row 3: sheet tools
        int y3 = boxY + boxH + 4;
        addRenderableWidget(Button.builder(Component.literal("Import saved"), b -> {
            saveBox();
            say(Capture.importSaved(), false);
            syncBox();
        }).bounds(left, y3, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Copy"), b -> {
            saveBox();
            minecraft.keyboardHandler.setClipboard(Capture.text());
            say("Copied the sheet. It is also in " + Capture.file(), false);
        }).bounds(left + bw + gap, y3, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Copy full context"), b -> {
            saveBox();
            String ctx = Capture.fullContext();
            minecraft.keyboardHandler.setClipboard(ctx);
            say("Copied " + ctx.split("\n").length + " lines for the LLM; also in " + Capture.contextFile(), false);
        }).bounds(left + 2 * (bw + gap), y3, bw, 20).build());
        showButton = addRenderableWidget(Button.builder(Component.literal(""), b -> {
            cfg.captureShow = !cfg.captureShow;
            Config.save();
            refresh();
        }).bounds(left + 3 * (bw + gap), y3, bw, 20).build());

        // row 4: sets, editor, done
        int y4 = y3 + 24;
        setBox = new EditBox(font, left + 1, y4 + 1, bw - 2, 18, Component.literal("Set"));
        setBox.setMaxLength(30);
        setBox.setValue(cfg.captureSet);
        addRenderableWidget(setBox);
        addRenderableWidget(Button.builder(Component.literal("Open set"), b -> {
            saveBox();
            String r = Capture.openSet(setBox.getValue());
            say(r, !r.startsWith("Set"));
            setBox.setValue(cfg.captureSet);
            syncBox();
        }).bounds(left + bw + gap, y4, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Script editor"), b -> {
            saveBox();
            minecraft.setScreen(new QueueScreen());
        }).bounds(left + 2 * (bw + gap), y4, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(left + 3 * (bw + gap), y4, bw, 20).build());

        refresh();
    }

    private static List<String> typeLabels() {
        List<String> out = new ArrayList<>();
        for (Capture.Type t : Capture.Type.values()) out.add(t.label);
        return out;
    }

    private void refresh() {
        Capture.Type t = Capture.type();
        typeButton.setMessage(Component.literal("Role: " + t.label + " ▾"));
        boolean named = t == Capture.Type.SPOT || t == Capture.Type.AREA;
        nameBox.setHint(Component.literal(named ? "name" : t.kind == Capture.Kind.AREA ? "name (same = more boxes)" : "name (optional)"));
        schemButton.visible = t.kind == Capture.Kind.SCHEMATIC;
        schemButton.setMessage(Component.literal(Capture.schematic == null ? "Schematic file... ▾" : Capture.schematic + " ▾"));
        rapidButton.setMessage(Component.literal("Rapid: " + (cfg.captureRapid ? "on" : "off")));
        showButton.setMessage(Component.literal("Show in world: " + (cfg.captureShow ? "on" : "off")));
    }

    private String synced = "";   // the sheet as last put into the box

    private void saveBox() {
        if (box == null) return;
        Capture.setText(box.getValue());
        synced = box.getValue();
    }

    private void syncBox() {
        synced = Capture.text();
        box.setValue(synced);
    }

    private void say(String text, boolean bad) {
        notice = text;
        noticeBad = bad;
        noticeUntil = System.currentTimeMillis() + 6000;
    }

    private int tickCount;

    @Override
    public void tick() {
        super.tick();
        if (++tickCount % 20 != 0) return;
        if (box.getValue().equals(synced) && !Capture.text().equals(synced)) syncBox();   // the file was changed outside (an LLM?)
        else saveBox();   // the file stays current while you edit
    }

    // ------------------------------------------------------------------ drop-down

    private void openList(Button b, List<String> items, java.util.function.Consumer<Integer> pick) {
        listItems = items;
        listPick = pick;
        listScroll = 0;
        listX = b.getX();
        listY = b.getY() + 20;
        int w = b.getWidth();
        for (String s : items) w = Math.max(w, font.width(s) + 12);
        listW = Math.min(w, width - listX - 4);
    }

    private int listRows() {
        return Math.min(MAX_ROWS, listItems.size());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (listItems != null) {
            double mx = event.x(), my = event.y();
            if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listRows() * ROW + 4) {
                int i = listScroll + (int) ((my - listY - 2) / ROW);
                if (i >= 0 && i < listItems.size()) listPick.accept(i);
            }
            listItems = null;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (listItems != null) {
            listScroll = Math.max(0, Math.min(listItems.size() - listRows(), listScroll + (scrollY > 0 ? -1 : 1)));
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        saveBox();
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
        g.centeredText(font, "Capture: places, areas and schematics for the script (set '" + cfg.captureSet + "')", width / 2, 8, 0xFFFFFFFF);

        Capture.Type t = Capture.type();
        if (!schemButton.visible) {
            String hint = t.hint.isEmpty() ? (t.kind == Capture.Kind.AREA ? "two corners, or Use selection" : "a place under your own name") : t.hint;
            g.text(font, font.plainSubstrByWidth(hint, width0 - 150 - 120 - 12), left + 150 + 120 + 10, 28, 0xFF909090);
        }
        String looked = target == null ? "Looking at: nothing (look at a block before opening the menu, or use Aim in world)"
                : "Looking at: " + target.name() + " at " + target.pos();
        if (t.kind == Capture.Kind.AREA) {
            looked += Capture.corner1 == null ? "   |   next: corner 1" : "   |   corner 1 at " + Capture.corner1 + ", next: corner 2";
        }
        g.text(font, font.plainSubstrByWidth(looked, width0), left, 72, 0xFFB0B0B0);

        boolean showNotice = System.currentTimeMillis() < noticeUntil;
        String status;
        int color;
        if (showNotice) {
            status = notice;
            color = noticeBad ? 0xFFFF7070 : 0xFF60E060;
        } else {
            List<String> stale = Capture.stale();
            status = stale.isEmpty() ? "Sheet: " + Capture.file() : "Check: " + String.join("; ", stale);
            color = stale.isEmpty() ? 0xFF707070 : 0xFFFFD060;
        }
        g.text(font, font.plainSubstrByWidth(status, width0), left, height - 14, color);

        if (listItems != null) {
            int rows = listRows();
            int h = rows * ROW + 4;
            g.fill(listX, listY, listX + listW, listY + h, 0xF0101010);
            for (int i = 0; i < rows; i++) {
                int idx = listScroll + i;
                if (idx >= listItems.size()) break;
                int ry = listY + 2 + i * ROW;
                boolean hover = mouseX >= listX && mouseX < listX + listW && mouseY >= ry && mouseY < ry + ROW;
                if (hover) g.fill(listX + 1, ry, listX + listW - 1, ry + ROW, 0xFF2F5F8F);
                g.text(font, listItems.get(idx), listX + 5, ry + 2, 0xFFE0E0E0, false);
            }
        }
    }
}
