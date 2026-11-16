package dev.buildtools;

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
 * Remembers the blocks the engine itself placed as scaffolding (bridges, pillars, stairs of throwaway blocks) so
 * ScaffoldProcess can pick them up in one quick sweep afterwards.
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
            if (!Util.isThrowaway(player.getItemInHand(hand))) return InteractionResult.PASS;
            if (!dev.midtown.api.EngineSelector.current().isBusy()) return InteractionResult.PASS;
            BlockPos clicked = hit.getBlockPos();
            BlockPos placed = level.getBlockState(clicked).canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
            var outside = Api.scaffoldOutsideSchematic;   // a schematic build knows which blocks are wanted
            if (outside != null && !outside.test(placed, bi.getBlock())) return InteractionResult.PASS;   // the schematic wants it: not scaffolding
            if (spots.size() < MAX) spots.put(placed.immutable(), bi.getBlock());
            return InteractionResult.PASS;
        });
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
        var outside = Api.scaffoldOutsideSchematic;
        int n = 0;
        for (var e : spots.entrySet()) {
            BlockPos pos = e.getKey();
            if (pos.distSqr(p.blockPosition()) <= (double) r * r
                    && level.hasChunkAt(pos)
                    && (outside == null || outside.test(pos, e.getValue()))
                    && level.getBlockState(pos).getBlock() == e.getValue()) {
                n++;
            }
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
        if (!level.hasChunkAt(pos)) return false;
        if (level.getBlockState(pos).getBlock() == b) return true;
        spots.remove(pos);
        return false;
    }

    static boolean isRecorded(BlockPos pos) {
        return spots.containsKey(pos);
    }

    static void forget(BlockPos pos) {
        spots.remove(pos);
    }

    static void clear() {
        spots.clear();
    }
}
