package dev.baritonequeue;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static dev.baritonequeue.Config.cfg;

/** The saved presets: Insert puts one into the script at the cursor of the editor, X deletes it. */
final class PresetScreen extends Screen {
    private static final int ROW = 24;
    private static final int TOP = 40;

    private final QueueScreen parent;
    private int scroll;
    private int perPage = 1;
    private final List<AbstractWidget> rowWidgets = new ArrayList<>();
    private final List<String> shown = new ArrayList<>();
    private int left, rowWidth;

    PresetScreen(QueueScreen parent) {
        super(Component.literal("Presets"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rowWidth = Math.min(width - 24, 380);
        left = (width - rowWidth) / 2;
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> minecraft.setScreen(parent))
                .bounds(width / 2 - 100, height - 26, 200, 20).build());
        int bw = (rowWidth - 12) / 4, by = height - 50;
        addRenderableWidget(Button.builder(Component.literal("Copy all"), b -> PresetShare.copy(cfg.presets))
                .bounds(left, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Paste"), b -> { PresetShare.paste(); layoutRows(); })
                .bounds(left + (bw + 4), by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Export file"), b -> PresetShare.export())
                .bounds(left + 2 * (bw + 4), by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Import file"), b -> { PresetShare.importFile(); layoutRows(); })
                .bounds(left + 3 * (bw + 4), by, bw, 20).build());
        layoutRows();
    }

    private void layoutRows() {
        for (AbstractWidget w : rowWidgets) removeWidget(w);
        rowWidgets.clear();
        shown.clear();

        List<String> names = new ArrayList<>(cfg.presets.keySet());
        perPage = Math.max(1, (height - TOP - 70) / ROW);
        scroll = Math.max(0, Math.min(scroll, names.size() - perPage));
        for (int i = scroll; i < Math.min(names.size(), scroll + perPage); i++) {
            String name = names.get(i);
            int y = TOP + (i - scroll) * ROW;
            shown.add(name);
            AbstractWidget insert = addRenderableWidget(Button.builder(Component.literal("Insert"), b -> {
                parent.insertPreset(cfg.presets.get(name));
                minecraft.setScreen(parent);
            }).bounds(left + rowWidth - 104, y, 80, 20).build());
            AbstractWidget copy = addRenderableWidget(Button.builder(Component.literal("Copy"), b ->
                    PresetShare.copy(java.util.Map.of(name, cfg.presets.get(name)))).bounds(left + rowWidth - 164, y, 56, 20).build());
            rowWidgets.add(copy);
            AbstractWidget delete = addRenderableWidget(Button.builder(Component.literal("X").withStyle(ChatFormatting.RED), b -> {
                cfg.presets.remove(name);
                Config.save();
                layoutRows();
            }).bounds(left + rowWidth - 20, y, 20, 20).build());
            rowWidgets.add(insert);
            rowWidgets.add(delete);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll -= (int) Math.signum(scrollY);
        layoutRows();
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
        g.centeredText(font, "Insert puts the preset into your script at the cursor line. "
                + "Save your own from the editor: select lines, type a name, Save preset.", width / 2, 24, 0xFF9A9A9A);
        if (cfg.presets.isEmpty()) {
            g.centeredText(font, "No presets yet.", width / 2, TOP + 10, 0xFF9A9A9A);
        }
        for (int i = 0; i < shown.size(); i++) {
            String name = shown.get(i);
            List<String> lines = cfg.presets.get(name);
            if (lines == null) continue;
            int y = TOP + i * ROW;
            String label = name + "  (" + lines.size() + " line" + (lines.size() == 1 ? "" : "s") + ")";
            g.text(font, font.plainSubstrByWidth(label, rowWidth - 172), left, y + 6, 0xFFE0E0E0);
            if (mouseY >= y && mouseY < y + 20 && mouseX >= left && mouseX < left + rowWidth - 108) {
                List<Component> tip = new ArrayList<>();
                for (int k = 0; k < Math.min(lines.size(), 8); k++) tip.add(Component.literal(lines.get(k)));
                if (lines.size() > 8) tip.add(Component.literal("... +" + (lines.size() - 8) + " more"));
                g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
            }
        }
    }
}
