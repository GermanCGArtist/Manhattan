package dev.baritonequeue;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Cuts a big box into chunks (columns of full height) that are worked one after the other. Pure: no world, so it is
 * tested in LangTest. The columns are aligned to the box's min corner, so the same box always gives the same chunks
 * (their ids in the ledger stay valid between runs).
 */
final class Chunker {
    private Chunker() {}

    static final int DEFAULT_SIZE = 32;

    /** A box, both corners inclusive. */
    record Box(int x0, int y0, int z0, int x1, int y1, int z1) {
        int sx() { return x1 - x0 + 1; }

        int sy() { return y1 - y0 + 1; }

        int sz() { return z1 - z0 + 1; }

        boolean contains(int x, int y, int z) { return x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1; }

        boolean containsXZ(int x, int z) { return x >= x0 && x <= x1 && z >= z0 && z <= z1; }

        /** The middle of the box, as doubles (a chunk of even size has no middle block). */
        double cx() { return (x0 + x1 + 1) / 2.0; }

        double cz() { return (z0 + z1 + 1) / 2.0; }

        double distanceXZ(double x, double z) { return Math.hypot(cx() - x, cz() - z); }

        /** "x0,z0": the end of a chunk's job id. */
        String key() { return x0 + "," + z0; }

        /** "x0 z0 .. x1 z1" for messages. */
        String text() { return x0 + " " + z0 + " .. " + x1 + " " + z1; }
    }

    /** The chunk columns of a box: size x size in XZ, full height; the last ones in each direction are smaller. Ordered by x, then z. */
    static List<Box> split(Box whole, int size) {
        int s = Math.max(1, size);
        List<Box> out = new ArrayList<>();
        for (int x = whole.x0(); x <= whole.x1(); x += s) {
            for (int z = whole.z0(); z <= whole.z1(); z += s) {
                out.add(new Box(x, whole.y0(), z, Math.min(whole.x1(), x + s - 1), whole.y1(), Math.min(whole.z1(), z + s - 1)));
            }
        }
        return out;
    }

    /** The open chunk whose centre is nearest to the position (ties: the earlier one in the list), or null when none is open. */
    static Box next(List<Box> chunks, BlockPos from, Predicate<Box> open) {
        return nearest(chunks, from.getX() + 0.5, from.getZ() + 0.5, open);
    }

    private static Box nearest(List<Box> chunks, double fx, double fz, Predicate<Box> open) {
        Box best = null;
        double bestD = Double.MAX_VALUE;
        for (Box b : chunks) {
            if (!open.test(b)) continue;
            double d = b.distanceXZ(fx, fz);
            if (d < bestD) { best = b; bestD = d; }
        }
        return best;
    }

    /**
     * The order the next n open chunks will come in if the bot starts at the given chunk: each one is the nearest to
     * the one before. The start itself counts as the first one when it is open.
     */
    static List<Box> upcoming(List<Box> chunks, Box start, int n, Predicate<Box> open) {
        List<Box> out = new ArrayList<>();
        Box cur = start;
        if (cur != null && open.test(cur)) out.add(cur);
        while (cur != null && out.size() < n) {
            Box nx = nearest(chunks, cur.cx(), cur.cz(), b -> open.test(b) && !out.contains(b));
            if (nx == null) break;
            out.add(nx);
            cur = nx;
        }
        return out;
    }
}
