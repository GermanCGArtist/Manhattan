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

    /** Capture sheets (the capture menu, key K): folder ("" = <game folder>/manhattan-context), the open set, options. */
    String captureDir = "";
    String captureSet = "main";
    boolean captureShow = true;
    boolean captureRapid = false;
    String captureType = "dump";

    /** Which default presets were already offered; newer ones are added once on load (deleted ones stay deleted). */
    int presetsVersion = 1;

    /** 0/1 = scripts of the old one-command-per-line kind; 2 = the script language. Old ones are converted on load. */
    int scriptVersion = 0;

    /** The helpers' automatic triggers were switched off once (2026-11-07: the user wants the script to call them). */
    boolean helpersManual = false;

    /** The script as it was before the conversion to the script language (kept, just in case). */
    List<String> scriptBeforeV2 = null;

    /** Saved snippets: name -> lines. Inserted into the script from the editor. */
    java.util.Map<String, List<String>> presets = defaultPresets();

    private static java.util.Map<String, List<String>> defaultPresets() {
        java.util.Map<String, List<String>> p = new java.util.LinkedHashMap<>();
        p.put("Water job: fill, then mine (chests)", new ArrayList<>(List.of(
                "// Fill the water of area 1 with dirt from a chest, then mine areas 2 and 3 into another chest.",
                "// Save the areas first: select them (#sel pos1 / pos2), then /queue area save 1  (and 2, 3).",
                "// Chests: put the cursor on an empty line and press Pick block, then aim at the chest and press J.",
                "let dirt_chest = pos(100, 64, -20)",
                "let dump_chest = pos(104, 64, -20)",
                "",
                "// 1. fill area 1, every column from the bottom up; fetch more dirt while there is water left",
                "area(\"1\")",
                "loop",
                "  take_upto(dirt_chest, \"dirt\", 512)",
                "  let left = fill_water(\"dirt\")",
                "  if left == 0 or has(\"dirt\") then break end   // done, or the rest cannot be reached",
                "end",
                "put(dirt_chest, \"dirt\")",
                "",
                "// 2. mine areas 2 and 3 from the top, layer by layer; a full inventory goes into the dump chest",
                "on full",
                "  dump(dump_chest)",
                "end",
                "#buildInLayers true",
                "#layerOrder true",
                "for name in [\"2\", \"3\"]",
                "  area(name)",
                "  #sel ca",
                "  dump(dump_chest)",
                "end")));
        p.put("Fill water from dry ground", new ArrayList<>(List.of(
                "// Select the area first (area(\"name\") or #sel pos1 / pos2). Never swims; every column from the bottom up.",
                "// Seagrass and kelp count as water. Sand or gravel sink to the bottom by themselves: fill_water(\"sand\").",
                "let left = fill_water(\"dirt\")",
                "print(\"Water left:\", left)")));
        p.put("Clear trees", new ArrayList<>(List.of(
                "// Logs, then leaves (select the area first)",
                "#sel replace oak_log birch_log spruce_log jungle_log acacia_log dark_oak_log mangrove_log cherry_log pale_oak_log air",
                "#sel replace oak_leaves birch_leaves spruce_leaves jungle_leaves acacia_leaves dark_oak_leaves mangrove_leaves cherry_leaves pale_oak_leaves azalea_leaves flowering_azalea_leaves air",
                "collect_scaffold()   // take down what Baritone placed to reach the tops")));
        p.put("Mine the entire area (layers)", new ArrayList<>(List.of(
                "// Select the area first. Layer by layer, top to bottom.",
                "#buildInLayers true",
                "#layerOrder true",
                "#sel ca")));
        p.put("Remove water (layers)", new ArrayList<>(List.of(
                "// Select the area first. Bottom up, so the water has nowhere to flow back from.",
                "#buildInLayers true",
                "#layerOrder false",
                "#sel replace water air")));
        p.put("Survival helpers", new ArrayList<>(List.of(
                "// Switch the helpers on (they stay on). option_list: options()",
                "option(\"survival.eat\", true)",
                "option(\"survival.sleep\", true)",
                "option(\"survival.defence\", true)",
                "option(\"survival.respawn\", true)",
                "option(\"survival.resume\", true)",
                "option(\"survival.reconnect\", true)",
                "// option(\"survival.bed\", pos(...))      Pick block on the bed fills in the position",
                "// option(\"survival.supply\", pos(...))   the chest restock() takes from",
                "// want(\"food\", 32)                      what restock() keeps with you")));
        p.put("Keep fed and sleep at night", new ArrayList<>(List.of(
                "on food < 8",
                "  eat()",
                "end",
                "on night",
                "  sleep()",
                "end")));
        p.put("Empty into a chest when full", new ArrayList<>(List.of(
                "let dump_chest = pos(100, 64, -20)",
                "on full",
                "  dump(dump_chest)",
                "end")));
        p.put("Several areas in a row", new ArrayList<>(List.of(
                "for name in [\"a\", \"b\", \"c\"]",
                "  area(name)",
                "  #sel ca",
                "end")));
        p.put("Mine diamonds (until 64)", new ArrayList<>(List.of(
                "while count(\"diamond\") < 64",
                "  #mine 8 diamond_ore deepslate_diamond_ore",
                "end")));
        p.put("Mine all common ores", new ArrayList<>(List.of(
                "#mine coal_ore deepslate_coal_ore iron_ore deepslate_iron_ore copper_ore deepslate_copper_ore gold_ore deepslate_gold_ore redstone_ore deepslate_redstone_ore lapis_ore deepslate_lapis_ore diamond_ore deepslate_diamond_ore emerald_ore deepslate_emerald_ore")));
        p.put("Gather wood", new ArrayList<>(List.of(
                "#mine 64 oak_log birch_log spruce_log jungle_log acacia_log dark_oak_log mangrove_log cherry_log pale_oak_log")));
        p.put("Farm crops", new ArrayList<>(List.of(
                "// Harvests and replants around you; runs until Skip or Stop",
                "#farm")));
        p.put("Strip mine (100 blocks)", new ArrayList<>(List.of(
                "// A tunnel 2 high, 1 wide, 100 long in the direction you look",
                "#tunnel 2 1 100")));
        p.put("Timed mining session (6 x 20 min)", new ArrayList<>(List.of(
                "timeout(1200)   // every action ends after 20 minutes",
                "repeat 6",
                "  #mine diamond_ore deepslate_diamond_ore",
                "end",
                "timeout(null)")));
        p.put("Go home", new ArrayList<>(List.of(
                "let home = pos(0, 64, 0)   // Pick block fills it in",
                "goto(home)")));
        p.put("Wait for the morning", new ArrayList<>(List.of(
                "wait until day")));
        p.put("Retry until it works", new ArrayList<>(List.of(
                "let tries = 0",
                "loop",
                "  try",
                "    take(chest(\"dirt\"), \"dirt\", 64)",
                "    break",
                "  catch err",
                "    tries += 1",
                "    print(\"Failed ({tries}): {err}\")",
                "    if tries >= 3 then stop(\"Giving up.\") end",
                "    wait 60",
                "  end",
                "end")));
        return p;
    }

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
                    if (cfg.presets == null) cfg.presets = defaultPresets();
                    if (cfg.chests == null) cfg.chests = new java.util.LinkedHashMap<>();
                    if (cfg.areas == null) cfg.areas = new java.util.LinkedHashMap<>();
                    if (cfg.captureDir == null) cfg.captureDir = "";
                    if (cfg.captureSet == null || cfg.captureSet.isBlank()) cfg.captureSet = "main";
                    if (cfg.captureType == null) cfg.captureType = "dump";
                    if (cfg.lastArea == null) cfg.lastArea = new ArrayList<>();
                }
                if (cfg.scriptVersion < 2) migrate();
            } else {
                cfg.scriptVersion = 2;
                cfg.presetsVersion = 6;
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
        defaultPresets().forEach(converted::put);
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
        cfg.presetsVersion = 6;
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
