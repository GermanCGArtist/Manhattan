package dev.baritonequeue;

import dev.midtown.api.Area;
import net.minecraft.core.BlockPos;

import java.util.List;

/** Offline unit tests for {@link ChunkGrid}. */
public class ChunkGridTest {
    private static int passes = 0;
    private static int fails = 0;

    private static void check(String name, boolean cond) {
        if (cond) {
            passes++;
        } else {
            fails++;
            System.out.println("FAIL " + name);
        }
    }

    private static void eq(String name, Object got, Object expected) {
        if (java.util.Objects.equals(got, expected)) {
            passes++;
        } else {
            fails++;
            System.out.println("FAIL " + name + "\n  expected: " + expected + "\n  got:      " + got);
        }
    }

    public static void main(String[] args) {
        testOrderStrategyParse();
        testShouldChunk();
        testSplitUniformAndRemainder();
        testSplitNegativeCoordsAndGeometry();
        testSplitLShapeSkipsEmptyCorners();
        testClipMultiBox();
        testOrderSerpentine();
        testPickNext();

        System.out.println("ChunkGridTest: " + passes + " passed, " + fails + " failed.");
        if (fails > 0) System.exit(1);
    }

    private static void testOrderStrategyParse() {
        eq("parse serpentine lower", ChunkGrid.OrderStrategy.parse("serpentine"), ChunkGrid.OrderStrategy.SERPENTINE);
        eq("parse nearest upper", ChunkGrid.OrderStrategy.parse(" NEAREST "), ChunkGrid.OrderStrategy.NEAREST);
        eq("parse null", ChunkGrid.OrderStrategy.parse(null), ChunkGrid.OrderStrategy.SERPENTINE);
        eq("parse unknown", ChunkGrid.OrderStrategy.parse("spiral"), ChunkGrid.OrderStrategy.SERPENTINE);
    }

    private static void testShouldChunk() {
        List<int[]> box32 = List.of(new int[]{0, 60, 0, 31, 64, 31});
        check("exact 32x32 does not chunk at size 32", !ChunkGrid.shouldChunk(box32, 32, true));
        check("disabled does not chunk", !ChunkGrid.shouldChunk(List.of(new int[]{0, 60, 0, 63, 64, 63}), 32, false));
        check("non-positive chunkSize does not chunk", !ChunkGrid.shouldChunk(List.of(new int[]{0, 60, 0, 63, 64, 63}), 0, true));
        check("null boxes does not chunk", !ChunkGrid.shouldChunk(null, 32, true));
        check("empty boxes does not chunk", !ChunkGrid.shouldChunk(List.of(), 32, true));
        check("33 wide chunks at 32", ChunkGrid.shouldChunk(List.of(new int[]{0, 60, 0, 32, 64, 10}), 32, true));
        check("33 deep chunks at 32", ChunkGrid.shouldChunk(List.of(new int[]{0, 60, 0, 10, 64, 32}), 32, true));
        check("union of two small boxes chunks when span > size",
                ChunkGrid.shouldChunk(List.of(new int[]{0, 60, 0, 9, 64, 9}, new int[]{30, 60, 0, 39, 64, 9}), 32, true));
    }

    private static void testSplitUniformAndRemainder() {
        List<ChunkGrid.ColumnBox> cols = ChunkGrid.split(List.of(new int[]{100, -60, 80, 124, -55, 97}), 10);
        // X: 100..124 -> 25 blocks -> 3 slices (100..109, 110..119, 120..124)
        // Z: 80..97 -> 18 blocks -> 2 slices (80..89, 90..97)
        eq("3x2 column count", cols.size(), 6);
        eq("first col index", cols.get(0).index(), 0);
        eq("first col min", cols.get(0).min(), new BlockPos(100, -60, 80));
        eq("first col max", cols.get(0).max(), new BlockPos(109, -55, 89));
        ChunkGrid.ColumnBox last = cols.get(5);
        eq("last col index", last.index(), 5);
        eq("last col gridX", last.gridX(), 2);
        eq("last col gridZ", last.gridZ(), 1);
        eq("last col min", last.min(), new BlockPos(120, -60, 90));
        eq("last col max", last.max(), new BlockPos(124, -55, 97));
    }

    private static void testSplitNegativeCoordsAndGeometry() {
        List<ChunkGrid.ColumnBox> cols = ChunkGrid.split(List.of(new int[]{-40, -64, -30, -9, 10, -1}), 16);
        // X: -40..-9 (32 -> 2), Z: -30..-1 (30 -> 2)
        eq("negative 2x2 count", cols.size(), 4);
        ChunkGrid.ColumnBox c0 = cols.get(0);
        eq("c0 min", c0.min(), new BlockPos(-40, -64, -30));
        eq("c0 max", c0.max(), new BlockPos(-25, 10, -15));
        check("contains min", c0.contains(new BlockPos(-40, -64, -30)));
        check("contains max", c0.contains(new BlockPos(-25, 10, -15)));
        check("excludes y out of bounds", !c0.contains(new BlockPos(-30, 11, -20)));
        check("containsXZ ignores y", c0.containsXZ(-30, -20));
        eq("centerXZ", c0.centerXZ(0), new BlockPos(-33, 0, -23));
        eq("distanceSqXZ inside is 0", c0.distanceSqXZ(new BlockPos(-30, 50, -20)), 0.0);
        eq("distanceSqXZ edge is 0", c0.distanceSqXZ(new BlockPos(-25, 0, -15)), 0.0);
        eq("distanceSqXZ outside corner (3,4)", c0.distanceSqXZ(new BlockPos(-22, 0, -11)), 25.0);
        eq("toMidtownArea", c0.toMidtownArea(), new Area(-40, -64, -30, -25, 10, -15));
    }

    private static void testSplitLShapeSkipsEmptyCorners() {
        // L-shape: horizontal arm X=0..35, Z=0..11; vertical arm X=0..11, Z=12..35; Y=60..65
        List<int[]> lShape = List.of(
                new int[]{0, 60, 0, 35, 65, 11},
                new int[]{0, 60, 12, 11, 65, 35}
        );
        List<ChunkGrid.ColumnBox> cols = ChunkGrid.split(lShape, 12);
        // Grid is 3x3: row gz=0 has gx=0,1,2 (3 cols); row gz=1 has gx=0 (1 col); row gz=2 has gx=0 (1 col) => 5 cols total
        eq("L-shape column count", cols.size(), 5);
        for (int i = 0; i < cols.size(); i++) {
            eq("sequential index " + i, cols.get(i).index(), i);
            check("non-empty clip " + i, !cols.get(i).clip(lShape).isEmpty());
        }
    }

    private static void testClipMultiBox() {
        ChunkGrid.ColumnBox col = new ChunkGrid.ColumnBox(0, 0, 0, new BlockPos(10, 60, 10), new BlockPos(19, 70, 19));
        List<int[]> boxes = List.of(
                new int[]{15, 65, 15, 5, 62, 5},   // unnormalized; overlaps 10..15, 62..65, 10..15
                new int[]{25, 60, 25, 30, 70, 30}  // disjoint
        );
        List<int[]> clipped = col.clip(boxes);
        eq("clipped count", clipped.size(), 1);
        check("clipped box exact", java.util.Arrays.equals(clipped.get(0), new int[]{10, 62, 10, 15, 65, 15}));
    }

    private static void testOrderSerpentine() {
        List<ChunkGrid.ColumnBox> grid = ChunkGrid.split(List.of(new int[]{0, 60, 0, 29, 64, 29}), 10);
        eq("3x3 count", grid.size(), 9);

        // Start near min corner (0, 60, 0) -> row gz=0: gx 0,1,2; row gz=1: gx 2,1,0; row gz=2: gx 0,1,2
        List<ChunkGrid.ColumnBox> fromMin = ChunkGrid.orderSerpentine(grid, new BlockPos(1, 60, 1));
        StringBuilder seqMin = new StringBuilder();
        for (int i = 0; i < fromMin.size(); i++) {
            ChunkGrid.ColumnBox c = fromMin.get(i);
            eq("serpentine min reindex " + i, c.index(), i);
            seqMin.append(c.gridX()).append(',').append(c.gridZ()).append(';');
        }
        eq("serpentine from min corner", seqMin.toString(), "0,0;1,0;2,0;2,1;1,1;0,1;0,2;1,2;2,2;");

        // Start near max corner (29, 60, 29) -> row gz=2: gx 2,1,0; row gz=1: gx 0,1,2; row gz=0: gx 2,1,0
        List<ChunkGrid.ColumnBox> fromMax = ChunkGrid.orderSerpentine(grid, new BlockPos(29, 60, 29));
        StringBuilder seqMax = new StringBuilder();
        for (int i = 0; i < fromMax.size(); i++) {
            ChunkGrid.ColumnBox c = fromMax.get(i);
            eq("serpentine max reindex " + i, c.index(), i);
            seqMax.append(c.gridX()).append(',').append(c.gridZ()).append(';');
        }
        eq("serpentine from max corner", seqMax.toString(), "2,2;1,2;0,2;0,1;1,1;2,1;2,0;1,0;0,0;");

        // Null startPos defaults to min corner
        List<ChunkGrid.ColumnBox> fromNull = ChunkGrid.orderSerpentine(grid, null);
        eq("serpentine null starts at (0,0)", fromNull.get(0).gridX() + "," + fromNull.get(0).gridZ(), "0,0");
    }

    private static void testPickNext() {
        List<ChunkGrid.ColumnBox> grid = ChunkGrid.split(List.of(new int[]{0, 60, 0, 29, 64, 19}), 10);
        // 3x2 grid
        ChunkGrid.ColumnBox serp = ChunkGrid.pickNext(grid, new BlockPos(25, 60, 15), ChunkGrid.OrderStrategy.SERPENTINE);
        eq("SERPENTINE picks first", serp, grid.get(0));

        ChunkGrid.ColumnBox near = ChunkGrid.pickNext(grid, new BlockPos(25, 60, 15), ChunkGrid.OrderStrategy.NEAREST);
        eq("NEAREST picks containing chunk (2,1)", near.gridX() + "," + near.gridZ(), "2,1");

        ChunkGrid.ColumnBox nearNull = ChunkGrid.pickNext(grid, null, ChunkGrid.OrderStrategy.NEAREST);
        eq("NEAREST with null player picks first", nearNull, grid.get(0));

        // Tie-breaking by list order: equidistant from (15, 60, 9) vs (15, 60, 10)? Check exact tie at X=15, Z=9.5 -> integer coords:
        List<ChunkGrid.ColumnBox> two = List.of(
                new ChunkGrid.ColumnBox(0, 0, 0, new BlockPos(0, 60, 0), new BlockPos(9, 64, 9)),
                new ChunkGrid.ColumnBox(1, 1, 0, new BlockPos(20, 60, 0), new BlockPos(29, 64, 9))
        );
        // Player at x=15 -> dist to [0..9] is 6 (36), dist to [20..29] is 5 (25); at x=14 -> dist 5 vs 6;
        // For exact tie: [0..9] and [19..28] with player at x=14 (dist 5 vs 5)
        List<ChunkGrid.ColumnBox> tied = List.of(
                new ChunkGrid.ColumnBox(0, 0, 0, new BlockPos(0, 60, 0), new BlockPos(9, 64, 9)),
                new ChunkGrid.ColumnBox(1, 1, 0, new BlockPos(19, 60, 0), new BlockPos(28, 64, 9))
        );
        eq("NEAREST tie breaks by list order", ChunkGrid.pickNext(tied, new BlockPos(14, 60, 5), ChunkGrid.OrderStrategy.NEAREST), tied.get(0));
    }
}
