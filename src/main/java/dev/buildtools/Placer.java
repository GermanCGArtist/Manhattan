package dev.buildtools;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Does one placement the way a player would: look at a face next to the target cell (smooth turn), then right-click.
 *   BLOCK  - put a throwaway block into the cell (replaces water, air, ...)
 *   WATER  - empty a water bucket into the cell (makes a source block)
 *   PICKUP - scoop the water source in the cell with an empty bucket
 * The caller keeps the player within reach (see canReach) and calls tick() every game tick.
 */
final class Placer {
    enum Mode { BLOCK, WATER, PICKUP }

    enum Status { WORKING, DONE, FAILED }

    private record Click(BlockPos against, Direction face, Vec3 aim) {}

    private static final double REACH = 4.4;
    private static final int MAX_ACTIONS = 4;
    private static final int WALK_TIMEOUT = 20 * 30;
    private static final int ACT_TIMEOUT = 20 * 6;

    /** When set, BLOCK mode places this item (if carried) instead of any throwaway block. */
    static Item preferred;

    private Mode mode = Mode.BLOCK;
    private int waterBefore;     // PICKUP: water buckets carried when the scoop started (done = one more)
    private BlockPos target;
    private int walkTicks, actTicks, cooldown, actions;
    private String failure = "";

    BlockPos target() {
        return target;
    }

    String failure() {
        return failure;
    }

    void start(BlockPos target, Mode mode) {
        start(target, mode, -1);
    }

    /** waterBuckets: for a scoop, the water buckets carried before it (-1 = not counted: the source must vanish). */
    void start(BlockPos target, Mode mode, int waterBuckets) {
        this.target = target;
        this.mode = mode;
        this.waterBefore = waterBuckets;
        walkTicks = 0;
        actTicks = 0;
        cooldown = 0;
        actions = 0;
        failure = "";
    }

    /** Whether a click on the target is possible from where the player stands right now. */
    boolean canReach(Minecraft mc, LocalPlayer p) {
        return findClick(mc, p) != null;
    }

    /** Whether the cell has a solid neighbour to click against (so it can be placed into at all). */
    static boolean hasClickable(Level level, BlockPos cell) {
        for (Direction d : Direction.values()) {
            if (Util.isSolid(level, cell.relative(d))) return true;
        }
        return false;
    }

    /** Could a block be put into the cell from an eye at this point (a solid face of a neighbour visible and in reach)? */
    static boolean placeableFrom(Level level, LocalPlayer p, Vec3 eye, BlockPos cell) {
        for (Direction d : Direction.values()) {
            BlockPos n = cell.relative(d);
            if (!Util.isSolid(level, n)) continue;
            Direction face = d.getOpposite();
            Vec3 aim = Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            if (eye.distanceTo(aim) > REACH - 0.15) continue;
            Vec3 end = aim.subtract(face.getStepX() * 0.02, face.getStepY() * 0.02, face.getStepZ() * 0.02);
            BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(n)) return true;
        }
        return false;
    }

    private Click findClick(Minecraft mc, LocalPlayer p) {
        Level level = mc.level;
        Vec3 eye = p.getEyePosition();

        if (mode == Mode.PICKUP) {
            Vec3 aim = Vec3.atCenterOf(target);
            if (eye.distanceTo(aim) > REACH) return null;
            BlockHitResult hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.SOURCE_ONLY, p));
            return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target) ? new Click(target, null, aim) : null;
        }

        Click best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos n = target.relative(d);
            if (!Util.isSolid(level, n)) continue;
            Direction face = d.getOpposite();   // the face of n that looks at the target cell
            Vec3 aim = Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            double dist = eye.distanceTo(aim);
            if (dist > REACH || dist >= bestDist) continue;
            Vec3 end = aim.subtract(face.getStepX() * 0.02, face.getStepY() * 0.02, face.getStepZ() * 0.02);
            BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(n)) {
                best = new Click(n, face, aim);
                bestDist = dist;
            }
        }
        return best;
    }

    private boolean done(Level level, LocalPlayer p) {
        BlockState s = level.getBlockState(target);
        return switch (mode) {
            case BLOCK -> Util.isSolid(level, target);
            case WATER -> Util.isSourceWater(s);
            // a scoop is done when a water bucket is really in the inventory (a source that merely vanished is not)
            case PICKUP -> waterBefore < 0 ? !Util.isSourceWater(s) : Util.countItem(p, Items.WATER_BUCKET) > waterBefore;
        };
    }

    Status tick(Minecraft mc, LocalPlayer p) {
        if (done(mc.level, p)) return Status.DONE;

        Click click = findClick(mc, p);
        if (click == null) {
            if (++walkTicks > WALK_TIMEOUT) return fail("could not get into reach of " + target.toShortString());
            return Status.WORKING;
        }
        if (++actTicks > ACT_TIMEOUT) return fail("nothing happened at " + target.toShortString());

        // the right item in hand
        Item item = mode == Mode.WATER ? Items.WATER_BUCKET : Items.BUCKET;
        int slot = mode == Mode.BLOCK ? blockSlot(p) : Util.slotOf(p, item);
        if (slot < 0) {
            return fail(mode == Mode.BLOCK ? "no throwaway blocks (cobblestone, dirt, ...) in the inventory"
                    : mode == Mode.WATER ? "no water bucket in the inventory" : "no empty bucket in the inventory");
        }
        Util.equip(mc, p, slot);

        boolean aimed = Util.aimAt(p, click.aim());
        if (cooldown > 0) cooldown--;
        if (aimed && cooldown == 0) {
            if (actions >= MAX_ACTIONS) return fail("clicking at " + target.toShortString() + " had no effect");
            if (mode == Mode.BLOCK) {
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(click.aim(), click.face(), click.against(), false));
            } else {
                mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);   // buckets aim with the look ray
            }
            p.swing(InteractionHand.MAIN_HAND);
            actions++;
            cooldown = 6;   // like a player: no more than a click every few ticks
        }
        return Status.WORKING;
    }

    private static int blockSlot(LocalPlayer p) {
        if (preferred != null) {
            int s = Util.slotOf(p, preferred);
            if (s >= 0) return s;
        }
        return Util.throwawaySlot(p);
    }

    private Status fail(String why) {
        failure = why;
        return Status.FAILED;
    }
}
