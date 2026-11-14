package dev.buildtools;

import dev.midtown.api.EngineSelector;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

import static dev.buildtools.Config.cfg;

/**
 * Tells the path engine never to break the blocks that make a base work: chests, hoppers, beds, furnaces,
 * redstone parts ...
 */
final class Avoid {
    private Avoid() {}

    private static final String[] PARTS = {"chest", "barrel", "hopper", "dropper", "dispenser", "furnace", "smoker",
            "shulker_box", "brewing_stand", "enchanting_table", "anvil", "lectern", "jukebox", "repeater", "comparator",
            "observer", "piston", "crafter", "beacon", "composter"};

    private static List<Block> functional;
    private static final List<Block> applied = new ArrayList<>();

    private static List<Block> functional() {
        if (functional == null) {
            List<Block> out = new ArrayList<>();
            for (Block b : BuiltInRegistries.BLOCK) {
                String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
                boolean match = path.endsWith("_bed");
                for (String p : PARTS) {
                    if (path.contains(p)) { match = true; break; }
                }
                if (!path.equals("chiseled_bookshelf") && match) out.add(b);
            }
            functional = out;
        }
        return functional;
    }

    /** Called about once a second. */
    static void sync() {
        boolean want = cfg.protect && cfg.avoidBlocks;
        if (want) {
            if (applied.isEmpty()) {
                applied.addAll(functional());
                EngineSelector.current().setDisallowedBreakBlocks(applied);
            }
        } else if (!applied.isEmpty()) {
            applied.clear();
            EngineSelector.current().setDisallowedBreakBlocks(applied);
        }
    }
}
