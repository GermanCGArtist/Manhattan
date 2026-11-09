package dev.baritonequeue;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * How far one step of the script is. Baritone does not report progress, so it is worked out from the world:
 * "sel ca / replace / set" count the blocks in the selection that still have to change (a little each tick, so big
 * selections cost nothing), "goto" uses the distance, "wait" the clock. Everything else is unknown (-1) and only
 * counts once it is finished.
 */
final class Estimate {
    private static final int BUDGET = 8000;   // blocks looked at per tick

    private enum Mode { NONE, TIME, SELECTION, GOTO }

    private Mode mode = Mode.NONE;
    private int ticks;
    private int timeTotal;

    // selection
    private Predicate<BlockState> remains;
    private List<int[]> boxes;
    private int box, retry;
    private long index, counted;
    private long peak = -1, remaining = -1;

    // goto
    private double startDist = -1, dist = -1;
    private final double[] target = new double[3];
    private boolean has3;

    // progress marks, for the stall check
    private double best = Double.MAX_VALUE;
    private boolean progressed;
    // A selection can only be counted where chunks are loaded, so the count depends on where you stand. Each pass is
    // compared with the best earlier pass that saw about the same share of the selection (key = share in 5% steps).
    private final java.util.Map<Integer, Long> bestByView = new java.util.HashMap<>();
    private long loadedSeen, volume;

    /** A wait of this many ticks. */
    static Estimate time(int ticks) {
        Estimate e = new Estimate();
        e.mode = Mode.TIME;
        e.timeTotal = Math.max(1, ticks);
        return e;
    }

    /** Counts the blocks of the selection that are still there, for clear_area. */
    static Estimate solid() {
        Estimate e = new Estimate();
        e.remains = st -> !st.isAir() && st.getFluidState().isEmpty();
        e.mode = Mode.SELECTION;
        return e;
    }

    /** Counts the wet cells of the selection (water, seagrass, kelp ...), for fill_water. */
    static Estimate water() {
        Estimate e = new Estimate();
        e.remains = st -> st.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && !st.blocksMotion();
        e.mode = Mode.SELECTION;
        return e;
    }

    /** The text of the step, as the runner executes it (no leading #). */
    static Estimate of(String command) {
        Estimate e = new Estimate();
        String[] w = command.trim().split("\\s+");
        String c = w[0].toLowerCase();
        try {
            if ((c.equals("sel") || c.equals("selection") || c.equals("s")) && w.length > 1) {
                e.selection(w);
            } else if (c.equals("goto") && (w.length == 4 || w.length == 3)) {
                e.mode = Mode.GOTO;
                if (w.length == 4) {
                    e.target[0] = Double.parseDouble(w[1]);
                    e.target[1] = Double.parseDouble(w[2]);
                    e.target[2] = Double.parseDouble(w[3]);
                    e.has3 = true;
                } else {
                    e.target[0] = Double.parseDouble(w[1]);
                    e.target[2] = Double.parseDouble(w[2]);
                }
            }
        } catch (RuntimeException ex) {
            e.mode = Mode.NONE;   // not a form we can follow
        }
        return e;
    }

    private static Block block(String word) {
        String w = word.toLowerCase();
        Identifier id = Identifier.tryParse(w.contains(":") ? w : "minecraft:" + w);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return null;
        return BuiltInRegistries.BLOCK.getValue(id);
    }

    private void selection(String[] w) {
        String sub = w[1].toLowerCase();
        switch (sub) {
            case "cleararea", "ca" -> {
                remains = st -> !st.isAir();
                mode = Mode.SELECTION;
            }
            case "set", "fill", "s", "f" -> {
                if (w.length < 3) return;
                Block to = block(w[2]);
                if (to == null) return;
                remains = st -> st.getBlock() != to;
                mode = Mode.SELECTION;
            }
            case "replace", "r" -> {
                if (w.length < 4) return;   // sel replace <from...> <to>
                Set<Block> from = new HashSet<>();
                for (int i = 2; i < w.length - 1; i++) {
                    Block b = block(w[i]);
                    if (b == null) return;
                    from.add(b);
                }
                remains = st -> from.contains(st.getBlock());
                mode = Mode.SELECTION;
            }
            default -> { }
        }
    }

    boolean known() {
        return fraction() >= 0;
    }

    void tick() {
        ticks++;
        switch (mode) {
            case SELECTION -> scan();
            case GOTO -> {
                var p = Minecraft.getInstance().player;
                if (p == null) return;
                double dx = p.getX() - target[0], dz = p.getZ() - target[2];
                double dy = has3 ? p.getY() - target[1] : 0;
                dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (startDist < 0) startDist = Math.max(dist, 1);
            }
            default -> { }
        }
        double now = mode == Mode.GOTO ? dist : -1;
        if (now >= 0 && now < best - 1.5) {
            best = now;
            progressed = true;
        }
    }

    /** True once if the step got measurably closer to done since the last call (fewer blocks left, nearer the goal). */
    boolean progressed() {
        boolean p = progressed;
        progressed = false;
        return p;
    }

    /** Whether "progressed" is meaningful for this step (it can be measured). */
    boolean measurable() {
        return (mode == Mode.SELECTION && remaining >= 0) || (mode == Mode.GOTO && dist >= 0);
    }

    private static List<int[]> readBoxes() {
        List<int[]> out = new ArrayList<>();
        for (var s : Util.baritone().getSelectionManager().getSelections()) {
            out.add(new int[]{s.min().x, s.min().y, s.min().z, s.max().x, s.max().y, s.max().z});
        }
        return out;
    }

    /** Looks at a slice of the selection per tick; a full pass sets "remaining". */
    private void scan() {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        if (boxes == null) {
            if (retry > 0) { retry--; return; }   // the selection may not exist yet; look again in a second
            boxes = readBoxes();
            if (boxes.isEmpty()) { boxes = null; retry = 20; return; }
            box = 0;
            index = 0;
            counted = 0;
            loadedSeen = 0;
            volume = 0;
            for (int[] bx : boxes) volume += (bx[3] - bx[0] + 1L) * (bx[4] - bx[1] + 1L) * (bx[5] - bx[2] + 1L);
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int budget = BUDGET;
        while (budget > 0 && box < boxes.size()) {
            int[] b = boxes.get(box);
            long sx = b[3] - b[0] + 1L, sy = b[4] - b[1] + 1L, sz = b[5] - b[2] + 1L;
            long n = sx * sy * sz;
            while (budget > 0 && index < n) {
                int x = b[0] + (int) (index % sx);
                int z = b[2] + (int) ((index / sx) % sz);
                int y = b[1] + (int) (index / (sx * sz));
                index++;
                budget--;
                pos.set(x, y, z);
                if (!level.hasChunkAt(pos)) {   // not loaded: cannot tell, leave the rest of this chunk's row out
                    int run = (int) Math.min(16 - (x & 15), sx - (index - 1) % sx);
                    index += run - 1;
                    continue;
                }
                loadedSeen++;
                if (remains.test(level.getBlockState(pos))) counted++;
            }
            if (index >= n) { box++; index = 0; }
        }
        if (box >= boxes.size()) {
            remaining = counted;
            peak = Math.max(peak, counted);
            boxes = null;
            int view = volume == 0 ? 0 : (int) (loadedSeen * 20 / volume);
            Long before = bestByView.get(view);
            if (before == null || counted < before) {
                bestByView.put(view, counted);
                progressed = true;   // new view, or fewer blocks left than any earlier look from here
            }
        }
    }

    /** 0..1, or -1 when this step cannot be followed (yet). */
    double fraction() {
        switch (mode) {
            case TIME -> { return Math.min(1.0, ticks / (double) timeTotal); }
            case SELECTION -> {
                if (peak < 0) return -1;
                return peak == 0 ? 1.0 : Math.max(0.0, 1.0 - remaining / (double) peak);
            }
            case GOTO -> {
                if (startDist < 0) return -1;
                return Math.max(0.0, Math.min(1.0, 1.0 - dist / startDist));
            }
            default -> { return -1; }
        }
    }

    /** What is left, in words ("1234 blocks left"), or "". */
    String left() {
        return switch (mode) {
            case SELECTION -> remaining < 0 ? "measuring..." : remaining + " block" + (remaining == 1 ? "" : "s") + " left";
            case GOTO -> dist < 0 ? "" : Math.round(dist) + " blocks to go";
            default -> "";
        };
    }

    /** Ticks until this step is done at the pace so far, or -1. */
    long etaTicks() {
        double f = fraction();
        if (mode == Mode.TIME) return Math.max(0, timeTotal - ticks);
        if (f < 0.03 || f >= 1 || ticks < 100) return -1;
        return (long) (ticks * (1 - f) / f);
    }

    int elapsedTicks() {
        return ticks;
    }

    static String duration(long ticks) {
        long s = ticks / 20;
        if (s < 60) return s + " s";
        long m = s / 60;
        if (m < 60) return m + " min";
        return (m / 60) + " h " + (m % 60) + " min";
    }
}
