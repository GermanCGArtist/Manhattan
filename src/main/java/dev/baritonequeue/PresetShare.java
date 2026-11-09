package dev.baritonequeue;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.baritonequeue.Config.cfg;

/**
 * Presets as plain text, to send to someone or keep: a "## name" line, then the lines of the preset.
 * Copy puts it on the clipboard, Paste adds what is on the clipboard, and Export/Import use
 * config/baritonequeue-presets.txt.
 */
final class PresetShare {
    private PresetShare() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("baritonequeue-presets.txt");
    }

    static String toText(Map<String, List<String>> presets) {
        StringBuilder sb = new StringBuilder();
        presets.forEach((name, lines) -> {
            sb.append("## ").append(name).append('\n');
            for (String l : lines) sb.append(l).append('\n');
            sb.append('\n');
        });
        return sb.toString();
    }

    static Map<String, List<String>> fromText(String text) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        String name = null;
        List<String> lines = new ArrayList<>();
        for (String raw : text.replace("\r", "").split("\n", -1)) {
            if (raw.startsWith("## ")) {
                flush(out, name, lines);
                name = raw.substring(3).trim();
                lines = new ArrayList<>();
            } else {
                lines.add(raw);
            }
        }
        if (name == null) name = "Pasted preset";   // plain script lines without a header
        flush(out, name, lines);
        return out;
    }

    private static void flush(Map<String, List<String>> out, String name, List<String> lines) {
        if (name == null) return;
        List<String> l = new ArrayList<>(lines);
        while (!l.isEmpty() && l.get(l.size() - 1).isBlank()) l.remove(l.size() - 1);
        while (!l.isEmpty() && l.get(0).isBlank()) l.remove(0);
        if (!l.isEmpty() && !name.isEmpty()) out.put(name, l);
    }

    /** Adds the presets (a name that exists gets " (2)", " (3)", ...). Returns how many were added. */
    static int add(Map<String, List<String>> in) {
        int n = 0;
        for (var e : in.entrySet()) {
            String name = e.getKey();
            for (int i = 2; cfg.presets.containsKey(name); i++) name = e.getKey() + " (" + i + ")";
            cfg.presets.put(name, new ArrayList<>(e.getValue()));
            n++;
        }
        if (n > 0) Config.save();
        return n;
    }

    static void copy(Map<String, List<String>> presets) {
        Minecraft.getInstance().keyboardHandler.setClipboard(toText(presets));
        Util.msg(presets.size() + " preset" + (presets.size() == 1 ? "" : "s") + " copied. Paste them anywhere, or into the editor of someone else with Paste.");
    }

    static void paste() {
        String text = Minecraft.getInstance().keyboardHandler.getClipboard();
        int n = text == null || text.isBlank() ? 0 : add(fromText(text));
        Util.msg(n == 0 ? "The clipboard has no preset text." : n + " preset" + (n == 1 ? "" : "s") + " added from the clipboard.");
    }

    static void export() {
        try {
            Files.writeString(file(), toText(cfg.presets));
            Util.msg("Presets saved to " + file() + ". Send that file to someone, who puts it in the same folder and presses Import.");
        } catch (Exception e) {
            Util.msg("Could not write the file: " + e.getMessage());
        }
    }

    static void importFile() {
        try {
            if (!Files.exists(file())) { Util.msg("No file " + file().getFileName() + " in the config folder."); return; }
            int n = add(fromText(Files.readString(file())));
            Util.msg(n + " preset" + (n == 1 ? "" : "s") + " added from " + file().getFileName() + ".");
        } catch (Exception e) {
            Util.msg("Could not read the file: " + e.getMessage());
        }
    }
}
