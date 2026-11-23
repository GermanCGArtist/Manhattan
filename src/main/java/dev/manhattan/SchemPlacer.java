package dev.manhattan;

import dev.manhattan.Schem.Mode;
import dev.manhattan.Schem.Phase;
import dev.manhattan.Schem.Plan;
import dev.midtown.body.BodyController;
import dev.midtown.body.HotbarController;
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
    private static final String MOTOR_OWNER = "schem";
    private static final double REACH = 4.4;
    private static final int MAX_BREAKS = 2, MAX_CLICKS = 3;

    private record Cand(BlockPos against, Direction face, Vec3 hit, float yaw, float pitch, boolean sneak) {}

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
    private int ticks, stageTicks, attempts, clicks, stagnant, turned, walkCooldown, materialWaitTicks, walkStallTicks;
    private double walkBestDistSq = Double.MAX_VALUE;
    private boolean breaking, sneakingForPlace;
    private BlockPos breakPos, lastSpot;
    private final java.util.Set<BlockPos> badSpots = new java.util.HashSet<>();
    private final java.util.Set<BlockPos> deferred = new java.util.HashSet<>();
    private final java.util.Set<Integer> retriedLayers = new java.util.HashSet<>();
    private int placed;
    final List<String> failures = new ArrayList<>();

    static boolean isInteractive(Block b) {
        return b instanceof net.minecraft.world.level.block.ChestBlock
                || b instanceof net.minecraft.world.level.block.BarrelBlock
                || b instanceof net.minecraft.world.level.block.ShulkerBoxBlock
                || b instanceof net.minecraft.world.level.block.HopperBlock
                || b instanceof net.minecraft.world.level.block.AbstractFurnaceBlock
                || b instanceof net.minecraft.world.level.block.CraftingTableBlock
                || b instanceof net.minecraft.world.level.block.DispenserBlock
                || b instanceof net.minecraft.world.level.block.DoorBlock
                || b instanceof net.minecraft.world.level.block.TrapDoorBlock
                || b instanceof net.minecraft.world.level.block.FenceGateBlock
                || b instanceof net.minecraft.world.level.block.ButtonBlock
                || b instanceof net.minecraft.world.level.block.LeverBlock
                || b instanceof net.minecraft.world.level.block.BedBlock
                || b instanceof net.minecraft.world.level.block.AnvilBlock
                || b instanceof net.minecraft.world.level.block.BrewingStandBlock
                || b instanceof net.minecraft.world.level.block.EnchantingTableBlock
                || b instanceof net.minecraft.world.level.block.EnderChestBlock
                || b instanceof net.minecraft.world.level.block.GrindstoneBlock
                || b instanceof net.minecraft.world.level.block.LecternBlock
                || b instanceof net.minecraft.world.level.block.LoomBlock
                || b instanceof net.minecraft.world.level.block.SmithingTableBlock
                || b instanceof net.minecraft.world.level.block.StonecutterBlock
                || b instanceof net.minecraft.world.level.block.DiodeBlock
                || b instanceof net.minecraft.world.level.block.NoteBlock
                || b instanceof net.minecraft.world.level.block.BeaconBlock
                || b instanceof net.minecraft.world.level.block.BellBlock;
    }

    static boolean needsSneak(Level level, BlockPos against, BlockState want) {
        if (level != null && against != null && isInteractive(level.getBlockState(against).getBlock())) {
            return true;
        }
        if (want != null && want.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                && want.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)
                && want.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) == net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
            return true;
        }
        return false;
    }

    private void setSneak(Minecraft mc, LocalPlayer p, boolean down) {
        if (sneakingForPlace == down) return;
        sneakingForPlace = down;
        if (mc != null && mc.options != null) mc.options.keyShift.setDown(down);
        if (p != null) p.setShiftKeyDown(down);
    }

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
        walkCooldown = 0;
        badSpots.clear();
        deferred.clear();
        retriedLayers.clear();
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
            } else if (!d.isAir() && Schem.phaseAt(plan, plan.idx(x, y, z), d, now) == Phase.ORIENTED && Schem.cost(d) != null && Schem.todo(plan, plan.idx(x, y, z), mode, d, now)) {
                queue.addLast(w);                                               // bottom-up
            }
        }
    }

    void stop() {
        dev.midtown.api.EngineSelector.current().cancel();
        stopBreaking();
        Minecraft mc = Minecraft.getInstance();
        setSneak(mc, mc != null ? mc.player : null, false);
        BodyController.INSTANCE.releaseIfOwner(mc, MOTOR_OWNER);
        cur = null;
        walkCooldown = 0;
        materialWaitTicks = 0;
        deferred.clear();
        queue.clear();
    }

    private void stopBreaking() {
        Minecraft mc = Minecraft.getInstance();
        if (breaking) BodyController.INSTANCE.stopBreaking(mc);
        breaking = false;
    }

    // ------------------------------------------------------------------ candidates

    private static BlockState simulate(Level level, LocalPlayer p, Block block, ItemStack stack, Cand c) {
        float oy = p.getYRot(), ox = p.getXRot();
        boolean os = p.isShiftKeyDown();
        try {
            p.setYRot(c.yaw());
            p.setXRot(c.pitch());
            p.setShiftKeyDown(c.sneak());
            BlockPlaceContext ctx = new BlockPlaceContext(level, p, InteractionHand.MAIN_HAND, stack, new BlockHitResult(c.hit(), c.face(), c.against(), false));
            if (!ctx.canPlace()) return null;
            return block.getStateForPlacement(ctx);
        } catch (RuntimeException e) {
            return null;
        } finally {
            p.setYRot(oy);
            p.setXRot(ox);
            p.setShiftKeyDown(os);
        }
    }

    /**
     * Center of {@code face} on {@code n}'s actual collision hitbox (e.g. y+0.875 for chests, 0.0625 inset on chest sides),
     * so raycasts ({@link #sees}) hit non-full blocks like chests, hoppers, slabs, and beds instead of missing in air.
     */
    private static Vec3 faceCenter(Level level, BlockPos n, Direction face) {
        if (level != null) {
            var shape = level.getBlockState(n).getShape(level, n);
            if (!shape.isEmpty()) {
                net.minecraft.world.phys.AABB bb = shape.bounds();
                double cx = n.getX() + (bb.minX + bb.maxX) * 0.5;
                double cy = n.getY() + (bb.minY + bb.maxY) * 0.5;
                double cz = n.getZ() + (bb.minZ + bb.maxZ) * 0.5;
                return switch (face) {
                    case UP -> new Vec3(cx, n.getY() + bb.maxY, cz);
                    case DOWN -> new Vec3(cx, n.getY() + bb.minY, cz);
                    case NORTH -> new Vec3(cx, cy, n.getZ() + bb.minZ);
                    case SOUTH -> new Vec3(cx, cy, n.getZ() + bb.maxZ);
                    case WEST -> new Vec3(n.getX() + bb.minX, cy, cz);
                    case EAST -> new Vec3(n.getX() + bb.maxX, cy, cz);
                };
            }
        }
        return Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
    }

    /** What to click / look at for the current spot. Independent of where the player stands. */
    private List<Cand> candidates(Level level, LocalPlayer p) {
        List<Cand> out = new ArrayList<>();
        if (clearing) {
            for (Direction d : Direction.values()) {
                out.add(new Cand(cur, d, faceCenter(level, cur, d), 0, 0, false));
            }
            return out;
        }
        if (aimState != null && aimState.hasProperty(BlockStateProperties.SLAB_TYPE) && level.getBlockState(cur).is(aimState.getBlock())) {
            out.add(new Cand(cur, Direction.UP, faceCenter(level, cur, Direction.UP), 0, 90f, false));   // the second slab: click the top of the first
            return out;
        }
        ItemStack stack = new ItemStack(item);
        Block block = aimState.getBlock();
        boolean isChest = block instanceof net.minecraft.world.level.block.ChestBlock;
        // For the second half of a double chest (aimState is LEFT/RIGHT), modern MC connects chests reliably
        // by shift-clicking the side face of the already-placed partner chest.
        BlockPos forcedPartner = null;
        if (isChest && aimState.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)
                && aimState.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
            BlockPos partner = cur.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(aimState));
            if (level.getBlockState(partner).is(block)) {
                forcedPartner = partner;
            }
        }
        float[] pitches = {0f, 60f, -60f, 90f, -90f};
        for (Direction dir : Direction.values()) {
            BlockPos n = cur.relative(dir);
            if (forcedPartner != null && !n.equals(forcedPartner)) continue;
            BlockState ns = level.getBlockState(n);
            if (ns.isAir() || Schem.replaceable(ns)) continue;
            Direction face = dir.getOpposite();
            Vec3 center = faceCenter(level, n, face);
            List<Vec3> hits = new ArrayList<>();
            hits.add(center);
            if (face.getAxis().isHorizontal()) {
                hits.add(center.add(0, 0.2, 0));
                hits.add(center.add(0, -0.2, 0));
            }
            boolean interactive = isInteractive(ns.getBlock());
            boolean[] sneakModes = (interactive || forcedPartner != null) ? new boolean[]{true}
                    : (isChest ? new boolean[]{true, false} : new boolean[]{false});
            for (boolean snk : sneakModes) {
                for (Vec3 hit : hits) {
                    for (int i = 0; i < 16; i++) {
                        float yaw = i * 22.5f;
                        for (float pitch : pitches) {
                            Cand c = new Cand(n, face, hit, yaw, pitch, snk);
                            BlockState s = simulate(level, p, block, stack, c);
                            if (s != null && Schem.norm(s).equals(aimState)) {
                                out.add(c);
                                break;
                            }
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
        BlockHitResult r = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
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

    private boolean canBreakInClear(Level level, BlockPos pos) {
        if (pos.equals(cur)) return true;
        int rx = pos.getX() - plan.min.getX();
        int ry = pos.getY() - plan.min.getY();
        int rz = pos.getZ() - plan.min.getZ();
        if (rx < 0 || rx >= plan.sx || rz < 0 || rz >= plan.sz || ry < 0) return false;
        if (ry >= plan.sy) return true;
        int idx = plan.idx(rx, ry, rz);
        if (plan.excluded.get(idx)) return false;
        BlockState d = plan.st[idx];
        return d == null || d.isAir() || (mode != Mode.ADD && !Schem.norm(level.getBlockState(pos)).equals(d));
    }

    /** A spot to stand on from which some candidate is in reach with a clear line; the nearest one to the player. */
    private BlockPos standSpot(Level level, LocalPlayer p) {
        List<Cand> uniq = new ArrayList<>();
        for (Cand c : cands) if (uniq.stream().noneMatch(u -> u.against().equals(c.against()) && u.face() == c.face() && u.hit().equals(c.hit()))) uniq.add(c);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos fallback = null;
        double fallbackD = Double.MAX_VALUE;
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = -4; dy <= 2; dy++) {
            BlockPos s = cur.offset(dx, dy, dz);
            if (s.equals(cur) || s.above().equals(cur)) continue;
            if (!level.getBlockState(s).getCollisionShape(level, s).isEmpty() || !level.getBlockState(s.above()).getCollisionShape(level, s.above()).isEmpty()) continue;
            // dry ground, or in water with the head out (wading on the floor, or swimming at the surface): a block wanted
            // under water (a flooded trench) has no dry spot near it
            boolean wet = !level.getFluidState(s).isEmpty();
            if (!level.getFluidState(s.above()).isEmpty()) continue;
            if (level.getBlockState(s.below()).getCollisionShape(level, s.below()).isEmpty() && !wet) continue;
            if (badSpots.contains(s)) continue;
            int rx = s.getX() - plan.min.getX();
            int rz = s.getZ() - plan.min.getZ();
            boolean insideFootprint = rx >= 0 && rx < plan.sx && rz >= 0 && rz < plan.sz;
            double d = s.distSqr(p.blockPosition()) + 40.0 * Math.max(0, s.getY() - p.blockPosition().getY())   // climbing is expensive
                    + (wet ? 12 : 0) + (clearing && !insideFootprint ? 100 : 0);
            Vec3 eye = new Vec3(s.getX() + 0.5, s.getY() + 1.62, s.getZ() + 0.5);
            if (clearing) {
                if (eye.distanceTo(Vec3.atCenterOf(cur)) <= REACH - 0.5) {
                    BlockHitResult r = level.clip(new ClipContext(eye, Vec3.atCenterOf(cur), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
                    BlockPos hitPos = r.getType() == HitResult.Type.BLOCK ? r.getBlockPos() : cur;
                    if (canBreakInClear(level, hitPos)) {
                        if (d < bestD) { best = s; bestD = d; }
                    } else if (rx >= -1 && rx <= plan.sx && rz >= -1 && rz <= plan.sz && s.getY() >= cur.getY()) {
                        if (d < fallbackD) { fallback = s; fallbackD = d; }
                    }
                }
                continue;
            }
            if (d >= bestD) continue;
            for (Cand c : uniq) {
                if (sees(level, p, eye, c)) { best = s; bestD = d; break; }
            }
        }
        return best != null ? best : fallback;
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

    private static boolean equipItem(Minecraft mc, LocalPlayer p, Item it) {
        return HotbarController.selectItem(mc, p, st -> !st.isEmpty() && st.is(it));
    }

    private static boolean aim(LocalPlayer p, float yaw, float pitch) {
        float dy = Mth.wrapDegrees(yaw - p.getYRot()), dp = pitch - p.getXRot();
        BodyController.INSTANCE.aimSmooth(p, yaw, pitch, 35f, 25f);
        return Math.abs(dy) < 0.5f && Math.abs(dp) < 0.5f;
    }

    // ------------------------------------------------------------------ the loop

    private String what() {
        return clearing ? "dig" : BuiltInRegistries.BLOCK.getKey(want.getBlock()).getPath();
    }

    private void fail(String why) {
        failures.add(cur.toShortString() + " " + what() + ": " + why);
        stopBreaking();
        Minecraft mc = Minecraft.getInstance();
        setSneak(mc, mc != null ? mc.player : null, false);
        cur = null;
    }

    private void next(Level level, LocalPlayer p) {
        BlockPos pick = null;
        int minDeferredY = Integer.MAX_VALUE;
        for (BlockPos q : queue) {
            if (deferred.contains(q)) {
                if (q.getY() < minDeferredY) minDeferredY = q.getY();
                continue;
            }
            if (pick == null) { pick = q; continue; }
            boolean better = clearing ? q.getY() > pick.getY() : q.getY() < pick.getY();
            if (better || (q.getY() == pick.getY() && q.distSqr(p.blockPosition()) < pick.distSqr(p.blockPosition()))) pick = q;
        }
        // Retry deferred blocks on a lower Y layer once before building a higher Y layer that could bury them
        if (!clearing && minDeferredY < Integer.MAX_VALUE && (pick == null || minDeferredY < pick.getY()) && !retriedLayers.contains(minDeferredY)) {
            retriedLayers.add(minDeferredY);
            final int retryY = minDeferredY;
            deferred.removeIf(pos -> pos.getY() == retryY);
            badSpots.clear();
            pick = null;
            for (BlockPos q : queue) {
                if (q.getY() != retryY) continue;
                if (pick == null || q.distSqr(p.blockPosition()) < pick.distSqr(p.blockPosition())) pick = q;
            }
        }
        if (pick == null) {
            // Every remaining block in queue was deferred with no valid placement face: fail one and drop it so we don't spin
            BlockPos stuck = queue.pollFirst();
            if (stuck != null) {
                cur = stuck;
                want = plan.at(cur.getX() - plan.min.getX(), cur.getY() - plan.min.getY(), cur.getZ() - plan.min.getZ());
                fail(clearing ? "enclosed, no side to dig from" : "no block next to it to click against, or no way to orient it");
            }
            return;
        }
        queue.remove(pick);
        cur = pick;
        want = plan.at(cur.getX() - plan.min.getX(), cur.getY() - plan.min.getY(), cur.getZ() - plan.min.getZ());
        attempts = clicks = ticks = stageTicks = turned = walkCooldown = 0;
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
        } else if (want.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                && want.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)) {
            var wantType = want.getValue(net.minecraft.world.level.block.ChestBlock.TYPE);
            if (wantType != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                BlockPos partner = cur.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(want));
                BlockState ps = level.getBlockState(partner);
                boolean partnerReady = ps.is(want.getBlock())
                        && ps.hasProperty(net.minecraft.world.level.block.ChestBlock.FACING)
                        && ps.getValue(net.minecraft.world.level.block.ChestBlock.FACING)
                                == want.getValue(net.minecraft.world.level.block.ChestBlock.FACING);
                if (!partnerReady) {
                    // First half of a double chest: place as SINGLE first (sneak prevents merging with wrong neighbor);
                    // placing the partner next will pair both halves into the exact LEFT/RIGHT double chest.
                    aimState = want.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
                            net.minecraft.world.level.block.state.properties.ChestType.SINGLE);
                }
            }
        }
        if (slotOf(p, item) < 0) {
            if (SchemProcess.shouldYieldForMaterialFetch() && materialWaitTicks < 40) {
                queue.addLast(cur);
                cur = null;
                materialWaitTicks++;
                SchemProcess.markWaitingForMaterials();
                return;
            }
            fail("no " + Schem.itemName(item) + " in the inventory");
            return;
        }
        materialWaitTicks = 0;
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
            if (queue.isEmpty()) {
                BodyController.INSTANCE.releaseIfOwner(mc, MOTOR_OWNER);
                return true;
            }
            next(level, p);
            return false;
        }
        ticks++;
        stageTicks++;
        if (walkCooldown > 0) walkCooldown--;
        if (ticks > 20 * 90) { fail("took too long"); return false; }
        switch (stage) {
            case APPROACH -> {
                setSneak(mc, p, false);
                if (cands == null && !clearing && !Schem.replaceable(level.getBlockState(cur)) && !wantsMerge(level)) {
                    // a block in the cell (scaffolding put there during the build): take it away first, then aim
                    if (p.getEyePosition().distanceTo(Vec3.atCenterOf(cur)) <= REACH - 0.3) {
                        dev.midtown.api.EngineSelector.current().cancel();
                        breakPos = cur;
                        stage = Stage.BREAK;
                        stageTicks = 0;
                        return false;
                    }
                    if (stageTicks > 20 * 40) { fail("could not get into reach to take away what is in the way"); return false; }
                    if (walkCooldown == 0 && !dev.midtown.api.EngineSelector.current().isBusy()) {
                        walkCooldown = 10;
                        BodyController.INSTANCE.releaseIfOwner(mc, MOTOR_OWNER);
                        dev.midtown.api.EngineSelector.current().goTo(dev.midtown.api.GoalSpec.near(cur, 2));
                    }
                    return false;
                }
                if (cands == null) {
                    cands = candidates(level, p);
                    if (cands.isEmpty()) {
                        // Defer this spot so next() moves on immediately to other placeable blocks;
                        // deferred is cleared whenever any new block is placed (creating new neighbor faces).
                        deferred.add(cur);
                        queue.addLast(cur);
                        cur = null;
                        return false;
                    }
                    stagnant = 0;
                }
                if (clearing && p.getEyePosition().distanceTo(Vec3.atCenterOf(cur)) <= REACH - 0.3) {
                    BlockHitResult r = level.clip(new ClipContext(p.getEyePosition(), Vec3.atCenterOf(cur), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
                    BlockPos hitPos = r.getType() == HitResult.Type.BLOCK ? r.getBlockPos() : cur;
                    if (canBreakInClear(level, hitPos)) {
                        breakPos = hitPos;
                        if (dev.buildtools.Api.protectedAt(breakPos)) { fail("a safe area is in the way at " + breakPos.toShortString()); return false; }
                        dev.midtown.api.EngineSelector.current().cancel();
                        chosen = null;
                        stage = Stage.BREAK;
                        stageTicks = 0;
                        return false;
                    }
                }
                BlockPos feet = p.blockPosition();
                boolean inside = !clearing && (feet.equals(cur) || feet.above().equals(cur)
                        || p.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(cur)));
                chosen = inside ? null : clearing ? null : reachable(level, p);
                if (chosen == null) {
                    if (stageTicks > 20 * 20) { fail("could not get into reach"); return false; }
                    boolean engineBusy = dev.midtown.api.EngineSelector.current().isBusy();
                    if (engineBusy && lastSpot != null) {
                        double dSq = p.position().distanceToSqr(lastSpot.getX() + 0.5, lastSpot.getY(), lastSpot.getZ() + 0.5);
                        if (dSq < walkBestDistSq - 0.15) {
                            walkBestDistSq = dSq;
                            walkStallTicks = 0;
                        } else if (++walkStallTicks >= 60) {
                            // Stalled for 3s trying to reach lastSpot: blacklist it and immediately pick another standSpot
                            dev.midtown.api.EngineSelector.current().cancel();
                            badSpots.add(lastSpot);
                            lastSpot = null;
                            walkStallTicks = 0;
                            walkBestDistSq = Double.MAX_VALUE;
                            walkCooldown = 0;
                            engineBusy = false;
                        }
                    }
                    if (walkCooldown == 0 && !engineBusy) {
                        walkCooldown = 6;
                        if (lastSpot != null) badSpots.add(lastSpot);   // it walked there (or tried) and still no reach: not that one
                        BlockPos spot = standSpot(level, p);
                        lastSpot = spot;
                        if (spot == null) { fail("no spot to stand on with a clear line to it"); return false; }
                        walkStallTicks = 0;
                        walkBestDistSq = p.position().distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
                        System.out.println("[Queue] schem: " + what() + " at " + cur.toShortString() + ": walking to " + spot.toShortString());
                        BodyController.INSTANCE.releaseIfOwner(mc, MOTOR_OWNER);
                        dev.midtown.api.EngineSelector.current().goTo(dev.midtown.api.GoalSpec.exact(spot));
                    }
                    return false;
                }
                walkStallTicks = 0;
                walkBestDistSq = Double.MAX_VALUE;
                dev.midtown.api.EngineSelector.current().cancel();
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
                BodyController.INSTANCE.acquire(mc, MOTOR_OWNER);
                aim(p, BodyController.yawTo(at.x - p.getEyePosition().x, at.z - p.getEyePosition().z),
                        BodyController.pitchTo(at.x - p.getEyePosition().x, at.y - p.getEyePosition().y, at.z - p.getEyePosition().z));
                if (!breaking) ClearProcess.equipFor(mc, p, s);
                breaking = BodyController.INSTANCE.breakBlockTick(mc, bp);
            }
            case AIM -> {
                if (p.containerMenu != p.inventoryMenu) {
                    p.closeContainer();
                    return false;
                }
                if (stageTicks > 20 * 6) { setSneak(mc, p, false); fail("could not turn / click"); return false; }
                if (!Schem.replaceable(level.getBlockState(cur)) && !wantsMerge(level)) { setSneak(mc, p, false); stage = Stage.BREAK; stageTicks = 0; return false; }
                if (chosen == null || p.getEyePosition().distanceTo(chosen.hit()) > REACH) { setSneak(mc, p, false); stage = Stage.APPROACH; stageTicks = 0; return false; }
                if (!equipItem(mc, p, item)) {
                    setSneak(mc, p, false);
                    if (SchemProcess.shouldYieldForMaterialFetch() && materialWaitTicks < 40) {
                        materialWaitTicks++;
                        SchemProcess.markWaitingForMaterials();
                        return false;
                    }
                    fail("no " + Schem.itemName(item) + " in the inventory");
                    return false;
                }
                materialWaitTicks = 0;
                BodyController.INSTANCE.acquire(mc, MOTOR_OWNER);
                boolean sneak = chosen.sneak();
                setSneak(mc, p, sneak);
                if (!aim(p, chosen.yaw(), chosen.pitch())) { turned = 0; return false; }
                if (++turned < (sneak ? 3 : 2)) return false;   // let rotation + sneak packet reach server before clicking
                turned = 0;
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(chosen.hit(), chosen.face(), chosen.against(), false));
                p.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
                setSneak(mc, p, false);
                clicks++;
                stage = Stage.VERIFY;
                stageTicks = 0;
            }
            case VERIFY -> {
                setSneak(mc, p, false);
                if (p.containerMenu != p.inventoryMenu) {
                    p.closeContainer();
                }
                if (stageTicks < 5) return false;
                BlockState s = level.getBlockState(cur);
                BlockState n = Schem.norm(s);
                if (n.equals(want)) { placed++; deferred.clear(); cur = null; return false; }
                if (n.equals(aimState) && !aimState.equals(want)) {
                    if (want.getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
                        // First half of a double chest placed as SINGLE: done here, partner half will pair it!
                        placed++;
                        deferred.clear();
                        cur = null;
                        return false;
                    }
                    // the lower slab is in: now the second one
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
