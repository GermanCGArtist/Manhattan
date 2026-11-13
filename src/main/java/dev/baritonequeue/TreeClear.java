package dev.baritonequeue;

import baritone.api.BaritoneAPI;
import baritone.api.utils.BetterBlockPos;
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
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.baritonequeue.Values.error;

/**
 * clear_trees([chest]): the selection's trees one by one, nearest first. For each tree its own small selection and a
 * "#sel replace <its blocks> air" with buildInLayers off (Baritone climbs on dirt where it must), then the drops are
 * picked up and the scaffolding is taken down before the next tree. Leaves are left to decay; at the end it waits for
 * them a little and sweeps the area for saplings, sticks and apples. With a chest, a nearly full inventory and the end
 * go into it. Returns what was gained (item -> count, what went into the chest included).
 */
final class TreeClear {
    private TreeClear() {}

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
    private static final int DUMP_BELOW = 6;    // free slots: below this, a chest trip between trees

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

    /** Every tree in the selection: logs etc. that touch (also diagonally), plus vines and bee nests near them. */
    private static List<Tree> scan(Interp in, Level level, List<int[]> boxes) {
        List<Tree> out = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        long cells = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int[] b : boxes) {
            for (int x = b[0]; x <= b[3]; x++) {
                for (int z = b[2]; z <= b[5]; z++) {
                    for (int y = b[1]; y <= b[4]; y++) {
                        if (++cells % 40000 == 0) in.yieldTick();
                        m.set(x, y, z);
                        if (!level.hasChunkAt(m) || !body(level.getBlockState(m)) || seen.contains(m)) continue;
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
        }
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

    private static void select(int x0, int y0, int z0, int x1, int y1, int z1) {
        var sm = Util.baritone().getSelectionManager();
        sm.removeAllSelections();
        sm.addSelection(new BetterBlockPos(x0, y0, z0), new BetterBlockPos(x1, y1, z1));
    }

    private static final int AROUND = 5;   // how far from the tree Baritone may stand on a pillar

    private static final Set<Block> GROUND = Set.of(Blocks.GRASS_BLOCK, Blocks.MYCELIUM, Blocks.PODZOL, Blocks.DIRT_PATH, Blocks.FARMLAND);

    /**
     * Scaffolding kinds (Baritone's throwaway blocks) in the boxes, each grown by AROUND sideways and 3 up.
     * except == null: a snapshot, which also holds the grass (grass under a pillar turns into dirt: that is ground,
     * not scaffolding). Otherwise: the scaffolding blocks that are not in the snapshot, i.e. new.
     */
    private static Set<BlockPos> throwaway(Interp in, Level level, List<int[]> boxes, Set<BlockPos> except) {
        Set<Block> kinds = new HashSet<>();
        for (Item it : BaritoneAPI.getSettings().acceptableThrowawayItems.value) {
            if (it instanceof net.minecraft.world.item.BlockItem bi) kinds.add(bi.getBlock());
        }
        Set<BlockPos> out = new HashSet<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        long cells = 0;
        for (int[] b : boxes) {
            for (int x = b[0] - AROUND; x <= b[3] + AROUND; x++) {
                for (int z = b[2] - AROUND; z <= b[5] + AROUND; z++) {
                    for (int y = b[1]; y <= b[4] + 3; y++) {
                        if (++cells % 40000 == 0) in.yieldTick();
                        m.set(x, y, z);
                        if (!level.hasChunkAt(m)) continue;
                        Block k = level.getBlockState(m).getBlock();
                        if (except == null ? kinds.contains(k) || GROUND.contains(k) : kinds.contains(k) && !except.contains(m)) out.add(m.immutable());
                    }
                }
            }
        }
        return out;
    }

    /** New scaffolding in the boxes goes on BuildTools' list, so collect_scaffold() takes it down; returns how many. */
    private static int markNew(Interp in, Level level, List<int[]> boxes, Set<BlockPos> before) {
        Set<BlockPos> fresh = throwaway(in, level, boxes, before);
        for (BlockPos b : fresh) dev.buildtools.Api.addScaffold(b, level.getBlockState(b).getBlock());
        return fresh.size();
    }

    /** Leaves in the selection that no longer touch a log (within 6) and will fall apart. */
    private static int decaying(Interp in, Level level, List<int[]> boxes) {
        int n = 0;
        long cells = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int[] b : boxes) {
            for (int x = b[0]; x <= b[3]; x++) {
                for (int z = b[2]; z <= b[5]; z++) {
                    for (int y = b[1]; y <= b[4]; y++) {
                        if (++cells % 40000 == 0) in.yieldTick();
                        m.set(x, y, z);
                        if (!level.hasChunkAt(m)) continue;
                        BlockState s = level.getBlockState(m);
                        if (s.getBlock() instanceof LeavesBlock && s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT)
                                && s.hasProperty(LeavesBlock.DISTANCE) && s.getValue(LeavesBlock.DISTANCE) >= LeavesBlock.DECAY_DISTANCE) n++;
                    }
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ the run

    private static final class Run {
        final Interp in;
        final int line;
        final BlockPos chest;
        final String chestText;
        final Map<Item, Integer> stored = new HashMap<>();
        int dumps;

        Run(Interp in, int line, BlockPos chest, String chestText) {
            this.in = in;
            this.line = line;
            this.chest = chest;
            this.chestText = chestText;
        }

        void pickup() {
            in.await(() -> new Actions.Feature(() -> { dev.loadchest.LoadChestMod.pickupNow(); return null; },
                    dev.loadchest.LoadChestMod::pickingUp, null, () -> true), "pickup", line);
        }

        void scaffold() {
            if (dev.buildtools.Api.scaffoldCount() == 0) return;
            in.await(() -> new Actions.Feature(dev.buildtools.Api::collectScaffoldNow, dev.buildtools.Api::collectingScaffold,
                    dev.buildtools.Api::stopCollectingScaffold, () -> true), "collect_scaffold", line);
        }

        /** Into the chest, keeping the scaffolding blocks; what went in is counted per item. */
        void dump() {
            LocalPlayer p = player(line);
            Map<Item, Integer> before = inventory(p);
            List<String> added = new ArrayList<>();
            var rules = dev.loadchest.LoadChestMod.keepRules();
            for (Item it : BaritoneAPI.getSettings().acceptableThrowawayItems.value) {
                String n = ChestOp.name(it);
                if (before.containsKey(it) && !rules.containsKey(n)) {
                    rules.put(n, 128);
                    added.add(n);
                }
            }
            if (!added.isEmpty()) dev.loadchest.LoadChestMod.saveSettings();
            try {
                ChestOp op = new ChestOp(chestText, ChestOp.Kind.DUMP, null, -1, false);
                in.await(() -> new Actions.Chest(op, chest), "dump", line);
                dumps++;
            } catch (Values.ScriptError e) {
                Util.msg("clear_trees: could not empty into the chest (" + e.getMessage() + "), going on.");
            } finally {
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
            if (chest != null && freeSlots(player(line)) < DUMP_BELOW) dump();
        }
    }

    static Object run(Interp in, int line, BlockPos chest, String chestText) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        LocalPlayer p = player(line);
        if (level == null) throw error("Not in a world.", line);
        List<int[]> boxes = Areas.read();
        if (boxes.isEmpty()) throw error("There is no selection. Select the forest first: area(\"name\") or select(corner1, corner2).", line);

        Run r = new Run(in, line, chest, chestText);
        Map<Item, Integer> start = inventory(p);
        var s = BaritoneAPI.getSettings();
        boolean oldLayers = s.buildInLayers.value;
        boolean throwaway = false;
        for (Item it : s.acceptableThrowawayItems.value) if (start.containsKey(it)) throwaway = true;
        if (!throwaway) Util.msg("clear_trees: you carry no scaffolding blocks (#acceptableThrowawayItems, e.g. dirt): tall trees may be out of reach.");

        List<Tree> trees = scan(in, level, boxes);
        Set<BlockPos> ground = throwaway(in, level, boxes, null);   // what is there before: never taken for scaffolding
        int total = trees.size(), cleared = 0;
        List<BlockPos> spots = new ArrayList<>();
        Util.msg("clear_trees: " + total + " trees in the selection.");
        try {
            s.buildInLayers.value = false;
            while (true) {
                // the nearest tree that still stands and has tries left
                BlockPos me = player(line).blockPosition();
                Tree next = null;
                double best = Double.MAX_VALUE;
                for (Tree t : trees) {
                    if (t.tries >= TRIES) continue;
                    t.blocks.removeIf(b -> !level.hasChunkAt(b) || !tree(level, b));
                    if (t.blocks.isEmpty()) continue;
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
                    kinds.add(id(level.getBlockState(b).getBlock()));
                }
                int n = t.blocks.size();
                Util.msg("Tree " + (cleared + 1) + "/" + total + (t.tries > 1 ? " (second try)" : "") + ": " + n + " blocks ("
                        + String.join(", ", kinds) + ") at " + t.seed.getX() + " " + t.seed.getY() + " " + t.seed.getZ());
                select(x0, y0, z0, x1, y1, z1);
                List<int[]> box = List.of(new int[]{x0, y0 - 2, z0, x1, y1, z1});
                Set<BlockPos> before = throwaway(in, level, box, null);
                String cmd = "sel replace " + String.join(" ", kinds) + " air";
                in.await(() -> new Actions.BaritoneCommand(cmd), cmd, line);
                r.pickup();   // drops on the leaves: Baritone may pillar up to them
                // every scaffolding block that is new around the tree, also those BuildTools' recorder did not see
                markNew(in, level, box, before);
                r.scaffold();
                r.pickup();   // what the scaffolding dropped
                t.blocks.removeIf(b -> !tree(level, b));
                if (t.blocks.isEmpty()) {
                    cleared++;
                    spots.add(t.seed);
                } else if (t.tries >= TRIES) {
                    Util.msg("clear_trees: " + t.blocks.size() + " blocks of the tree at " + t.seed.getX() + " " + t.seed.getY() + " "
                            + t.seed.getZ() + " are out of reach; leaving them.");
                    spots.add(t.seed);
                }
                r.dumpIfFull();
            }
        } finally {
            s.buildInLayers.value = oldLayers;
            var sm = Util.baritone().getSelectionManager();
            sm.removeAllSelections();
            for (int[] b : boxes) sm.addSelection(new BetterBlockPos(b[0], b[1], b[2]), new BetterBlockPos(b[3], b[4], b[5]));
        }

        // leaves: give them a while to fall apart, then one sweep over the area for what they dropped
        if (!spots.isEmpty()) {
            int waited = 0;
            while (waited < LEAF_WAIT_S && decaying(in, level, boxes) > 0) {
                in.await(() -> new Actions.Wait(5), "wait for the leaves", line);
                waited += 5;
            }
            Map<Long, BlockPos> cells = new HashMap<>();
            for (BlockPos b : spots) cells.putIfAbsent(Math.floorDiv(b.getX(), SWEEP_CELL) * 100000L + Math.floorDiv(b.getZ(), SWEEP_CELL), b);
            List<BlockPos> todo = new ArrayList<>(cells.values());
            Util.msg("clear_trees: sweeping " + todo.size() + " spot" + (todo.size() == 1 ? "" : "s") + " for saplings, sticks and apples"
                    + (waited > 0 ? " (waited " + waited + " s for the leaves)" : "") + ".");
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
        }
        // anything left standing from pickups and the sweep (pillars up to drops on the leaves)
        for (int round = 0; round < 3 && markNew(in, level, boxes, ground) > 0; round++) {
            r.scaffold();
            r.pickup();   // may climb again: look once more
        }
        if (chest != null) r.dump();

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
        int left = 0;
        for (Tree t : trees) {
            t.blocks.removeIf(b -> !level.hasChunkAt(b) || !tree(level, b));
            if (!t.blocks.isEmpty()) left++;
        }
        Util.msg("clear_trees: " + cleared + " of " + total + " trees cleared" + (left > 0 ? ", " + left + " not (out of reach)" : "")
                + ". Gained: " + (sb.length() == 0 ? "nothing" : sb) + (chest != null ? " (in the chest " + chestText + ")" : "") + ".");
        return out;
    }
}
