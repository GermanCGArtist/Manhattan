package dev.buildtools;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

import static dev.buildtools.Config.cfg;

/**
 * Enforces the safe areas.
 */
final class Guard {
    private Guard() {}

    private static final long GRACE_MS = 3000;     // blocked this long in a row -> stop the task
    private static final long RESET_MS = 2000;     // quiet this long -> start counting again

    private static long firstBlocked = -1, lastBlocked = -1;
    private static boolean announce, stop;
    private static String what = "", zoneName = "", blockName = "";
    private static BlockPos where = BlockPos.ZERO;

    static void register() {
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                level.isClientSide() ? check(pos, "break a block", true) : InteractionResult.PASS);

        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide()) return InteractionResult.PASS;
            if (!(player.getItemInHand(hand).getItem() instanceof BlockItem)) return InteractionResult.PASS;   // not a placement
            BlockPos clicked = hit.getBlockPos();
            BlockState state = level.getBlockState(clicked);
            if (usable(level, clicked, state)) return InteractionResult.PASS;   // opening a chest, bed, door ...
            BlockPos placed = state.canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
            return check(placed, "place a block", false);
        });
    }

    private static boolean usable(Level level, BlockPos pos, BlockState state) {
        return level.getBlockEntity(pos) instanceof MenuProvider
                || state.getBlock() instanceof BedBlock
                || state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof TrapDoorBlock
                || state.getBlock() instanceof FenceGateBlock;
    }

    private static InteractionResult check(BlockPos pos, String action, boolean breaking) {
        if (!cfg.protect || cfg.zones.isEmpty()) return InteractionResult.PASS;
        if (!dev.midtown.api.EngineSelector.current().isBusy()) return InteractionResult.PASS;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) return InteractionResult.PASS;
        boolean clicking = breaking ? mc.options.keyAttack.isDown() : mc.options.keyUse.isDown();
        if (!clicking) return InteractionResult.PASS;
        Config.Zone zone = zoneAt(pos);
        if (zone == null || (breaking ? zone.allowBreak : zone.allowPlace)) return InteractionResult.PASS;

        long now = System.currentTimeMillis();
        if (firstBlocked < 0 || now - lastBlocked > RESET_MS) {
            firstBlocked = now;
            announce = true;
        }
        lastBlocked = now;
        what = action;
        zoneName = zone.name;
        where = pos;
        var level = net.minecraft.client.Minecraft.getInstance().level;
        blockName = level == null ? "" : level.getBlockState(pos).getBlock().getName().getString();
        if (now - firstBlocked > GRACE_MS) stop = true;
        return InteractionResult.FAIL;
    }

    static Config.Zone zoneAt(BlockPos pos) {
        for (Config.Zone z : cfg.zones) {
            if (z.contains(pos)) return z;
        }
        return null;
    }

    /** Called every client tick (chat output and cancelling must not happen inside the click callback). */
    static void tick() {
        if (announce) {
            announce = false;
            Util.warn("guard", "Blocked Baritone from trying to " + what + " at " + where.toShortString()
                    + " inside the safe area '" + zoneName + "'.");
        }
        if (stop) {
            stop = false;
            firstBlocked = -1;
            Util.haltQueue("The task was stopped, so the queue stopped too.");
            dev.midtown.api.EngineSelector.current().cancel();
            Util.msg("PROBLEM: Baritone's route leads through the safe area '" + zoneName + "': it kept trying to " + what
                    + (blockName.isEmpty() ? "" : " (" + blockName + ")") + " at " + where.toShortString()
                    + ". It cannot plan around plain blocks, so I stopped the task. Move the job away from the area, "
                    + "make the area smaller (/buildtools protect remove " + zoneName + "), or switch protection off (/buildtools protect off).");
        }
    }
}
