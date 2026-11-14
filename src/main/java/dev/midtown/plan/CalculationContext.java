package dev.midtown.plan;

import dev.midtown.api.Area;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable planning parameters and precomputed player capabilities for {@link MidtownPlanner}.
 * <p>
 * Holds precomputed tool break speeds (cached per {@link BlockState}), expendable block count,
 * {@code maxSafeFall}, parkour toggles, {@code RegionRules}-compatible safe-area masks,
 * disallowed break blocks, and temporary stuck-edge/node blacklists.
 */
public final class CalculationContext {

    public static final double DEFAULT_WALK_TICKS_PER_BLOCK = 4.317;
    public static final double DEFAULT_SPRINT_TICKS_PER_BLOCK = 3.56;
    public static final double DEFAULT_DIAGONAL_MULTIPLIER = 1.4142135623730951;
    public static final int DEFAULT_MAX_SAFE_FALL = 3;
    public static final long DEFAULT_TIME_BUDGET_MS = 35L;
    public static final int DEFAULT_MAX_NODES = 120_000;
    public static final int DEFAULT_ITEM_SAVER_THRESHOLD = 5;

    /**
     * Safe-area mask mirroring {@code dev.buildtools.Config.Zone} and {@code RegionRules}.
     *
     * @param name          zone identifier
     * @param area          inclusive 3D block bounds
     * @param allowBreak    whether breaking blocks inside (or within reach of) this area is allowed
     * @param allowPlace    whether placing blocks inside (or within reach of) this area is allowed
     * @param reachEnforced if true, denies breaking/placing anywhere while standing within {@code reachMargin} of {@code area}
     * @param reachMargin   margin in blocks around {@code area} when {@code reachEnforced} is true (typically 6)
     */
    public record ProtectedRegion(
            String name,
            Area area,
            boolean allowBreak,
            boolean allowPlace,
            boolean reachEnforced,
            int reachMargin) {

        public ProtectedRegion {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(area, "area");
            if (reachMargin < 0) throw new IllegalArgumentException("reachMargin must be >= 0");
        }

        public static ProtectedRegion denyAll(String name, Area area) {
            return new ProtectedRegion(name, area, false, false, false, 0);
        }

        public static ProtectedRegion denyAllWithReach(String name, Area area, int reachMargin) {
            return new ProtectedRegion(name, area, false, false, true, reachMargin);
        }

        public boolean blocksBreak(int targetX, int targetY, int targetZ, int standX, int standY, int standZ) {
            if (allowBreak) return false;
            if (area.contains(targetX, targetY, targetZ)) return true;
            return reachEnforced && withinMargin(standX, standY, standZ, reachMargin);
        }

        public boolean blocksPlace(int targetX, int targetY, int targetZ, int standX, int standY, int standZ) {
            if (allowPlace) return false;
            if (area.contains(targetX, targetY, targetZ)) return true;
            return reachEnforced && withinMargin(standX, standY, standZ, reachMargin);
        }

        private boolean withinMargin(int x, int y, int z, int margin) {
            return x >= area.minX() - margin && x <= area.maxX() + margin
                    && y >= area.minY() - margin && y <= area.maxY() + margin
                    && z >= area.minZ() - margin && z <= area.maxZ() + margin;
        }
    }

    /**
     * Predicate for dynamic edge blacklisting (e.g. bridging {@code EdgeTracker} into A*).
     */
    @FunctionalInterface
    public interface EdgeBlacklistPredicate {
        boolean isBlacklisted(int fx, int fy, int fz, int tx, int ty, int tz);
    }

    /** Precomputed inventory tool snapshot (safe to read from background planner threads). */
    private record ToolEntry(ItemStack stack, int efficiencyLevel) {}

    private final boolean allowBreak;
    private final boolean allowPlace;
    private final boolean allowParkour;
    private final boolean allowParkourPlace;
    private final boolean allowSprint;
    private final int maxSafeFall;
    private final int expendableBlockCount;
    private final long timeBudgetMs;
    private final int maxNodesExpanded;
    private final int breakDelayTicks;
    private final int itemSaverThreshold;
    private final double placeCostTicks;
    private final double parkourCostPenalty;
    private final HazardCost hazardCost;

    private final List<ProtectedRegion> protectedRegions;
    private final Set<Block> disallowedBreakBlocks;
    private final Set<BlockKind> disallowedBreakKinds;
    private final Set<Long> blacklistedNodes;
    private final Set<Long> blacklistedEdges;
    private final EdgeBlacklistPredicate dynamicEdgeBlacklist;
    private final EnumMap<BlockKind, Integer> defaultBreakTicksByKind;

    private final List<ToolEntry> tools;
    private final float digSpeedMultiplier;
    private final Map<BlockState, Integer> breakTicksCache = new ConcurrentHashMap<>(256);

    private CalculationContext(Builder b) {
        this.allowBreak = b.allowBreak;
        this.allowPlace = b.allowPlace;
        this.allowParkour = b.allowParkour;
        this.allowParkourPlace = b.allowParkourPlace;
        this.allowSprint = b.allowSprint;
        this.maxSafeFall = b.maxSafeFall;
        this.expendableBlockCount = b.expendableBlockCount;
        this.timeBudgetMs = b.timeBudgetMs;
        this.maxNodesExpanded = b.maxNodesExpanded;
        this.breakDelayTicks = b.breakDelayTicks;
        this.itemSaverThreshold = b.itemSaverThreshold;
        this.placeCostTicks = b.placeCostTicks;
        this.parkourCostPenalty = b.parkourCostPenalty;
        this.hazardCost = b.hazardCost;
        this.protectedRegions = List.copyOf(b.protectedRegions);
        this.disallowedBreakBlocks = b.disallowedBreakBlocks.isEmpty()
                ? Set.of() : Collections.unmodifiableSet(new HashSet<>(b.disallowedBreakBlocks));
        this.disallowedBreakKinds = b.disallowedBreakKinds.isEmpty()
                ? Set.of() : Collections.unmodifiableSet(new HashSet<>(b.disallowedBreakKinds));
        this.blacklistedNodes = b.blacklistedNodes.isEmpty()
                ? Set.of() : Collections.unmodifiableSet(new HashSet<>(b.blacklistedNodes));
        this.blacklistedEdges = b.blacklistedEdges.isEmpty()
                ? Set.of() : Collections.unmodifiableSet(new HashSet<>(b.blacklistedEdges));
        this.dynamicEdgeBlacklist = b.dynamicEdgeBlacklist;
        this.defaultBreakTicksByKind = new EnumMap<>(b.defaultBreakTicksByKind);
        this.tools = List.copyOf(b.tools);
        this.digSpeedMultiplier = b.digSpeedMultiplier;
    }

    /** Returns default offline planning settings (64 expendable blocks, parkour on, 35ms budget). */
    public static CalculationContext defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Captures precomputed tool break speeds (with {@value #DEFAULT_ITEM_SAVER_THRESHOLD} durability reserve),
     * expendable block count, and feather-falling safe fall height from a live {@link LocalPlayer} on the client thread.
     */
    public static Builder fromPlayer(LocalPlayer player) {
        return fromPlayer(player, DEFAULT_ITEM_SAVER_THRESHOLD);
    }

    /**
     * Captures precomputed tool break speeds (skipping damageable tools with {@code remaining <= itemSaverThreshold}
     * when {@code itemSaverThreshold > 0}), expendable block count, and feather-falling safe fall height
     * from a live {@link LocalPlayer} on the client thread.
     */
    public static Builder fromPlayer(LocalPlayer player, int itemSaverThreshold) {
        Objects.requireNonNull(player, "player");
        if (itemSaverThreshold < 0) {
            throw new IllegalArgumentException("itemSaverThreshold must be >= 0");
        }
        Builder b = new Builder();

        Inventory inv = player.getInventory();
        int expendable = 0;
        List<ToolEntry> capturedTools = new ArrayList<>();

        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            if (isExpendableBlockItem(stack)) {
                expendable += stack.getCount();
            }

            if (stack.isDamageableItem()) {
                if (itemSaverThreshold > 0
                        && stack.getMaxDamage() - stack.getDamageValue() <= itemSaverThreshold) {
                    continue;
                }
                int eff = enchantmentLevel(stack, Enchantments.EFFICIENCY);
                capturedTools.add(new ToolEntry(stack.copy(), eff));
            }
        }

        // Compute Haste / Mining Fatigue multiplier.
        float mult = 1.0f;
        if (MobEffectUtil.hasDigSpeed(player)) {
            mult *= 1.0f + (MobEffectUtil.getDigSpeedAmplification(player) + 1) * 0.2f;
        }
        if (player.hasEffect(MobEffects.MINING_FATIGUE)) {
            MobEffectInstance inst = player.getEffect(MobEffects.MINING_FATIGUE);
            int amp = inst != null ? inst.getAmplifier() : 0;
            float penalty = switch (amp) {
                case 0 -> 0.3f;
                case 1 -> 0.09f;
                case 2 -> 0.0027f;
                default -> 0.00081f;
            };
            mult *= penalty;
        }

        // Feather Falling boots boost maxSafeFall safely.
        int safeFall = DEFAULT_MAX_SAFE_FALL;
        ItemStack boots = player.getItemBySlot(EquipmentSlot.FEET);
        if (!boots.isEmpty()) {
            int ff = enchantmentLevel(boots, Enchantments.FEATHER_FALLING);
            if (ff > 0) {
                safeFall = DEFAULT_MAX_SAFE_FALL + Math.min(4, ff);
            }
        }

        b.expendableBlockCount(expendable)
                .maxSafeFall(safeFall)
                .itemSaverThreshold(itemSaverThreshold)
                .tools(capturedTools)
                .digSpeedMultiplier(mult);
        return b;
    }

    public boolean allowBreak() {
        return allowBreak;
    }

    public boolean allowPlace() {
        return allowPlace;
    }

    public boolean allowParkour() {
        return allowParkour;
    }

    public boolean allowParkourPlace() {
        return allowParkourPlace;
    }

    public boolean allowSprint() {
        return allowSprint;
    }

    public int maxSafeFall() {
        return maxSafeFall;
    }

    public int expendableBlockCount() {
        return expendableBlockCount;
    }

    public long timeBudgetMs() {
        return timeBudgetMs;
    }

    public int maxNodesExpanded() {
        return maxNodesExpanded;
    }

    public int breakDelayTicks() {
        return breakDelayTicks;
    }

    public int itemSaverThreshold() {
        return itemSaverThreshold;
    }

    public double placeCostTicks() {
        return placeCostTicks;
    }

    public double parkourCostPenalty() {
        return parkourCostPenalty;
    }

    public HazardCost hazardCost() {
        return hazardCost;
    }

    public double horizontalTicksPerBlock() {
        return allowSprint ? DEFAULT_SPRINT_TICKS_PER_BLOCK : DEFAULT_WALK_TICKS_PER_BLOCK;
    }

    public List<ProtectedRegion> protectedRegions() {
        return protectedRegions;
    }

    public Set<Block> disallowedBreakBlocks() {
        return disallowedBreakBlocks;
    }

    /** Returns true if breaking {@code (tx, ty, tz)} from standing {@code (sx, sy, sz)} is forbidden. */
    public boolean isBreakProtected(
            int tx, int ty, int tz,
            int sx, int sy, int sz,
            BlockView world) {
        if (disallowedBreakKinds.contains(world.kindAt(tx, ty, tz))) {
            return true;
        }
        if (world instanceof WorldSnapshot ws && !disallowedBreakBlocks.isEmpty()) {
            BlockState state = ws.blockStateAt(tx, ty, tz);
            if (disallowedBreakBlocks.contains(state.getBlock())) {
                return true;
            }
        }
        for (ProtectedRegion r : protectedRegions) {
            if (r.blocksBreak(tx, ty, tz, sx, sy, sz)) {
                return true;
            }
        }
        return false;
    }

    /** Returns true if placing at {@code (tx, ty, tz)} from standing {@code (sx, sy, sz)} is forbidden. */
    public boolean isPlaceProtected(int tx, int ty, int tz, int sx, int sy, int sz) {
        for (ProtectedRegion r : protectedRegions) {
            if (r.blocksPlace(tx, ty, tz, sx, sy, sz)) {
                return true;
            }
        }
        return false;
    }

    /** Returns true if standing at {@code (x, y, z)} is blacklisted. */
    public boolean isNodeBlacklisted(int x, int y, int z) {
        return !blacklistedNodes.isEmpty() && blacklistedNodes.contains(BlockPos.asLong(x, y, z));
    }

    /** Returns true if the directed edge {@code from -> to} is temporarily blacklisted. */
    public boolean isEdgeBlacklisted(int fx, int fy, int fz, int tx, int ty, int tz) {
        if (!blacklistedEdges.isEmpty()
                && blacklistedEdges.contains(packEdgeKey(fx, fy, fz, tx, ty, tz))) {
            return true;
        }
        return dynamicEdgeBlacklist != null && dynamicEdgeBlacklist.isBlacklisted(fx, fy, fz, tx, ty, tz);
    }

    /** Packs a directed edge {@code (from -> to)} into a deterministic 64-bit key for blacklisting. */
    public static long packEdgeKey(int fx, int fy, int fz, int tx, int ty, int tz) {
        long a = BlockPos.asLong(fx, fy, fz);
        long b = BlockPos.asLong(tx, ty, tz);
        return a * 0x9E3779B97F4A7C15L ^ Long.rotateLeft(b, 27);
    }

    /** Returns the synthetic default break ticks for {@code kind} (used by {@link GridWorldSnapshot}). */
    public int defaultBreakTicks(BlockKind kind) {
        return defaultBreakTicksByKind.getOrDefault(kind, 15);
    }

    /**
     * Computes and caches the best estimated break time in ticks for a Minecraft {@link BlockState}
     * given the player's precomputed inventory tools and status effects.
     * Returns {@code 0} for passable blocks and {@code -1} for unbreakable blocks.
     */
    public int breakTicks(BlockState state) {
        Integer cached = breakTicksCache.get(state);
        if (cached != null) return cached;

        int ticks = computeBreakTicks(state);
        breakTicksCache.putIfAbsent(state, ticks);
        return ticks;
    }

    private int computeBreakTicks(BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return 0;
        }
        float hardness = state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (hardness < 0.0f) {
            return -1;
        }
        if (hardness == 0.0f) {
            return 1;
        }

        float bestSpeed = 1.0f;
        boolean canHarvest = !state.requiresCorrectToolForDrops();

        for (ToolEntry tool : tools) {
            float speed = tool.stack().getDestroySpeed(state);
            boolean harvests = !state.requiresCorrectToolForDrops() || tool.stack().isCorrectToolForDrops(state);
            if (speed > 1.0f && tool.efficiencyLevel() > 0) {
                speed += tool.efficiencyLevel() * tool.efficiencyLevel() + 1;
            }
            if (harvests && !canHarvest) {
                canHarvest = true;
                bestSpeed = speed;
            } else if (harvests == canHarvest && speed > bestSpeed) {
                bestSpeed = speed;
            }
        }

        bestSpeed *= digSpeedMultiplier;
        if (bestSpeed <= 0.0001f) {
            return 10_000;
        }
        float divisor = canHarvest ? 30.0f : 100.0f;
        float damagePerTick = bestSpeed / hardness / divisor;
        if (damagePerTick >= 1.0f) {
            return 1 + breakDelayTicks;
        }
        return Math.max(1, (int) Math.ceil(1.0f / damagePerTick)) + breakDelayTicks;
    }

    private static int enchantmentLevel(
            ItemStack stack,
            net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key) {
        ItemEnchantments ench = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        for (var entry : ench.entrySet()) {
            if (entry.getKey().is(key)) {
                return entry.getIntValue();
            }
        }
        return 0;
    }

    private static boolean isExpendableBlockItem(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem bi)) return false;
        Block b = bi.getBlock();
        if (b instanceof FallingBlock || b instanceof EntityBlock) return false;
        BlockState state = b.defaultBlockState();
        if (state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS)
                || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS)
                || state.is(BlockTags.SLABS) || state.is(BlockTags.STAIRS)
                || state.is(BlockTags.CAMPFIRES) || state.is(BlockTags.FIRE)) {
            return false;
        }
        BlockKind kind = WorldSnapshot.classify(state);
        if (kind != BlockKind.SOLID) return false;
        return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    /** Fluent builder for {@link CalculationContext}. */
    public static final class Builder {
        private boolean allowBreak = true;
        private boolean allowPlace = true;
        private boolean allowParkour = true;
        private boolean allowParkourPlace = false;
        private boolean allowSprint = true;
        private int maxSafeFall = DEFAULT_MAX_SAFE_FALL;
        private int expendableBlockCount = 64;
        private long timeBudgetMs = DEFAULT_TIME_BUDGET_MS;
        private int maxNodesExpanded = DEFAULT_MAX_NODES;
        private int breakDelayTicks = 5;
        private int itemSaverThreshold = DEFAULT_ITEM_SAVER_THRESHOLD;
        private double placeCostTicks = 6.0;
        private double parkourCostPenalty = 6.0;
        private HazardCost hazardCost = HazardCost.DEFAULT;
        private final List<ProtectedRegion> protectedRegions = new ArrayList<>();
        private final Set<Block> disallowedBreakBlocks = new HashSet<>();
        private final Set<BlockKind> disallowedBreakKinds = new HashSet<>();
        private final Set<Long> blacklistedNodes = new HashSet<>();
        private final Set<Long> blacklistedEdges = new HashSet<>();
        private EdgeBlacklistPredicate dynamicEdgeBlacklist;
        private final EnumMap<BlockKind, Integer> defaultBreakTicksByKind = new EnumMap<>(BlockKind.class);
        private List<ToolEntry> tools = List.of();
        private float digSpeedMultiplier = 1.0f;

        private Builder() {
            defaultBreakTicksByKind.put(BlockKind.AIR, 0);
            defaultBreakTicksByKind.put(BlockKind.WATER, 0);
            defaultBreakTicksByKind.put(BlockKind.LAVA, -1);
            defaultBreakTicksByKind.put(BlockKind.FIRE, 0);
            defaultBreakTicksByKind.put(BlockKind.UNBREAKABLE, -1);
            defaultBreakTicksByKind.put(BlockKind.SOLID, 15);
            defaultBreakTicksByKind.put(BlockKind.SLAB_OR_STAIR, 12);
            defaultBreakTicksByKind.put(BlockKind.FENCE_OR_WALL, 15);
            defaultBreakTicksByKind.put(BlockKind.MAGMA, 15);
            defaultBreakTicksByKind.put(BlockKind.CACTUS, 8);
            defaultBreakTicksByKind.put(BlockKind.BERRY_BUSH, 2);
            defaultBreakTicksByKind.put(BlockKind.CAMPFIRE, 12);
            defaultBreakTicksByKind.put(BlockKind.WITHER_ROSE, 1);
            defaultBreakTicksByKind.put(BlockKind.COBWEB, 12);
            defaultBreakTicksByKind.put(BlockKind.POWDER_SNOW, 5);
            defaultBreakTicksByKind.put(BlockKind.GRAVEL_OR_SAND, 6);
            defaultBreakTicksByKind.put(BlockKind.LADDER_OR_VINE, 4);
        }

        public Builder allowBreak(boolean v) { this.allowBreak = v; return this; }
        public Builder allowPlace(boolean v) { this.allowPlace = v; return this; }
        public Builder allowParkour(boolean v) { this.allowParkour = v; return this; }
        public Builder allowParkourPlace(boolean v) { this.allowParkourPlace = v; return this; }
        public Builder allowSprint(boolean v) { this.allowSprint = v; return this; }
        public Builder maxSafeFall(int v) {
            if (v < 0) throw new IllegalArgumentException("maxSafeFall must be >= 0");
            this.maxSafeFall = v;
            return this;
        }
        public Builder expendableBlockCount(int v) {
            if (v < 0) throw new IllegalArgumentException("expendableBlockCount must be >= 0");
            this.expendableBlockCount = v;
            return this;
        }
        public Builder timeBudgetMs(long v) {
            if (v <= 0) throw new IllegalArgumentException("timeBudgetMs must be > 0");
            this.timeBudgetMs = v;
            return this;
        }
        public Builder maxNodesExpanded(int v) {
            if (v <= 0) throw new IllegalArgumentException("maxNodesExpanded must be > 0");
            this.maxNodesExpanded = v;
            return this;
        }
        public Builder breakDelayTicks(int v) {
            if (v < 0) throw new IllegalArgumentException("breakDelayTicks must be >= 0");
            this.breakDelayTicks = v;
            return this;
        }
        public Builder itemSaverThreshold(int v) {
            if (v < 0) throw new IllegalArgumentException("itemSaverThreshold must be >= 0");
            this.itemSaverThreshold = v;
            return this;
        }
        public Builder placeCostTicks(double v) { this.placeCostTicks = v; return this; }
        public Builder parkourCostPenalty(double v) { this.parkourCostPenalty = v; return this; }
        public Builder hazardCost(HazardCost v) {
            this.hazardCost = Objects.requireNonNull(v, "hazardCost");
            return this;
        }
        public Builder addProtectedRegion(ProtectedRegion region) {
            this.protectedRegions.add(Objects.requireNonNull(region, "region"));
            return this;
        }
        public Builder addProtectedArea(String name, Area area) {
            return addProtectedRegion(ProtectedRegion.denyAll(name, area));
        }
        public Builder addDisallowedBreakBlock(Block block) {
            this.disallowedBreakBlocks.add(Objects.requireNonNull(block, "block"));
            return this;
        }
        public Builder addDisallowedBreakBlocks(Collection<Block> blocks) {
            if (blocks != null) this.disallowedBreakBlocks.addAll(blocks);
            return this;
        }
        public Builder addDisallowedBreakKind(BlockKind kind) {
            this.disallowedBreakKinds.add(Objects.requireNonNull(kind, "kind"));
            return this;
        }
        public Builder blacklistNode(BlockPos pos) {
            Objects.requireNonNull(pos, "pos");
            this.blacklistedNodes.add(pos.asLong());
            return this;
        }
        public Builder blacklistNode(int x, int y, int z) {
            this.blacklistedNodes.add(BlockPos.asLong(x, y, z));
            return this;
        }
        public Builder blacklistEdge(BlockPos from, BlockPos to) {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            this.blacklistedEdges.add(packEdgeKey(
                    from.getX(), from.getY(), from.getZ(),
                    to.getX(), to.getY(), to.getZ()));
            return this;
        }
        public Builder blacklistEdgeKey(long edgeKey) {
            this.blacklistedEdges.add(edgeKey);
            return this;
        }
        public Builder dynamicEdgeBlacklist(EdgeBlacklistPredicate predicate) {
            this.dynamicEdgeBlacklist = predicate;
            return this;
        }
        public Builder defaultBreakTicks(BlockKind kind, int ticks) {
            this.defaultBreakTicksByKind.put(Objects.requireNonNull(kind, "kind"), ticks);
            return this;
        }
        private Builder tools(List<ToolEntry> tools) { this.tools = tools; return this; }
        private Builder digSpeedMultiplier(float mult) { this.digSpeedMultiplier = mult; return this; }

        public CalculationContext build() {
            return new CalculationContext(this);
        }
    }
}
