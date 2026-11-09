package dev.baritonequeue;

import baritone.api.pathing.goals.GoalBlock;
import dev.baritonequeue.Schem.Mode;
import dev.baritonequeue.Schem.Phase;
import dev.baritonequeue.Schem.Plan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Our own hands, for the two jobs Baritone's builder does badly:
 *  - ORIENTED blocks (stairs, slabs, trapdoors, logs, doors, observers, signs, wall torches ...): for every spot it
 *    picks a face to click and a yaw/pitch for which the game's own getStateForPlacement gives exactly the wanted
 *    state (simulated before clicking), turns, right-clicks like a player, verifies, and on a wrong result breaks it
 *    and retries (twice, never in add mode), then reports it.
 *  - CLEAR (exact mode): breaks the schematic's air blocks top-down, from a spot with a clear line to them.
 * Baritone only walks (GoalBlock on a spot that was checked for reach and line of sight).
 */
final class SchemPlacer {
    private static final double REACH = 4.4;
    private static final int MAX_BREAKS = 2, MAX_CLICKS = 3;

    private record Cand(BlockPos against, Direction face, Vec3 hit, float yaw, float pitch) {}

    private enum Stage { APPROACH, BREAK, AIM, VERIFY }

    private Plan plan;
    private Mode mode;
    private boolean clearing;
    private final LinkedList<BlockPos> queue = new LinkedList<>();
    private BlockPos cur;
    private BlockState want;        // the final state wanted at cur
    private BlockState aimState;    // what the click has to produce now (a double slab is two clicks)
    private Item item;
    private Stage stage;
    private Cand chosen;
    private List<Cand> cands;
    private int ticks, stageTicks, attempts, clicks, stagnant, turned;
    private boolean breaking;
    private BlockPos breakPos, lastSpot;
    private final java.util.Set<BlockPos> badSpots = new java.util.HashSet<>();
    private int placed;
    final List<String> failures = new ArrayList<>();

    int placed() { return placed; }

    void begin(Plan plan, Mode mode, Phase phase) {
        this.plan = plan;
        this.mode = mode;
        clearing = phase == Phase.CLEAR;
        queue.clear();
        failures.clear();
        placed = 0;
        cur = null;
        stagnant = 0;
        badSpots.clear();
        Level level = Minecraft.getInstance().level;
        for (int y = 0; y < plan.sy; y++) for (int z = 0; z < plan.sz; z++) for (int x = 0; x < plan.sx; x++) {
            BlockState d = plan.st[plan.idx(x, y, z)];
            if (d == null || plan.excluded.get(plan.idx(x, y, z))) continue;
            BlockPos w = plan.world(x, y, z);
            if (!level.hasChunkAt(w)) continue;
            BlockState now = Schem.norm(level.getBlockState(w));
            if (clearing) {
                boolean wrongBlock = !d.isAir() && mode != Mode.ADD && !now.isAir() && !Schem.replaceable(now) && !now.equals(d);
                if ((d.isAir() && Schem.todo(mode, d, now)) || wrongBlock) queue.addFirst(w);   // top-down: schematic air to dig out, wrong blocks to take away
            } else if (!d.isAir() && Schem.phaseOf(d) == Phase.ORIENTED && Schem.cost(d) != null && Schem.todo(mode, d, now)) {
                queue.addLast(w);                                               // bottom-up
            }
        }
    }

    void stop() {
        Util.baritone().getPathingBehavior().cancelEverything();
        stopBreaking();
        cur = null;
        queue.clear();
    }

    private void stopBreaking() {
        Minecraft mc = Minecraft.getInstance();
        if (breaking && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        breaking = false;
    }

    // ------------------------------------------------------------------ candidates

    private static BlockState simulate(Level level, LocalPlayer p, Block block, ItemStack stack, Cand c) {
        float oy = p.getYRot(), ox = p.getXRot();
        try {
            p.setYRot(c.yaw());
            p.setXRot(c.pitch());
            BlockPlaceContext ctx = new BlockPlaceContext(level, p, InteractionHand.MAIN_HAND, stack, new BlockHitResult(c.hit(), c.face(), c.against(), false));
            if (!ctx.canPlace()) return null;
            return block.getStateForPlacement(ctx);
        } catch (RuntimeException e) {
            return null;
        } finally {
            p.setYRot(oy);
            p.setXRot(ox);
        }
    }

    private static Vec3 faceCenter(BlockPos n, Direction face) {
        return Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
    }

    /** What to click / look at for the current spot. Independent of where the player stands. */
    private List<Cand> candidates(Level level, LocalPlayer p) {
        List<Cand> out = new ArrayList<>();
        if (clearing) {
            for (Direction d : Direction.values()) {
                out.add(new Cand(cur, d, faceCenter(cur, d), 0, 0));
            }
            return out;
        }
        if (aimState != null && aimState.hasProperty(BlockStateProperties.SLAB_TYPE) && level.getBlockState(cur).is(aimState.getBlock())) {
            out.add(new Cand(cur, Direction.UP, faceCenter(cur, Direction.UP), 0, 90f));   // the second slab: click the top of the first
            return out;
        }
        ItemStack stack = new ItemStack(item);
        Block block = aimState.getBlock();
        float[] pitches = {0f, 60f, -60f, 90f, -90f};
        for (Direction dir : Direction.values()) {
            BlockPos n = cur.relative(dir);
            BlockState ns = level.getBlockState(n);
            if (ns.isAir() || Schem.replaceable(ns)) continue;
            Direction face = dir.getOpposite();
            Vec3 center = faceCenter(n, face);
            List<Vec3> hits = new ArrayList<>();
            hits.add(center);
            if (face.getAxis().isHorizontal()) {
                hits.add(center.add(0, 0.25, 0));
                hits.add(center.add(0, -0.25, 0));
            }
            for (Vec3 hit : hits) {
                for (int i = 0; i < 16; i++) {
                    float yaw = i * 22.5f;
                    for (float pitch : pitches) {
                        Cand c = new Cand(n, face, hit, yaw, pitch);
                        BlockState s = simulate(level, p, block, stack, c);
                        if (s != null && Schem.norm(s).equals(aimState)) {
                            out.add(c);
                            break;
                        }
                    }
                }
            }
        }
        return out;
    }

    private static boolean sees(Level level, LocalPlayer p, Vec3 eye, Cand c) {
        if (eye.distanceTo(c.hit()) > REACH - 0.2) return false;
        Vec3 end = c.hit().subtract(c.face().getStepX() * 0.02, c.face().getStepY() * 0.02, c.face().getStepZ() * 0.02);
        BlockHitResult r = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return r.getType() == HitResult.Type.BLOCK && r.getBlockPos().equals(c.against());
    }

    private Cand reachable(Level level, LocalPlayer p) {
        Vec3 eye = p.getEyePosition();
        Cand best = null;
        double bestScore = Double.MAX_VALUE;
        for (Cand c : cands) {
            if (!sees(level, p, eye, c)) continue;
            double score = Math.abs(Mth.wrapDegrees(c.yaw() - p.getYRot())) + Math.abs(c.pitch() - p.getXRot()) * 0.5 + eye.distanceTo(c.hit());
            if (score < bestScore) { best = c; bestScore = score; }
        }
        return best;
    }

    /** A spot to stand on from which some candidate is in reach with a clear line; the nearest one to the player. */
    private BlockPos standSpot(Level level, LocalPlayer p) {
        List<Cand> uniq = new ArrayList<>();
        for (Cand c : cands) if (uniq.stream().noneMatch(u -> u.against().equals(c.against()) && u.face() == c.face() && u.hit().equals(c.hit()))) uniq.add(c);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = -4; dy <= 2; dy++) {
            BlockPos s = cur.offset(dx, dy, dz);
            if (s.equals(cur) || s.above().equals(cur)) continue;
            if (!level.getBlockState(s).getCollisionShape(level, s).isEmpty() || !level.getBlockState(s.above()).getCollisionShape(level, s.above()).isEmpty()) continue;
            if (level.getBlockState(s.below()).getCollisionShape(level, s.below()).isEmpty()) continue;
            if (!level.getFluidState(s).isEmpty()) continue;
            if (badSpots.contains(s)) continue;
            double d = s.distSqr(p.blockPosition()) + 40.0 * Math.max(0, s.getY() - p.blockPosition().getY());   // climbing is expensive
            if (d >= bestD) continue;
            Vec3 eye = new Vec3(s.getX() + 0.5, s.getY() + 1.62, s.getZ() + 0.5);
            if (clearing) {   // the way may need digging: only the distance counts
                if (eye.distanceTo(Vec3.atCenterOf(cur)) <= REACH - 0.7) { best = s; bestD = d; }
                continue;
            }
            for (Cand c : uniq) {
                if (sees(level, p, eye, c)) { best = s; bestD = d; break; }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ items

    private static int slotOf(LocalPlayer p, Item it) {
        int found = -1;
        for (int i = 0; i < 36; i++) {
            if (p.getInventory().getItem(i).is(it)) {
                if (i < 9) return i;
                if (found < 0) found = i;
            }
        }
        return found;
    }

    private static void equip(Minecraft mc, LocalPlayer p, int idx) {
        if (idx < 9) {
            p.getInventory().setSelectedSlot(idx);
            return;
        }
        int target = p.getInventory().getSelectedSlot();
        for (int h = 0; h < 9; h++) if (p.getInventory().getItem(h).isEmpty()) { target = h; break; }
        mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, idx, target, ContainerInput.SWAP, p);
        p.getInventory().setSelectedSlot(target);
    }

    private static boolean aim(LocalPlayer p, float yaw, float pitch) {
        float dy = Mth.wrapDegrees(yaw - p.getYRot()), dp = pitch - p.getXRot();
        p.setYRot(p.getYRot() + Mth.clamp(dy, -35f, 35f));
        p.setXRot(Mth.clamp(p.getXRot() + Mth.clamp(dp, -25f, 25f), -90f, 90f));
        return Math.abs(dy) < 0.5f && Math.abs(dp) < 0.5f;
    }

    // ------------------------------------------------------------------ the loop

    private String what() {
        return clearing ? "dig" : BuiltInRegistries.BLOCK.getKey(want.getBlock()).getPath();
    }

    private void fail(String why) {
        failures.add(cur.toShortString() + " " + what() + ": " + why);
        stopBreaking();
        cur = null;
    }

    private void next(Level level, LocalPlayer p) {
        BlockPos pick = null;
        for (BlockPos q : queue) {
            if (pick == null) { pick = q; continue; }
            boolean better = clearing ? q.getY() > pick.getY() : q.getY() < pick.getY();
            if (better || (q.getY() == pick.getY() && q.distSqr(p.blockPosition()) < pick.distSqr(p.blockPosition()))) pick = q;
        }
        queue.remove(pick);
        cur = pick;
        want = plan.at(cur.getX() - plan.min.getX(), cur.getY() - plan.min.getY(), cur.getZ() - plan.min.getZ());
        attempts = clicks = ticks = stageTicks = turned = 0;
        cands = null;
        chosen = null;
        lastSpot = null;
        aimState = want;
        BlockState now = Schem.norm(level.getBlockState(cur));
        if (clearing) {
            if (level.getBlockState(cur).isAir()) { placed++; cur = null; return; }
            stage = Stage.APPROACH;
            return;
        }
        if (now.equals(want)) { placed++; cur = null; return; }
        Object[] cost = Schem.cost(want);
        item = (Item) cost[0];
        if (item == Items.AIR) { fail("has no item"); return; }
        if (want.hasProperty(BlockStateProperties.SLAB_TYPE) && want.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
            aimState = want.setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);   // first the lower slab, then the second on top
        }
        if (slotOf(p, item) < 0) { fail("no " + Schem.itemName(item) + " in the inventory"); return; }
        breakPos = null;
        stage = Stage.APPROACH;
    }

    /** One game tick. Returns true when there is nothing left to do. */
    boolean tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Level level = mc.level;
        if (p == null || level == null || mc.gameMode == null) return false;
        if (cur == null) {
            if (queue.isEmpty()) return true;
            next(level, p);
            return false;
        }
        ticks++;
        stageTicks++;
        if (ticks > 20 * 90) { fail("took too long"); return false; }
        switch (stage) {
            case APPROACH -> {
                if (cands == null) {
                    cands = candidates(level, p);
                    if (cands.isEmpty()) {
                        // nothing to click against / no open side yet (a block placed or dug later may give one): come back later
                        if (++stagnant > queue.size() + 1) { fail(clearing ? "enclosed, no side to dig from" : "no block next to it to click against, or no way to orient it"); stagnant = 0; return false; }
                        queue.addLast(cur);
                        cur = null;
                        return false;
                    }
                    stagnant = 0;
                }
                if (clearing && p.getEyePosition().distanceTo(Vec3.atCenterOf(cur)) <= REACH - 0.3) {
                    BlockHitResult r = level.clip(new ClipContext(p.getEyePosition(), Vec3.atCenterOf(cur), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
                    breakPos = r.getType() == HitResult.Type.BLOCK ? r.getBlockPos() : cur;
                    if (dev.buildtools.Api.protectedAt(breakPos)) { fail("a safe area is in the way at " + breakPos.toShortString()); return false; }
                    Util.baritone().getPathingBehavior().cancelEverything();
                    chosen = null;
                    stage = Stage.BREAK;
                    stageTicks = 0;
                    return false;
                }
                BlockPos feet = p.blockPosition();
                boolean inside = !clearing && (feet.equals(cur) || feet.above().equals(cur));
                chosen = inside ? null : clearing ? null : reachable(level, p);
                if (chosen == null) {
                    if (stageTicks > 20 * 40) { fail("could not get into reach"); return false; }
                    if (stageTicks % 100 == 1 && !Util.baritone().getCustomGoalProcess().isActive()) {
                        if (lastSpot != null) badSpots.add(lastSpot);   // it walked there (or tried) and still no reach: not that one
                        BlockPos spot = standSpot(level, p);
                        lastSpot = spot;
                        if (spot == null) { fail("no spot to stand on with a clear line to it"); return false; }
                        System.out.println("[Queue] schem: " + what() + " at " + cur.toShortString() + ": walking to " + spot.toShortString());
                        Util.baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(spot));
                    }
                    return false;
                }
                Util.baritone().getPathingBehavior().cancelEverything();
                breakPos = cur;
                stage = !Schem.replaceable(level.getBlockState(cur)) && !wantsMerge(level) ? Stage.BREAK : Stage.AIM;
                stageTicks = 0;
            }
            case BREAK -> {
                BlockPos bp = breakPos != null ? breakPos : cur;
                BlockState s = level.getBlockState(bp);
                if (clearing ? s.isAir() : Schem.replaceable(s)) {
                    stopBreaking();
                    if (clearing && bp.equals(cur)) { placed++; cur = null; breakPos = null; }
                    else if (clearing) { stage = Stage.APPROACH; breakPos = null; stageTicks = 0; }
                    else { stage = Stage.AIM; stageTicks = 0; }
                    return false;
                }
                if (stageTicks > 20 * 15) { fail("could not break what is there"); return false; }
                Vec3 at = Vec3.atCenterOf(bp);
                if (p.getEyePosition().distanceTo(at) > REACH) { stage = Stage.APPROACH; cands = clearing ? null : cands; stageTicks = 0; stopBreaking(); return false; }
                Direction f = Direction.getApproximateNearest(p.getEyePosition().subtract(at));
                aim(p, (float) (Math.toDegrees(Math.atan2(at.z - p.getEyePosition().z, at.x - p.getEyePosition().x)) - 90.0),
                        (float) -Math.toDegrees(Math.atan2(at.y - p.getEyePosition().y, Math.hypot(at.x - p.getEyePosition().x, at.z - p.getEyePosition().z))));
                if (!breaking) { mc.gameMode.startDestroyBlock(bp, f); breaking = true; }
                else mc.gameMode.continueDestroyBlock(bp, f);
                p.swing(InteractionHand.MAIN_HAND);
            }
            case AIM -> {
                if (stageTicks > 20 * 6) { fail("could not turn / click"); return false; }
                if (!Schem.replaceable(level.getBlockState(cur)) && !wantsMerge(level)) { stage = Stage.BREAK; stageTicks = 0; return false; }
                if (chosen == null || p.getEyePosition().distanceTo(chosen.hit()) > REACH) { stage = Stage.APPROACH; stageTicks = 0; return false; }
                int slot = slotOf(p, item);
                if (slot < 0) { fail("no " + Schem.itemName(item) + " in the inventory"); return false; }
                equip(mc, p, slot);
                if (!aim(p, chosen.yaw(), chosen.pitch())) { turned = 0; return false; }
                if (++turned < 6) return false;   // let the rotation reach the server before clicking
                turned = 0;
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(chosen.hit(), chosen.face(), chosen.against(), false));
                p.swing(InteractionHand.MAIN_HAND);
                clicks++;
                stage = Stage.VERIFY;
                stageTicks = 0;
            }
            case VERIFY -> {
                if (stageTicks < 5) return false;
                BlockState s = level.getBlockState(cur);
                BlockState n = Schem.norm(s);
                if (n.equals(want)) { placed++; cur = null; return false; }
                if (n.equals(aimState) && !aimState.equals(want)) {   // the lower slab is in: now the second one
                    aimState = want;
                    cands = null;
                    chosen = null;
                    clicks = 0;
                    stage = Stage.APPROACH;
                    stageTicks = 0;
                    return false;
                }
                if (!Schem.replaceable(s)) {
                    // placed, but not as wanted: break it and try again (never in add mode: that leaves what is there alone)
                    if (++attempts > MAX_BREAKS) { fail("placed as " + s + " instead, gave up after " + MAX_BREAKS + " retries"); return false; }   // (it was our own click into a free spot, so breaking it is fine in add mode too)
                    aimState = want.hasProperty(BlockStateProperties.SLAB_TYPE) && want.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE
                            ? want.setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM) : want;
                    cands = null;
                    stage = Stage.BREAK;
                    stageTicks = 0;
                    return false;
                }
                if (clicks >= MAX_CLICKS) { fail("the click did not place anything"); return false; }
                stage = Stage.AIM;
                stageTicks = 0;
            }
        }
        return false;
    }

    /** The second slab of a double slab is clicked onto the first one: there is a block, and that is fine. */
    private boolean wantsMerge(Level level) {
        return !clearing && aimState != null && aimState.equals(want) && want.hasProperty(BlockStateProperties.SLAB_TYPE)
                && want.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE && level.getBlockState(cur).is(want.getBlock());
    }
}
