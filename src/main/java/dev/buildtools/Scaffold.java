package dev.buildtools;

import baritone.api.BaritoneAPI;
import baritone.api.utils.input.Input;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers the blocks Baritone itself placed as scaffolding (bridges, pillars, stairs of throwaway blocks) so
 * ScaffoldProcess can pick them up in one quick sweep afterwards, instead of the builder taking them down layer by layer.
 *
 * Only throwaway blocks (Baritone's acceptableThrowawayItems) placed while Baritone is clicking are recorded, and not
 * while the builder is placing real blocks: with the builder running, only a queue step that clears (sel ca,
 * sel replace ... air, sel set air) counts, because there nothing the builder places is wanted.
 */
final class Scaffold {
    private Scaffold() {}

    private static final int MAX = 20_000;

    private static final Map<BlockPos, Block> spots = new LinkedHashMap<>();
    private static String dimension = "";
    private static Object world;   // the level object: a new world (same dimension name) starts with no spots

    static void register() {
        // registered after Guard, so a placement Guard vetoes (safe areas) never reaches this
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide() || !Config.cfg.scaffoldRecord) return InteractionResult.PASS;
            if (!(player.getItemInHand(hand).getItem() instanceof BlockItem bi)) return InteractionResult.PASS;
            if (!BaritoneAPI.getSettings().acceptableThrowawayItems.value.contains(bi)) return InteractionResult.PASS;
            if (!Util.baritone().getInputOverrideHandler().isInputForcedDown(Input.CLICK_RIGHT)) return InteractionResult.PASS;   // you, not Baritone
            BlockPos clicked = hit.getBlockPos();
            BlockPos placed = level.getBlockState(clicked).canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
            var outside = Api.scaffoldOutsideSchematic;   // a schematic build knows which blocks are wanted
            if (outside != null && !outside.test(placed, bi.getBlock())) return InteractionResult.PASS;   // the schematic wants it: not scaffolding
            if (Util.baritone().getBuilderProcess().isActive() && !clearingStep()) {   // the builder may be building
                if (outside == null || !outside.test(placed, bi.getBlock())) return InteractionResult.PASS;
            }
            if (spots.size() < MAX) spots.put(placed.immutable(), bi.getBlock());
            return InteractionResult.PASS;
        });
    }

    /** The queue step that runs now only clears: whatever the builder places is scaffolding. */
    private static boolean clearingStep() {
        String step = System.getProperty("baritonequeue.step");
        if (step == null) return false;
        String[] w = step.trim().toLowerCase().split("\\s+");
        if (w.length < 2 || !(w[0].equals("sel") || w[0].equals("selection") || w[0].equals("s"))) return false;
        switch (w[1]) {
            case "ca", "cleararea" -> { return true; }
            case "replace", "r", "set", "fill", "s", "f" -> {
                String last = w[w.length - 1];
                return last.equals("air") || last.equals("minecraft:air");
            }
            default -> { return false; }
        }
    }

    /** Called every tick: the list belongs to one dimension. */
    static void tick(Minecraft mc) {
        if (mc.level == null) return;
        if (mc.level != world) {
            world = mc.level;
            spots.clear();
            dimension = "";
        }
        String d = mc.level.dimension().identifier().toString();
        if (!d.equals(dimension)) {
            spots.clear();
            dimension = d;
        }
    }

    static Map<BlockPos, Block> spots() {
        return spots;
    }

    static int count() {
        return spots.size();
    }

    /** Recorded blocks still standing within r blocks of the player. */
    static int near(LocalPlayer p, int r) {
        Level level = p.level();
        int n = 0;
        for (var e : spots.entrySet()) {
            if (e.getKey().distSqr(p.blockPosition()) <= (double) r * r && level.getBlockState(e.getKey()).getBlock() == e.getValue()) n++;
        }
        return n;
    }

    /** Still the block that was placed there? Forgets the spot when not. */
    static boolean valid(Level level, BlockPos pos) {
        Block b = spots.get(pos);
        if (b == null) return false;
        var outside = Api.scaffoldOutsideSchematic;
        if (outside != null && !outside.test(pos, b)) {   // a schematic wants this block here: never taken down
            spots.remove(pos);
            return false;
        }
        if (level.getBlockState(pos).getBlock() == b) return true;
        spots.remove(pos);
        return false;
    }

    static void forget(BlockPos pos) {
        spots.remove(pos);
    }

    static void clear() {
        spots.clear();
    }
}
