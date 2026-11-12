package dev.baritonequeue;

import baritone.api.schematic.ISchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Litematica placement turned into something Baritone's builder can build: the plan (every block of the schematic in
 * world space), the wrapper ISchematic that answers for one mode and one phase, the material list and the diff.
 */
final class Schem {
    private Schem() {}

    enum Mode {
        ADD, FIX, EXACT;

        static Mode of(String s) {
            return switch (s.toLowerCase()) {
                case "add" -> ADD;
                case "fix" -> FIX;
                case "exact" -> EXACT;
                default -> null;
            };
        }

        String label() { return name().toLowerCase(); }
    }

    /** The order things are built in. FLUID is not built yet (listed in the report). */
    enum Phase {
        CLEAR("clear (dig out schematic air)"), FULL("full blocks"), SHAPED("shaped blocks (fences, walls, panes ...)"),
        ORIENTED("oriented blocks and blocks in water (stairs, top slabs, doors, logs, wall torches ... placed by our own placer)"),
        ATTACHED("attached blocks (torches, doors, signs ...)"), REDSTONE("redstone"), POWER("power sources (levers, buttons, redstone torches ...)"),
        FLUID("fluids");

        final String label;

        Phase(String label) { this.label = label; }
    }

    // ------------------------------------------------------------------ what kind of block is it


    /** Blocks whose state depends on how a player places them: Baritone knows one orientation per item, so it must not place these. */
    static boolean oriented(BlockState st) {
        for (var prop : st.getProperties()) {
            String n = prop.getName();
            if (n.equals("facing") || n.equals("half") || n.equals("axis") || n.equals("rotation") || n.equals("face") || n.equals("orientation")) return true;
            if (prop == BlockStateProperties.SLAB_TYPE) return true;
        }
        return false;
    }

    /**
     * The phase for a spot, given what is there now. Baritone's builder will not place into water (it stops the whole
     * layer with "Unreplaceable liquids", so nothing above gets built): full and shaped blocks wanted where water is
     * now go to our own placer, which clicks a neighbour like a player and replaces the water.
     */
    static Phase phaseAt(BlockState want, BlockState now) {
        Phase ph = phaseOf(want);
        if ((ph == Phase.FULL || ph == Phase.SHAPED) && now.getBlock() instanceof LiquidBlock) return Phase.ORIENTED;
        return ph;
    }

    /**
     * phaseAt() for a cell of the plan: a block in a cell that was open when the build started was put there during the
     * build (Baritone's scaffolding goes into any cell that wants a different block). Baritone would break it and put it
     * back again and again to stand on; our placer takes it away and places the right block once, at the end.
     */
    static Phase phaseAt(Plan plan, int i, BlockState want, BlockState now) {
        Phase ph = phaseAt(want, now);
        if ((ph == Phase.FULL || ph == Phase.SHAPED) && plan.wasOpen(i) && !now.isAir() && !replaceable(now) && !norm(now).equals(want)) return Phase.ORIENTED;
        return ph;
    }

    static Phase phaseOf(BlockState st) {
        Block b = st.getBlock();
        if (b instanceof LiquidBlock) return Phase.FLUID;
        if (oriented(st)) return Phase.ORIENTED;
        String p = BuiltInRegistries.BLOCK.getKey(b).getPath();
        if (p.equals("lever") || p.endsWith("_button") || p.endsWith("_pressure_plate") || p.equals("redstone_torch") || p.equals("redstone_wall_torch")
                || p.equals("redstone_block") || p.equals("tripwire_hook") || p.equals("daylight_detector")) return Phase.POWER;
        if (p.equals("redstone_wire") || p.equals("repeater") || p.equals("comparator") || p.equals("piston") || p.equals("sticky_piston")
                || p.equals("observer") || p.equals("dispenser") || p.equals("dropper") || p.equals("powered_rail") || p.equals("detector_rail")
                || p.equals("activator_rail") || p.equals("tripwire") || p.equals("note_block")) return Phase.REDSTONE;
        if (p.endsWith("torch") || p.endsWith("_sign") || p.endsWith("_hanging_sign") || (p.endsWith("_door") && !p.endsWith("trapdoor")) || p.endsWith("_bed")
                || p.equals("ladder") || p.equals("vine") || p.endsWith("carpet") || p.endsWith("_banner") || p.equals("rail") || p.endsWith("lantern")
                || p.endsWith("candle") || p.endsWith("_fan") || p.endsWith("_head") || p.endsWith("_skull") || p.startsWith("potted_") || p.equals("snow")
                || p.equals("glow_lichen") || p.equals("sculk_vein") || p.endsWith("sapling") || p.equals("short_grass") || p.equals("tall_grass")
                || p.equals("fern") || p.equals("large_fern") || p.endsWith("tulip") || p.equals("dandelion") || p.equals("poppy") || p.endsWith("_wire")
                || p.equals("sea_pickle") || p.equals("cake") || p.equals("flower_pot") || p.equals("pointed_dripstone") || p.equals("hanging_roots")
                || p.equals("moss_carpet") || p.equals("fire") || p.equals("sweet_berry_bush") || p.equals("kelp") || p.equals("seagrass") || p.equals("tall_seagrass")) return Phase.ATTACHED;
        if (p.endsWith("stairs") || p.endsWith("slab") || p.endsWith("fence") || p.endsWith("fence_gate") || p.endsWith("_wall") || p.endsWith("trapdoor")
                || p.endsWith("_pane") || p.equals("iron_bars") || p.equals("chain") || p.equals("anvil") || p.equals("chipped_anvil") || p.equals("damaged_anvil")
                || p.equals("end_rod") || p.equals("hopper") || p.endsWith("cauldron") || p.equals("lightning_rod") || p.equals("brewing_stand")
                || p.equals("campfire") || p.equals("soul_campfire") || p.equals("bell") || p.equals("grindstone") || p.equals("lectern")
                || p.equals("enchanting_table") || p.equals("conduit") || p.equals("dragon_egg") || p.endsWith("_bars")) return Phase.SHAPED;
        return Phase.FULL;
    }

    // ------------------------------------------------------------------ the plan

    static final class Plan {
        String name;
        BlockPos min;
        int sx, sy, sz;
        BlockState[] st;
        BitSet excluded;
        /**
         * Cells that were open (air, liquid, replaceable) when the build started. In add mode a block that is there now
         * was put there during the build - Baritone's scaffolding goes into any cell that wants a different block - so
         * it is replaced like air, not kept as "a block that was already there". null: not known (counted as not open).
         */
        BitSet open;

        boolean wasOpen(int i) { return open != null && open.get(i); }

        /** Remembers which cells are open now (called when a build starts). */
        void markOpen(net.minecraft.world.level.Level level) {
            open = new BitSet(st.length);
            BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
            for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) {
                int i = idx(x, y, z);
                if (st[i] == null) continue;
                mp.set(min.getX() + x, min.getY() + y, min.getZ() + z);
                if (!level.hasChunkAt(mp)) continue;
                BlockState cur = level.getBlockState(mp);
                if (cur.isAir() || replaceable(cur) || cur.getBlock() instanceof LiquidBlock) open.set(i);
            }
        }
        final List<String> notes = new ArrayList<>();      // unknown blocks, regions skipped ...
        final Map<String, Integer> fluids = new LinkedHashMap<>();

        int idx(int x, int y, int z) { return (y * sz + z) * sx + x; }

        BlockState at(int x, int y, int z) {
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return null;
            return st[idx(x, y, z)];
        }

        BlockPos world(int x, int y, int z) { return new BlockPos(min.getX() + x, min.getY() + y, min.getZ() + z); }
    }

    private static int[] mirror(int x, int z, Mirror m) {
        return switch (m) {
            case LEFT_RIGHT -> new int[]{x, -z};
            case FRONT_BACK -> new int[]{-x, z};
            default -> new int[]{x, z};
        };
    }

    private static int[] rotate(int x, int z, Rotation r) {
        return switch (r) {
            case CLOCKWISE_90 -> new int[]{-z, x};
            case CLOCKWISE_180 -> new int[]{-x, -z};
            case COUNTERCLOCKWISE_90 -> new int[]{z, -x};
            default -> new int[]{x, z};
        };
    }

    private static int[] tf(int x, int z, Mirror m, Rotation r) {
        int[] a = mirror(x, z, m);
        return rotate(a[0], a[1], r);
    }

    /** One region's blocks into world space; calls out for each block (air included). */
    private interface Sink {
        void put(int wx, int wy, int wz, BlockState state);
    }

    private static void place(SchemSource.Placement pl, Litematic.Schematic s, Plan plan, Sink sink, List<int[]> corners) {
        for (Litematic.Region r : s.regions.values()) {
            SchemSource.Sub sub = null;
            for (SchemSource.Sub c : pl.subs()) if (c.name().equals(r.name)) sub = c;
            if (!pl.subs().isEmpty() && sub == null) continue;
            if (sub != null && !sub.enabled()) {
                if (sink != null) plan.notes.add("region '" + r.name + "' is switched off in Litematica, skipped");
                continue;
            }
            BlockPos at = sub != null ? sub.pos() : new BlockPos(r.px, r.py, r.pz);
            Rotation sr = sub != null ? sub.rotation() : Rotation.NONE;
            Mirror sm = sub != null ? sub.mirror() : Mirror.NONE;
            int[] xs = {r.minX(), r.minX() + r.ax() - 1}, zs = {r.minZ(), r.minZ() + r.az() - 1};
            if (corners != null) {
                for (int x : xs) for (int z : zs) {
                    int[] a = tf(x, z, sm, sr);
                    int[] b = tf(a[0] + at.getX(), a[1] + at.getZ(), pl.mirror(), pl.rotation());
                    corners.add(new int[]{b[0] + pl.origin().getX(), pl.origin().getY() + at.getY() + r.minY(), b[1] + pl.origin().getZ()});
                    corners.add(new int[]{b[0] + pl.origin().getX(), at.getY() + r.minY() + r.ay() - 1 + pl.origin().getY(), b[1] + pl.origin().getZ()});
                }
                continue;
            }
            for (int ly = 0; ly < r.ay(); ly++) {
                for (int lz = 0; lz < r.az(); lz++) {
                    for (int lx = 0; lx < r.ax(); lx++) {
                        BlockState state = r.at(lx, ly, lz);
                        int[] a = tf(lx + r.minX(), lz + r.minZ(), sm, sr);
                        int[] b = tf(a[0] + at.getX(), a[1] + at.getZ(), pl.mirror(), pl.rotation());
                        int wy = pl.origin().getY() + at.getY() + r.minY() + ly;
                        BlockState t = state.mirror(sm).rotate(sr).mirror(pl.mirror()).rotate(pl.rotation());
                        sink.put(b[0] + pl.origin().getX(), wy, b[1] + pl.origin().getZ(), t);
                    }
                }
            }
        }
    }

    /** The plan of these placements; later placements win where they overlap (air never overwrites a block). Null + error text on failure. */
    static Plan plan(List<SchemSource.Placement> pls, String[] error) {
        List<Litematic.Schematic> loaded = new ArrayList<>();
        for (SchemSource.Placement pl : pls) {
            try {
                loaded.add(Litematic.read(pl.file()));
            } catch (IOException | RuntimeException e) {
                error[0] = "Cannot read " + pl.file() + " (" + e + ").";
                return null;
            }
        }
        Plan plan = new Plan();
        plan.name = pls.size() == 1 ? pls.get(0).name() : pls.size() + " placements";
        List<int[]> corners = new ArrayList<>();
        for (int i = 0; i < pls.size(); i++) place(pls.get(i), loaded.get(i), plan, null, corners);
        if (corners.isEmpty()) {
            error[0] = "There is nothing to build in " + plan.name + " (no enabled region).";
            return null;
        }
        int x0 = Integer.MAX_VALUE, y0 = x0, z0 = x0, x1 = Integer.MIN_VALUE, y1 = x1, z1 = x1;
        for (int[] c : corners) {
            x0 = Math.min(x0, c[0]); x1 = Math.max(x1, c[0]);
            y0 = Math.min(y0, c[1]); y1 = Math.max(y1, c[1]);
            z0 = Math.min(z0, c[2]); z1 = Math.max(z1, c[2]);
        }
        long vol = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        if (vol > 40_000_000L) {
            error[0] = "The schematic is too big to build in one go (" + vol + " blocks in its box).";
            return null;
        }
        plan.min = new BlockPos(x0, y0, z0);
        plan.sx = x1 - x0 + 1;
        plan.sy = y1 - y0 + 1;
        plan.sz = z1 - z0 + 1;
        plan.st = new BlockState[(int) vol];
        for (int i = 0; i < pls.size(); i++) {
            Litematic.Schematic s = loaded.get(i);
            for (String u : s.unknown) plan.notes.add("unknown block " + u + " (this Minecraft does not have it), built as air");
            place(pls.get(i), s, plan, (wx, wy, wz, state) -> {
                int ix = wx - plan.min.getX(), iy = wy - plan.min.getY(), iz = wz - plan.min.getZ();
                if (ix < 0 || iy < 0 || iz < 0 || ix >= plan.sx || iy >= plan.sy || iz >= plan.sz) return;
                int k = plan.idx(ix, iy, iz);
                if (plan.st[k] == null || !state.isAir()) plan.st[k] = norm(state);
            }, null);
        }
        // safe areas (buildtools) are not touched, neither built nor dug
        plan.excluded = new BitSet(plan.st.length);
        int safe = 0;
        for (int y = 0; y < plan.sy; y++) for (int z = 0; z < plan.sz; z++) for (int x = 0; x < plan.sx; x++) {
            if (plan.st[plan.idx(x, y, z)] != null && dev.buildtools.Api.protectedAt(plan.world(x, y, z))) {
                plan.excluded.set(plan.idx(x, y, z));
                safe++;
            }
        }
        if (safe > 0) plan.notes.add(safe + " blocks lie inside safe areas and are left out");
        for (BlockState d : plan.st) {
            if (d != null && d.getBlock() instanceof LiquidBlock) plan.fluids.merge(BuiltInRegistries.BLOCK.getKey(d.getBlock()).getPath(), 1, Integer::sum);
        }
        return plan;
    }


    /** Properties the game derives from the neighbours (or that cannot be chosen when placing): set to the block's default so they never count as a difference. */
    private static final java.util.Set<String> DERIVED = java.util.Set.of("north", "east", "south", "west", "up", "down", "power", "powered", "shape", "waterlogged", "attached", "disarmed", "in_wall", "open_derived");

    @SuppressWarnings({"unchecked", "rawtypes"})
    static BlockState norm(BlockState st) {
        Block b = st.getBlock();
        String p = BuiltInRegistries.BLOCK.getKey(b).getPath();
        boolean wire = p.equals("redstone_wire"), conn = p.endsWith("fence") || p.endsWith("_pane") || p.equals("iron_bars") || p.endsWith("_wall") || p.endsWith("chest");
        boolean config = p.equals("repeater") || p.equals("comparator") || p.equals("note_block") || p.contains("door") || p.endsWith("chest");
        boolean stairs = p.endsWith("stairs");
        BlockState def = b.defaultBlockState();
        for (var prop : st.getProperties()) {
            String n = prop.getName();
            boolean derived = (wire && (DERIVED.contains(n))) || (conn && (n.equals("north") || n.equals("east") || n.equals("south") || n.equals("west") || n.equals("up") || n.equals("waterlogged")))
                    || (stairs && (n.equals("shape") || n.equals("waterlogged"))) || n.equals("waterlogged");
            if (config && (n.equals("delay") || n.equals("locked") || n.equals("mode") || n.equals("note") || n.equals("hinge") || (n.equals("type") && p.endsWith("chest")) || n.equals("powered") || n.equals("open"))) derived = true;
            if (derived) st = st.setValue((net.minecraft.world.level.block.state.properties.Property) prop, (Comparable) def.getValue(prop));
        }
        return st;
    }

    static boolean replaceable(BlockState cur) {
        return cur.isAir() || cur.canBeReplaced() || cur.getBlock() instanceof LiquidBlock;
    }

    // ------------------------------------------------------------------ the wrapper Baritone builds

    static final class Wrapper implements ISchematic {
        final Plan plan;
        final Mode mode;
        final Phase phase;
        /** Items not in the inventory right now; their blocks are left out (skip mode). null = all or nothing. */
        final java.util.function.Supplier<java.util.Set<Item>> missing;
        /** Cells (plan indices) to ignore for now: the builder stalled on them. null = none. */
        final java.util.function.IntPredicate skip;

        Wrapper(Plan plan, Mode mode, Phase phase, java.util.function.Supplier<java.util.Set<Item>> missing, java.util.function.IntPredicate skip) {
            this.skip = skip;
            this.plan = plan;
            this.mode = mode;
            this.phase = phase;
            this.missing = missing;
        }

        @Override
        public boolean inSchematic(int x, int y, int z, BlockState current) {
            BlockState d = plan.at(x, y, z);
            if (d == null || plan.excluded.get(plan.idx(x, y, z))) return false;
            if (phase == Phase.CLEAR || phase == Phase.ORIENTED) return false;   // our own placer does these
            if (skip != null && skip.test(plan.idx(x, y, z))) return false;
            if (d.isAir() || phaseAt(plan, plan.idx(x, y, z), d, current) != phase) return false;
            if (!(mode != Mode.ADD || norm(current).equals(d) || replaceable(current) || plan.wasOpen(plan.idx(x, y, z)))) return false;
            if (missing != null && !norm(current).equals(d)) {
                Object[] cost = cost(d);
                if (cost != null && missing.get().contains((Item) cost[0])) return false;   // no item for it: build the rest
            }
            return true;
        }

        @Override
        public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
            BlockState d = plan.at(x, y, z);
            if (phase == Phase.CLEAR || d == null) return Blocks.AIR.defaultBlockState();
            return d;
        }

        @Override public int widthX() { return plan.sx; }
        @Override public int heightY() { return plan.sy; }
        @Override public int lengthZ() { return plan.sz; }
    }


    // ------------------------------------------------------------------ why a block is still missing

    /** Why a block that still has to be built was not built (the groups of the end report). */
    enum Reason {
        NO_ITEM("no material with you"), WET("water in the cell"), NO_SUPPORT("nothing to place against"), STALLED("not reached (the builder found no way to it or stopped making progress)");

        final String label;

        Reason(String label) { this.label = label; }
    }

    record Why(Reason reason, String item) {}

    /** Reason for one block of the plan that is still wrong (given what is in the world and the inventory now). */
    static Why whyLeft(Level level, BlockPos p, BlockState d, BlockState cur) {
        Object[] cost = cost(d);
        if (cost != null && (Item) cost[0] != Items.AIR && have((Item) cost[0]) == 0) return new Why(Reason.NO_ITEM, itemName((Item) cost[0]));
        if (cur.getBlock() instanceof LiquidBlock) return new Why(Reason.WET, null);
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            BlockPos n = p.relative(dir);
            if (level.hasChunkAt(n) && !replaceable(level.getBlockState(n))) return new Why(Reason.STALLED, null);
        }
        return new Why(Reason.NO_SUPPORT, null);
    }

    /** Every loaded, not excluded block of the plan that still has to be built (not dug), with the reason. Key: BlockPos.asLong(). */
    static Map<Long, Why> leftCells(Plan plan, Mode mode, Level level) {
        Map<Long, Why> out = new LinkedHashMap<>();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int y = 0; y < plan.sy; y++) for (int z = 0; z < plan.sz; z++) for (int x = 0; x < plan.sx; x++) {
            int i = plan.idx(x, y, z);
            BlockState d = plan.st[i];
            if (d == null || d.isAir() || plan.excluded.get(i) || cost(d) == null) continue;
            mp.set(plan.min.getX() + x, plan.min.getY() + y, plan.min.getZ() + z);
            if (!level.hasChunkAt(mp)) continue;
            BlockState cur = norm(level.getBlockState(mp));
            if (cur.equals(d) || !todo(plan, i, mode, d, cur)) continue;
            out.put(mp.asLong(), whyLeft(level, mp, d, cur));
        }
        return out;
    }

    // ------------------------------------------------------------------ counting

    /** todo() for a cell of the plan: in add mode a cell that was open when the build started takes any block away again. */
    static boolean todo(Plan plan, int i, Mode mode, BlockState d, BlockState cur) {
        if (mode == Mode.ADD && !d.isAir() && plan.wasOpen(i) && !cur.equals(d)) return true;
        return todo(mode, d, cur);
    }

    /** Does this position still need work in this mode? (Phase and safe areas are not looked at here.) */
    static boolean todo(Mode mode, BlockState d, BlockState cur) {
        if (d.isAir()) return mode == Mode.EXACT && !cur.isAir() && !(cur.getBlock() instanceof LiquidBlock);
        if (cur.equals(d)) return false;
        return mode != Mode.ADD || replaceable(cur);
    }

    /** The items a block costs: one stack of {item, count}, or null for the second half of a door, bed, tall plant ... */
    static Object[] cost(BlockState d) {
        Block b = d.getBlock();
        if (d.isAir()) return null;
        if (b instanceof LiquidBlock) return new Object[]{b == Blocks.LAVA ? Items.LAVA_BUCKET : Items.WATER_BUCKET, 1, true};
        if (d.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && d.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return null;
        if (d.hasProperty(BlockStateProperties.BED_PART) && d.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return null;
        if (b == Blocks.REDSTONE_WIRE) return new Object[]{Items.REDSTONE, 1, false};
        Item it = b.asItem();
        if (it == Items.AIR) return new Object[]{Items.AIR, 1, false};
        int n = 1;
        if (d.hasProperty(BlockStateProperties.SLAB_TYPE) && d.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) n = 2;
        if (d.hasProperty(BlockStateProperties.CANDLES)) n = d.getValue(BlockStateProperties.CANDLES);
        if (d.hasProperty(BlockStateProperties.PICKLES)) n = d.getValue(BlockStateProperties.PICKLES);
        if (d.hasProperty(BlockStateProperties.LAYERS)) n = d.getValue(BlockStateProperties.LAYERS);
        return new Object[]{it, n, false};
    }

    static final class Counts {
        int correct, missing, wrong, wrongState, extra, unloaded, excluded;
        final Map<Phase, Integer> todoByPhase = new LinkedHashMap<>();
        final Map<Item, Integer> items = new LinkedHashMap<>();   // still needed
        final List<Item> reusable = new ArrayList<>();
        int noItem;
        final List<String> examples = new ArrayList<>();

        int todoTotal() { return missing + wrong + extra; }
    }

    static Counts count(Plan plan, Mode mode, Level level) {
        Counts c = new Counts();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int y = 0; y < plan.sy; y++) for (int z = 0; z < plan.sz; z++) for (int x = 0; x < plan.sx; x++) {
            BlockState d = plan.st[plan.idx(x, y, z)];
            if (d == null) continue;
            if (plan.excluded.get(plan.idx(x, y, z))) { c.excluded++; continue; }
            mp.set(plan.min.getX() + x, plan.min.getY() + y, plan.min.getZ() + z);
            if (!level.hasChunkAt(mp)) { c.unloaded++; continue; }
            BlockState cur = norm(level.getBlockState(mp));
            if (d.isAir()) {
                if (todo(mode, d, cur)) {
                    c.extra++;
                    c.todoByPhase.merge(Phase.CLEAR, 1, Integer::sum);
                    example(c, mp, "air", cur);
                }
                continue;
            }
            if (cur.equals(d)) { c.correct++; continue; }
            Phase ph = phaseAt(plan, plan.idx(x, y, z), d, cur);
            boolean needs = todo(plan, plan.idx(x, y, z), mode, d, cur);
            if (cur.isAir() || replaceable(cur)) c.missing++; else if (needs) { c.wrong++; if (cur.getBlock() == d.getBlock()) c.wrongState++; if (mode != Mode.ADD) c.todoByPhase.merge(Phase.CLEAR, 1, Integer::sum); } else continue;   // add mode leaves foreign blocks alone
            if (!needs) continue;
            c.todoByPhase.merge(ph, 1, Integer::sum);
            example(c, mp, BuiltInRegistries.BLOCK.getKey(d.getBlock()).getPath(), cur);
            Object[] cost = cost(d);
            if (cost == null) continue;
            if ((Item) cost[0] == Items.AIR) { c.noItem++; continue; }
            if ((Boolean) cost[2]) { if (!c.reusable.contains((Item) cost[0])) { c.reusable.add((Item) cost[0]); c.items.merge((Item) cost[0], 1, (a, b) -> a); } }
            else c.items.merge((Item) cost[0], (Integer) cost[1], Integer::sum);
        }
        return c;
    }

    private static void example(Counts c, BlockPos p, String wants, BlockState cur) {
        if (c.examples.size() < 8) c.examples.add(p.getX() + " " + p.getY() + " " + p.getZ() + ": wants " + wants + ", has " + BuiltInRegistries.BLOCK.getKey(cur.getBlock()).getPath());
    }

    /** How many of an item the player carries (whole inventory). */
    static int have(Item it) {
        var p = net.minecraft.client.Minecraft.getInstance().player;
        if (p == null) return 0;
        int n = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var s = inv.getItem(i);
            if (s.is(it)) n += s.getCount();
        }
        return n;
    }

    static String itemName(Item it) {
        return BuiltInRegistries.ITEM.getKey(it).getPath();
    }

}
