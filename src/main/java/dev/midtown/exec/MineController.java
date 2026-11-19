package dev.midtown.exec;

import dev.midtown.api.Area;
import dev.midtown.api.BlockFilter;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Encapsulates active mining session state, target blacklisting, and live-world target scanning
 * for {@link MidtownEngine}.
 */
public final class MineController {

    private volatile BlockFilter activeMineFilter;
    private volatile Area activeMineBox;
    private final Set<BlockPos> activeMineBlacklist = new HashSet<>();
    private volatile BlockPos lastMineTarget = null;
    private volatile int lastMineTargetAttempts = 0;

    /** Starts a mining session with {@code filter} and optional bounding {@code box}, clearing prior blacklist state. */
    public void beginMine(BlockFilter filter, Area box) {
        Objects.requireNonNull(filter, "filter");
        this.activeMineBlacklist.clear();
        this.lastMineTarget = null;
        this.lastMineTargetAttempts = 0;
        this.activeMineFilter = filter;
        this.activeMineBox = box;
    }

    /** Clears active mining filter, area box, target blacklist, and consecutive-attempt tracking. */
    public void clear() {
        this.activeMineFilter = null;
        this.activeMineBox = null;
        this.activeMineBlacklist.clear();
        this.lastMineTarget = null;
        this.lastMineTargetAttempts = 0;
    }

    public boolean isActive() {
        return activeMineFilter != null;
    }

    public BlockFilter filter() {
        return activeMineFilter;
    }

    public Area box() {
        return activeMineBox;
    }

    public Set<BlockPos> blacklist() {
        return Collections.unmodifiableSet(activeMineBlacklist);
    }

    public boolean isBlacklisted(BlockPos pos) {
        return pos != null && activeMineBlacklist.contains(pos);
    }

    /**
     * Records a planning attempt against {@code target} and blacklists it if attempted more than twice
     * consecutively. Returns {@code true} when {@code target} was blacklisted.
     */
    public boolean recordAttemptAndCheckBlacklist(BlockPos target) {
        if (target == null) {
            return false;
        }
        if (target.equals(lastMineTarget)) {
            lastMineTargetAttempts++;
        } else {
            lastMineTarget = target.immutable();
            lastMineTargetAttempts = 1;
        }
        if (lastMineTargetAttempts > 2) {
            activeMineBlacklist.add(target.immutable());
            lastMineTarget = null;
            lastMineTargetAttempts = 0;
            return true;
        }
        return false;
    }

    /** Returns true if any non-blacklisted, breakable, unprotected block matching {@code filter} exists in the search bounds. */
    public boolean hasMatchingBlockInArea(
            Minecraft mc,
            BlockPos start,
            BlockFilter filter,
            Area box,
            Predicate<BlockPos> isProtectedAt,
            Predicate<Block> isDisallowedBreak) {
        if (mc == null || mc.level == null || filter == null || start == null) return false;
        int defaultRadius = 48;
        int minX = box != null ? box.minX() : start.getX() - defaultRadius;
        int maxX = box != null ? box.maxX() : start.getX() + defaultRadius;
        int minY = Math.max(mc.level.getMinY(), box != null ? box.minY() : start.getY() - 24);
        int maxY = Math.min(mc.level.getMaxY() - 1, box != null ? box.maxY() : start.getY() + 24);
        int minZ = box != null ? box.minZ() : start.getZ() - defaultRadius;
        int maxZ = box != null ? box.maxZ() : start.getZ() + defaultRadius;

        if (box != null && box.volume() > 500_000L) {
            minX = Math.max(minX, start.getX() - defaultRadius);
            maxX = Math.min(maxX, start.getX() + defaultRadius);
            minY = Math.max(minY, start.getY() - 32);
            maxY = Math.min(maxY, start.getY() + 32);
            minZ = Math.max(minZ, start.getZ() - defaultRadius);
            maxZ = Math.min(maxZ, start.getZ() + defaultRadius);
        }

        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    mp.set(x, y, z);
                    if (isValidMineCandidate(mc, mp, filter, isProtectedAt, isDisallowedBreak)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Finds the nearest matching block within 4.5-block eye reach of {@code endPos}, preferring clear line of sight. */
    public BlockPos findNearestMatchingInReach(
            Minecraft mc,
            BlockFilter filter,
            Area box,
            BlockPos endPos,
            Predicate<BlockPos> isProtectedAt,
            Predicate<Block> isDisallowedBreak) {
        if (mc == null || mc.level == null || filter == null || endPos == null) return null;
        double eyeX = endPos.getX() + 0.5;
        double eyeY = endPos.getY() + 1.62;
        double eyeZ = endPos.getZ() + 0.5;
        Vec3 eye = new Vec3(eyeX, eyeY, eyeZ);

        int minX = endPos.getX() - 5;
        int maxX = endPos.getX() + 5;
        int minY = Math.max(mc.level.getMinY(), endPos.getY() - 4);
        int maxY = Math.min(mc.level.getMaxY() - 1, endPos.getY() + 6);
        int minZ = endPos.getZ() - 5;
        int maxZ = endPos.getZ() + 5;

        if (box != null) {
            minX = Math.max(minX, box.minX());
            maxX = Math.min(maxX, box.maxX());
            minY = Math.max(minY, box.minY());
            maxY = Math.min(maxY, box.maxY());
            minZ = Math.max(minZ, box.minZ());
            maxZ = Math.min(maxZ, box.maxZ());
        }

        BlockPos bestLos = null;
        double bestLosDistSq = Double.MAX_VALUE;
        BlockPos bestAny = null;
        double bestAnyDistSq = Double.MAX_VALUE;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    mp.set(x, y, z);
                    if (!isValidMineCandidate(mc, mp, filter, isProtectedAt, isDisallowedBreak)) continue;

                    double dx = (x + 0.5) - eyeX;
                    double dy = (y + 0.5) - eyeY;
                    double dz = (z + 0.5) - eyeZ;
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > 20.25) continue;

                    if (distSq < bestAnyDistSq) {
                        bestAnyDistSq = distSq;
                        bestAny = mp.immutable();
                    }
                    if (distSq < bestLosDistSq && hasClearSight(mc, eye, mp)) {
                        bestLosDistSq = distSq;
                        bestLos = mp.immutable();
                    }
                }
            }
        }
        return bestLos != null ? bestLos : bestAny;
    }

    private boolean isValidMineCandidate(
            Minecraft mc,
            BlockPos pos,
            BlockFilter filter,
            Predicate<BlockPos> isProtectedAt,
            Predicate<Block> isDisallowedBreak) {
        if (activeMineBlacklist.contains(pos)) return false;
        if (!mc.level.hasChunkAt(pos)) return false;
        var state = mc.level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) return false;
        if (!filter.matches(state)) return false;
        if (isProtectedAt != null && isProtectedAt.test(pos)) return false;
        if (state.getDestroySpeed(mc.level, pos) < 0.0f) return false;
        return isDisallowedBreak == null || !isDisallowedBreak.test(state.getBlock());
    }

    private static boolean hasClearSight(Minecraft mc, Vec3 eye, BlockPos target) {
        if (mc.level == null || mc.player == null) return true;
        var hit = mc.level.clip(new ClipContext(
                eye, Vec3.atCenterOf(target), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS
                || (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target));
    }
}
