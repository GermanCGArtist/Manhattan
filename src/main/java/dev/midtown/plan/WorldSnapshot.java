package dev.midtown.plan;

import dev.midtown.api.BlockFilter;
import dev.midtown.api.GoalSpec;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lock-free, immutable snapshot of loaded {@link LevelChunk} block states for off-thread 3D A*.
 * <p>
 * Captured on the client tick thread via {@link #capture(ClientLevel, BlockPos, int)}, copying each
 * non-air {@link LevelChunkSection}'s {@link PalettedContainer} so background planner threads never
 * touch live mutable {@code LevelChunk}s or {@code ClientLevel}.
 */
public final class WorldSnapshot implements BlockView {

    public static final int DEFAULT_CHUNK_RADIUS = 12;

    private static final Map<BlockState, BlockKind> KIND_CACHE = new ConcurrentHashMap<>(1024);
    private static final Map<BlockState, Boolean> CAVE_ROOF_CACHE = new ConcurrentHashMap<>(1024);

    private final int minY;
    private final int maxY;
    private final int minSectionY;
    private final int sectionsCount;
    private final ChunkTable chunks;
    private final BlockState airState;

    private WorldSnapshot(
            int minY,
            int maxY,
            int minSectionY,
            int sectionsCount,
            ChunkTable chunks,
            BlockState airState) {
        this.minY = minY;
        this.maxY = maxY;
        this.minSectionY = minSectionY;
        this.sectionsCount = sectionsCount;
        this.chunks = chunks;
        this.airState = airState;
    }

    /** Captures loaded chunks around {@code center} using {@value #DEFAULT_CHUNK_RADIUS} chunk radius. */
    public static WorldSnapshot capture(ClientLevel level, BlockPos center) {
        return capture(level, center, DEFAULT_CHUNK_RADIUS);
    }

    /**
     * Computes a goal-aware chunk capture radius around {@code start} for {@code goal},
     * clamping spatial goals between {@code 4} and {@value #DEFAULT_CHUNK_RADIUS} chunks
     * and falling back to {@value #DEFAULT_CHUNK_RADIUS} for unbounded or null goals.
     */
    public static int goalChunkRadius(BlockPos start, GoalSpec goal) {
        if (start == null || goal == null) {
            return DEFAULT_CHUNK_RADIUS;
        }
        int tx;
        int tz;
        switch (goal) {
            case GoalSpec.Exact g -> {
                tx = g.pos().getX();
                tz = g.pos().getZ();
            }
            case GoalSpec.Near g -> {
                tx = g.pos().getX();
                tz = g.pos().getZ();
            }
            case GoalSpec.XZ g -> {
                tx = g.x();
                tz = g.z();
            }
            case GoalSpec.LineOfSight g -> {
                tx = g.target().getX();
                tz = g.target().getZ();
            }
            case GoalSpec.RunAway _ -> {
                return DEFAULT_CHUNK_RADIUS;
            }
        }
        long dx = Math.abs((long) tx - start.getX());
        long dz = Math.abs((long) tz - start.getZ());
        long horizDist = Math.max(dx, dz);
        long radius = (horizDist >> 4) + 3L;
        return (int) Math.min(DEFAULT_CHUNK_RADIUS, Math.max(4L, radius));
    }

    /** Captures loaded chunks around {@code start} sized dynamically via {@link #goalChunkRadius(BlockPos, GoalSpec)}. */
    public static WorldSnapshot captureForGoal(ClientLevel level, BlockPos start, GoalSpec goal) {
        return capture(level, start, goalChunkRadius(start, goal));
    }

    /**
     * Captures an immutable snapshot of all loaded {@link ChunkStatus#FULL} chunks within
     * {@code chunkRadius} chunks of {@code center}. Must be called from the client thread.
     */
    public static WorldSnapshot capture(ClientLevel level, BlockPos center, int chunkRadius) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(center, "center");
        int radius = Math.max(1, chunkRadius);

        int centerCx = center.getX() >> 4;
        int centerCz = center.getZ() >> 4;
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        int minSectionY = level.getMinSectionY();
        int sectionsCount = level.getSectionsCount();

        ClientChunkCache chunkSource = level.getChunkSource();
        int side = radius * 2 + 1;
        ChunkTable table = new ChunkTable(Math.max(16, side * side * 2));

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = centerCx + dx;
                int cz = centerCz + dz;
                LevelChunk chunk = chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;

                LevelChunkSection[] srcSections = chunk.getSections();
                @SuppressWarnings("unchecked")
                PalettedContainer<BlockState>[] copied = new PalettedContainer[sectionsCount];
                int limit = Math.min(sectionsCount, srcSections.length);
                for (int i = 0; i < limit; i++) {
                    LevelChunkSection sec = srcSections[i];
                    if (sec != null && !sec.hasOnlyAir()) {
                        copied[i] = sec.getStates().copy();
                    }
                }
                table.put(ChunkPos.pack(cx, cz), copied);
            }
        }

        return new WorldSnapshot(
                minY,
                maxY,
                minSectionY,
                sectionsCount,
                table,
                Blocks.AIR.defaultBlockState());
    }

    /** Number of loaded chunks captured in this snapshot. */
    public int loadedChunkCount() {
        return chunks.size();
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }

    @Override
    public boolean isLoaded(int x, int y, int z) {
        if (y < minY || y >= maxY) return false;
        return chunks.get(ChunkPos.pack(x >> 4, z >> 4)) != null;
    }

    /** Returns the captured {@link BlockState} at {@code (x, y, z)}, or air if unloaded/out-of-bounds. */
    public BlockState blockStateAt(int x, int y, int z) {
        if (y < minY || y >= maxY) return airState;
        PalettedContainer<BlockState>[] sections = chunks.get(ChunkPos.pack(x >> 4, z >> 4));
        if (sections == null) return airState;
        int secIdx = (y >> 4) - minSectionY;
        if (secIdx < 0 || secIdx >= sectionsCount) return airState;
        PalettedContainer<BlockState> container = sections[secIdx];
        if (container == null) return airState;
        return container.get(x & 15, y & 15, z & 15);
    }

    @Override
    public BlockKind kindAt(int x, int y, int z) {
        if (y < minY || y >= maxY) return BlockKind.AIR;
        PalettedContainer<BlockState>[] sections = chunks.get(ChunkPos.pack(x >> 4, z >> 4));
        if (sections == null) return BlockKind.AIR;
        int secIdx = (y >> 4) - minSectionY;
        if (secIdx < 0 || secIdx >= sectionsCount) return BlockKind.AIR;
        PalettedContainer<BlockState> container = sections[secIdx];
        if (container == null) return BlockKind.AIR;
        BlockState state = container.get(x & 15, y & 15, z & 15);
        return classify(state);
    }

    @Override
    public boolean isCaveCovered(int x, int fromY, int z, int maxScan) {
        if (maxScan <= 0 || fromY >= maxY) return false;
        PalettedContainer<BlockState>[] sections = chunks.get(ChunkPos.pack(x >> 4, z >> 4));
        if (sections == null) return false;
        int top = Math.min(maxY, fromY + maxScan);
        int cy = Math.max(minY, fromY);
        int lx = x & 15;
        int lz = z & 15;
        while (cy < top) {
            int secIdx = (cy >> 4) - minSectionY;
            if (secIdx < 0 || secIdx >= sectionsCount) break;
            PalettedContainer<BlockState> container = sections[secIdx];
            if (container == null) {
                cy = ((cy >> 4) + 1) << 4;
                continue;
            }
            int secTop = Math.min(top, ((cy >> 4) + 1) << 4);
            while (cy < secTop) {
                if (isCaveRoof(container.get(lx, cy & 15, lz))) {
                    return true;
                }
                cy++;
            }
        }
        return false;
    }

    private static boolean isCaveRoof(BlockState state) {
        Boolean cached = CAVE_ROOF_CACHE.get(state);
        if (cached != null) return cached;
        boolean roof = !state.isAir()
                && !state.is(BlockTags.LEAVES)
                && !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
        CAVE_ROOF_CACHE.putIfAbsent(state, roof);
        return roof;
    }

    @Override
    public int breakTicks(int x, int y, int z, CalculationContext ctx) {
        if (!isLoaded(x, y, z)) return -1;
        BlockState state = blockStateAt(x, y, z);
        return ctx.breakTicks(state);
    }

    @Override
    public boolean matchesFilter(int x, int y, int z, BlockFilter filter) {
        if (filter == null || !isLoaded(x, y, z)) return false;
        return filter.matches(blockStateAt(x, y, z));
    }

    /** Classifies a Minecraft {@link BlockState} into a {@link BlockKind}, caching per state singleton. */
    public static BlockKind classify(BlockState state) {
        BlockKind cached = KIND_CACHE.get(state);
        if (cached != null) return cached;
        BlockKind computed = computeKind(state);
        KIND_CACHE.putIfAbsent(state, computed);
        return computed;
    }

    private static BlockKind computeKind(BlockState state) {
        if (state.isAir()) return BlockKind.AIR;

        Block b = state.getBlock();
        if (b == Blocks.LAVA) return BlockKind.LAVA;
        if (b == Blocks.WATER) return BlockKind.WATER;
        if (b == Blocks.MAGMA_BLOCK) return BlockKind.MAGMA;
        if (b == Blocks.CACTUS) return BlockKind.CACTUS;
        if (b == Blocks.SWEET_BERRY_BUSH) return BlockKind.BERRY_BUSH;
        if (b == Blocks.WITHER_ROSE) return BlockKind.WITHER_ROSE;
        if (b == Blocks.COBWEB) return BlockKind.COBWEB;
        if (b == Blocks.POWDER_SNOW) return BlockKind.POWDER_SNOW;
        if (state.is(BlockTags.FIRE)) return BlockKind.FIRE;
        if (state.is(BlockTags.CAMPFIRES)) return BlockKind.CAMPFIRE;

        FluidState fluid = state.getFluidState();
        if (fluid.is(FluidTags.LAVA)) return BlockKind.LAVA;

        if (state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) < 0) {
            return BlockKind.UNBREAKABLE;
        }
        if (b instanceof FallingBlock) {
            return BlockKind.GRAVEL_OR_SAND;
        }
        if (state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS)) {
            return BlockKind.FENCE_OR_WALL;
        }
        if (state.is(BlockTags.FENCE_GATES)
                && state.hasProperty(BlockStateProperties.OPEN)
                && !state.getValue(BlockStateProperties.OPEN)) {
            return BlockKind.FENCE_OR_WALL;
        }
        if (state.is(BlockTags.CLIMBABLE)) {
            return BlockKind.LADDER_OR_VINE;
        }
        if (state.is(BlockTags.SLABS) || state.is(BlockTags.STAIRS)) {
            return BlockKind.SLAB_OR_STAIR;
        }
        if (fluid.is(FluidTags.WATER) && !state.blocksMotion()) {
            return BlockKind.WATER;
        }
        if (state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
            return BlockKind.AIR;
        }
        return BlockKind.SOLID;
    }

    /** Primitive open-addressed {@code long -> PalettedContainer<BlockState>[]} hash table. */
    private static final class ChunkTable {
        private long[] keys;
        private PalettedContainer<BlockState>[][] values;
        private int mask;
        private int size;
        private int threshold;

        @SuppressWarnings("unchecked")
        ChunkTable(int initialCapacity) {
            int cap = Integer.highestOneBit(Math.max(16, initialCapacity) - 1) << 1;
            this.keys = new long[cap];
            this.values = new PalettedContainer[cap][];
            this.mask = cap - 1;
            this.threshold = (int) (cap * 0.7f);
        }

        void put(long key, PalettedContainer<BlockState>[] val) {
            if (size >= threshold) rehash();
            int idx = mix(key) & mask;
            while (values[idx] != null) {
                if (keys[idx] == key) {
                    values[idx] = val;
                    return;
                }
                idx = (idx + 1) & mask;
            }
            keys[idx] = key;
            values[idx] = val;
            size++;
        }

        PalettedContainer<BlockState>[] get(long key) {
            int idx = mix(key) & mask;
            while (values[idx] != null) {
                if (keys[idx] == key) return values[idx];
                idx = (idx + 1) & mask;
            }
            return null;
        }

        int size() {
            return size;
        }

        @SuppressWarnings("unchecked")
        private void rehash() {
            long[] oldK = keys;
            PalettedContainer<BlockState>[][] oldV = values;
            int newCap = oldK.length << 1;
            keys = new long[newCap];
            values = new PalettedContainer[newCap][];
            mask = newCap - 1;
            threshold = (int) (newCap * 0.7f);
            for (int i = 0; i < oldK.length; i++) {
                PalettedContainer<BlockState>[] v = oldV[i];
                if (v == null) continue;
                long k = oldK[i];
                int idx = mix(k) & mask;
                while (values[idx] != null) {
                    idx = (idx + 1) & mask;
                }
                keys[idx] = k;
                values[idx] = v;
            }
        }

        private static int mix(long x) {
            x ^= (x >>> 33);
            x *= 0xff51afd7ed558ccdL;
            x ^= (x >>> 33);
            return (int) x;
        }
    }
}
