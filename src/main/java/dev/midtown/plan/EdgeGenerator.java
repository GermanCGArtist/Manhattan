package dev.midtown.plan;

import net.minecraft.core.BlockPos;

/**
 * Neighbor expansion and {@link MoveEdge} generation rules for 3D survival A* in {@link MidtownPlanner}.
 * <p>
 * Evaluates cardinal walks, diagonal walks, ascents, descends, falls, break-throughs, bridges,
 * pillars, and parkour jumps into a reusable {@link NeighborBuffer}.
 */
public final class EdgeGenerator {

    public static final byte EDGE_WALK = 0;
    public static final byte EDGE_ASCEND = 1;
    public static final byte EDGE_DESCEND = 2;
    public static final byte EDGE_FALL = 3;
    public static final byte EDGE_BREAK = 4;
    public static final byte EDGE_BRIDGE = 5;
    public static final byte EDGE_PILLAR = 6;
    public static final byte EDGE_PARKOUR = 7;

    public static final int[][] CARDINALS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };
    public static final int[][] DIAGONALS = {
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private EdgeGenerator() {}

    /** Expands all valid survival traversal edges from {@code (x, y, z)} into {@code out}. */
    public static void generateNeighbors(
            BlockView world,
            CalculationContext ctx,
            int x, int y, int z,
            int placedSoFar,
            boolean stoodOnPlaced,
            NeighborBuffer out) {

        HazardCost hazards = ctx.hazardCost();
        double horizCost = ctx.horizontalTicksPerBlock();
        boolean inWater = world.isWater(x, y, z);
        boolean standableHere = stoodOnPlaced || world.canStandOn(x, y - 1, z);

        // 1. Cardinal flat moves: WalkEdge, BreakThroughEdge, BridgeEdge, DescendEdge, FallEdge, AscendEdge
        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];

            if (!world.isLoaded(nx, y, nz)) {
                out.touchedUnloaded = true;
                continue;
            }

            // --- Flat (dy = 0): WalkEdge / BridgeEdge / BreakThroughEdge ---
            tryCardinalFlat(world, ctx, hazards, x, y, z, nx, nz, horizCost, inWater, standableHere, placedSoFar, out);

            // --- Step up (dy = +1): AscendEdge / BreakThroughEdge ---
            tryAscend(world, ctx, hazards, x, y, z, nx, nz, horizCost, inWater, standableHere, out);

            // --- Step down (dy = -1) or Fall (dy <= -2): DescendEdge / FallEdge / BreakThroughEdge ---
            tryDescendOrFall(world, ctx, hazards, x, y, z, nx, nz, horizCost, out);
        }

        // 2. Diagonal WalkEdge (flat only, strict 2-block corner clearance)
        for (int[] d : DIAGONALS) {
            int nx = x + d[0];
            int nz = z + d[1];
            tryDiagonalWalk(world, ctx, hazards, x, y, z, nx, nz, horizCost * CalculationContext.DEFAULT_DIAGONAL_MULTIPLIER, out);
        }

        // 3. PillarEdge: straight up (0, +1, 0)
        tryPillar(world, ctx, hazards, x, y, z, standableHere, placedSoFar, out);

        // 4. Dig straight down 1 block (BreakThroughEdge (0, -1, 0))
        tryDigDown(world, ctx, hazards, x, y, z, out);

        // 5. ParkourEdge: 1-3 block horizontal gaps
        if (ctx.allowParkour() && standableHere) {
            tryParkour(world, ctx, hazards, x, y, z, horizCost, placedSoFar, out);
        }
    }

    static void tryCardinalFlat(
            BlockView world,
            CalculationContext ctx,
            HazardCost hazards,
            int x, int y, int z,
            int nx, int nz,
            double horizCost,
            boolean inWater,
            boolean standableHere,
            int placedSoFar,
            NeighborBuffer out) {

        if (ctx.isNodeBlacklisted(nx, y, nz) || ctx.isEdgeBlacklisted(x, y, z, nx, y, nz)) {
            return;
        }
        if (!world.isInYBounds(y - 1) || !world.isInYBounds(y + 1)) return;

        boolean feetPass = world.isPassable(nx, y, nz);
        boolean headPass = world.isPassable(nx, y + 1, nz);
        boolean groundStand = world.canStandOn(nx, y - 1, nz);
        boolean targetWater = world.isWater(nx, y, nz);

        if (feetPass && headPass) {
            if (groundStand || (targetWater && !world.isLava(nx, y - 1, nz))) {
                double pen = hazards.nodePenalty(world, ctx, nx, y, nz);
                if (!Double.isInfinite(pen)) {
                    double moveCost = horizCost + (inWater || targetWater ? 4.0 : 0.0) + pen;
                    out.add(nx, y, nz, moveCost, EDGE_WALK, (byte) 0, 0L, Long.MIN_VALUE, placedSoFar);
                }
                return;
            }
            // No ground at (nx, y - 1, nz): try BridgeEdge if replaceable and we have expendable blocks.
            if (standableHere
                    && ctx.allowPlace()
                    && placedSoFar < ctx.expendableBlockCount()
                    && world.isReplaceable(nx, y - 1, nz)
                    && !ctx.isPlaceProtected(nx, y - 1, nz, x, y, z)) {
                double pen = hazards.nodePenalty(world, ctx, nx, y, nz);
                // Ignore ground penalty of the replaceable air/water we're covering, check feet/head + cactus/cliff
                double feetHeadPen = hazards.passThroughPenalty(world, nx, y, nz);
                if (!Double.isInfinite(feetHeadPen)) {
                    double bridgePenalty = Double.isInfinite(pen) ? feetHeadPen : pen;
                    double moveCost = horizCost + ctx.placeCostTicks() + bridgePenalty;
                    out.add(nx, y, nz, moveCost, EDGE_BRIDGE, (byte) 0, BlockPos.asLong(nx, y - 1, nz), Long.MIN_VALUE, placedSoFar + 1);
                }
            }
            return;
        }

        // Blocked at feet and/or head: try BreakThroughEdge (1-2 blocks) onto standable ground.
        if (ctx.allowBreak() && (groundStand || targetWater)) {
            tryBreakThrough(world, ctx, hazards, x, y, z, nx, y, nz,
                    nx, y, nz,
                    nx, y + 1, nz,
                    Integer.MIN_VALUE, 0, 0,
                    2, horizCost, placedSoFar, out);
        }
    }

    static void tryAscend(
            BlockView world,
            CalculationContext ctx,
            HazardCost hazards,
            int x, int y, int z,
            int nx, int nz,
            double horizCost,
            boolean inWater,
            boolean standableHere,
            NeighborBuffer out) {

        int ny = y + 1;
        if (!world.isInYBounds(ny + 1)) return;
        if (ctx.isNodeBlacklisted(nx, ny, nz) || ctx.isEdgeBlacklisted(x, y, z, nx, ny, nz)) return;
        if (!standableHere && !inWater) return;

        // Target ground at (nx, y, nz) must be standable (or water if swimming up in water).
        boolean groundStand = world.canStandOn(nx, y, nz);
        boolean targetWater = world.isWater(nx, ny, nz);
        if (!groundStand && !(inWater && targetWater)) return;

        boolean overPass = world.isPassable(x, y + 2, z);
        boolean feetPass = world.isPassable(nx, ny, nz);
        boolean headPass = world.isPassable(nx, ny + 1, nz);

        if (overPass && feetPass && headPass) {
            double pen = hazards.nodePenalty(world, ctx, nx, ny, nz);
            if (!Double.isInfinite(pen)) {
                double cost = horizCost + 3.5 + (inWater ? 3.0 : 0.0) + pen;
                out.add(nx, ny, nz, cost, EDGE_ASCEND, (byte) 0, 0L, Long.MIN_VALUE, out.currentPlacedFallback);
            }
            return;
        }

        if (ctx.allowBreak() && groundStand) {
            tryBreakThrough(world, ctx, hazards, x, y, z, nx, ny, nz,
                    x, y + 2, z,
                    nx, ny, nz,
                    nx, ny + 1, nz,
                    3, horizCost + 4.0, out.currentPlacedFallback, out);
        }
    }

    static void tryDescendOrFall(
            BlockView world,
            CalculationContext ctx,
            HazardCost hazards,
            int x, int y, int z,
            int nx, int nz,
            double horizCost,
            NeighborBuffer out) {

        if (!world.isInYBounds(y + 1)) return;

        // Step-off column (nx, y, nz) and (nx, y + 1, nz) must be clear (or breakable for 1-block descend).
        boolean stepFeetPass = world.isPassable(nx, y, nz);
        boolean stepHeadPass = world.isPassable(nx, y + 1, nz);

        if (!stepFeetPass || !stepHeadPass) {
            // Try BreakThroughEdge descending 1 block to (nx, y - 1, nz)
            int ny = y - 1;
            if (ctx.allowBreak()
                    && world.isInYBounds(ny - 1)
                    && world.canStandOn(nx, ny - 1, nz)
                    && !ctx.isNodeBlacklisted(nx, ny, nz)
                    && !ctx.isEdgeBlacklisted(x, y, z, nx, ny, nz)) {
                tryBreakThrough(world, ctx, hazards, x, y, z, nx, ny, nz,
                        nx, y + 1, nz,
                        nx, y, nz,
                        nx, ny, nz,
                        3, horizCost + 2.5, out.currentPlacedFallback, out);
            }
            return;
        }

        // Check step-off column hazards at (nx, y, nz) / (nx, y + 1, nz)
        double stepPen = hazards.passThroughPenalty(world, nx, y, nz);
        if (Double.isInfinite(stepPen)) return;

        // 1-block descend (dy = -1)
        int d1y = y - 1;
        if (world.isInYBounds(d1y - 1)) {
            if (world.isPassable(nx, d1y, nz)) {
                if (world.canStandOn(nx, d1y - 1, nz) || (world.isWater(nx, d1y, nz) && !world.isLava(nx, d1y - 1, nz))) {
                    if (!ctx.isNodeBlacklisted(nx, d1y, nz) && !ctx.isEdgeBlacklisted(x, y, z, nx, d1y, nz)) {
                        double pen = hazards.nodePenalty(world, ctx, nx, d1y, nz);
                        if (!Double.isInfinite(pen)) {
                            out.add(nx, d1y, nz, horizCost + 2.0 + pen, EDGE_DESCEND, (byte) 0, 0L, Long.MIN_VALUE, out.currentPlacedFallback);
                        }
                    }
                    return;
                }
            } else if (ctx.allowBreak()
                    && world.canStandOn(nx, d1y - 1, nz)
                    && !ctx.isNodeBlacklisted(nx, d1y, nz)
                    && !ctx.isEdgeBlacklisted(x, y, z, nx, d1y, nz)) {
                // Only (nx, d1y, nz) is solid while (nx, y, nz) and (nx, y+1, nz) are passable
                tryBreakThrough(world, ctx, hazards, x, y, z, nx, d1y, nz,
                        nx, d1y, nz,
                        Integer.MIN_VALUE, 0, 0,
                        Integer.MIN_VALUE, 0, 0,
                        2, horizCost + 2.5, out.currentPlacedFallback, out);
                return;
            } else {
                return;
            }
        }

        // Vertical FallEdge (dy <= -2): scan downward until solid ground or water.
        int maxScan = Math.max(ctx.maxSafeFall(), 32);
        for (int drop = 2; drop <= maxScan; drop++) {
            int ly = y - drop;
            if (!world.isInYBounds(ly - 1) || !world.isLoaded(nx, ly, nz)) {
                if (!world.isLoaded(nx, ly, nz)) out.touchedUnloaded = true;
                break;
            }
            BlockKind at = world.kindAt(nx, ly, nz);
            if (at == BlockKind.LAVA || at == BlockKind.FIRE || at == BlockKind.CACTUS) {
                break;
            }
            if (at == BlockKind.WATER) {
                if (!ctx.isNodeBlacklisted(nx, ly, nz) && !ctx.isEdgeBlacklisted(x, y, z, nx, ly, nz)) {
                    double pen = hazards.nodePenalty(world, ctx, nx, ly, nz);
                    if (!Double.isInfinite(pen)) {
                        double cost = horizCost + drop * 1.6 + pen;
                        out.add(nx, ly, nz, cost, EDGE_FALL, (byte) 1, 0L, Long.MIN_VALUE, out.currentPlacedFallback);
                    }
                }
                break;
            }
            if (!at.isPassable()) {
                break;
            }
            BlockKind below = world.kindAt(nx, ly - 1, nz);
            if (below.canStandOn()) {
                if (drop <= ctx.maxSafeFall()
                        && !ctx.isNodeBlacklisted(nx, ly, nz)
                        && !ctx.isEdgeBlacklisted(x, y, z, nx, ly, nz)) {
                    double pen = hazards.nodePenalty(world, ctx, nx, ly, nz);
                    if (!Double.isInfinite(pen)) {
                        double cost = horizCost + drop * 1.8 + pen;
                        out.add(nx, ly, nz, cost, EDGE_FALL, (byte) 0, 0L, Long.MIN_VALUE, out.currentPlacedFallback);
                    }
                }
                break;
            }
        }
    }

    static void tryDiagonalWalk(
            BlockView world,
            CalculationContext ctx,
            HazardCost hazards,
            int x, int y, int z,
            int nx, int nz,
            double diagCost,
            NeighborBuffer out) {

        if (!world.isLoaded(nx, y, nz)) {
            out.touchedUnloaded = true;
            return;
        }
        if (!world.isInYBounds(y - 1) || !world.isInYBounds(y + 1)) return;
        if (ctx.isNodeBlacklisted(nx, y, nz) || ctx.isEdgeBlacklisted(x, y, z, nx, y, nz)) return;

        if (!world.isPassable(nx, y, nz) || !world.isPassable(nx, y + 1, nz)) return;
        if (!world.canStandOn(nx, y - 1, nz) && !(world.isWater(nx, y, nz) && !world.isLava(nx, y - 1, nz))) {
            return;
        }

        // Both diagonal corner columns must be passable at feet & head and free of hard-blocked hazards.
        if (!world.isPassable(nx, y, z) || !world.isPassable(nx, y + 1, z)) return;
        if (!world.isPassable(x, y, nz) || !world.isPassable(x, y + 1, nz)) return;

        double c1 = hazards.passThroughPenalty(world, nx, y, z);
        if (Double.isInfinite(c1)) return;
        double c2 = hazards.passThroughPenalty(world, x, y, nz);
        if (Double.isInfinite(c2)) return;

        double pen = hazards.nodePenalty(world, ctx, nx, y, nz);
        if (Double.isInfinite(pen)) return;

        double cornerPenalty = (c1 + c2) * 0.25;
        out.add(nx, y, nz, diagCost + pen + cornerPenalty, EDGE_WALK, (byte) 1, 0L, Long.MIN_VALUE, out.currentPlacedFallback);
    }

    static void tryPillar(
            BlockView world,
            CalculationContext ctx,
            HazardCost hazards,
            int x, int y, int z,
            boolean standableHere,
            int placedSoFar,
            NeighborBuffer out) {

        if (!ctx.allowPlace() || placedSoFar >= ctx.expendableBlockCount()) return;
        int ny = y + 1;
        if (!world.isInYBounds(ny + 1) || !world.isInYBounds(y - 1)) return;
        if (ctx.isNodeBlacklisted(x, ny, z) || ctx.isEdgeBlacklisted(x, y, z, x, ny, z)) return;

        if (!standableHere) return;
        if (!world.isReplaceable(x, y, z)) return;
        if (!world.isPassable(x, ny, z) || !world.isPassable(x, ny + 1, z)) return;
        if (ctx.isPlaceProtected(x, y, z, x, y, z)) return;

        double pen = hazards.passThroughPenalty(world, x, ny, z);
        if (Double.isInfinite(pen)) return;

        double cost = 16.0 + ctx.placeCostTicks() * 1.5 + pen;
        out.add(x, ny, z, cost, EDGE_PILLAR, (byte) 0, BlockPos.asLong(x, y, z), Long.MIN_VALUE, placedSoFar + 1);
    }

    static void tryDigDown(
            BlockView world,
            CalculationContext ctx,
            HazardCost hazards,
            int x, int y, int z,
            NeighborBuffer out) {

        if (!ctx.allowBreak()) return;
        int ny = y - 1;
        if (!world.isInYBounds(ny - 1)) return;
        if (ctx.isNodeBlacklisted(x, ny, z) || ctx.isEdgeBlacklisted(x, y, z, x, ny, z)) return;
        if (world.isPassable(x, ny, z)) return;
        if (!world.canStandOn(x, ny - 1, z)) return;

        tryBreakThrough(world, ctx, hazards, x, y, z, x, ny, z,
                x, ny, z,
                Integer.MIN_VALUE, 0, 0,
                Integer.MIN_VALUE, 0, 0,
                2, 4.5, out.currentPlacedFallback, out);
    }

    static void tryParkour(
            BlockView world,
            CalculationContext ctx,
            HazardCost hazards,
            int x, int y, int z,
            double horizCost,
            int placedSoFar,
            NeighborBuffer out) {

        if (!world.isInYBounds(y + 2) || !world.isInYBounds(y - 1)) return;
        // Takeoff requires headroom at y + 2
        if (!world.isPassable(x, y + 2, z)) return;

        for (int[] d : CARDINALS) {
            int dx = d[0];
            int dz = d[1];

            for (int gap = 1; gap <= 3; gap++) {
                int dist = gap + 1;
                int tx = x + dx * dist;
                int tz = z + dz * dist;

                if (!world.isLoaded(tx, y, tz)) {
                    out.touchedUnloaded = true;
                    break;
                }

                // Verify all gap columns are clear across feet/head/jump-arc (y..y+2) and actually a gap
                boolean arcClear = true;
                boolean hasActualGap = false;
                for (int k = 1; k <= gap; k++) {
                    int gx = x + dx * k;
                    int gz = z + dz * k;
                    if (!world.isLoaded(gx, y, gz)) {
                        arcClear = false;
                        out.touchedUnloaded = true;
                        break;
                    }
                    if (!world.isPassable(gx, y, gz)
                            || !world.isPassable(gx, y + 1, gz)
                            || !world.isPassable(gx, y + 2, gz)) {
                        arcClear = false;
                        break;
                    }
                    if (Double.isInfinite(hazards.passThroughPenalty(world, gx, y, gz))) {
                        arcClear = false;
                        break;
                    }
                    if (!world.canStandOn(gx, y - 1, gz)) {
                        hasActualGap = true;
                    }
                }
                if (!arcClear) break; // Further gaps along this ray are also blocked
                if (!hasActualGap) continue;
                // A long jump over a deep drop: a miss means a big fall and climbing back up; bridge instead.
                if (gap >= 2 && gapDeeperThan(world, x, y, z, dx, dz, gap, ctx.maxSafeFall())) break;

                // Landing at same Y (gaps 1..3)
                tryParkourLanding(world, ctx, hazards, x, y, z, tx, y, tz, gap, dist * horizCost, placedSoFar, out);

                // Ascending 1 block on a 1-block parkour gap
                if (gap == 1 && world.isInYBounds(y + 3)) {
                    tryParkourLanding(world, ctx, hazards, x, y, z, tx, y + 1, tz, gap, dist * horizCost + 4.0, placedSoFar, out);
                }
            }
        }
    }

    static void tryParkourLanding(
            BlockView world,
            CalculationContext ctx,
            HazardCost hazards,
            int x, int y, int z,
            int tx, int ty, int tz,
            int gap,
            double baseCost,
            int placedSoFar,
            NeighborBuffer out) {

        if (!world.isInYBounds(ty + 2) || !world.isInYBounds(ty - 1)) return;
        if (ctx.isNodeBlacklisted(tx, ty, tz) || ctx.isEdgeBlacklisted(x, y, z, tx, ty, tz)) return;
        if (!world.isPassable(tx, ty, tz) || !world.isPassable(tx, ty + 1, tz)) return;
        if ((gap >= 2 || ty > y) && !world.isPassable(tx, ty + 2, tz)) return;

        if (world.canStandOn(tx, ty - 1, tz)) {
            double pen = hazards.nodePenalty(world, ctx, tx, ty, tz);
            if (!Double.isInfinite(pen)) {
                double cost = baseCost + ctx.parkourCostPenalty() + gap * 1.5 + pen;
                out.add(tx, ty, tz, cost, EDGE_PARKOUR, (byte) gap, 0L, Long.MIN_VALUE, placedSoFar);
            }
        } else if (ctx.allowParkourPlace()
                && ctx.allowPlace()
                && placedSoFar < ctx.expendableBlockCount()
                && world.isReplaceable(tx, ty - 1, tz)
                && hasAdjacentSolidSupport(world, tx, ty - 1, tz)
                && !ctx.isPlaceProtected(tx, ty - 1, tz, tx, ty, tz)) {
            double pen = hazards.passThroughPenalty(world, tx, ty, tz);
            if (!Double.isInfinite(pen)) {
                double cost = baseCost + ctx.parkourCostPenalty() + ctx.placeCostTicks() + gap * 1.5 + pen;
                out.add(tx, ty, tz, cost, EDGE_PARKOUR, (byte) (gap | 8), BlockPos.asLong(tx, ty - 1, tz), Long.MIN_VALUE, placedSoFar + 1);
            }
        }
    }

    /** True if under any gap column the first standable block is more than {@code maxFall} below {@code y}. */
    static boolean gapDeeperThan(BlockView world, int x, int y, int z, int dx, int dz, int gap, int maxFall) {
        for (int k = 1; k <= gap; k++) {
            int gx = x + dx * k;
            int gz = z + dz * k;
            boolean shallow = false;
            for (int drop = 1; drop <= maxFall; drop++) {
                if (!world.isInYBounds(y - drop - 1)) break;
                if (world.isWater(gx, y - drop, gz) || world.canStandOn(gx, y - drop - 1, gz)) {
                    shallow = true;
                    break;
                }
                if (!world.isPassable(gx, y - drop, gz)) break;
            }
            if (!shallow) return true;
        }
        return false;
    }

    static boolean hasAdjacentSolidSupport(BlockView world, int x, int y, int z) {
        return world.hasSolidCollision(x + 1, y, z)
                || world.hasSolidCollision(x - 1, y, z)
                || world.hasSolidCollision(x, y, z + 1)
                || world.hasSolidCollision(x, y, z - 1)
                || world.hasSolidCollision(x, y - 1, z);
    }

    static void tryBreakThrough(
            BlockView world,
            CalculationContext ctx,
            HazardCost hazards,
            int sx, int sy, int sz,
            int tx, int ty, int tz,
            int b1x, int b1y, int b1z,
            int b2x, int b2y, int b2z,
            int b3x, int b3y, int b3z,
            int maxBreakBlocks,
            double baseMoveCost,
            int placedSoFar,
            NeighborBuffer out) {

        if (ctx.isNodeBlacklisted(tx, ty, tz) || ctx.isEdgeBlacklisted(sx, sy, sz, tx, ty, tz)) return;

        int breakCount = 0;
        long aux1 = Long.MIN_VALUE;
        long aux2 = Long.MIN_VALUE;
        long aux3 = Long.MIN_VALUE;
        double breakCost = 0.0;

        int[][] candidates = {
                {b1x, b1y, b1z},
                {b2x, b2y, b2z},
                {b3x, b3y, b3z}
        };

        for (int[] c : candidates) {
            int bx = c[0];
            if (bx == Integer.MIN_VALUE) continue;
            int by = c[1];
            int bz = c[2];
            if (!world.isLoaded(bx, by, bz)) {
                out.touchedUnloaded = true;
                return;
            }
            if (world.isPassable(bx, by, bz)) {
                double passPen = hazards.passThroughPenalty(world, bx, by, bz);
                if (Double.isInfinite(passPen)) return;
                continue;
            }
            if (!world.kindAt(bx, by, bz).isBreakable()) return;
            if (ctx.isBreakProtected(bx, by, bz, sx, sy, sz, world)) return;
            int ticks = world.breakTicks(bx, by, bz, ctx);
            if (ticks < 0) return;

            double adjPen = hazards.adjacentBreakPenalty(world, bx, by, bz);
            if (Double.isInfinite(adjPen)) return;

            breakCount++;
            if (breakCount > maxBreakBlocks) return;

            long packed = BlockPos.asLong(bx, by, bz);
            if (breakCount == 1) aux1 = packed;
            else if (breakCount == 2) aux2 = packed;
            else aux3 = packed;

            breakCost += ticks + ctx.blockBreakPenalty() + adjPen + CalculationContext.builtBreakPenalty(bx, by, bz, world);
        }

        if (breakCount == 0) return;

        double targetPen = hazards.nodePenalty(world, ctx, tx, ty, tz);
        if (Double.isInfinite(targetPen)) return;

        out.add(tx, ty, tz, baseMoveCost + breakCost + targetPen, EDGE_BREAK, (byte) breakCount, aux1, aux2, aux3, placedSoFar);
    }

    /** True if the incoming edge placed a block directly supporting the destination node. */
    public static boolean isPlacedSupportEdge(byte edgeType, byte edgeFlag) {
        return edgeType == EDGE_BRIDGE
                || edgeType == EDGE_PILLAR
                || (edgeType == EDGE_PARKOUR && (edgeFlag & 8) != 0);
    }

    /** Reusable primitive buffer collecting expanded neighbor transitions from a single A* node. */
    public static final class NeighborBuffer {
        private static final int CAP = 48;
        public final int[] tx = new int[CAP];
        public final int[] ty = new int[CAP];
        public final int[] tz = new int[CAP];
        public final double[] cost = new double[CAP];
        public final byte[] edgeType = new byte[CAP];
        public final byte[] edgeFlag = new byte[CAP];
        public final long[] aux1 = new long[CAP];
        public final long[] aux2 = new long[CAP];
        public final long[] aux3 = new long[CAP];
        public final int[] nextPlaced = new int[CAP];
        public int count;
        int currentPlacedFallback;
        public boolean touchedUnloaded;

        public void clear(int placedSoFar) {
            count = 0;
            currentPlacedFallback = placedSoFar;
            touchedUnloaded = false;
        }

        public void add(int x, int y, int z, double c, byte type, byte flag, long a1, long a2, int placed) {
            add(x, y, z, c, type, flag, a1, a2, Long.MIN_VALUE, placed);
        }

        public void add(int x, int y, int z, double c, byte type, byte flag, long a1, long a2, long a3, int placed) {
            if (count >= CAP) return;
            tx[count] = x;
            ty[count] = y;
            tz[count] = z;
            cost[count] = c;
            edgeType[count] = type;
            edgeFlag[count] = flag;
            aux1[count] = a1;
            aux2[count] = a2;
            aux3[count] = a3;
            nextPlaced[count] = placed;
            count++;
        }
    }
}
