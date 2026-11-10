package dev.baritonequeue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The presets that come with the mod. VERSION goes up when they change: on load, a user's copy of an old default that
 * was never edited is replaced by the new one (or removed when it is gone), new ones are added, and presets the user
 * edited or made stay as they are. Every preset is parsed at build time (ParseFile), so none can ship broken.
 */
final class Presets {
    private Presets() {}

    static final int VERSION = 7;

    /** The current default presets (2.5.0). */
    static Map<String, List<String>> defaults() {
        Map<String, List<String>> p = new LinkedHashMap<>();
        p.put("Canal: dig, plug the water, build, fill with water", new ArrayList<>(List.of(
                "// A canal in five steps. Save the areas first: select each (#sel pos1 / pos2, several boxes with #sel pos1 again),",
                "// then /queue area save trench, /queue area save water_old, /queue area save canal (the water part, up to the water line).",
                "// Place the schematics in Litematica (enabled). Positions: cursor on the pos(...), Pick block, aim at the chest, press J.",
                "let storage = pos(0, 64, 0)       // dug blocks go here; dirt for scaffolding and plugs comes from here",
                "let tools = pos(2, 64, 0)         // pickaxes, shovels, 16 buckets + at least 2 WATER buckets (one source never refills)",
                "let bed = pos(4, 64, 0)",
                "let supply = pos(6, 64, 0)        // a chest or shulker box with the schematic's blocks",
                "let scaffold = \"dirt\"",
                "",
                "option(\"survival.bed\", bed)",
                "option(\"survival.supply\", tools)",
                "want(\"_pickaxe\", 2)",
                "want(\"_shovel\", 2)",
                "want(\"bucket\", 16)",
                "want(\"food\", 32)",
                "keep(scaffold, 256)",
                "enable(\"eat\", \"sleep\", \"defence\", \"restock\", \"escape\")",
                "on full do dump(storage) end",
                "on restock_needed do restock() end",
                "#acceptableThrowawayItems {scaffold}",
                "#allowInventory true",
                "restock()",
                "",
                "print(\"1/5: digging\")",
                "area(\"trench\")",
                "if clear_area(2) > 0           // top down, 2 layers per pass",
                "  #sel ca",
                "end",
                "collect_scaffold()",
                "",
                "print(\"2/5: plugging the old water\")",
                "area(\"water_old\")",
                "fill_water(scaffold, storage)",
                "",
                "print(\"3/5: digging the water part\")",
                "area(\"canal\")",
                "if clear_area(2) > 0",
                "  #sel ca",
                "end",
                "collect_scaffold()",
                "dump(storage)",
                "",
                "print(\"4/5: building the schematics\")",
                "let need = schem_needed(\"all\", \"add\")",
                "for item in keys(need)",
                "  try",
                "    take_upto(supply, item, need[item])",
                "  catch err",
                "    print(\"No {item} in the supply chest: those blocks are left out\")",
                "  end",
                "end",
                "take_upto(storage, scaffold, 128)",
                "schem_build(\"all\", \"add\", \"skip\")",
                "collect_scaffold()",
                "schem_check(\"all\", \"add\")",
                "",
                "print(\"5/5: filling with water\")",
                "dump(storage)",
                "take_upto(storage, scaffold, 256)    // for sealing open ends",
                "restock()",
                "try",
                "  take_upto(tools, \"water_bucket\", 2)",
                "catch err",
                "  print(\"No water buckets in the tools chest: flood() needs water within 16 blocks then\")",
                "end",
                "area(\"canal\")",
                "flood()",
                "print(\"Canal done.\")")));
        p.put("Build a Litematica schematic (materials from chests)", new ArrayList<>(List.of(
                "// Place the schematic in Litematica first; /schem list shows the placement numbers.",
                "// mode: \"add\" fills only empty spots, \"fix\" also replaces wrong blocks, \"exact\" also digs out (into a hill).",
                "let placement = 1",
                "let mode = \"add\"",
                "let scaffold_chest = pos(0, 64, 0)   // Pick block: the chest with the scaffold blocks",
                "let scaffold = \"dirt\"",
                "let chests = [pos(2, 64, 0), pos(4, 64, 0)]   // chests, barrels or shulker boxes with the materials",
                "",
                "#acceptableThrowawayItems {scaffold}",
                "take_upto(scaffold_chest, scaffold, 128)",
                "let missing = []",
                "let left = -1",
                "let now_left = 0",
                "loop",
                "  let need = schem_needed(placement, mode)",
                "  for item in keys(need)",
                "    if contains(missing, item) then continue end",
                "    for c in chests",
                "      if count(item) >= need[item] or full then break end",
                "      try",
                "        take_upto(c, item, need[item])",
                "      catch err",
                "      end",
                "    end",
                "    if count(item) == 0 and not full then push(missing, item) end",
                "  end",
                "  now_left = schem_build(placement, mode, \"skip\")   // \"strict\": stop as soon as an item is missing",
                "  if now_left == 0 or now_left == left then break end   // done, or nothing more can be built",
                "  left = now_left",
                "end",
                "collect_scaffold()   // the blocks Baritone placed to reach things",
                "schem_check(placement, mode)",
                "if len(missing) > 0 then print(\"Not in the chests: {join(missing, ', ')}\") end")));
        p.put("Dig an area (top down, 2 layers per pass)", new ArrayList<>(List.of(
                "// Select the area first (area(\"name\") or #sel pos1 / pos2). clear_area(3) takes 3 layers per pass.",
                "if clear_area(2) > 0",
                "  #sel ca            // Baritone finishes what clear_area could not get at",
                "end",
                "collect_scaffold()")));
        p.put("Mine the entire area", new ArrayList<>(List.of(
                "// Select the area first. Top down, everything in reach at each stop, drops picked up on the way.",
                "// Blocks next to water or lava are left alone (nothing floods).",
                "if clear_area() > 0",
                "  #sel ca",
                "end")));
        p.put("Several areas in a row", new ArrayList<>(List.of(
                "// Save the areas first: /queue area save a  (b, c ...)",
                "for name in [\"a\", \"b\", \"c\"]",
                "  area(name)",
                "  if clear_area() > 0",
                "    #sel ca",
                "  end",
                "end")));
        p.put("Water job: fill, then mine (chests)", new ArrayList<>(List.of(
                "// Fill the water of area 1 with dirt from a chest, then mine areas 2 and 3 into another chest.",
                "// Save the areas first: select them (#sel pos1 / pos2), then /queue area save 1  (and 2, 3).",
                "// Chests: put the cursor on an empty line and press Pick block, then aim at the chest and press J.",
                "let dirt_chest = pos(100, 64, -20)",
                "let dump_chest = pos(104, 64, -20)",
                "on full do dump(dump_chest) end",
                "",
                "// 1. fill area 1, every column from the bottom up; more dirt comes from the chest by itself",
                "area(\"1\")",
                "let left = fill_water(\"dirt\", dirt_chest)",
                "if left > 0 then print(\"{left} wet cells could not be reached\") end",
                "put(dirt_chest, \"dirt\")",
                "",
                "// 2. mine areas 2 and 3 from the top; a full inventory goes into the dump chest",
                "for name in [\"2\", \"3\"]",
                "  area(name)",
                "  if clear_area() > 0",
                "    #sel ca",
                "  end",
                "  dump(dump_chest)",
                "end")));
        p.put("Fill water with dirt (from a chest)", new ArrayList<>(List.of(
                "// Select the area first. Every column from the bottom up; dives where it is too deep.",
                "// Seagrass and kelp count as water. Sand or gravel sink by themselves: fill_water(\"sand\", chest).",
                "let dirt_chest = pos(0, 64, 0)   // Pick block: the chest it fetches more dirt from",
                "let left = fill_water(\"dirt\", dirt_chest)",
                "print(\"Water left: {left}\")")));
        p.put("Fill an area with water (buckets)", new ArrayList<>(List.of(
                "// Select the area first, only up to the water line: every air block in it becomes a water source.",
                "// Open sides are sealed with dirt first. Carry empty buckets and at least 2 water buckets (or have water",
                "// within 16 blocks): one source alone never refills, two make an endless supply.",
                "#acceptableThrowawayItems dirt",
                "flood()")));
        p.put("Clear trees", new ArrayList<>(List.of(
                "// Logs, then leaves (select the area first)",
                "#sel replace oak_log birch_log spruce_log jungle_log acacia_log dark_oak_log mangrove_log cherry_log pale_oak_log air",
                "#sel replace oak_leaves birch_leaves spruce_leaves jungle_leaves acacia_leaves dark_oak_leaves mangrove_leaves cherry_leaves pale_oak_leaves azalea_leaves flowering_azalea_leaves air",
                "collect_scaffold()   // take down what Baritone placed to reach the tops")));
        p.put("Survival helpers", new ArrayList<>(List.of(
                "// The helpers act while the script runs (until it ends): eat, sleep, fight, restock, escape water.",
                "// Their settings stay saved: options(\"survival\") lists them.",
                "option(\"survival.bed\", pos(0, 64, 0))      // Pick block on the bed",
                "option(\"survival.supply\", pos(2, 64, 0))   // the chest restock() takes from",
                "want(\"food\", 32)                           // what restock() keeps with you",
                "want(\"_pickaxe\", 2)",
                "enable(\"eat\", \"sleep\", \"defence\", \"restock\", \"escape\")")));
        p.put("Keep fed and sleep at night", new ArrayList<>(List.of(
                "on food < 14 do eat() end",
                "on bed_time do sleep() end")));
        p.put("Empty into a chest when full", new ArrayList<>(List.of(
                "let dump_chest = pos(100, 64, -20)",
                "keep(\"dirt\", 64)   // dump() leaves this much (scaffolding)",
                "on full do dump(dump_chest) end")));
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

    /** The default presets of 2.0 - 2.4 (presetsVersion 6): unchanged copies of these are replaced on upgrade. */
    static Map<String, List<String>> v6() {
        Map<String, List<String>> p = new LinkedHashMap<>();
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

    /** Brings a user's presets up to date: old untouched defaults out, current defaults in, own presets kept. */
    static Map<String, List<String>> upgrade(Map<String, List<String>> user) {
        Map<String, List<String>> old = v6();
        Map<String, List<String>> out = new LinkedHashMap<>();
        defaults().forEach(out::put);
        user.forEach((name, lines) -> {
            List<String> was = old.get(name);
            if (was != null && was.equals(lines)) return;              // an untouched old default: replaced or gone
            if (out.containsKey(name) && !out.get(name).equals(lines)) {
                out.put(name + " (yours)", lines);                     // edited by the user: kept beside the new one
            } else {
                out.putIfAbsent(name, lines);
            }
        });
        return out;
    }
}
