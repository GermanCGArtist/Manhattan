package dev.baritonequeue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Persisted settings (config/baritonequeue.json). */
final class Config {
    /** The commands as typed, one per line. */
    List<String> script = new ArrayList<>();

    /** A step that makes no progress for this many minutes is stopped and skipped (0 = never). */
    int stallMinutes = 30;

    /** Named chests for the chest lines of a script: name -> {x, y, z}. */
    java.util.Map<String, int[]> chests = new java.util.LinkedHashMap<>();

    /** Saved areas: name -> boxes {x1,y1,z1,x2,y2,z2}; lastArea is the latest selection, remembered by itself. */
    java.util.Map<String, List<int[]>> areas = new java.util.LinkedHashMap<>();
    List<int[]> lastArea = new ArrayList<>();

    /** Which default presets were already offered; newer ones are added once on load (deleted ones stay deleted). */
    int presetsVersion = 1;

    /** 0/1 = scripts of the old one-command-per-line kind; 2 = the script language. Old ones are converted on load. */
    int scriptVersion = 0;

    /** The helpers' automatic triggers were switched off once (2026-11-07: the user wants the script to call them). */
    boolean helpersManual = false;

    /** The script as it was before the conversion to the script language (kept, just in case). */
    List<String> scriptBeforeV2 = null;

    /** Saved snippets: name -> lines. Inserted into the script from the editor. */
    java.util.Map<String, List<String>> presets = Presets.defaults();


    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static Config cfg = new Config();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("baritonequeue.json");
    }

    static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                try (Reader r = Files.newBufferedReader(p)) {
                    Config c = GSON.fromJson(r, Config.class);
                    if (c != null) cfg = c;
                    if (cfg.script == null) cfg.script = new ArrayList<>();
                    if (cfg.presets == null) cfg.presets = Presets.defaults();
                    if (cfg.chests == null) cfg.chests = new java.util.LinkedHashMap<>();
                    if (cfg.areas == null) cfg.areas = new java.util.LinkedHashMap<>();
                    if (cfg.lastArea == null) cfg.lastArea = new ArrayList<>();
                }
                if (cfg.scriptVersion < 2) migrate();
                if (cfg.presetsVersion < Presets.VERSION) {   // new default presets: untouched old ones are replaced
                    cfg.presets = Presets.upgrade(cfg.presets);
                    cfg.presetsVersion = Presets.VERSION;
                    save();
                }
            } else {
                cfg.scriptVersion = 2;
                cfg.presetsVersion = Presets.VERSION;
                save();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Old scripts and presets into the script language; the new default presets replace the old ones. */
    private static void migrate() {
        if (!cfg.script.isEmpty()) {
            cfg.scriptBeforeV2 = new ArrayList<>(cfg.script);
            cfg.script = new ArrayList<>(Migrate.convert(cfg.script));
            cfg.script.add(0, "// Converted from the old kind of script. The old one is kept in config/baritonequeue.json (scriptBeforeV2).");
        }
        java.util.Map<String, List<String>> converted = new java.util.LinkedHashMap<>();
        Presets.defaults().forEach(converted::put);
        java.util.Set<String> oldDefaults = java.util.Set.of("Clear trees", "Replace water and lava", "Mine area", "Profile: AFK mining",
                "Profile: building", "Wait for night, then sleep", "Mine diamonds", "Mine iron and coal", "Mine all common ores",
                "Gather wood", "Farm crops", "Explore", "Strip mine (100 blocks)", "Follow me", "Save home / go home",
                "Go to coordinates", "Clear area, then fill with stone", "Timed mining session (6 x 20 min)", "Pause between jobs",
                "Mine all trees, then take down the scaffolding", "Mine the entire area (layers)", "Fill water with dirt, then mine (chests)",
                "Fill water from dry ground (terminator)", "Remove water (layers)");
        cfg.presets.forEach((k, v) -> {
            if (!oldDefaults.contains(k) && !converted.containsKey(k)) converted.put(k, new ArrayList<>(Migrate.convert(v)));
        });
        cfg.presets = converted;
        cfg.presetsVersion = Presets.VERSION;
        cfg.scriptVersion = 2;
        save();
    }

    static void save() {
        try (Writer w = Files.newBufferedWriter(path())) {
            GSON.toJson(cfg, w);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
