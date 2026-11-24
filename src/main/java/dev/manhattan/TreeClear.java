package dev.manhattan;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import static dev.manhattan.Values.error;

/**
 * clear_trees([chest]): the selection's trees one by one, nearest first. For each tree its own small selection and a
 * "#sel replace <its blocks> air" with buildInLayers off (Baritone climbs on dirt where it must), then the drops are
 * picked up and the scaffolding is taken down before the next tree. Leaves are left to decay; at the end it waits for
 * them a little and sweeps the area for saplings, sticks and apples. With a chest, a nearly full inventory and the end
 * go into it. Returns what was gained (item -> count, what went into the chest included).
 *
 * A big selection is cut into full-height chunks (the same 32 x 32 columns as clear_area, but no bands in Y; regions
 * apart from each other on their own) and worked one chunk at a time: each chunk is scanned just before it is cut, so work starts
 * at once, and finished chunks are written to the job ledger and skipped after a restart. A tree belongs to the
 * chunk that finds its first block (the whole tree is collected, also the part over a chunk border). Sections of
 * the world that hold only air are skipped by every scan.
 */
final class TreeClear {
    private TreeClear() {}

    static volatile int lastRemainingTrees;

    static int lastRemainingTrees() {
        return lastRemainingTrees;
    }

    /** The blocks a tree is made of (besides every log, wood and stem: BlockTags.LOGS). */
    private static final Set<Block> BODY = Set.of(Blocks.MANGROVE_ROOTS, Blocks.MUDDY_MANGROVE_ROOTS,
            Blocks.MUSHROOM_STEM, Blocks.RED_MUSHROOM_BLOCK, Blocks.BROWN_MUSHROOM_BLOCK,
            Blocks.NETHER_WART_BLOCK, Blocks.WARPED_WART_BLOCK, Blocks.SHROOMLIGHT, Blocks.CREAKING_HEART);
    /** Hanging on or growing near a tree: mined along with the tree they are next to. */
    private static final Set<Block> ATTACHED = Set.of(Blocks.VINE, Blocks.BEE_NEST, Blocks.COCOA, Blocks.PALE_HANGING_MOSS,
            Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT, Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT);
    private static final int TRIES = 3;
    private static final int LEAF_WAIT_S = 60;
    private static final int SWEEP_CELL = 24;   // the end sweep stops once per 24x24 cell that had a tree
    private static final int DUMP_BELOW = 2;    // free slots: below this (inventory about full), a chest trip between trees
    private static final double PICK_BASE = 5;    // a drop is worth a walk of this many blocks ...
    private static final double PICK_PER_ITEM = 2; // ... plus this many per item in its cluster
    private static final int SCAFFOLD_LOW = 24;     // fewer scaffolding blocks than this in the inventory: take pillars down for material
    private static final int MAX_PENDING = 160;     // more pillar blocks than this standing: take the stale ones down
    private static final int REUSE_R = 16;          // pillars this close to a tree still to be cut are kept for it
    private static final int MAX_MARK = 1500;      // more new scaffolding than this at once cannot be pillars
    private static final int SCAFFOLD_NEAR = 32;   // only scaffolding this close to the tree is taken down between trees
    private static final long SPOT_AGE_MS = 4 * 60_000L;   // drops despawn after 5 min: older spots are not swept

    static boolean body(BlockState s) {
        return s.is(BlockTags.LOGS) || BODY.contains(s.getBlock());
    }

    private static final class Tree {
        final List<BlockPos> blocks = new ArrayList<>();
        final BlockPos seed;   // the lowest block found first
        int tries;

        Tree(BlockPos seed) {
            this.seed = seed;
        }
    }

    private static boolean inside(List<int[]> boxes, int x, int y, int z) {
        for (int[] b : boxes) {
            if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]) return true;
        }
        return false;
    }

    private static boolean tree(Level level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return body(s) || ATTACHED.contains(s.getBlock());
    }

    /**
     * Calls {@code f} for every block of the boxes (each grown by {@code padH} sideways and {@code padUp} upwards) in a
     * loaded chunk, skipping every 16 x 16 x 16 section that holds only air without looking at its blocks. Gives the
     * game a tick now and then (about every 40000 blocks looked at).
     */
    static void forBlocks(Interp in, Level level, List<int[]> boxes, int padH, int padUp, BiConsumer<BlockPos.MutableBlockPos, BlockState> f) {
        long work = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int[] b : boxes) {
            int x0 = b[0] - padH, x1 = b[3] + padH, z0 = b[2] - padH, z1 = b[5] + padH, y0 = b[1], y1 = b[4] + padUp;
            for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
                for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                    LevelChunk ch = level.getChunkSource().getChunkNow(cx, cz);
                    if (ch == null) continue;
                    for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                        int idx = ch.getSectionIndex(sy << 4);
                        if (idx < 0 || idx >= ch.getSectionsCount()) continue;
                        if (ch.getSection(idx).hasOnlyAir()) {
                            if ((work += 16) >= 40000) { work = 0; in.yieldTick(); }
                            continue;
                        }
                        int ax = Math.max(x0, cx << 4), bx = Math.min(x1, (cx << 4) + 15);
                        int az = Math.max(z0, cz << 4), bz = Math.min(z1, (cz << 4) + 15);
                        int ay = Math.max(y0, sy << 4), by = Math.min(y1, (sy << 4) + 15);
                        for (int x = ax; x <= bx; x++) {
                            for (int z = az; z <= bz; z++) {
                                for (int y = ay; y <= by; y++) {
                                    if (++work >= 40000) { work = 0; in.yieldTick(); }
                                    m.set(x, y, z);
                                    f.accept(m, ch.getBlockState(m));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The trees whose first block (found in scan order) lies in {@code scanBoxes}: logs etc. that touch (also
     * diagonally), plus vines and bee nests near them. A tree is collected whole inside {@code allBoxes}; what is in
     * {@code seen} already belongs to a tree found before.
     */
    private static List<Tree> scan(Interp in, Level level, List<int[]> scanBoxes, List<int[]> boxes, Set<BlockPos> seen) {
        List<Tree> out = new ArrayList<>();
        forBlocks(in, level, scanBoxes, 0, 0, (m, state) -> {
            if (!body(state) || seen.contains(m)) return;
            int x = m.getX(), y = m.getY(), z = m.getZ();
            {
                {
                    {
                        Tree t = new Tree(m.immutable());
                        ArrayDeque<BlockPos> todo = new ArrayDeque<>();
                        todo.add(t.seed);
                        seen.add(t.seed);
                        int x0 = x, x1 = x, y0 = y, y1 = y, z0 = z, z1 = z;
                        while (!todo.isEmpty()) {
                            BlockPos p = todo.poll();
                            t.blocks.add(p);
                            x0 = Math.min(x0, p.getX()); x1 = Math.max(x1, p.getX());
                            y0 = Math.min(y0, p.getY()); y1 = Math.max(y1, p.getY());
                            z0 = Math.min(z0, p.getZ()); z1 = Math.max(z1, p.getZ());
                            for (int dx = -1; dx <= 1; dx++) {
                                for (int dy = -1; dy <= 1; dy++) {
                                    for (int dz = -1; dz <= 1; dz++) {
                                        BlockPos q = p.offset(dx, dy, dz);
                                        if (seen.contains(q) || !inside(boxes, q.getX(), q.getY(), q.getZ())) continue;
                                        if (!level.hasChunkAt(q) || !body(level.getBlockState(q))) continue;
                                        seen.add(q);
                                        todo.add(q);
                                    }
                                }
                            }
                        }
                        // vines hang far down from the leaves, bee nests sit beside the trunk
                        for (int ax = x0 - 3; ax <= x1 + 3; ax++) {
                            for (int az = z0 - 3; az <= z1 + 3; az++) {
                                for (int ay = y0 - 12; ay <= y1 + 1; ay++) {
                                    if (!inside(boxes, ax, ay, az)) continue;
                                    BlockPos q = new BlockPos(ax, ay, az);
                                    if (seen.contains(q) || !level.hasChunkAt(q) || !ATTACHED.contains(level.getBlockState(q).getBlock())) continue;
                                    seen.add(q);
                                    t.blocks.add(q);
                                }
                            }
                        }
                        out.add(t);
                    }
                }
            }
        });
        return out;
    }

    private static Map<Item, Integer> inventory(LocalPlayer p) {
        Map<Item, Integer> m = new HashMap<>();
        for (int k = 0; k < 36; k++) {
            ItemStack st = p.getInventory().getItem(k);
            if (!st.isEmpty()) m.merge(st.getItem(), st.getCount(), Integer::sum);
        }
        ItemStack off = p.getOffhandItem();
        if (!off.isEmpty()) m.merge(off.getItem(), off.getCount(), Integer::sum);
        return m;
    }

    private static int scaffoldCount(LocalPlayer p) {
        int n = 0;
        Map<Item, Integer> inv = inventory(p);
        for (Item it : THROWAWAY_ITEMS) n += inv.getOrDefault(it, 0);
        return n;
    }

    private static int freeSlots(LocalPlayer p) {
        int n = 0;
        for (int k = 0; k < 36; k++) if (p.getInventory().getItem(k).isEmpty()) n++;
        return n;
    }

    private static String id(Block b) {
        var k = BuiltInRegistries.BLOCK.getKey(b);
        return k.getNamespace().equals("minecraft") ? k.getPath() : k.toString();
    }

    private static LocalPlayer player(int line) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) throw error("Not in a world.", line);
        return p;
    }

    private static final List<Item> THROWAWAY_ITEMS = List.of(
            net.minecraft.world.item.Items.DIRT,
            net.minecraft.world.item.Items.COBBLESTONE,
            net.minecraft.world.item.Items.NETHERRACK,
            net.minecraft.world.item.Items.STONE,
            net.minecraft.world.item.Items.COBBLED_DEEPSLATE,
            net.minecraft.world.item.Items.SANDSTONE);

    private static final int AROUND = 5;   // how far from the tree Baritone may stand on a pillar

    private static final Set<Block> GROUND = Set.of(Blocks.GRASS_BLOCK, Blocks.MYCELIUM, Blocks.PODZOL, Blocks.DIRT_PATH, Blocks.FARMLAND);

    /**
     * Scaffolding kinds (Baritone's throwaway blocks) in the boxes, each grown by AROUND sideways and 3 up.
     * except == null: a snapshot, which also holds the grass (grass under a pillar turns into dirt: that is ground,
     * not scaffolding). Otherwise: the scaffolding blocks that are not in the snapshot, i.e. new.
     */
    private static Set<BlockPos> throwaway(Interp in, Level level, List<int[]> boxes, Set<BlockPos> except) {
        Set<Block> kinds = new HashSet<>();
        for (Item it : THROWAWAY_ITEMS) {
            if (it instanceof net.minecraft.world.item.BlockItem bi) kinds.add(bi.getBlock());
        }
        Set<BlockPos> out = new HashSet<>();
        forBlocks(in, level, boxes, AROUND, 3, (m, state) -> {
            Block k = state.getBlock();
            if (except == null ? kinds.contains(k) || GROUND.contains(k) : kinds.contains(k) && !except.contains(m)) out.add(m.immutable());
        });
        return out;
    }

    /** New scaffolding in the boxes goes on BuildTools' list, so collect_scaffold() takes it down; returns how many. */
    private static int markNew(Interp in, Level level, List<int[]> boxes, Set<BlockPos> before) {
        Set<BlockPos> fresh = throwaway(in, level, boxes, before);
        fresh.removeIf(b -> !ScaffoldZones.inAny(b));   // only inside the zones around the cut trees: anything else is terrain or not ours
        if (fresh.size() > MAX_MARK) {   // that is the terrain, not pillars: marking it would have the bot dig the hill away
            Util.msg("clear_trees: " + fresh.size() + " 'new' scaffolding blocks is implausible, ignoring them.");
            return 0;
        }
        for (BlockPos b : fresh) dev.buildtools.Api.addScaffold(b, level.getBlockState(b).getBlock());
        return fresh.size();
    }

    /** Leaves in the selection that no longer touch a log (within 6) and will fall apart. */
    private static int decaying(Interp in, Level level, List<int[]> boxes) {
        int[] n = new int[1];
        forBlocks(in, level, boxes, 0, 0, (m, s) -> {
            if (s.getBlock() instanceof LeavesBlock && s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT)
                    && s.hasProperty(LeavesBlock.DISTANCE) && s.getValue(LeavesBlock.DISTANCE) >= LeavesBlock.DECAY_DISTANCE) n[0]++;
        });
        return n[0];
    }

    // ------------------------------------------------------------------ the run

    private static final class Run {
        final Interp in;
        final int line;
        final BlockPos chest;
        final String chestText;
        final boolean allowMidRunDump;
        final Map<Item, Integer> stored = new HashMap<>();
        int dumps;
        final Map<String, Long> phases = new java.util.TreeMap<>();   // where the time goes, for the report at the end

        void lap(String phase, long since) {
            phases.merge(phase, System.nanoTime() - since, Long::sum);
        }

        Run(Interp in, int line, BlockPos chest, String chestText, boolean allowMidRunDump) {
            this.in = in;
            this.line = line;
            this.chest = chest;
            this.chestText = chestText;
            this.allowMidRunDump = allowMidRunDump;
        }

        void pickup() {
            in.await(() -> new Actions.Feature(() -> { dev.loadchest.LoadChestMod.pickupNow(); return null; },
                    dev.loadchest.LoadChestMod::pickingUp, null, () -> true), "pickup", line);
        }

        /** Takes down the scaffolding near c (all of it when c is null); pillars elsewhere stay on the list for later. */
        void scaffold(BlockPos c) {
            var far = c == null ? null : dev.buildtools.Api.stashScaffoldFarFrom(c, SCAFFOLD_NEAR);
            try {
                if (dev.buildtools.Api.scaffoldCount() == 0) return;
                long t0 = System.nanoTime();
                in.await(() -> new Actions.Feature(dev.buildtools.Api::collectScaffoldNow, dev.buildtools.Api::collectingScaffold,
                        dev.buildtools.Api::stopCollectingScaffold, () -> true), "collect_scaffold", line);
                lap("scaffold teardown", t0);
            } finally {
                dev.buildtools.Api.restoreScaffold(far);
            }
        }

        /**
         * Lazy scaffold teardown. Pillars stay up between trees (taking them down after every tree and building them again
         * for the next one is most of the time spent). They come down when
         *  - the scaffolding blocks in the inventory run low (needed as material): everything near the bot,
         *  - more than MAX_PENDING stand (the list and the walk back to them would grow): near the bot, but not those that
         *    are within REUSE_R of a tree that is still to be cut (they are likely to be used again),
         *  - a chunk is finished ({@code chunkEnd}): all of them, the next trees are elsewhere.
         * Pillars not taken now stay on the list; the end of the run takes down what is left.
         */
        /** Recorded scaffolding outside every tree zone is dropped from the list: it stays where it is (no digging in caves for it). */
        void forgetOutsideZones() {
            int n = 0;
            for (BlockPos b : dev.buildtools.Api.scaffoldSpots()) {
                if (!ScaffoldZones.inAny(b)) {
                    dev.buildtools.Api.forgetScaffold(b);
                    n++;
                }
            }
            if (n > 0) Util.msg("clear_trees: leaving " + n + " scaffolding blocks outside the tree zones alone.");
        }

        /** Colours of the drawn zones: orange while scaffolding still stands inside, green once it is gone. */
        void refreshZones(Level level) {
            var spots = dev.buildtools.Api.scaffoldSpots();
            List<BlockPos> standing = new ArrayList<>();
            for (BlockPos b : spots) {
                if (dev.buildtools.Api.isScaffold(level, b)) standing.add(b);
            }
            for (ScaffoldZones.Zone z : ScaffoldZones.all()) {
                boolean p = false;
                for (BlockPos b : standing) if (z.contains(b)) { p = true; break; }
                z.pending = p;
            }
        }

        void tidy(Level level, List<Tree> open, boolean chunkEnd) {
            forgetOutsideZones();
            try {
                tidy0(level, open, chunkEnd);
            } finally {
                refreshZones(level);
            }
        }

        private void tidy0(Level level, List<Tree> open, boolean chunkEnd) {
            int pending = dev.buildtools.Api.scaffoldCount();
            if (pending == 0) return;
            BlockPos me = player(line).blockPosition();
            boolean low = scaffoldCount(player(line)) < SCAFFOLD_LOW;
            if (!chunkEnd && !low && pending <= MAX_PENDING) return;
            double near2 = (double) SCAFFOLD_NEAR * SCAFFOLD_NEAR, reuse2 = (double) REUSE_R * REUSE_R;
            var keep = dev.buildtools.Api.stashScaffoldIf(b -> {
                if (chunkEnd) return false;
                if (b.distSqr(me) > near2) return true;   // far from here: later
                if (low) return false;
                for (Tree t : open) {
                    double dx = b.getX() - t.seed.getX(), dz = b.getZ() - t.seed.getZ();
                    if (dx * dx + dz * dz <= reuse2) return true;   // a tree is still to be cut near it
                }
                return false;
            });
            try {
                if (dev.buildtools.Api.scaffoldCount() == 0) return;
                Util.msg("clear_trees: taking down " + dev.buildtools.Api.scaffoldCount() + " pillar blocks ("
                        + (chunkEnd ? "chunk done" : low ? "running low on blocks" : "too many standing") + "), keeping " + keep.size() + ".");
                long t0 = System.nanoTime();
                in.await(() -> new Actions.Feature(dev.buildtools.Api::collectScaffoldNow, dev.buildtools.Api::collectingScaffold,
                        dev.buildtools.Api::stopCollectingScaffold, () -> true), "collect_scaffold", line);
                lap("scaffold teardown", t0);
            } finally {
                dev.buildtools.Api.restoreScaffold(keep);
            }
            collect(level, player(line).blockPosition());   // what the scaffolding dropped
        }

        /**
         * Picks up the drops near {@code around} that are worth the walk: a drop (or a cluster of them) is fetched when
         * it is closer than PICK_BASE + PICK_PER_ITEM per item; the rest stays where it is (and is not walked back for).
         */
        void collect(Level level, BlockPos around) {
            long t0 = System.nanoTime();
            try {
                collect0(level, around);
            } finally {
                lap("drops", t0);
            }
        }

        private void collect0(Level level, BlockPos around) {
            Set<Integer> tried = new HashSet<>();
            for (int trip = 0; trip < 8; trip++) {
                LocalPlayer p = player(line);
                List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, new AABB(around).inflate(14, 10, 14),
                        e -> e.isAlive() && !tried.contains(e.getId()));
                ItemEntity best = null;
                double bd = Double.MAX_VALUE;
                for (ItemEntity e : items) {
                    double d = e.distanceToSqr(p);
                    if (d < bd) { bd = d; best = e; }
                }
                if (best == null) return;
                int n = 0;
                for (ItemEntity e : items) if (e.distanceToSqr(best) <= 9) n += e.getItem().getCount();
                if (Math.sqrt(bd) > PICK_BASE + PICK_PER_ITEM * n) return;
                tried.add(best.getId());
                if (bd < 1.5 * 1.5) continue;   // within reach: picked up by itself
                BlockPos at = best.blockPosition();
                String go = "goto " + at.getX() + " " + at.getY() + " " + at.getZ();
                try {
                    in.await(() -> new Actions.BaritoneCommand(go), go, line);
                } catch (Values.ScriptError e) {
                    // no way to it: leave it
                }
            }
        }

        /** Into the chest, keeping the scaffolding blocks; what went in is counted per item. */
        void dump() {
            LocalPlayer p = player(line);
            Map<Item, Integer> before = inventory(p);
            List<String> added = new ArrayList<>();
            var rules = dev.loadchest.LoadChestMod.keepRules();
            for (Item it : THROWAWAY_ITEMS) {
                String n = ChestOp.name(it);
                if (before.containsKey(it) && !rules.containsKey(n)) {
                    rules.put(n, 128);
                    added.add(n);
                }
            }
            if (!added.isEmpty()) dev.loadchest.LoadChestMod.saveSettings();
            long t0 = System.nanoTime();
            try {
                ChestOp op = new ChestOp(chestText, ChestOp.Kind.DUMP, null, -1, false);
                in.await(() -> new Actions.Chest(op, chest), "dump", line);
                dumps++;
            } catch (Values.ScriptError e) {
                Util.msg("clear_trees: could not empty into the chest (" + e.getMessage() + "), going on.");
            } finally {
                lap("chest", t0);
                if (!added.isEmpty()) {
                    added.forEach(rules::remove);
                    dev.loadchest.LoadChestMod.saveSettings();
                }
            }
            Map<Item, Integer> after = inventory(player(line));
            before.forEach((it, n) -> {
                int gone = n - after.getOrDefault(it, 0);
                if (gone > 0) stored.merge(it, gone, Integer::sum);
            });
        }

        void dumpIfFull() {
            if (allowMidRunDump && chest != null && freeSlots(player(line)) < DUMP_BELOW) dump();
        }
    }

    /** Are all the game's chunks under this column there (the client has them)? */
    private static boolean loaded(Level level, ChunkGrid.ColumnBox c) {
        for (int cx = c.minX() >> 4; cx <= c.maxX() >> 4; cx++) {
            for (int cz = c.minZ() >> 4; cz <= c.maxZ() >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) return false;
            }
        }
        return true;
    }

    static Object run(Interp in, int line, BlockPos chest, String chestText) {
        return run(in, line, chest, chestText, null, null, true, null, 0);
    }

    static Object run(
            Interp in,
            int line,
            BlockPos chest,
            String chestText,
            List<int[]> explicitBoxes,
            String taskIdPrefix,
            boolean allowMidRunDump) {
        return run(in, line, chest, chestText, explicitBoxes, taskIdPrefix, allowMidRunDump, null, 0);
    }

    static Object run(
            Interp in,
            int line,
            BlockPos chest,
            String chestText,
            List<int[]> explicitBoxes,
            String taskIdPrefix,
            boolean allowMidRunDump,
            dev.manhattan.fleet.ChunkLeaseProvider leaseProvider,
            int explicitChunkSize) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        LocalPlayer p = player(line);
        if (level == null) throw error("Not in a world.", line);
        List<int[]> boxes;
        if (explicitBoxes != null) {
            boxes = new ArrayList<>(explicitBoxes.size());
            for (int[] b : explicitBoxes) {
                if (b != null && b.length >= 6) {
                    boxes.add(new int[]{
                            Math.min(b[0], b[3]), Math.min(b[1], b[4]), Math.min(b[2], b[5]),
                            Math.max(b[0], b[3]), Math.max(b[1], b[4]), Math.max(b[2], b[5])
                    });
                }
            }
        } else {
            boxes = Areas.read();
        }
        if (boxes.isEmpty()) throw error("There is no selection. Select the forest first: area(\"name\") or select(corner1, corner2).", line);

        lastRemainingTrees = 0;
        Run r = new Run(in, line, chest, chestText, allowMidRunDump);
        ScaffoldZones.clear();
        boolean autoSweep = dev.buildtools.TreeZoneOptions.setAutoSweep(false);   // it would take scaffolding down anywhere, not only in the zones
        boolean prevSuppressed = dev.buildtools.TreeZoneOptions.setAutoSweepSuppressed(true);
        var prevAllowed = dev.buildtools.Api.scaffoldAllowedZone;
        dev.buildtools.Api.scaffoldAllowedZone = ScaffoldZones::inAny;
        try {
            Map<Item, Integer> start = inventory(p);
            boolean throwaway = false;
            for (Item it : THROWAWAY_ITEMS) if (start.containsKey(it)) throwaway = true;
            if (!throwaway) Util.msg("clear_trees: you carry no scaffolding blocks (e.g. dirt): tall trees may be out of reach.");

            boolean fleetMode = leaseProvider != null;
            boolean chunked = fleetMode || Config.cfg.chunkEnabled;
            int size = fleetMode
                    ? (explicitChunkSize > 0 ? explicitChunkSize : Config.cfg.chunkSize)
                    : (chunked ? Config.cfg.chunkSize : 1 << 20);
            int gap = fleetMode ? -1 : (chunked ? Config.cfg.chunkGap : -1);
            // Full-height columns, no bands in Y: trees stand on the ground, and with bands the bot walked the whole footprint
            // once per band (a 250 high selection = dozens of passes over mostly empty sky). Air-only sections are skipped by the scan.
            List<ChunkGrid.ColumnBox> rawChunks = ChunkGrid.split(boxes, size, 0, gap);
            List<ChunkGrid.ColumnBox> chunks = fleetMode
                    ? rawChunks
                    : ChunkGrid.orderSerpentine(rawChunks, p.blockPosition());
            Map<String, ChunkGrid.ColumnBox> chunkByKey = new LinkedHashMap<>();
            if (fleetMode) {
                for (ChunkGrid.ColumnBox c : chunks) {
                    chunkByKey.put(ChunkRunner.chunkKey(taskIdPrefix, c), c);
                }
            }
            // finished chunks of an earlier run (same selection, same sizes) are skipped; one chunk is not worth a ledger entry
            String signature = "clear_trees:" + size + "y0g" + gap;
            for (int[] b : boxes) signature += ":" + b[0] + "," + b[1] + "," + b[2] + "-" + b[3] + "," + b[4] + "," + b[5];
            Jobs.Node actionNode = null;
            if (chunks.size() > 1) {
                actionNode = (taskIdPrefix != null && !taskIdPrefix.isBlank())
                        ? Jobs.taskAction(taskIdPrefix, "clear_trees")
                        : Jobs.action("clear_trees", line, "", QueueMod.runner != null && QueueMod.runner.running());
            }
            Ledger ledger = Jobs.ledger();

            Set<BlockPos> ground = throwaway(in, level, boxes, null);   // what is there before: never taken for scaffolding
            Set<BlockPos> seen = new HashSet<>();
            List<Tree> trees = new ArrayList<>();
            int total = 0, cleared = 0, gaveUp = 0, chunkNo = 0, skipped = 0, quickFails = 0;
            List<BlockPos> spots = new ArrayList<>();
            Map<BlockPos, Long> spotTimes = new HashMap<>();
            if (fleetMode) {
                Util.msg("clear_trees: " + chunks.size() + " fleet chunk" + (chunks.size() == 1 ? "" : "s") + ", claiming from Fleet Server.");
            } else {
                Util.msg("clear_trees: " + (chunks.size() == 1 ? "scanning the selection." : chunks.size() + " chunks, scanned and cut one after the other."));
            }
            int localIdx = 0;
            while (true) {
                ChunkGrid.ColumnBox chunk;
                String leaseKey = null;
                if (fleetMode) {
                    dev.manhattan.fleet.ChunkLeaseProvider.ClaimResult claim = leaseProvider.pollNextChunk();
                    if (claim == null || claim.status() == dev.manhattan.fleet.ChunkLeaseProvider.ClaimStatus.WAITING) {
                        in.await(() -> new Actions.Wait(0.1), "wait for fleet chunk lease", line);
                        continue;
                    }
                    if (claim.status() == dev.manhattan.fleet.ChunkLeaseProvider.ClaimStatus.NO_WORK) {
                        break;
                    }
                    leaseKey = claim.chunkKey();
                    chunk = chunkByKey.get(leaseKey);
                    if (chunk == null) {
                        // If the leased chunk belongs to a different task in the same Fleet Project,
                        // finish this task cleanly without marking the other task's chunk as FAILED.
                        if (taskIdPrefix != null && leaseKey != null && !leaseKey.startsWith(taskIdPrefix + "/")) {
                            break;
                        }
                        leaseProvider.reportChunk(leaseKey, dev.manhattan.fleet.wire.FleetWire.OUTCOME_FAILED, "Unknown chunk key");
                        continue;
                    }
                } else {
                    if (localIdx >= chunks.size()) break;
                    chunk = chunks.get(localIdx++);
                }
                chunkNo++;
                Jobs.Node node = actionNode == null ? null : Jobs.chunk(actionNode, chunk);
                if (node != null) {
                    if (!fleetMode) {
                        Ledger.Entry e = ledger.get(node.id, signature);
                        if (e != null && e.state == Ledger.State.DONE) {
                            node.state = Ledger.State.DONE;
                            skipped++;
                            continue;
                        }
                    }
                    Jobs.set(node, Ledger.State.RUNNING, 0, 0, null, signature);
                }
                List<int[]> clipped = chunk.clip(boxes);
                // far from where the bot is, the chunks of the world may not be there yet: go there first
                BlockPos at = chunk.centerXZ(chunk.minY());
                BlockPos here = player(line).blockPosition();
                if (!loaded(level, chunk) || chunk.distanceSqXZ(here) > 96.0 * 96.0) {
                    String go = "goto " + at.getX() + " " + at.getZ();
                    long w0 = System.nanoTime();
                    in.await(() -> new Actions.BaritoneCommand(go), go, line);
                    r.lap("walk", w0);
                }
                // what stands here before any pillar: natural ground (also stone) is never scaffolding. The snapshot at the start
                // cannot hold chunks that were not loaded then (a forest far from the base), so this chunk is added now
                ground.addAll(throwaway(in, level, clipped, null));
                long sc0 = System.nanoTime();
                List<Tree> found = scan(in, level, clipped, boxes, seen);
                r.lap("scan", sc0);
                trees.addAll(found);
                total += found.size();
                if (chunks.size() > 1) {
                    Util.msg("clear_trees: chunk " + chunkNo + "/" + chunks.size() + " (" + chunk.minX() + " " + chunk.minZ() + " .. "
                            + chunk.maxX() + " " + chunk.maxZ() + (chunk.ySlices() > 1 ? ", y " + chunk.minY() + " .. " + chunk.maxY() : "")
                            + "): " + found.size() + " tree" + (found.size() == 1 ? "" : "s") + ".");
                }
                while (true) {
                    boolean anyRemaining = false;
                    for (Tree t : found) {
                        if (t.tries < TRIES && !t.blocks.isEmpty()) { anyRemaining = true; break; }
                    }
                    if (!anyRemaining) break;
                    // after a long trip away (bed, dump, respawn) walk back before pruning so unloaded chunks never erase uncut trees
                    if (!loaded(level, chunk)) {
                        String go = "goto " + at.getX() + " " + at.getZ();
                        long w0 = System.nanoTime();
                        try {
                            in.await(() -> new Actions.BaritoneCommand(go), go, line);
                        } catch (Values.ScriptError e) {
                            // try anyway
                        }
                        r.lap("walk", w0);
                    }
                    // the nearest tree that still stands, is loaded, and has tries left
                    BlockPos me = player(line).blockPosition();
                    Tree next = null;
                    double best = Double.MAX_VALUE;
                    for (Tree t : found) {
                        if (t.tries >= TRIES) continue;
                        t.blocks.removeIf(b -> level.hasChunkAt(b) && !tree(level, b));
                        if (t.blocks.isEmpty() || !level.hasChunkAt(t.seed)) continue;
                        double dx = t.seed.getX() - me.getX(), dz = t.seed.getZ() - me.getZ();
                        double d = dx * dx + dz * dz + 0.25 * (t.seed.getY() - me.getY()) * (t.seed.getY() - me.getY());
                        if (d < best) { best = d; next = t; }
                    }
                    if (next == null) break;
                    Tree t = next;
                    t.tries++;
                    int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
                    int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
                    Set<String> kinds = new LinkedHashSet<>();
                    for (BlockPos b : t.blocks) {
                        x0 = Math.min(x0, b.getX()); x1 = Math.max(x1, b.getX());
                        y0 = Math.min(y0, b.getY()); y1 = Math.max(y1, b.getY());
                        z0 = Math.min(z0, b.getZ()); z1 = Math.max(z1, b.getZ());
                        if (level.hasChunkAt(b)) kinds.add(id(level.getBlockState(b).getBlock()));
                    }
                    if (kinds.isEmpty()) continue;
                    int n = t.blocks.size();
                    // after a trip to the base (restock, bed, chest) the bot may stand far away: mining from there finds no path
                    BlockPos from = player(line).blockPosition();
                    double fdx = t.seed.getX() - from.getX(), fdz = t.seed.getZ() - from.getZ();
                    boolean wasFar = fdx * fdx + fdz * fdz > 48 * 48;
                    if (wasFar) {
                        String go = "goto " + t.seed.getX() + " " + t.seed.getZ();
                        long w0 = System.nanoTime();
                        try {
                            in.await(() -> new Actions.BaritoneCommand(go), go, line);
                        } catch (Values.ScriptError e) {
                            // the mining below tries on
                        }
                        r.lap("walk", w0);
                    }
                    // no scaffolding blocks (a restock may have emptied them): mined trees stay out of reach, every try ends in seconds
                    if (scaffoldCount(player(line)) < 16 && quickFails >= 1) {
                        Util.msg("clear_trees: only " + scaffoldCount(player(line)) + " scaffolding blocks left, mining some dirt.");
                        try {
                            in.await(() -> new Actions.BaritoneCommand("mine 32 dirt"), "mine 32 dirt", line);
                        } catch (Values.ScriptError e) {
                            // goes on with what it has
                        }
                    }
                    // two quick failures in a row: the bot probably stands in a cave or pit; walk to the tree's surface first
                    if (quickFails >= 2 && !wasFar) {
                        String go = "goto " + t.seed.getX() + " " + t.seed.getZ();
                        try {
                            in.await(() -> new Actions.BaritoneCommand(go), go, line);
                        } catch (Values.ScriptError e) {
                            // the mining below tries on
                        }
                        quickFails = 0;
                    }
                    long tryStart = System.nanoTime();
                    Util.msg("Tree " + (cleared + gaveUp + 1) + "/" + total + (t.tries > 1 ? " (second try)" : "") + ": " + n + " blocks ("
                            + String.join(", ", kinds) + ") at " + t.seed.getX() + " " + t.seed.getY() + " " + t.seed.getZ());
                    List<int[]> box = List.of(new int[]{x0, y0 - 2, z0, x1, y1, z1});
                    ScaffoldZones.Zone zone = ScaffoldZones.add(x0, y0, z0, x1, y1, z1);   // scaffolding comes down only in here
                    zone.current = true;
                    zone.pending = true;
                    Set<BlockPos> before = throwaway(in, level, box, null);
                    dev.midtown.api.BlockFilter filter = dev.midtown.api.BlockFilter.ofNames(kinds.toArray(String[]::new));
                    dev.midtown.api.Area treeArea = dev.midtown.api.Area.of(x0, y0, z0, x1, y1, z1);
                    long m0 = System.nanoTime();
                    in.await(() -> new Actions.MineArea(filter, treeArea, "mine tree"), "mine tree", line);
                    r.lap("mining + pillars", m0);
                    BlockPos mid = new BlockPos((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2);
                    r.collect(level, mid);   // the drops that are close; the others are not worth a walk
                    // every scaffolding block that is new around the tree, also those BuildTools' recorder did not see
                    markNew(in, level, box, before);
                    t.blocks.removeIf(b -> level.hasChunkAt(b) && !tree(level, b));
                    zone.current = false;
                    // pillars stay up for the next tree; only taken down when blocks run low or too many stand
                    List<Tree> openTrees = new ArrayList<>();
                    for (Tree o : found) if (o != t && o.tries < TRIES && !o.blocks.isEmpty()) openTrees.add(o);
                    r.tidy(level, openTrees, false);
                    if (!t.blocks.isEmpty() && t.blocks.size() == n && System.nanoTime() - tryStart < 15_000_000_000L) {
                        quickFails++;
                        BlockPos pp = player(line).blockPosition();
                        Util.msg("clear_trees: no way to the tree (bot at " + pp.getX() + " " + pp.getY() + " " + pp.getZ()
                                + ", " + scaffoldCount(player(line)) + " scaffolding blocks).");
                    } else {
                        quickFails = 0;
                    }
                    // a try that found no path (ends within seconds and took nothing) will fail the same way again: do not repeat it
                    if (!t.blocks.isEmpty() && t.blocks.size() == n && t.tries < TRIES
                            && !wasFar && System.nanoTime() - tryStart < 15_000_000_000L) {
                        t.tries = Math.max(t.tries, TRIES - 1);   // one more try, then give up
                    }
                    if (t.blocks.isEmpty()) {
                        cleared++;
                        spots.add(t.seed);
                        spotTimes.put(t.seed, System.currentTimeMillis());
                    } else if (t.tries >= TRIES) {
                        Util.msg("clear_trees: " + t.blocks.size() + " blocks of the tree at " + t.seed.getX() + " " + t.seed.getY() + " "
                                + t.seed.getZ() + " are out of reach; leaving them.");
                        spots.add(t.seed);
                        spotTimes.put(t.seed, System.currentTimeMillis());
                        gaveUp++;
                    }
                    r.dumpIfFull();
                }
                r.tidy(level, List.of(), true);
                int open = 0;
                for (Tree t : found) {
                    t.blocks.removeIf(bb -> level.hasChunkAt(bb) && !tree(level, bb));
                    if (!t.blocks.isEmpty()) open++;
                }
                if (fleetMode && leaseKey != null) {
                    leaseProvider.reportChunk(
                            leaseKey,
                            open == 0 ? dev.manhattan.fleet.wire.FleetWire.OUTCOME_DONE : dev.manhattan.fleet.wire.FleetWire.OUTCOME_DEFERRED,
                            open == 0 ? "" : open + " out of reach");
                }
                if (node != null) Jobs.set(node, open == 0 ? Ledger.State.DONE : Ledger.State.DEFERRED, 1, open, open == 0 ? null : open + " out of reach", signature);
                if (actionNode != null) Jobs.settle(actionNode);
            }
            if (skipped > 0) Util.msg("clear_trees: " + skipped + " chunk" + (skipped == 1 ? "" : "s") + " already done in an earlier run, skipped.");

            // leaves: give them a while to fall apart, then one sweep for what they dropped. Drops despawn after 5 minutes, so
            // only the spots of the last minutes are worth it (in a big area the early ones are long gone)
            long cutoff = System.currentTimeMillis() - SPOT_AGE_MS;
            spots.removeIf(b -> spotTimes.getOrDefault(b, 0L) < cutoff);
            if (!spots.isEmpty()) {
                int waited = 0;
                while (waited < LEAF_WAIT_S && decaying(in, level, boxes) > 0) {
                    long lw0 = System.nanoTime();
                    in.await(() -> new Actions.Wait(5), "wait for the leaves", line);
                    r.lap("leaf wait", lw0);
                    waited += 5;
                }
                Map<Long, BlockPos> cells = new HashMap<>();
                for (BlockPos b : spots) cells.putIfAbsent(Math.floorDiv(b.getX(), SWEEP_CELL) * 100000L + Math.floorDiv(b.getZ(), SWEEP_CELL), b);
                List<BlockPos> todo = new ArrayList<>(cells.values());
                Util.msg("clear_trees: sweeping " + todo.size() + " spot" + (todo.size() == 1 ? "" : "s") + " for saplings, sticks and apples"
                        + (waited > 0 ? " (waited " + waited + " s for the leaves)" : "") + ".");
                long sw0 = System.nanoTime();
                while (!todo.isEmpty()) {
                    BlockPos me = player(line).blockPosition();
                    BlockPos at = todo.get(0);
                    for (BlockPos b : todo) if (b.distSqr(me) < at.distSqr(me)) at = b;
                    todo.remove(at);
                    double dx = at.getX() - me.getX(), dz = at.getZ() - me.getZ();
                    AABB around = new AABB(at).inflate(16, 12, 16);
                    boolean drops = !level.getEntitiesOfClass(ItemEntity.class, around, e -> e.isAlive()).isEmpty();
                    boolean far = dx * dx + dz * dz > 40 * 40;   // items further away than that the client may not know of
                    if (!drops && !far) continue;
                    if (dx * dx + dz * dz > 10 * 10) {
                        String go = "goto " + at.getX() + " " + at.getZ();
                        in.await(() -> new Actions.BaritoneCommand(go), go, line);
                    }
                    r.pickup();
                    r.dumpIfFull();
                }
                r.lap("sweep", sw0);
            }
            // anything left standing from pickups and the sweep (pillars up to drops on the leaves)
            for (int round = 0; round < 3 && markNew(in, level, boxes, ground) > 0; round++) {
                r.forgetOutsideZones();
                r.scaffold(null);
                r.pickup();   // may climb again: look once more
            }
            r.forgetOutsideZones();
            r.scaffold(null);   // whatever stands in the zones still
            r.refreshZones(level);
            if (chest != null && (!fleetMode || cleared > 0)) r.dump();

            // the report: what is in the inventory now and not before, plus what went into the chest
            Map<Item, Integer> now = inventory(player(line));
            Map<Item, Integer> gained = new HashMap<>(r.stored);
            now.forEach((it, n) -> gained.merge(it, n, Integer::sum));
            start.forEach((it, n) -> gained.merge(it, -n, Integer::sum));
            List<Map.Entry<Item, Integer>> list = new ArrayList<>(gained.entrySet());
            list.removeIf(e -> e.getValue() <= 0);
            list.sort((a, b) -> b.getValue() - a.getValue());
            Map<Object, Object> out = Values.newMap();
            StringBuilder sb = new StringBuilder();
            for (var e : list) {
                out.put(ChestOp.name(e.getKey()), (double) e.getValue());
                if (sb.length() > 0) sb.append(", ");
                sb.append(e.getValue()).append(' ').append(ChestOp.name(e.getKey()));
            }
            {
                StringBuilder ph = new StringBuilder();
                long sum = 0;
                for (long v : r.phases.values()) sum += v;
                for (var e : r.phases.entrySet()) ph.append(ph.length() > 0 ? ", " : "").append(e.getKey()).append(' ').append(e.getValue() / 1_000_000_000L).append(" s");
                Util.msg("clear_trees: time per phase (" + sum / 1_000_000_000L + " s counted): " + ph + ".");
            }
            int left = 0;
            for (Tree t : trees) {
                t.blocks.removeIf(b -> level.hasChunkAt(b) && !tree(level, b));
                if (!t.blocks.isEmpty()) left++;
            }
            lastRemainingTrees = left;
            Util.msg("clear_trees: " + cleared + " of " + total + " trees cleared" + (left > 0 ? ", " + left + " not (out of reach)" : "")
                    + ". Gained: " + (sb.length() == 0 ? "nothing" : sb) + (chest != null ? " (in the chest " + chestText + ")" : "") + ".");
            return out;
        } finally {
            dev.buildtools.Api.scaffoldAllowedZone = prevAllowed;
            dev.buildtools.TreeZoneOptions.setAutoSweepSuppressed(prevSuppressed);
            dev.buildtools.TreeZoneOptions.setAutoSweep(autoSweep);
            ScaffoldZones.clearCurrentFlags();
        }
    }
}
