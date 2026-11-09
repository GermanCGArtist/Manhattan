package dev.buildtools;

import baritone.api.utils.input.Input;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
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
 * Enforces the safe areas. Every block break and block placement by Baritone goes through Minecraft's own
 * startDestroyBlock / useItemOn, and Fabric lets us veto those. A break or placement is only vetoed when Baritone
 * itself is clicking (its forced input is down), so what you do by hand, and what the other addons do, is untouched.
 *
 * Walking through a safe area, opening its chests and sleeping in its bed all still work.
 *
 * The veto stops the damage but not Baritone's plan: if it keeps insisting on breaking something in there, it would
 * retry forever. So after a few seconds of being blocked the running Baritone task is stopped, with a message.
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
                level.isClientSide() ? check(pos, "break a block", Input.CLICK_LEFT, true) : InteractionResult.PASS);

        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide()) return InteractionResult.PASS;
            if (!(player.getItemInHand(hand).getItem() instanceof BlockItem)) return InteractionResult.PASS;   // not a placement
            BlockPos clicked = hit.getBlockPos();
            BlockState state = level.getBlockState(clicked);
            if (usable(level, clicked, state)) return InteractionResult.PASS;   // opening a chest, bed, door ...
            BlockPos placed = state.canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
            return check(placed, "place a block", Input.CLICK_RIGHT, false);
        });
    }

    private static boolean usable(Level level, BlockPos pos, BlockState state) {
        return level.getBlockEntity(pos) instanceof MenuProvider
                || state.getBlock() instanceof BedBlock
                || state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof TrapDoorBlock
                || state.getBlock() instanceof FenceGateBlock;
    }

    private static InteractionResult check(BlockPos pos, String action, Input input, boolean breaking) {
        if (!cfg.protect || cfg.zones.isEmpty()) return InteractionResult.PASS;
        if (!Util.baritone().getInputOverrideHandler().isInputForcedDown(input)) return InteractionResult.PASS;   // not Baritone
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
            Util.baritone().getPathingBehavior().cancelEverything();
            Util.msg("PROBLEM: Baritone's route leads through the safe area '" + zoneName + "': it kept trying to " + what
                    + (blockName.isEmpty() ? "" : " (" + blockName + ")") + " at " + where.toShortString()
                    + ". It cannot plan around plain blocks, so I stopped the task. Move the job away from the area, "
                    + "make the area smaller (/buildtools protect remove " + zoneName + "), or switch protection off (/buildtools protect off).");
        }
    }
}
