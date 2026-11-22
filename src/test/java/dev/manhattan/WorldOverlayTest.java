package dev.manhattan;

import dev.manhattan.fleet.FleetProtocol;
import dev.manhattan.project.Project;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline unit tests for {@link WorldOverlay}: point &amp; area collection, deduplication across
 * Project/Fleet/SurvivalKit/LoadChest/BuildTools sources, label formatting, and live status transitions.
 */
public final class WorldOverlayTest {
    private static int passed = 0;

    public static void main(String[] args) {
        testPointCollectionAndDeduplication();
        testFleetEditorSeesPeerBeds();
        testAreaCollectionAndSafePriority();
        testLabelFormatting();
        testStatusTransitions();
        testAdjacentAreaMerging();
        testOverlapResolutionDistinctLayers();
        testPerElementVisibility();
        System.out.println("WorldOverlayTest: ALL " + passed + " tests passed.");
    }

    private static void testPointCollectionAndDeduplication() {
        Project p = new Project("base", "Base", "world");
        p.places.bed = new int[]{10, 64, -20};
        p.places.toolChest = new int[]{12, 64, -20};
        p.places.returnChest = new int[]{14, 64, -20};
        p.places.dumpChest = new int[]{16, 64, -20};
        p.places.scaffoldChest = new int[]{18, 64, -20};
        p.places.materialChests.add(new int[]{20, 64, -20});

        // SurvivalKit & LoadChest mirror the same coordinates while running -> must deduplicate!
        Map<String, int[]> savedChests = new LinkedHashMap<>();
        savedChests.put("cobble", new int[]{22, 64, -20});

        WorldOverlay.Sources src = new WorldOverlay.Sources(
                p, p, null,
                new int[]{10, 64, -20}, // fleet assigned bed at same coords as project bed
                List.of(),
                new int[]{10, 64, -20}, // survival bed mirror
                new int[]{12, 64, -20}, // survival supply mirror of toolChest
                List.of(),
                new int[]{14, 64, -20}, // survival return mirror
                new int[]{16, 64, -20}, // loadchest mirror of dumpChest
                savedChests,
                List.of(), true, null, null,
                Map.of(), Map.of()
        );

        List<WorldOverlay.PointMarker> pts = WorldOverlay.collectPoints(src);
        assertEquals(7, pts.size(), "Expected 7 deduplicated point markers (1 bed + 6 chests)");

        WorldOverlay.PointMarker bed = pts.get(0);
        assertEquals(WorldOverlay.Category.BED, bed.category(), "First marker is bed");
        assertEquals("Assigned Bed", bed.label(), "Assigned Bed not cluttered with duplicate 'Bed'");

        WorldOverlay.PointMarker tool = pts.get(1);
        assertEquals("Tool Chest", tool.label(), "Tool Chest not duplicated by Supply Chest mirror");
        passed++;
    }

    private static void testFleetEditorSeesPeerBeds() {
        FleetProtocol.BotInfo bot1 = new FleetProtocol.BotInfo();
        bot1.name = "MinerBot1";
        bot1.assignedBed = new int[]{100, 70, 200};

        FleetProtocol.BotInfo bot2 = new FleetProtocol.BotInfo();
        bot2.name = "MinerBot2";
        bot2.localBed = new int[]{104, 70, 200};

        WorldOverlay.Sources src = new WorldOverlay.Sources(
                null, null, null, null,
                List.of(bot1, bot2),
                null, null, List.of(), null, null,
                Map.of(), List.of(), true, null, null,
                Map.of(), Map.of()
        );

        List<WorldOverlay.PointMarker> pts = WorldOverlay.collectPoints(src);
        assertEquals(2, pts.size(), "Editor sees both fleet peer beds");
        assertTrue(pts.get(0).label().contains("MinerBot1"), "Bot 1 bed labeled with bot name");
        assertTrue(pts.get(1).label().contains("MinerBot2"), "Bot 2 bed labeled with bot name");
        passed++;
    }

    private static void testAreaCollectionAndSafePriority() {
        Project p = new Project("base", "Base", "world");
        p.places.protectedAreas.put("camp", List.of(new int[]{0, 60, 0, 10, 70, 10}));

        dev.buildtools.Api.ProtectedZone z = new dev.buildtools.Api.ProtectedZone(
                "camp", "camp", 0, 60, 0, 10, 70, 10, false, false);

        Map<String, List<int[]>> savedAreas = new LinkedHashMap<>();
        savedAreas.put("quarry", List.of(new int[]{30, 50, 30, 50, 65, 50}));
        savedAreas.put("camp_saved", List.of(new int[]{0, 60, 0, 10, 70, 10}));

        Map<String, String> roles = Map.of("quarry", "dig", "camp_saved", "custom");

        WorldOverlay.Sources src = new WorldOverlay.Sources(
                p, p, null, null, List.of(),
                null, null, List.of(), null, null,
                Map.of(),
                List.of(z), true,
                new int[]{-5, 64, -5}, new int[]{-1, 68, -1},
                savedAreas, roles
        );

        List<WorldOverlay.AreaMarker> all = WorldOverlay.collectAreas(src, true, true);
        assertEquals(3, all.size(), "Deduped camp + pending safe area + quarry task area");
        assertTrue(all.get(0).safe(), "Camp stays marked safe even if same box exists in savedAreas");

        List<WorldOverlay.AreaMarker> safeOnly = WorldOverlay.collectAreas(src, true, false);
        assertEquals(2, safeOnly.size(), "Safe-only filter excludes quarry");
        passed++;
    }

    private static void testLabelFormatting() {
        WorldOverlay.PointMarker bed = new WorldOverlay.PointMarker(1, 64, 2, "Bed", WorldOverlay.Category.BED, WorldOverlay.Role.BED);
        assertEquals("Bed [OK]", WorldOverlay.formatPointLabel(bed, new WorldOverlay.CheckResult(WorldOverlay.Status.OK, "red_bed"), null), "OK bed label");
        assertEquals("Bed [MISSING!]", WorldOverlay.formatPointLabel(bed, new WorldOverlay.CheckResult(WorldOverlay.Status.MISSING, "air"), null), "Missing bed label");
        assertEquals("Bed [WRONG: dirt]", WorldOverlay.formatPointLabel(bed, new WorldOverlay.CheckResult(WorldOverlay.Status.WRONG, "dirt"), null), "Wrong block bed label");

        WorldOverlay.PointMarker chest = new WorldOverlay.PointMarker(3, 64, 4, "Tool Chest", WorldOverlay.Category.CHEST, WorldOverlay.Role.TOOL_CHEST);
        assertEquals("Tool Chest [OK] (EMPTY)", WorldOverlay.formatPointLabel(chest, new WorldOverlay.CheckResult(WorldOverlay.Status.OK, "chest"), "EMPTY"), "Fleet empty state appended");
        assertEquals("Tool Chest [BLOCKED]", WorldOverlay.formatPointLabel(chest, new WorldOverlay.CheckResult(WorldOverlay.Status.BLOCKED, "lid blocked"), null), "Blocked chest label");
        passed++;
    }

    private static void testStatusTransitions() {
        WorldOverlay.PointMarker bed = new WorldOverlay.PointMarker(10, 64, -20, "Bed", WorldOverlay.Category.BED, WorldOverlay.Role.BED);

        // Unloaded -> OK should NOT fire a notification
        assertTrue(WorldOverlay.evaluateTransition(WorldOverlay.Status.UNLOADED, WorldOverlay.Status.OK, bed, "red_bed") == null,
                "UNLOADED -> OK is silent");

        // OK -> MISSING (creeper blew up the bed!)
        String broke = WorldOverlay.evaluateTransition(WorldOverlay.Status.OK, WorldOverlay.Status.MISSING, bed, "air");
        assertTrue(broke != null && broke.contains("MISSING") && broke.contains("10 64 -20"),
                "OK -> MISSING notifies with coordinates");

        // MISSING -> OK (player placed bed back!)
        String fixed = WorldOverlay.evaluateTransition(WorldOverlay.Status.MISSING, WorldOverlay.Status.OK, bed, "red_bed");
        assertTrue(fixed != null && fixed.contains("recognised again") && fixed.contains("red_bed"),
                "MISSING -> OK notifies bed recognised again");

        // Same status -> silent
        assertTrue(WorldOverlay.evaluateTransition(WorldOverlay.Status.OK, WorldOverlay.Status.OK, bed, "red_bed") == null,
                "OK -> OK is silent");
        passed++;
    }

    private static void testAdjacentAreaMerging() {
        WorldOverlay.AreaMarker left = new WorldOverlay.AreaMarker(0, 60, 0, 9, 70, 9, "quarry", false, "dig");
        WorldOverlay.AreaMarker right = new WorldOverlay.AreaMarker(10, 60, 0, 19, 70, 9, "quarry", false, "dig");
        WorldOverlay.AreaMarker inner = new WorldOverlay.AreaMarker(2, 62, 2, 6, 68, 6, "quarry", false, "dig");
        WorldOverlay.AreaMarker other = new WorldOverlay.AreaMarker(10, 60, 0, 19, 70, 9, "safe_camp", true, "protect");

        List<WorldOverlay.AreaMarker> merged = WorldOverlay.mergeAdjacentAreas(List.of(left, right, inner, other));
        assertEquals(2, merged.size(), "Adjacent + contained boxes of same area merge into 1; distinct area kept separate");
        WorldOverlay.AreaMarker q = merged.get(0);
        assertEquals(0, q.x1(), "Merged x1");
        assertEquals(19, q.x2(), "Merged x2");
        passed++;
    }

    private static void testOverlapResolutionDistinctLayers() {
        WorldOverlay.AreaMarker a = new WorldOverlay.AreaMarker(0, 60, 0, 20, 80, 20, "dig1", false, "dig");
        WorldOverlay.AreaMarker b = new WorldOverlay.AreaMarker(10, 60, 10, 30, 80, 30, "dig2", false, "dig");
        WorldOverlay.AreaMarker c = new WorldOverlay.AreaMarker(100, 60, 100, 110, 70, 110, "far", false, "dig");

        List<WorldOverlay.StyledArea> styled = WorldOverlay.resolveOverlaps(List.of(a, b, c), true);
        assertEquals(3, styled.size(), "3 styled areas");
        assertTrue(styled.get(0).overlapping() && styled.get(1).overlapping(), "a and b detected as overlapping");
        assertTrue(!styled.get(2).overlapping(), "c is not overlapping");
        assertTrue(styled.get(0).layerIndex() != styled.get(1).layerIndex(), "Overlapping areas get distinct layers");
        assertTrue(styled.get(0).inflate() != styled.get(1).inflate(), "Overlapping areas get distinct Z-fighting offsets");
        assertTrue(styled.get(0).color() != styled.get(1).color(), "Overlapping task areas get distinct colors");
        passed++;
    }

    private static void testPerElementVisibility() {
        WorldOverlay.resetHidden();
        assertTrue(WorldOverlay.isAreaVisible("quarry"), "Visible by default");
        WorldOverlay.setAreaVisible("Quarry", false);
        assertTrue(!WorldOverlay.isAreaVisible("quarry"), "Case-insensitive hide works");

        WorldOverlay.setPointVisible(WorldOverlay.Category.BED, 10, 64, -20, false);
        assertTrue(!WorldOverlay.isPointVisible(WorldOverlay.Category.BED, 10, 64, -20), "Point hidden");
        assertTrue(WorldOverlay.isPointVisible(WorldOverlay.Category.CHEST, 10, 64, -20), "Different category unaffected");

        assertEquals(2, WorldOverlay.hiddenCount(), "2 hidden elements");
        WorldOverlay.resetHidden();
        assertEquals(0, WorldOverlay.hiddenCount(), "Reset clears all hidden elements");
        passed++;
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("Assertion failed: " + msg);
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Assertion failed (" + msg + "): expected=" + expected + " actual=" + actual);
        }
    }
}
