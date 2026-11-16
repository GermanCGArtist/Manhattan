package dev.buildtools;

import dev.baritonequeue.ClearProcess;
import dev.midtown.api.EngineSelector;
import dev.midtown.api.EngineStatus;
import dev.midtown.api.GoalSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.buildtools.Config.cfg;

/**
 * Picks up scaffolding left behind: walks to the nearest recorded block, and breaks
 * everything within reach from where it stands with the fastest tool in the hotbar, then collects the drops.
 */
final class ScaffoldProcess extends Feature {
    private static final double REACH = 4.4;
    private static final int TARGET_TIMEOUT = 20 * 12;
    private static final int GATHER_TIMEOUT = 20 * 6;

    private boolean working;
    private boolean gathering;
    private boolean sweepingCluster;
    private boolean waitingForPath;
    private boolean breakStarted;
    private boolean walkingToItem;
    private BlockPos target;
    private ItemEntity item;
    private BlockPos itemGoalPos;
    private int targetTicks, pathAttempts, itemTicks, itemAttempt, cooldown, check, broken, skipped;
    private final Set<Item> dropTypes = new HashSet<>();
    private final Set<Integer> ignoredItems = new HashSet<>();
    private final Set<BlockPos> announced = new HashSet<>();
    private final Set<BlockPos> sweepSkipped = new HashSet<>();
    private final List<BlockPos> brokenPositions = new ArrayList<>();

    ScaffoldProcess() {
        super("BuildTools scaffolding", 13);
    }

    @Override
    boolean busy() {
        return working;
    }

    // ------------------------------------------------------------------ triggers

    @Override
    void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Scaffold.tick(mc);
        if (cooldown > 0) cooldown--;
        if (working) {
            if (p == null || mc.level == null || mc.gameMode == null) {
                setWorking(false);
                return;
            }
            stepInternal(mc, p);
            return;
        }
        if (p == null || mc.level == null || !Util.scriptOn("scaffold", cfg.scaffoldAuto) || cooldown > 0 || ++check % 20 != 0 || Util.addonOff("scaffold")) return;
        if (!Util.baritoneBusy()) return;
        if (Scaffold.near(p, 40) >= cfg.scaffoldBatch) start(mc, p, true);
    }

    void startNow() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (working) { Util.msg("Already collecting scaffolding."); return; }
        start(mc, mc.player, false);
    }

    private void start(Minecraft mc, LocalPlayer p, boolean auto) {
        int n = Scaffold.near(p, auto ? 40 : 128);
        if (n == 0) {
            if (!auto) Util.msg("No scaffolding recorded near you.");
            return;
        }
        setWorking(true);
        resetTargetState();
        gathering = false;
        sweepingCluster = false;
        item = null;
        itemGoalPos = null;
        itemTicks = 0;
        itemAttempt = 0;
        broken = skipped = 0;
        dropTypes.clear();
        ignoredItems.clear();
        sweepSkipped.clear();
        brokenPositions.clear();
        Util.msg((auto ? "Enough scaffolding piled up (" : "Collecting scaffolding (") + n + " blocks)" + (auto ? ": taking it down now." : "..."));
    }

    private void resetTargetState() {
        target = null;
        targetTicks = 0;
        pathAttempts = 0;
        waitingForPath = false;
        breakStarted = false;
    }

    private void releaseWalkKey(Minecraft mc) {
        if (!walkingToItem) return;
        if (mc != null && mc.options != null) mc.options.keyUp.setDown(false);
        walkingToItem = false;
    }

    private Boolean breakBefore;

    private void setWorking(boolean on) {
        if (on == working) return;
        working = on;
        if (on) {
            breakBefore = RegionRules.setOwnBreak(false);
        } else {
            releaseWalkKey(Minecraft.getInstance());
            if (breakBefore != null) {
                RegionRules.setOwnBreak(breakBefore);
                breakBefore = null;
                EngineSelector.current().cancel();
            }
        }
    }

    void cancel() {
        setWorking(false);
        resetTargetState();
        gathering = false;
        sweepingCluster = false;
        item = null;
        itemGoalPos = null;
        sweepSkipped.clear();
        brokenPositions.clear();
        Minecraft mc = Minecraft.getInstance();
        releaseWalkKey(mc);
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        EngineSelector.current().cancel();
    }

    // ------------------------------------------------------------------ work

    @Override
    boolean active() {
        Minecraft mc = Minecraft.getInstance();
        if (working && (mc.player == null || mc.level == null || mc.gameMode == null)) setWorking(false);
        return working;
    }

    private void stepInternal(Minecraft mc, LocalPlayer p) {
        Level level = mc.level;

        if (target != null && !Scaffold.valid(level, target)) {
            broken++;
            brokenPositions.add(target.immutable());
            resetTargetState();
        }
        if (gathering || sweepingCluster) {
            stepGather(mc, p, level);
            return;
        }
        if (target == null) {
            BlockPos next = pickTarget(p, level);
            if (next == null) {
                gathering = true;
                item = null;
                itemTicks = 0;
                itemAttempt = 0;
                itemGoalPos = null;
                stepGather(mc, p, level);
                return;
            }
            if (!brokenPositions.isEmpty() && next.distSqr(p.blockPosition()) > 64) {
                ItemEntity drop = findItem(p, level);
                if (drop != null) {
                    sweepingCluster = true;
                    item = drop;
                    itemTicks = 0;
                    itemAttempt = 0;
                    itemGoalPos = null;
                    stepGather(mc, p, level);
                    return;
                }
            }
            target = next;
            targetTicks = 0;
            pathAttempts = 0;
            waitingForPath = false;
            breakStarted = false;
        }
        if (!level.getBlockState(target).isAir() && !announced.contains(target)) {
            announced.add(target.immutable());
            Util.msg("Taking down scaffolding at " + target.toShortString() + " (" + level.getBlockState(target).getBlock().getName().getString() + ")");
        }
        work(mc, p, level);
    }

    private void stepGather(Minecraft mc, LocalPlayer p, Level level) {
        if (item == null || !item.isAlive()) {
            item = findItem(p, level);
            itemTicks = 0;
            itemAttempt = 0;
            itemGoalPos = null;
        }
        if (item == null) {
            releaseWalkKey(mc);
            if (sweepingCluster) {
                sweepingCluster = false;
                return;
            }
            finish(mc);
            return;
        }
        if (++itemTicks > GATHER_TIMEOUT) {
            ignoredItems.add(item.getId());
            item = null;
            releaseWalkKey(mc);
            EngineSelector.current().cancel();
            return;
        }
        double dx = item.getX() - p.getX();
        double dz = item.getZ() - p.getZ();
        double horizDist = Math.hypot(dx, dz);
        double dy = Math.abs(item.getY() - p.getY());
        if (horizDist <= 2.2 && dy <= 1.2) {
            if (EngineSelector.current().isBusy()) EngineSelector.current().cancel();
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float err = Mth.wrapDegrees(yaw - p.getYRot());
            p.setYRot(p.getYRot() + Mth.clamp(err, -45f, 45f));
            boolean aligned = Math.abs(err) < 35.0 || horizDist >= 1.5;
            mc.options.keyUp.setDown(aligned);
            walkingToItem = true;
            return;
        }
        releaseWalkKey(mc);
        if (!EngineSelector.current().isBusy()) {
            EngineStatus st = EngineSelector.current().status();
            if (itemAttempt == 0) {
                itemAttempt = 1;
                itemGoalPos = item.blockPosition();
                EngineSelector.current().goTo(GoalSpec.exact(itemGoalPos));
            } else if (itemAttempt == 1 && (st == EngineStatus.FAILED || st == EngineStatus.DONE)) {
                itemAttempt = 2;
                itemGoalPos = item.blockPosition();
                EngineSelector.current().goTo(GoalSpec.near(itemGoalPos, 1));
            } else if (itemAttempt >= 2 && st == EngineStatus.FAILED) {
                ignoredItems.add(item.getId());
                item = null;
            } else if (itemAttempt >= 2 && st == EngineStatus.DONE) {
                if (itemGoalPos == null || item.blockPosition().distSqr(itemGoalPos) > 4) {
                    itemAttempt = 1;
                    itemGoalPos = item.blockPosition();
                    EngineSelector.current().goTo(GoalSpec.exact(itemGoalPos));
                } else {
                    ignoredItems.add(item.getId());
                    item = null;
                }
            }
        }
    }

    private void work(Minecraft mc, LocalPlayer p, Level level) {
        if (++targetTicks > TARGET_TIMEOUT) {
            sweepSkipped.add(target.immutable());
            skipped++;
            resetTargetState();
            mc.gameMode.stopDestroyBlock();
            EngineSelector.current().cancel();
            return;
        }
        BlockHitResult hit = reachHit(p, level, target);
        if (hit == null) {
            if (waitingForPath) {
                if (EngineSelector.current().isBusy()) return;
                waitingForPath = false;
            }
            pathAttempts++;
            if (pathAttempts == 1) {
                BlockPos stand = findBestStand(level, p, target);
                GoalSpec goal = stand != null ? GoalSpec.exact(stand) : GoalSpec.lineOfSight(target, REACH - 0.3);
                EngineSelector.current().goTo(goal);
                waitingForPath = true;
                return;
            }
            if (pathAttempts == 2) {
                EngineSelector.current().goTo(GoalSpec.near(target, 1));
                waitingForPath = true;
                return;
            }
            sweepSkipped.add(target.immutable());
            skipped++;
            resetTargetState();
            mc.gameMode.stopDestroyBlock();
            return;
        }

        if (EngineSelector.current().isBusy()) {
            EngineSelector.current().cancel();
        }
        waitingForPath = false;
        pathAttempts = 0;

        BlockState state = level.getBlockState(target);
        ClearProcess.equipFor(mc, p, state);
        if (Util.aimAt(p, hit.getLocation())) {
            dropTypes.add(state.getBlock().asItem());
            if (state.getDestroyProgress(p, level, target) >= 1.0f) {
                mc.gameMode.startDestroyBlock(target, hit.getDirection());
                p.swing(InteractionHand.MAIN_HAND);
                breakStarted = false;
            } else {
                if (!breakStarted) {
                    mc.gameMode.startDestroyBlock(target, hit.getDirection());
                    breakStarted = true;
                } else {
                    mc.gameMode.continueDestroyBlock(target, hit.getDirection());
                }
                p.swing(InteractionHand.MAIN_HAND);
            }
        }
    }

    private static List<Vec3> aimPoints(BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        List<Vec3> pts = new ArrayList<>(7);
        pts.add(center);
        for (Direction d : Direction.values()) {
            pts.add(center.add(d.getStepX() * 0.45, d.getStepY() * 0.45, d.getStepZ() * 0.45));
        }
        return pts;
    }

    private static BlockHitResult firstHit(Level level, LocalPlayer p, Vec3 eye, BlockPos pos, List<Vec3> pts, double maxReach) {
        double maxSq = maxReach * maxReach;
        List<Vec3> validPts = new ArrayList<>(pts.size());
        for (Vec3 pt : pts) {
            if (eye.distanceToSqr(pt) <= maxSq) validPts.add(pt);
        }
        validPts.sort(Comparator.comparingDouble(eye::distanceToSqr));
        for (Vec3 pt : validPts) {
            BlockHitResult hit = level.clip(new ClipContext(eye, pt, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) return hit;
        }
        return null;
    }

    static BlockHitResult reachHit(LocalPlayer p, Level level, BlockPos pos) {
        return firstHit(level, p, p.getEyePosition(), pos, aimPoints(pos), REACH);
    }

    private static boolean passable(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.getCollisionShape(level, pos).isEmpty() && s.getFluidState().isEmpty();
    }

    private BlockPos findBestStand(Level level, LocalPlayer p, BlockPos target) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        List<Vec3> targetPts = aimPoints(target);
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos stand = target.offset(dx, dy, dz);
                    if (stand.equals(target) || stand.below().equals(target)) continue;
                    if (!level.hasChunkAt(stand)) continue;
                    if (!passable(level, stand) || !passable(level, stand.above())) continue;
                    if (!Util.isSolid(level, stand.below())) continue;
                    Vec3 eye = new Vec3(stand.getX() + 0.5, stand.getY() + 1.62, stand.getZ() + 0.5);
                    if (firstHit(level, p, eye, target, targetPts, REACH - 0.2) == null) continue;
                    double score = stand.distSqr(p.blockPosition()) + (Scaffold.isRecorded(stand.below()) ? 25.0 : 0.0);
                    if (score < bestScore) {
                        bestScore = score;
                        best = stand;
                    }
                }
            }
        }
        return best;
    }

    private BlockPos pickTarget(LocalPlayer p, Level level) {
        List<BlockPos> snapshot = new ArrayList<>(Scaffold.spots().keySet());
        Map<BlockPos, Boolean> reachableMap = new HashMap<>();
        List<BlockPos> unreachableAll = new ArrayList<>();
        List<BlockPos> eligible = new ArrayList<>();
        for (BlockPos pos : snapshot) {
            if (!Scaffold.valid(level, pos)) continue;
            if (sweepSkipped.contains(pos)) continue;
            if (cfg.protect) {
                Config.Zone z = Guard.zoneAt(pos);
                if (z != null && !z.allowBreak) continue;
            }
            double d = pos.distSqr(p.blockPosition());
            if (d > 128.0 * 128.0) continue;
            boolean reach = d <= 36.0 && reachHit(p, level, pos) != null;
            reachableMap.put(pos, reach);
            if (!reach) unreachableAll.add(pos);
            eligible.add(pos);
        }

        BlockPos below = p.blockPosition().below();
        BlockPos bestReach = null;
        double bestReachScore = Double.MAX_VALUE;
        List<BlockPos> eligibleUnreachable = new ArrayList<>();

        for (BlockPos pos : eligible) {
            // Top-down column rule: never break a block underneath another valid scaffold block
            if (Scaffold.valid(level, pos.above())) continue;
            boolean reach = Boolean.TRUE.equals(reachableMap.get(pos));
            if (reach) {
                // Avoid cutting out a lower stair/pillar block while a higher block in the 3x3 column is still unreachable
                boolean higherUnreachableIn3x3 = false;
                for (BlockPos other : unreachableAll) {
                    if (other.getY() > pos.getY()
                            && Math.abs(other.getX() - pos.getX()) <= 1
                            && Math.abs(other.getZ() - pos.getZ()) <= 1) {
                        higherUnreachableIn3x3 = true;
                        break;
                    }
                }
                if (higherUnreachableIn3x3) continue;
                double dx = pos.getX() + 0.5 - p.getX();
                double dz = pos.getZ() + 0.5 - p.getZ();
                double horizDistSq = dx * dx + dz * dz;
                double score = -pos.getY() * 1000.0 - horizDistSq * 10.0 + (pos.equals(below) ? 5000.0 : 0.0);
                if (score < bestReachScore) {
                    bestReachScore = score;
                    bestReach = pos;
                }
            } else {
                eligibleUnreachable.add(pos);
            }
        }
        if (bestReach != null) return bestReach;
        if (eligibleUnreachable.isEmpty()) return null;

        // Nearest horizontal cluster among unreachable candidates
        BlockPos anchor = null;
        double anchorDistSq = Double.MAX_VALUE;
        for (BlockPos pos : eligibleUnreachable) {
            double dx = pos.getX() + 0.5 - p.getX();
            double dz = pos.getZ() + 0.5 - p.getZ();
            double hSq = dx * dx + dz * dz;
            if (hSq < anchorDistSq) {
                anchorDistSq = hSq;
                anchor = pos;
            }
        }

        BlockPos bestUnreach = null;
        double bestUnreachScore = Double.MAX_VALUE;
        for (BlockPos pos : eligibleUnreachable) {
            double cdx = pos.getX() - anchor.getX();
            double cdz = pos.getZ() - anchor.getZ();
            if (cdx * cdx + cdz * cdz > 16.0) continue;
            double dx = pos.getX() + 0.5 - p.getX();
            double dz = pos.getZ() + 0.5 - p.getZ();
            double horizDist = Math.hypot(dx, dz);
            double score = -pos.getY() * 100.0 + horizDist * 10.0;
            if (score < bestUnreachScore) {
                bestUnreachScore = score;
                bestUnreach = pos;
            }
        }
        return bestUnreach;
    }

    private ItemEntity findItem(LocalPlayer p, Level level) {
        ItemEntity best = null;
        double bestDist = Double.MAX_VALUE;
        Set<Integer> seenIds = new HashSet<>();
        List<AABB> boxes = new ArrayList<>();
        boxes.add(p.getBoundingBox().inflate(14, 6, 14));

        List<BlockPos> clusterCenters = new ArrayList<>();
        for (BlockPos bp : brokenPositions) {
            if (bp.distSqr(p.blockPosition()) <= 36) continue;
            boolean covered = false;
            for (BlockPos c : clusterCenters) {
                if (bp.distSqr(c) <= 36) {
                    covered = true;
                    break;
                }
            }
            if (!covered && level.hasChunkAt(bp)) {
                clusterCenters.add(bp);
                boxes.add(new AABB(bp).inflate(8, 5, 8));
            }
        }

        for (AABB box : boxes) {
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) {
                if (!seenIds.add(e.getId())) continue;
                if (!e.isAlive() || ignoredItems.contains(e.getId()) || !dropTypes.contains(e.getItem().getItem())) continue;
                double d = e.distanceToSqr(p);
                if (d < bestDist) {
                    bestDist = d;
                    best = e;
                }
            }
        }
        return best;
    }

    private void finish(Minecraft mc) {
        setWorking(false);
        resetTargetState();
        gathering = false;
        sweepingCluster = false;
        item = null;
        itemGoalPos = null;
        sweepSkipped.clear();
        brokenPositions.clear();
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        cooldown = 20 * 20;
        Util.msg("Scaffolding collected: " + broken + " block" + (broken == 1 ? "" : "s") + " removed"
                + (skipped > 0 ? ", " + skipped + " left (out of reach or inside a safe area)" : "") + ".");
    }

    @Override
    public void onLostControl() {
        if (working) cancel();
    }
}
