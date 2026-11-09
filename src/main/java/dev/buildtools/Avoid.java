package dev.buildtools;

import baritone.api.BaritoneAPI;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

import static dev.buildtools.Config.cfg;

/**
 * Tells Baritone's planner never to break the blocks that make a base work: chests, hoppers, beds, furnaces,
 * redstone parts ... Unlike the safe-area veto (which only stops a break at the last moment), this is Baritone's own
 * "blocksToDisallowBreaking" setting, so it plans AROUND them from the start and nothing stalls.
 * It is applied while safe areas are on and removed again when they are switched off.
 */
final class Avoid {
    private Avoid() {}

    private static final String[] PARTS = {"chest", "barrel", "hopper", "dropper", "dispenser", "furnace", "smoker",
            "shulker_box", "brewing_stand", "enchanting_table", "anvil", "lectern", "jukebox", "repeater", "comparator",
            "observer", "piston", "crafter", "beacon", "composter"};

    private static List<Block> functional;
    private static final List<Block> added = new ArrayList<>();

    private static List<Block> functional() {
        if (functional == null) {
            List<Block> out = new ArrayList<>();
            for (Block b : BuiltInRegistries.BLOCK) {
                String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
                boolean match = path.endsWith("_bed");
                for (String p : PARTS) {
                    if (path.contains(p)) { match = true; break; }
                }
                if (match && !path.equals("chiseled_bookshelf")) out.add(b);
            }
            functional = out;
        }
        return functional;
    }

    /** Called about once a second. */
    static void sync() {
        var setting = BaritoneAPI.getSettings().blocksToDisallowBreaking;
        boolean want = cfg.protect && cfg.avoidBlocks;
        if (want) {
            List<Block> now = new ArrayList<>(setting.value);
            boolean changed = false;
            for (Block b : functional()) {
                if (!now.contains(b)) {
                    now.add(b);
                    added.add(b);
                    changed = true;
                }
            }
            if (changed) setting.value = now;
        } else if (!added.isEmpty()) {
            List<Block> now = new ArrayList<>(setting.value);
            now.removeAll(added);
            setting.value = now;
            added.clear();
        }
    }
}
