package dev.midtown.plan;

/**
 * Pure-Java physical classification of blocks used by {@link BlockView} and {@link MidtownPlanner}.
 * <p>
 * Decoupled from Minecraft's {@code Block}/{@code BlockState} registries so synthetic grid snapshots
 * and A* unit tests can run without bootstrapping Minecraft.
 */
public enum BlockKind {
    /** Empty or zero-collision non-hazardous block (air, tall grass, flowers, torches, signs, etc.). */
    AIR,
    /** Standard full-height solid block (stone, dirt, planks, ores, etc.). */
    SOLID,
    /** Walkable partial-height solid block (slabs, stairs, carpets on solid, etc.). */
    SLAB_OR_STAIR,
    /** 1.5-block-tall collision block (fences, walls, closed fence gates) that blocks stepping over. */
    FENCE_OR_WALL,
    /** Water source or flowing water. */
    WATER,
    /** Lava source or flowing lava. */
    LAVA,
    /** Fire or soul fire. */
    FIRE,
    /** Magma block: solid support that deals damage when stood on without sneaking. */
    MAGMA,
    /** Cactus: solid-ish hazard that damages on touch or standing. */
    CACTUS,
    /** Sweet berry bush: slows movement and deals damage inside. */
    BERRY_BUSH,
    /** Campfire or soul campfire: deals fire damage when stood on. */
    CAMPFIRE,
    /** Wither rose: inflicts wither on contact. */
    WITHER_ROSE,
    /** Cobweb: severe movement slowdown. */
    COBWEB,
    /** Powder snow: sinking and freezing hazard. */
    POWDER_SNOW,
    /** Gravity-affected falling block (sand, gravel, concrete powder, anvil). */
    GRAVEL_OR_SAND,
    /** Climbable block (ladder, vines, scaffolding). */
    LADDER_OR_VINE,
    /** Unbreakable solid block (bedrock, barrier, end portal frame, command block). */
    UNBREAKABLE;

    /** True if a player's hitbox collides with this block as an obstacle. */
    public boolean hasSolidCollision() {
        return switch (this) {
            case SOLID, SLAB_OR_STAIR, FENCE_OR_WALL, MAGMA, CACTUS, CAMPFIRE, GRAVEL_OR_SAND, UNBREAKABLE -> true;
            case AIR, WATER, LAVA, FIRE, BERRY_BUSH, WITHER_ROSE, COBWEB, POWDER_SNOW, LADDER_OR_VINE -> false;
        };
    }

    /** True if a player can safely stand on top of this block. */
    public boolean canStandOn() {
        return switch (this) {
            case SOLID, SLAB_OR_STAIR, MAGMA, GRAVEL_OR_SAND, UNBREAKABLE -> true;
            case AIR, FENCE_OR_WALL, WATER, LAVA, FIRE, CACTUS, BERRY_BUSH, CAMPFIRE,
                 WITHER_ROSE, COBWEB, POWDER_SNOW, LADDER_OR_VINE -> false;
        };
    }

    /** True if a player can occupy this cell without breaking it (collision-free). */
    public boolean isPassable() {
        return !hasSolidCollision();
    }

    /** True if a bridging or pillaring block can be placed at this position. */
    public boolean isReplaceable() {
        return this == AIR || this == WATER;
    }

    /** True if this block is a fluid (water or lava). */
    public boolean isLiquid() {
        return this == WATER || this == LAVA;
    }

    /** True if this block can physically be mined/broken in survival. */
    public boolean isBreakable() {
        return switch (this) {
            case AIR, WATER, LAVA, FIRE, UNBREAKABLE -> false;
            default -> true;
        };
    }

    /** True if this block poses an environmental hazard evaluated by {@link HazardCost}. */
    public boolean isHazard() {
        return switch (this) {
            case LAVA, FIRE, MAGMA, CACTUS, BERRY_BUSH, CAMPFIRE, WITHER_ROSE, COBWEB, POWDER_SNOW -> true;
            default -> false;
        };
    }
}
