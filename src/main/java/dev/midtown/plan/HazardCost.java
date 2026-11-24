package dev.midtown.plan;

/**
 * Evaluates environmental hazard costs and hard blocks for standing nodes, traversal, and block-breaking.
 * <p>
 * Returns {@link Double#POSITIVE_INFINITY} for hard-blocked hazards (lava, fire, cactus, etc.)
 * and finite high-cost penalties for survivable hazards (magma, berry bushes, cobwebs, powder snow,
 * unprotected cliff edges, and water/gravel-adjacent digging).
 */
public final class HazardCost {

    private static final int COVER_SCAN = 64;

    public static final HazardCost DEFAULT = new Builder().build();

    private final double lavaCost;
    private final double fireCost;
    private final double magmaCost;
    private final double cactusCost;
    private final double berryBushCost;
    private final double campfireCost;
    private final double witherRoseCost;
    private final double cobwebCost;
    private final double powderSnowCost;
    private final double waterNodePenalty;
    private final double cliffEdgePenalty;
    private final double lavaAdjacentBreakPenalty;
    private final double waterAdjacentBreakPenalty;
    private final double fallingAboveBreakPenalty;
    private final double caveNodePenalty;

    private HazardCost(Builder b) {
        this.lavaCost = b.lavaCost;
        this.fireCost = b.fireCost;
        this.magmaCost = b.magmaCost;
        this.cactusCost = b.cactusCost;
        this.berryBushCost = b.berryBushCost;
        this.campfireCost = b.campfireCost;
        this.witherRoseCost = b.witherRoseCost;
        this.cobwebCost = b.cobwebCost;
        this.powderSnowCost = b.powderSnowCost;
        this.waterNodePenalty = b.waterNodePenalty;
        this.cliffEdgePenalty = b.cliffEdgePenalty;
        this.lavaAdjacentBreakPenalty = b.lavaAdjacentBreakPenalty;
        this.waterAdjacentBreakPenalty = b.waterAdjacentBreakPenalty;
        this.fallingAboveBreakPenalty = b.fallingAboveBreakPenalty;
        this.caveNodePenalty = b.caveNodePenalty;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Computes the hazard penalty for occupying/standing at {@code (x, y, z)}.
     * Returns {@link Double#POSITIVE_INFINITY} if the position is hard-blocked.
     */
    public double nodePenalty(BlockView world, CalculationContext ctx, int x, int y, int z) {
        BlockKind feet = world.kindAt(x, y, z);
        BlockKind head = world.kindAt(x, y + 1, z);
        BlockKind below = world.kindAt(x, y - 1, z);

        double cost = 0.0;
        cost += occupyPenalty(feet);
        if (Double.isInfinite(cost)) return cost;
        cost += occupyPenalty(head);
        if (Double.isInfinite(cost)) return cost;
        cost += groundPenalty(below);
        if (Double.isInfinite(cost)) return cost;

        // Cactus damages players horizontally adjacent to feet or head.
        if (isCactusNear(world, x, y, z)) {
            if (Double.isInfinite(cactusCost)) return Double.POSITIVE_INFINITY;
            cost += cactusCost;
        }

        // Open space with solid non-leaf roof overhead (a cave): dark, mobs spawn, lava and drops hide in it.
        // Walking there is costed up so the planner takes the surface when it is a fair detour.
        if (caveNodePenalty > 0.0 && feet.isPassable() && head.isPassable()
                && world.isCaveCovered(x, y + 2, z, COVER_SCAN)) {
            cost += caveNodePenalty;
        }

        // Unprotected cliff / lava-rim edge penalty.
        if (cliffEdgePenalty > 0.0 && below.canStandOn()) {
            cost += cliffEdgePenalty(world, ctx, x, y, z);
        }

        DeathMemory.DeathField deathField = ctx.deathField();
        if (deathField != null && !deathField.isEmpty()) {
            cost += deathField.penaltyAt(x, y, z);
        }

        PeerTraffic.PeerField peers = ctx.peerField();
        if (peers != null && !peers.isEmpty()) {
            cost += peers.penaltyAt(x, y, z);
        }

        return cost;
    }

    /**
     * Returns the penalty for breaking the block at {@code (bx, by, bz)} in {@link MoveEdge.BreakThroughEdge},
     * accounting for adjacent lava, water, and overhead falling blocks (sand/gravel).
     * Returns {@link Double#POSITIVE_INFINITY} if breaking this block would expose lava.
     */
    public double adjacentBreakPenalty(BlockView world, int bx, int by, int bz) {
        double penalty = 0.0;
        boolean hasWater = false;

        // Check 6 neighbors for lava or water (don't expose fluids).
        int[][] dirs = {
                {1, 0, 0}, {-1, 0, 0},
                {0, 0, 1}, {0, 0, -1},
                {0, 1, 0}, {0, -1, 0}
        };
        for (int[] d : dirs) {
            int nx = bx + d[0];
            int ny = by + d[1];
            int nz = bz + d[2];
            if (!world.isLoaded(nx, ny, nz)) continue;
            BlockKind k = world.kindAt(nx, ny, nz);
            if (k == BlockKind.LAVA) {
                if (Double.isInfinite(lavaAdjacentBreakPenalty)) return Double.POSITIVE_INFINITY;
                penalty += lavaAdjacentBreakPenalty;
            } else if (k == BlockKind.WATER && d[1] >= 0) {
                // Water on sides or above can flood into the tunnel.
                hasWater = true;
            }
        }
        if (hasWater) {
            penalty += waterAdjacentBreakPenalty;
        }

        // Check directly above for falling blocks (sand/gravel).
        if (world.isLoaded(bx, by + 1, bz) && world.isFallingBlock(bx, by + 1, bz)) {
            penalty += fallingAboveBreakPenalty;
        }
        return penalty;
    }

    /** Penalty for passing through a non-standing intermediate column (e.g. diagonal corner or parkour air). */
    public double passThroughPenalty(BlockView world, int x, int y, int z) {
        double p = occupyPenalty(world.kindAt(x, y, z));
        if (Double.isInfinite(p)) return p;
        double h = occupyPenalty(world.kindAt(x, y + 1, z));
        if (Double.isInfinite(h)) return h;
        if (isCactusNear(world, x, y, z)) {
            if (Double.isInfinite(cactusCost)) return Double.POSITIVE_INFINITY;
            return p + h + cactusCost;
        }
        return p + h;
    }

    private double occupyPenalty(BlockKind kind) {
        return switch (kind) {
            case LAVA -> lavaCost;
            case FIRE -> fireCost;
            case CACTUS -> cactusCost;
            case BERRY_BUSH -> berryBushCost;
            case CAMPFIRE -> campfireCost;
            case WITHER_ROSE -> witherRoseCost;
            case COBWEB -> cobwebCost;
            case POWDER_SNOW -> powderSnowCost;
            case WATER -> waterNodePenalty;
            default -> 0.0;
        };
    }

    private double groundPenalty(BlockKind below) {
        return switch (below) {
            case LAVA -> lavaCost;
            case FIRE -> fireCost;
            case MAGMA -> magmaCost;
            case CACTUS -> cactusCost;
            case CAMPFIRE -> campfireCost;
            default -> 0.0;
        };
    }

    private static boolean isCactusNear(BlockView world, int x, int y, int z) {
        return world.kindAt(x + 1, y, z) == BlockKind.CACTUS
                || world.kindAt(x - 1, y, z) == BlockKind.CACTUS
                || world.kindAt(x, y, z + 1) == BlockKind.CACTUS
                || world.kindAt(x, y, z - 1) == BlockKind.CACTUS
                || world.kindAt(x + 1, y + 1, z) == BlockKind.CACTUS
                || world.kindAt(x - 1, y + 1, z) == BlockKind.CACTUS
                || world.kindAt(x, y + 1, z + 1) == BlockKind.CACTUS
                || world.kindAt(x, y + 1, z - 1) == BlockKind.CACTUS;
    }

    private double cliffEdgePenalty(BlockView world, CalculationContext ctx, int x, int y, int z) {
        int exposedSides = 0;
        int maxDrop = Math.max(1, ctx.maxSafeFall());
        int[][] cardinals = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] c : cardinals) {
            int nx = x + c[0];
            int nz = z + c[1];
            if (!world.isLoaded(nx, y, nz)) continue;
            // Only an unprotected cliff if the adjacent cell at feet level is passable (no guardrail wall/block)
            if (!world.isPassable(nx, y, nz)) continue;
            if (world.isLava(nx, y, nz) || world.isLava(nx, y - 1, nz)) {
                exposedSides++;
                continue;
            }
            if (world.canStandOn(nx, y - 1, nz) || world.isWater(nx, y - 1, nz)) {
                continue;
            }
            boolean safeDropFound = false;
            for (int dy = 2; dy <= maxDrop + 1; dy++) {
                int cy = y - dy;
                if (!world.isInYBounds(cy)) break;
                BlockKind k = world.kindAt(nx, cy, nz);
                if (k == BlockKind.LAVA) {
                    break;
                }
                if (k == BlockKind.WATER || k.canStandOn()) {
                    safeDropFound = true;
                    break;
                }
            }
            if (!safeDropFound) {
                exposedSides++;
            }
        }
        return exposedSides > 0 ? cliffEdgePenalty * exposedSides : 0.0;
    }

    public double waterNodePenalty() {
        return waterNodePenalty;
    }

    public double cliffEdgePenalty() {
        return cliffEdgePenalty;
    }

    public double caveNodePenalty() {
        return caveNodePenalty;
    }

    public static final class Builder {
        private double lavaCost = Double.POSITIVE_INFINITY;
        private double fireCost = Double.POSITIVE_INFINITY;
        private double magmaCost = 80.0;
        private double cactusCost = Double.POSITIVE_INFINITY;
        private double berryBushCost = 60.0;
        private double campfireCost = Double.POSITIVE_INFINITY;
        private double witherRoseCost = 120.0;
        private double cobwebCost = 70.0;
        private double powderSnowCost = 80.0;
        private double waterNodePenalty = 5.0;
        private double cliffEdgePenalty = 4.0;
        private double lavaAdjacentBreakPenalty = Double.POSITIVE_INFINITY;
        private double waterAdjacentBreakPenalty = 100.0;
        private double fallingAboveBreakPenalty = 25.0;
        private double caveNodePenalty = 20.0;  // avoid unlit covered caves in favor of surface detours

        public Builder lavaCost(double v) { this.lavaCost = v; return this; }
        public Builder fireCost(double v) { this.fireCost = v; return this; }
        public Builder magmaCost(double v) { this.magmaCost = v; return this; }
        public Builder cactusCost(double v) { this.cactusCost = v; return this; }
        public Builder berryBushCost(double v) { this.berryBushCost = v; return this; }
        public Builder campfireCost(double v) { this.campfireCost = v; return this; }
        public Builder witherRoseCost(double v) { this.witherRoseCost = v; return this; }
        public Builder cobwebCost(double v) { this.cobwebCost = v; return this; }
        public Builder powderSnowCost(double v) { this.powderSnowCost = v; return this; }
        public Builder waterNodePenalty(double v) { this.waterNodePenalty = v; return this; }
        public Builder cliffEdgePenalty(double v) { this.cliffEdgePenalty = v; return this; }
        public Builder lavaAdjacentBreakPenalty(double v) { this.lavaAdjacentBreakPenalty = v; return this; }
        public Builder waterAdjacentBreakPenalty(double v) { this.waterAdjacentBreakPenalty = v; return this; }
        public Builder fallingAboveBreakPenalty(double v) { this.fallingAboveBreakPenalty = v; return this; }

        public Builder caveNodePenalty(double v) { this.caveNodePenalty = v; return this; }

        public HazardCost build() {
            return new HazardCost(this);
        }
    }
}
