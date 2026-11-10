package dev.buildtools;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Does one action the way a player would: look at the right spot (smooth turn), then click.
 *   BLOCK  - put a throwaway block into the cell (replaces water, air, ...)
 *   WATER  - empty a water bucket into the cell (makes a source block)
 *   PICKUP - scoop the water source in the cell with an empty bucket
 *   BREAK  - break the block in the cell (with the fastest tool)
 * Buckets are used along the player's real line of sight (the server traces it again), so a bucket is only emptied
 * or filled once that line really ends on the intended face. Before, a turn that was still 6 degrees off could empty
 * the bucket on the top of a trench wall, outside the area.
 * The caller keeps the player within reach (see canReach) and calls tick() every game tick.
 */
final class Placer {
    enum Mode { BLOCK, WATER, PICKUP, BREAK }

    enum Status { WORKING, DONE, FAILED }

    private record Click(BlockPos against, Direction face, Vec3 aim) {}

    static final double REACH = 4.4;
    private static final int MAX_ACTIONS = 4;
    private static final int WALK_TIMEOUT = 20 * 30;
    private static final int ACT_TIMEOUT = 20 * 8;
    private static final int RAY_TRIES = 12;   // ticks of fine aiming at one face before another face is tried

    /** When set, BLOCK mode places this item (if carried) instead of any throwaway block. */
    static Item preferred;

    private Mode mode = Mode.BLOCK;
    private int waterBefore;     // PICKUP: water buckets carried when the scoop started (done = one more)
    private BlockPos target;
    private int walkTicks, actTicks, cooldown, actions, rayMiss;
    private boolean breaking;
    private final Set<String> badFaces = new HashSet<>();   // faces whose line of sight kept ending elsewhere
    private String failure = "";

    BlockPos target() {
        return target;
    }

    Mode mode() {
        return mode;
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
        rayMiss = 0;
        breaking = false;
        badFaces.clear();
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
            if (faceVisible(level, p, eye, n, face, aim)) return true;
        }
        return false;
    }

    /** Could the block in the cell be broken from an eye at this point? */
    static boolean breakableFrom(Level level, LocalPlayer p, Vec3 eye, BlockPos cell) {
        Vec3 aim = Vec3.atCenterOf(cell);
        if (eye.distanceTo(aim) > REACH - 0.15) return false;
        BlockHitResult hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(cell);
    }

    /** The straight line from the eye to the aim point ends on exactly that face (also checks points near the face centre). */
    private static boolean faceVisible(Level level, LocalPlayer p, Vec3 eye, BlockPos n, Direction face, Vec3 aim) {
        Vec3 end = aim.subtract(face.getStepX() * 0.02, face.getStepY() * 0.02, face.getStepZ() * 0.02);
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(n) && hit.getDirection() == face;
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
        if (mode == Mode.BREAK) {
            return breakableFrom(level, p, eye, target) ? new Click(target, null, Vec3.atCenterOf(target)) : null;
        }

        Click best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos n = target.relative(d);
            if (!Util.isSolid(level, n)) continue;
            Direction face = d.getOpposite();   // the face of n that looks at the target cell
            if (badFaces.contains(n.toShortString() + face)) continue;
            Vec3 aim = Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            double dist = eye.distanceTo(aim);
            if (dist > REACH || dist >= bestDist) continue;
            if (faceVisible(level, p, eye, n, face, aim)) {
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
            case BREAK -> !Util.isSolid(level, target) && (s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced());
        };
    }

    /** Where the player's real line of sight ends (what the server will trace for a bucket). */
    private static BlockHitResult lookHit(Level level, LocalPlayer p, ClipContext.Fluid fluid) {
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getViewVector(1.0f).scale(REACH + 0.6));
        return level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluid, p));
    }

    Status tick(Minecraft mc, LocalPlayer p) {
        if (done(mc.level, p)) {
            if (breaking) { mc.gameMode.stopDestroyBlock(); breaking = false; }
            return Status.DONE;
        }

        Click click = findClick(mc, p);
        if (click == null) {
            if (breaking) { mc.gameMode.stopDestroyBlock(); breaking = false; }
            if (++walkTicks > WALK_TIMEOUT) return fail("could not get into reach of " + target.toShortString());
            return Status.WORKING;
        }
        if (++actTicks > ACT_TIMEOUT + (mode == Mode.BREAK ? 20 * 10 : 0)) return fail("nothing happened at " + target.toShortString());

        if (mode == Mode.BREAK) {
            if (!breaking) selectBestTool(mc, p, mc.level.getBlockState(target));
            if (!Util.aimAt(p, click.aim())) return Status.WORKING;
            Direction f = Direction.getApproximateNearest(p.getEyePosition().subtract(click.aim()));
            if (!breaking) { mc.gameMode.startDestroyBlock(target, f); breaking = true; }
            else mc.gameMode.continueDestroyBlock(target, f);
            p.swing(InteractionHand.MAIN_HAND);
            return Status.WORKING;
        }

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
        if (!aimed || cooldown > 0) return Status.WORKING;
        if (mode != Mode.BLOCK) {
            // a bucket goes where the line of sight ends: wait until it ends on the intended face (or the source)
            BlockHitResult real = lookHit(mc.level, p, mode == Mode.PICKUP ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE);
            boolean ok = real.getType() == HitResult.Type.BLOCK && (mode == Mode.PICKUP
                    ? real.getBlockPos().equals(target)
                    : real.getBlockPos().equals(click.against()) && real.getDirection() == click.face());
            if (!ok) {
                if (++rayMiss > RAY_TRIES) {   // this face does not work from here: try another one
                    if (mode == Mode.WATER) badFaces.add(click.against().toShortString() + click.face());
                    rayMiss = 0;
                }
                return Status.WORKING;
            }
            rayMiss = 0;
        }
        if (actions >= MAX_ACTIONS) return fail("clicking at " + target.toShortString() + " had no effect");
        if (mode == Mode.BLOCK) {
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(click.aim(), click.face(), click.against(), false));
        } else {
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);   // buckets aim with the look ray (checked above)
        }
        p.swing(InteractionHand.MAIN_HAND);
        actions++;
        cooldown = 6;   // like a player: no more than a click every few ticks
        return Status.WORKING;
    }

    /** The fastest tool for the block into the hand (from anywhere in the inventory). */
    private static void selectBestTool(Minecraft mc, LocalPlayer p, BlockState state) {
        int best = -1;
        float bestSpeed = 1.0f;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            float s = st.getDestroySpeed(state);
            if (s > bestSpeed + 0.01f) { bestSpeed = s; best = i; }
        }
        if (best >= 0) Util.equip(mc, p, best);
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
