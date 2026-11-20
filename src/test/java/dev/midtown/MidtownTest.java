package dev.midtown;

import dev.midtown.api.Area;
import dev.midtown.api.BenchmarkLogger;
import dev.midtown.api.BlockFilter;
import dev.midtown.api.EngineSelector;
import dev.midtown.api.EngineStatus;
import dev.midtown.api.GoalSpec;
import dev.midtown.api.PathEngine;
import dev.midtown.exec.EdgeTracker;
import dev.midtown.exec.EdgeTracker.EdgeCheckResult;
import dev.midtown.exec.MidtownCoordinator;
import dev.midtown.exec.MidtownCoordinator.ResumeOutcome;
import dev.midtown.exec.MidtownCoordinator.TickAction;
import dev.midtown.exec.MidtownEngine;
import dev.midtown.exec.MineController;
import dev.midtown.exec.PathNode;
import dev.midtown.exec.PathNode.EdgeKey;
import dev.midtown.exec.Planner;
import dev.midtown.plan.AStarStorage;
import dev.midtown.plan.BlockKind;
import dev.midtown.plan.CalculationContext;
import dev.midtown.plan.CalculationContext.ProtectedRegion;
import dev.midtown.plan.DeathMemory;
import dev.midtown.plan.EdgeGenerator;
import dev.midtown.plan.GoalAdapters;
import dev.midtown.plan.GridWorldSnapshot;
import dev.midtown.plan.HazardCost;
import dev.midtown.plan.MidtownPlanner;
import dev.midtown.plan.MoveEdge;
import dev.midtown.plan.PlanResult;
import dev.midtown.plan.WorldSnapshot;
import net.minecraft.core.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Offline smoke and contract tests for Midtown PathEngine abstractions,
 * EdgeTracker, DirectStepPlanner, MidtownCoordinator, and 3D A* MidtownPlanner.
 * Run directly or via Gradle task {@code midtownTest}.
 */
public class MidtownTest {

    private static int passes = 0;
    private static int fails = 0;

    public static void main(String[] args) {
        System.out.println("Running Midtown unit & smoke tests...");

        testEngineStatus();
        testArea();
        testGoalSpec();
        testBlockFilter();
        testBenchmarkLogger();
        testEngineSelector();
        testPathEngineContract();
        testEdgeTracker();
        testDirectStepPlanner();
        testMidtownCoordinator();
        testNearGoalConsistency();
        testStartGoalWithPathActionPreservation();
        testHazardCost();
        testMidtownPlanner();
        testItemSaverSettings();
        testMultiBoxProtectedArea();
        testWaterToShoreCrossing();
        testGoalChunkRadius();
        testPlannerComponentsParity();
        testMineController();
        testCaveAndDeathMemory();

        System.out.println("\nMidtownTest summary: " + passes + " passed, " + fails + " failed.");
        if (fails > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ Test Suites

    private static void testEngineStatus() {
        System.out.println("Checking EngineStatus enum...");
        same("EngineStatus count", EngineStatus.values().length, 6);
        same("IDLE name", EngineStatus.IDLE.name(), "IDLE");
        same("PLANNING name", EngineStatus.PLANNING.name(), "PLANNING");
        same("MOVING name", EngineStatus.MOVING.name(), "MOVING");
        same("STUCK name", EngineStatus.STUCK.name(), "STUCK");
        same("DONE name", EngineStatus.DONE.name(), "DONE");
        same("FAILED name", EngineStatus.FAILED.name(), "FAILED");
    }

    private static void testArea() {
        System.out.println("Checking Area bounds and queries...");
        Area a = Area.of(10, 20, 30, 0, 5, 10);
        same("area minX", a.minX(), 0);
        same("area minY", a.minY(), 5);
        same("area minZ", a.minZ(), 10);
        same("area maxX", a.maxX(), 10);
        same("area maxY", a.maxY(), 20);
        same("area maxZ", a.maxZ(), 30);
        same("area volume", a.volume(), 11L * 16L * 21L);

        same("area minPos", a.minPos(), new BlockPos(0, 5, 10));
        same("area maxPos", a.maxPos(), new BlockPos(10, 20, 30));

        assertTrue("area contains interior", a.contains(5, 10, 15));
        assertTrue("area contains min corner", a.contains(0, 5, 10));
        assertTrue("area contains max corner", a.contains(10, 20, 30));
        assertFalse("area outside X", a.contains(11, 10, 15));
        assertFalse("area outside Y", a.contains(5, 4, 15));
        assertFalse("area outside Z", a.contains(5, 10, 31));

        assertTrue("area contains BlockPos", a.contains(new BlockPos(5, 10, 15)));
        assertTrue("area within margin", a.within(new BlockPos(12, 20, 30), 2));
        assertFalse("area outside margin", a.within(new BlockPos(13, 20, 30), 2));

        Area fromPos = Area.of(new BlockPos(5, 15, 25), new BlockPos(-5, 0, 10));
        same("area of BlockPos minX", fromPos.minX(), -5);
        same("area of BlockPos maxX", fromPos.maxX(), 5);

        assertThrows("area inverted bounds constructor", IllegalArgumentException.class,
                () -> new Area(10, 0, 0, 5, 0, 0));
    }

    private static void testGoalSpec() {
        System.out.println("Checking GoalSpec hierarchy and factories...");
        BlockPos p = new BlockPos(1, 2, 3);

        GoalSpec.Exact exact = GoalSpec.exact(p);
        same("exact pos", exact.pos(), p);

        GoalSpec.Near near = GoalSpec.near(p, 4);
        same("near pos", near.pos(), p);
        same("near range", near.range(), 4);

        GoalSpec.XZ xz = GoalSpec.xz(100, -200);
        same("xz x", xz.x(), 100);
        same("xz z", xz.z(), -200);

        GoalSpec.LineOfSight los = GoalSpec.lineOfSight(p, 4.5);
        same("los target", los.target(), p);
        same("los maxRange", los.maxRange(), 4.5);

        GoalSpec.RunAway runaway = GoalSpec.runAway(p, 16.0);
        same("runaway from", runaway.from(), p);
        same("runaway distance", runaway.distance(), 16.0);

        assertThrows("exact null pos", NullPointerException.class, () -> GoalSpec.exact(null));
        assertThrows("near null pos", NullPointerException.class, () -> GoalSpec.near(null, 2));
        assertThrows("near negative range", IllegalArgumentException.class, () -> GoalSpec.near(p, -1));
        assertThrows("los null target", NullPointerException.class, () -> GoalSpec.lineOfSight(null, 4.0));
        assertThrows("los non-positive range", IllegalArgumentException.class, () -> GoalSpec.lineOfSight(p, 0.0));
        assertThrows("runaway null from", NullPointerException.class, () -> GoalSpec.runAway(null, 10.0));
        assertThrows("runaway non-positive distance", IllegalArgumentException.class, () -> GoalSpec.runAway(p, -1.0));
    }

    private static void testBlockFilter() {
        System.out.println("Checking BlockFilter creation and queries...");
        BlockFilter f = BlockFilter.ofNames("minecraft:diamond_ore", "iron_ore");
        same("filter blockIds size", f.blockIds().size(), 2);
        assertTrue("filter contains diamond_ore", f.blockIds().contains("minecraft:diamond_ore"));
        assertTrue("filter contains iron_ore", f.blockIds().contains("iron_ore"));
        assertFalse("filter isEmpty", f.isEmpty());

        BlockFilter predicateFilter = BlockFilter.matching(state -> true);
        assertFalse("predicateFilter isEmpty", predicateFilter.isEmpty());

        assertThrows("ofNames null array", NullPointerException.class, () -> BlockFilter.ofNames((String[]) null));
        assertThrows("matching null predicate", NullPointerException.class, () -> BlockFilter.matching(null));
    }

    private static void testBenchmarkLogger() {
        System.out.println("Checking BenchmarkLogger format and validation...");
        same("BenchmarkLogger prefix", BenchmarkLogger.PREFIX, "BENCHMARK_RESULT");

        String formatted = BenchmarkLogger.format("baritone", "nav_flat_obstacle", true, 12500, 120, 0, 3, 0);
        String expected = "BENCHMARK_RESULT engine=baritone scenario=nav_flat_obstacle pass=true elapsed_ms=12500 blocks_moved=120 stuck_count=0 interrupts_handled=3 deaths=0";
        same("benchmark line format", formatted, expected);

        String sanitized = BenchmarkLogger.format("midtown", "interrupt mid action", false, 4500, 30, 2, 1, 1);
        String expectedSanitized = "BENCHMARK_RESULT engine=midtown scenario=interrupt_mid_action pass=false elapsed_ms=4500 blocks_moved=30 stuck_count=2 interrupts_handled=1 deaths=1";
        same("benchmark line sanitized", sanitized, expectedSanitized);

        BenchmarkLogger.Result result = new BenchmarkLogger.Result(
                "baritone", "survival_marathon", true, 300000, 450, 0, 10, 0);
        same("benchmark Result format", BenchmarkLogger.format(result),
                "BENCHMARK_RESULT engine=baritone scenario=survival_marathon pass=true elapsed_ms=300000 blocks_moved=450 stuck_count=0 interrupts_handled=10 deaths=0");

        assertThrows("invalid engine", IllegalArgumentException.class,
                () -> new BenchmarkLogger.Result("unknown", "scenario", true, 0, 0, 0, 0, 0));
        assertThrows("blank scenario", IllegalArgumentException.class,
                () -> new BenchmarkLogger.Result("baritone", "   ", true, 0, 0, 0, 0, 0));
        assertThrows("negative elapsed", IllegalArgumentException.class,
                () -> new BenchmarkLogger.Result("baritone", "scenario", true, -1, 0, 0, 0, 0));
        assertThrows("negative blocks", IllegalArgumentException.class,
                () -> new BenchmarkLogger.Result("baritone", "scenario", true, 0, -1, 0, 0, 0));
        assertThrows("negative stuck", IllegalArgumentException.class,
                () -> new BenchmarkLogger.Result("baritone", "scenario", true, 0, 0, -1, 0, 0));
        assertThrows("negative interrupts", IllegalArgumentException.class,
                () -> new BenchmarkLogger.Result("baritone", "scenario", true, 0, 0, 0, -1, 0));
        assertThrows("negative deaths", IllegalArgumentException.class,
                () -> new BenchmarkLogger.Result("baritone", "scenario", true, 0, 0, 0, 0, -1));
    }

    private static void testEngineSelector() {
        System.out.println("Checking EngineSelector resolution and overrides...");
        String prevProp = System.getProperty(EngineSelector.PROPERTY);
        try {
            EngineSelector.clearOption();
            System.clearProperty(EngineSelector.PROPERTY);

            same("default engine", EngineSelector.selectedName(), EngineSelector.DEFAULT_ENGINE);
            same("default engine is midtown", EngineSelector.DEFAULT_ENGINE, "midtown");

            EngineSelector.setOption("baritone");
            same("option baritone", EngineSelector.selectedName(), "baritone");
            same("getOption baritone", EngineSelector.getOption(), "baritone");

            EngineSelector.setOption("  MIDTOWN  ");
            same("option normalized midtown", EngineSelector.selectedName(), "midtown");

            EngineSelector.clearOption();
            same("cleared option default", EngineSelector.selectedName(), "midtown");

            System.setProperty(EngineSelector.PROPERTY, "baritone");
            same("system property baritone", EngineSelector.selectedName(), "baritone");

            EngineSelector.setOption("midtown");
            same("option overrides property", EngineSelector.selectedName(), "midtown");

            assertThrows("invalid engine option", IllegalArgumentException.class,
                    () -> EngineSelector.setOption("bogus"));

            // Register a custom mock engine factory
            MockEngine mock = new MockEngine("midtown");
            java.util.List<Object> created = new java.util.ArrayList<>();
            EngineSelector.onCreate(created::add);
            EngineSelector.register("midtown", () -> mock);
            EngineSelector.setOption("midtown");
            same("current() returns registered mock", EngineSelector.current(), mock);
            EngineSelector.current();
            same("onCreate ran once for the new engine", created.size(), 1);
            same("onCreate got the new engine", created.get(0), mock);
            java.util.List<Object> seen = new java.util.ArrayList<>();
            EngineSelector.forEachCreated(seen::add);
            assertTrue("forEachCreated visits the created engine", seen.contains(mock));
        } finally {
            EngineSelector.clearOption();
            if (prevProp != null) {
                System.setProperty(EngineSelector.PROPERTY, prevProp);
            } else {
                System.clearProperty(EngineSelector.PROPERTY);
            }
        }
    }

    private static void testPathEngineContract() {
        System.out.println("Checking PathEngine default contract and status transitions...");
        MockEngine engine = new MockEngine("test-mock");

        same("engine name", engine.name(), "test-mock");
        same("initial status", engine.status(), EngineStatus.IDLE);
        assertFalse("idle is not busy", engine.isBusy());

        engine.setStatus(EngineStatus.PLANNING);
        assertTrue("planning is busy", engine.isBusy());

        engine.setStatus(EngineStatus.MOVING);
        assertTrue("moving is busy", engine.isBusy());

        engine.setStatus(EngineStatus.STUCK);
        assertTrue("stuck is busy", engine.isBusy());

        engine.setStatus(EngineStatus.DONE);
        assertFalse("done is not busy", engine.isBusy());

        engine.setStatus(EngineStatus.FAILED);
        assertFalse("failed is not busy", engine.isBusy());

        assertTrue("default allowBreak", engine.allowBreak());
        assertTrue("default allowPlace", engine.allowPlace());
        assertTrue("default allowParkourPlace", engine.allowParkourPlace());
        assertFalse("default isProtectedAt", engine.isProtectedAt(new BlockPos(0, 0, 0)));
        assertFalse("default setSetting", engine.setSetting("foo", "bar"));
        same("default getSetting", engine.getSetting("foo"), null);

        // Lifecycle methods
        GoalSpec goal = GoalSpec.exact(new BlockPos(10, 64, 10));
        engine.goTo(goal);
        same("goTo sets moving status", engine.status(), EngineStatus.MOVING);
        same("goTo sets goal", engine.lastGoal(), goal);

        engine.pause();
        same("pause sets idle status", engine.status(), EngineStatus.IDLE);

        engine.resume();
        same("resume sets moving status", engine.status(), EngineStatus.MOVING);

        engine.cancel();
        same("cancel sets idle status", engine.status(), EngineStatus.IDLE);
        same("cancel clears goal", engine.lastGoal(), null);
    }

    private static void testEdgeTracker() {
        System.out.println("Checking EdgeTracker stuck detection, timeouts, and temporary blacklist...");
        EdgeTracker tracker = new EdgeTracker();
        same("default stuck threshold", tracker.stuckThresholdTicks(), 20);
        same("default edge timeout", tracker.defaultEdgeTimeoutTicks(), 80);
        same("default blacklist duration", tracker.defaultBlacklistTicks(), 100);

        BlockPos from = new BlockPos(0, 64, 0);
        BlockPos to = new BlockPos(1, 64, 0);

        // 1. Stuck detection at 20 ticks of no progress
        tracker.beginEdge(from, to, 0.5, 64.0, 0.5, 0, 0);
        same("active edge from", tracker.activeEdge().from(), from);
        same("active edge to", tracker.activeEdge().to(), to);
        same("initial noProgressTicks", tracker.noProgressTicks(), 0);

        for (int tick = 1; tick <= 19; tick++) {
            EdgeCheckResult res = tracker.update(0.5, 64.0, 0.5, tick);
            same("tick " + tick + " check result", res, EdgeCheckResult.OK);
            same("tick " + tick + " noProgressTicks", tracker.noProgressTicks(), tick);
        }
        EdgeCheckResult stuckResult = tracker.update(0.5, 64.0, 0.5, 20);
        same("tick 20 stuck result", stuckResult, EdgeCheckResult.STUCK);

        // 2. Progress resets noProgressTicks
        tracker.beginEdge(from, to, 0.5, 64.0, 0.5, 0, 0);
        tracker.update(0.5, 64.0, 0.5, 1);
        tracker.update(0.5, 64.0, 0.5, 2);
        same("noProgressTicks before progress", tracker.noProgressTicks(), 2);
        EdgeCheckResult progressResult = tracker.update(0.7, 64.0, 0.5, 3);
        same("progress check result", progressResult, EdgeCheckResult.OK);
        same("noProgressTicks reset", tracker.noProgressTicks(), 0);

        // 3. Timeout after 80 ticks
        tracker.beginEdge(from, to, 0.5, 64.0, 0.5, 100, 80);
        same("elapsed 79 timeout check", tracker.update(0.5, 64.0, 0.5, 179), EdgeCheckResult.OK);
        same("elapsed 80 timeout check", tracker.update(0.5, 64.0, 0.5, 180), EdgeCheckResult.TIMED_OUT);

        // 4. Temporary blacklisting for 100 ticks
        EdgeKey edge = new EdgeKey(from, to);
        assertFalse("initially not blacklisted", tracker.isBlacklisted(edge, 100));
        tracker.blacklist(edge, 100);
        assertTrue("blacklisted at tick 150", tracker.isBlacklisted(edge, 150));
        assertTrue("blacklisted at tick 199", tracker.isBlacklisted(edge, 199));
        assertFalse("expired at tick 200", tracker.isBlacklisted(edge, 200));

        // Pruning and clearAll
        tracker.blacklist(new EdgeKey(from, new BlockPos(2, 64, 0)), 100);
        same("blacklisted count before prune", tracker.blacklistedCount(150), 1);
        tracker.clearAll();
        same("blacklisted count after clearAll", tracker.blacklistedCount(150), 0);
        same("active edge after clearAll", tracker.activeEdge(), null);
    }

    private static void testDirectStepPlanner() {
        System.out.println("Checking DirectStepPlanner step generation and blacklist avoidance...");
        Planner planner = new Planner.DirectStepPlanner();

        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos goalPos = new BlockPos(3, 64, 0);

        List<PathNode> nodes = planner.plan(GoalSpec.exact(goalPos), start, edge -> false);
        same("step count to (3, 64, 0)", nodes.size(), 3);
        same("step 0 pos", nodes.get(0).pos(), new BlockPos(1, 64, 0));
        same("step 1 pos", nodes.get(1).pos(), new BlockPos(2, 64, 0));
        same("step 2 pos", nodes.get(2).pos(), new BlockPos(3, 64, 0));
        same("step 0 action", nodes.get(0).action(), PathNode.ActionType.WALK);

        // Already at goal: returns empty list
        List<PathNode> alreadyAtGoal = planner.plan(GoalSpec.exact(start), start, edge -> false);
        assertTrue("already at goal path empty", alreadyAtGoal.isEmpty());

        // Blacklist avoidance: if edge (0, 64, 0) -> (1, 64, 0) is blacklisted, it avoids it
        EdgeKey blocked = new EdgeKey(start, new BlockPos(1, 64, 0));
        List<PathNode> altNodes = planner.plan(GoalSpec.exact(goalPos), start, blocked::equals);
        assertFalse("alternate path not empty", altNodes.isEmpty());
        assertFalse("first step avoided blocked edge", altNodes.get(0).pos().equals(new BlockPos(1, 64, 0)));
    }

    private static void testMidtownCoordinator() {
        System.out.println("Checking MidtownCoordinator interrupt stack, drift check, stuck replanning, and cancel...");
        MidtownCoordinator coord = new MidtownCoordinator();

        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(5, 64, 0);

        // 1. Starting a goal
        boolean started = coord.startGoal(GoalSpec.exact(target), start);
        assertTrue("startGoal succeeded", started);
        same("status MOVING", coord.status(), EngineStatus.MOVING);
        same("path length", coord.path().size(), 5);
        same("pathIndex 0", coord.pathIndex(), 0);

        // 2. Synchronous interrupt stack: pause() pushes frame
        var frameOpt = coord.pause();
        assertTrue("pause returns frame", frameOpt.isPresent());
        same("interruptStackSize 1", coord.interruptStackSize(), 1);
        assertTrue("isPaused true", coord.isPaused());

        // 3. Resume with NO drift (player still near start edge)
        ResumeOutcome resumeOk = coord.resume(0.5, 64.0, 0.5);
        same("resume within tolerance outcome", resumeOk, ResumeOutcome.CONTINUE_EDGE);
        same("interruptStackSize 0", coord.interruptStackSize(), 0);
        assertFalse("isPaused false", coord.isPaused());
        same("status restored to MOVING", coord.status(), EngineStatus.MOVING);

        // 4. Resume with EXCESSIVE drift (> 2.5 blocks drift triggers replan)
        coord.pause();
        same("interruptStackSize 1", coord.interruptStackSize(), 1);
        ResumeOutcome resumeDrift = coord.resume(20.5, 64.0, 0.5);
        same("resume with drift outcome", resumeDrift, ResumeOutcome.REPLANNED);
        same("interruptStackSize 0", coord.interruptStackSize(), 0);
        same("status MOVING after replan", coord.status(), EngineStatus.MOVING);
        same("replanned path start", coord.path().get(0).pos(), new BlockPos(19, 64, 0));

        // 5. Stuck detection and automatic replanning
        coord.startGoal(GoalSpec.exact(new BlockPos(4, 64, 0)), new BlockPos(0, 64, 0));
        same("status MOVING", coord.status(), EngineStatus.MOVING);
        for (int i = 0; i < 19; i++) {
            TickAction action = coord.tick(0.5, 64.0, 0.5);
            assertTrue("tick action is MoveTo", action instanceof TickAction.MoveTo);
            assertFalse("not replanned yet", ((TickAction.MoveTo) action).replannedThisTick());
        }
        TickAction stuckAction = coord.tick(0.5, 64.0, 0.5);
        assertTrue("stuck action is MoveTo", stuckAction instanceof TickAction.MoveTo);
        assertTrue("replanned on stuck", ((TickAction.MoveTo) stuckAction).replannedThisTick());
        same("replanCount 1", coord.replanCount(), 1);
        assertTrue("stuck edge blacklisted",
                coord.edgeTracker().isBlacklisted(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0), coord.tickCounter()));

        // 6. cancel() resets everything cleanly
        coord.pause();
        coord.cancel();
        same("status IDLE after cancel", coord.status(), EngineStatus.IDLE);
        same("interruptStack empty after cancel", coord.interruptStackSize(), 0);
        same("activeGoal null after cancel", coord.activeGoal(), null);
        assertTrue("path empty after cancel", coord.path().isEmpty());
        assertFalse("isPaused false after cancel", coord.isPaused());
    }

    private static void testHazardCost() {
        System.out.println("Checking HazardCost penalties and hard blocks...");
        HazardCost hazards = HazardCost.DEFAULT;
        GridWorldSnapshot grid = GridWorldSnapshot.builder(0, 0, 0, 5, 5, 5)
                .fillFloor(0, BlockKind.SOLID)
                .set(1, 1, 0, BlockKind.LAVA)
                .set(2, 1, 0, BlockKind.FIRE)
                .set(3, 1, 0, BlockKind.CACTUS)
                .set(4, 0, 3, BlockKind.MAGMA)
                .build();
        CalculationContext ctx = CalculationContext.defaults();

        assertTrue("lava node penalty is infinite", Double.isInfinite(hazards.nodePenalty(grid, ctx, 1, 1, 0)));
        assertTrue("fire node penalty is infinite", Double.isInfinite(hazards.nodePenalty(grid, ctx, 2, 1, 0)));
        assertTrue("cactus node penalty is infinite", Double.isInfinite(hazards.nodePenalty(grid, ctx, 3, 1, 0)));
        assertTrue("magma node penalty is finite", !Double.isInfinite(hazards.nodePenalty(grid, ctx, 4, 1, 3)) && hazards.nodePenalty(grid, ctx, 4, 1, 3) > 0);

        // Lava adjacent break penalty
        assertTrue("lava adjacent break penalty is infinite", Double.isInfinite(hazards.adjacentBreakPenalty(grid, 0, 1, 0)));
        same("safe block break penalty is 0", hazards.adjacentBreakPenalty(grid, 0, 4, 0), 0.0);
    }

    private static void testMidtownPlanner() {
        System.out.println("Checking MidtownPlanner 3D A* traversal, MoveEdges, and survival constraints...");
        MidtownPlanner planner = new MidtownPlanner();
        try {
            CalculationContext defaultCtx = CalculationContext.defaults();
            CalculationContext noBreakCtx = CalculationContext.builder().allowBreak(false).build();

            // 1. WalkEdge: flat, diagonal, and headroom constraint
            GridWorldSnapshot flatWorld = GridWorldSnapshot.builder(0, 0, 0, 10, 5, 10)
                    .fillFloor(0, BlockKind.SOLID)
                    .build();
            PlanResult flatResult = planner.planSync(flatWorld, defaultCtx, new BlockPos(0, 1, 0), GoalSpec.exact(new BlockPos(3, 1, 0)));
            assertTrue("flat walk complete", flatResult.isComplete());
            same("flat walk waypoints count", flatResult.waypoints().size(), 4);
            same("flat walk edges count", flatResult.edges().size(), 3);
            assertTrue("flat walk has WalkEdge", flatResult.edges().stream().allMatch(e -> e instanceof MoveEdge.WalkEdge));

            PlanResult diagResult = planner.planSync(flatWorld, defaultCtx, new BlockPos(0, 1, 0), GoalSpec.exact(new BlockPos(2, 1, 2)));
            assertTrue("diagonal walk complete", diagResult.isComplete());
            assertTrue("diagonal walk contains diagonal WalkEdge",
                    diagResult.edges().stream().anyMatch(e -> e instanceof MoveEdge.WalkEdge w && w.diagonal()));

            // Headroom check: ceiling at y=2 directly above path
            GridWorldSnapshot lowCeilingWorld = GridWorldSnapshot.builder(0, 0, 0, 5, 5, 5)
                    .fillFloor(0, BlockKind.SOLID)
                    .fillBox(0, 1, 1, 5, 3, 5, BlockKind.SOLID)
                    .fillBox(0, 2, 0, 5, 5, 0, BlockKind.SOLID)
                    .build();
            CalculationContext noTraverseCtx = CalculationContext.builder()
                    .allowBreak(false)
                    .allowPlace(false)
                    .build();
            PlanResult lowCeilingResult = planner.planSync(lowCeilingWorld, noTraverseCtx, new BlockPos(0, 1, 0), GoalSpec.exact(new BlockPos(3, 1, 0)));
            same("headroom blocked returns NO_PATH", lowCeilingResult.status(), PlanResult.Status.NO_PATH);

            // 2. AscendEdge and DescendEdge
            GridWorldSnapshot stairWorld = GridWorldSnapshot.builder(0, 0, 0, 5, 5, 5)
                    .set(0, 0, 0, BlockKind.SOLID)
                    .set(1, 1, 0, BlockKind.SOLID)
                    .set(2, 1, 0, BlockKind.SOLID)
                    .set(3, 0, 0, BlockKind.SOLID)
                    .build();
            PlanResult ascendResult = planner.planSync(stairWorld, defaultCtx, new BlockPos(0, 1, 0), GoalSpec.exact(new BlockPos(2, 2, 0)));
            assertTrue("ascend complete", ascendResult.isComplete());
            assertTrue("ascend has AscendEdge", ascendResult.edges().stream().anyMatch(e -> e instanceof MoveEdge.AscendEdge));

            PlanResult descendResult = planner.planSync(stairWorld, defaultCtx, new BlockPos(2, 2, 0), GoalSpec.exact(new BlockPos(3, 1, 0)));
            assertTrue("descend complete", descendResult.isComplete());
            assertTrue("descend has DescendEdge", descendResult.edges().stream().anyMatch(e -> e instanceof MoveEdge.DescendEdge));

            // 3. FallEdge: safe drop (3 blocks) and water landing (7 blocks)
            GridWorldSnapshot cliffWorld = GridWorldSnapshot.builder(0, 0, 0, 5, 10, 5)
                    .set(0, 3, 0, BlockKind.SOLID)
                    .set(1, 0, 0, BlockKind.SOLID)
                    .build();
            PlanResult dropResult = planner.planSync(cliffWorld, defaultCtx, new BlockPos(0, 4, 0), GoalSpec.exact(new BlockPos(1, 1, 0)));
            assertTrue("safe drop complete", dropResult.isComplete());
            assertTrue("drop has FallEdge", dropResult.edges().stream().anyMatch(e -> e instanceof MoveEdge.FallEdge f && !f.waterLanding() && f.fallDistance() == 3));

            GridWorldSnapshot waterDropWorld = GridWorldSnapshot.builder(0, 0, 0, 5, 15, 5)
                    .set(0, 7, 0, BlockKind.SOLID)
                    .set(1, 0, 0, BlockKind.SOLID)
                    .set(1, 1, 0, BlockKind.WATER)
                    .build();
            PlanResult waterDropResult = planner.planSync(waterDropWorld, defaultCtx, new BlockPos(0, 8, 0), GoalSpec.exact(new BlockPos(1, 1, 0)));
            assertTrue("water drop complete", waterDropResult.isComplete());
            assertTrue("water drop has FallEdge with water landing",
                    waterDropResult.edges().stream().anyMatch(e -> e instanceof MoveEdge.FallEdge f && f.waterLanding()));

            // 4. BreakThroughEdge
            GridWorldSnapshot wallWorld = GridWorldSnapshot.builder(0, 0, -2, 5, 5, 2)
                    .fillFloor(0, BlockKind.SOLID)
                    .fillBox(1, 1, -2, 1, 4, 2, BlockKind.SOLID)
                    .build();
            CalculationContext breakCtx = CalculationContext.builder().allowBreak(true).build();
            PlanResult breakResult = planner.planSync(wallWorld, breakCtx, new BlockPos(0, 1, 0), GoalSpec.exact(new BlockPos(2, 1, 0)));
            assertTrue("break complete", breakResult.isComplete());
            assertTrue("has BreakThroughEdge", breakResult.edges().stream().anyMatch(e -> e instanceof MoveEdge.BreakThroughEdge));

            PlanResult noBreakResult = planner.planSync(wallWorld, noBreakCtx, new BlockPos(0, 1, 0), GoalSpec.exact(new BlockPos(2, 1, 0)));
            same("no break returns NO_PATH", noBreakResult.status(), PlanResult.Status.NO_PATH);

            // 5. BridgeEdge and expendable block gating
            GridWorldSnapshot gapWorld = GridWorldSnapshot.builder(0, 0, -5, 5, 5, 5)
                    .set(0, 0, 0, BlockKind.SOLID)
                    .set(2, 0, 0, BlockKind.SOLID)
                    .fillBox(0, 1, 1, 5, 4, 5, BlockKind.SOLID)
                    .fillBox(0, 1, -5, 5, 4, -1, BlockKind.SOLID)
                    .build();
            CalculationContext bridgeCtx = CalculationContext.builder()
                    .allowParkour(false)
                    .allowPlace(true)
                    .expendableBlockCount(5)
                    .build();
            PlanResult bridgeResult = planner.planSync(gapWorld, bridgeCtx, new BlockPos(0, 1, 0), GoalSpec.exact(new BlockPos(2, 1, 0)));
            assertTrue("bridge complete", bridgeResult.isComplete());
            assertTrue("has BridgeEdge", bridgeResult.edges().stream().anyMatch(e -> e instanceof MoveEdge.BridgeEdge));

            CalculationContext noBlocksCtx = CalculationContext.builder()
                    .allowParkour(false)
                    .allowPlace(true)
                    .allowBreak(false)
                    .expendableBlockCount(0)
                    .build();
            PlanResult noBridgeResult = planner.planSync(gapWorld, noBlocksCtx, new BlockPos(0, 1, 0), GoalSpec.exact(new BlockPos(2, 1, 0)));
            same("no expendable blocks returns NO_PATH", noBridgeResult.status(), PlanResult.Status.NO_PATH);

            // 6. PillarEdge
            GridWorldSnapshot pillarWorld = GridWorldSnapshot.builder(0, 0, 0, 3, 6, 3)
                    .set(1, 0, 1, BlockKind.SOLID)
                    .build();
            CalculationContext pillarCtx = CalculationContext.builder()
                    .allowPlace(true)
                    .expendableBlockCount(5)
                    .build();
            PlanResult pillarResult = planner.planSync(pillarWorld, pillarCtx, new BlockPos(1, 1, 1), GoalSpec.exact(new BlockPos(1, 3, 1)));
            assertTrue("pillar complete", pillarResult.isComplete());
            assertTrue("has PillarEdge", pillarResult.edges().stream().anyMatch(e -> e instanceof MoveEdge.PillarEdge));

            // 7. ParkourEdge
            CalculationContext parkourCtx = CalculationContext.builder()
                    .allowPlace(false)
                    .allowParkour(true)
                    .build();
            PlanResult parkourResult = planner.planSync(gapWorld, parkourCtx, new BlockPos(0, 1, 0), GoalSpec.exact(new BlockPos(2, 1, 0)));
            assertTrue("parkour complete", parkourResult.isComplete());
            assertTrue("has ParkourEdge", parkourResult.edges().stream().anyMatch(e -> e instanceof MoveEdge.ParkourEdge));

            // 7b. No long parkour over a deep drop: a 2-block gap between 1-wide ledges 8 above the floor
            GridWorldSnapshot highGapWorld = GridWorldSnapshot.builder(-1, 0, -2, 5, 12, 2)
                    .fillFloor(0, BlockKind.SOLID)
                    .set(0, 8, 0, BlockKind.SOLID)
                    .set(3, 8, 0, BlockKind.SOLID)
                    .build();
            PlanResult highJump = planner.planSync(highGapWorld, parkourCtx, new BlockPos(0, 9, 0), GoalSpec.exact(new BlockPos(3, 9, 0)));
            assertTrue("no long parkour over a deep drop",
                    highJump.edges().stream().noneMatch(e -> e instanceof MoveEdge.ParkourEdge));

            // 7c. A 1-wide walkway along a wall over a 3-deep pit with 1, 2, 3 gaps and a 1-gap step up:
            // jump the gaps, don't drop into the pit and pillar back out (blocks on hand).
            GridWorldSnapshot wallWalk = GridWorldSnapshot.builder(-1, 0, -3, 29, 9, 3)
                    .fillFloor(0, BlockKind.SOLID)
                    .fillBox(0, 3, 0, 28, 3, 0, BlockKind.SOLID)
                    .fillBox(0, 4, 1, 28, 6, 1, BlockKind.SOLID)
                    .fillBox(20, 4, 0, 28, 4, 0, BlockKind.SOLID)
                    .set(5, 3, 0, BlockKind.AIR)
                    .fillBox(9, 3, 0, 10, 3, 0, BlockKind.AIR)
                    .fillBox(14, 3, 0, 16, 3, 0, BlockKind.AIR)
                    .set(19, 3, 0, BlockKind.AIR)
                    .build();
            CalculationContext wallWalkCtx = CalculationContext.builder()
                    .allowPlace(true).allowBreak(true).allowParkour(true).expendableBlockCount(64).build();
            PlanResult wallWalkResult = planner.planSync(wallWalk, wallWalkCtx, new BlockPos(0, 4, 0), GoalSpec.exact(new BlockPos(26, 5, 0)));
            System.out.println("wall walk: " + wallWalkResult.status() + " expanded=" + wallWalkResult.nodesExpanded() + " ms=" + wallWalkResult.elapsedMillis() + " " + wallWalkResult.edges().stream()
                    .map(e -> e.getClass().getSimpleName().replace("Edge", "") + "@" + e.to().toShortString().replace(" ", ""))
                    .toList());
            assertTrue("wall walk complete", wallWalkResult.isComplete());
            assertTrue("wall walk jumps the gaps",
                    wallWalkResult.edges().stream().filter(e -> e instanceof MoveEdge.ParkourEdge).count() >= 4);
            assertTrue("wall walk places nothing",
                    wallWalkResult.edges().stream().noneMatch(e -> e instanceof MoveEdge.PillarEdge || e instanceof MoveEdge.BridgeEdge));

            // 8. Hazard Avoidance (routing around lava)
            GridWorldSnapshot hazardWorld = GridWorldSnapshot.builder(0, 0, -2, 5, 5, 5)
                    .fillFloor(0, BlockKind.SOLID)
                    .set(1, 0, 0, BlockKind.LAVA)
                    .set(2, 0, 0, BlockKind.LAVA)
                    .build();
            PlanResult avoidResult = planner.planSync(hazardWorld, defaultCtx, new BlockPos(0, 1, 0), GoalSpec.exact(new BlockPos(3, 1, 0)));
            assertTrue("avoid lava complete", avoidResult.isComplete());
            assertTrue("no waypoints in lava", avoidResult.waypoints().stream().noneMatch(p -> (p.getX() == 1 || p.getX() == 2) && p.getZ() == 0));

            // 9. ProtectedRegion mask
            Area protArea = Area.of(1, 0, -2, 1, 4, 2);
            CalculationContext protCtx = CalculationContext.builder()
                    .allowBreak(true)
                    .addProtectedRegion(ProtectedRegion.denyAll("safe_zone", protArea))
                    .build();
            PlanResult protResult = planner.planSync(wallWorld, protCtx, new BlockPos(0, 1, 0), GoalSpec.exact(new BlockPos(2, 1, 0)));
            same("protected region blocks break", protResult.status(), PlanResult.Status.NO_PATH);

            // 10. planMineSync
            GridWorldSnapshot mineWorld = GridWorldSnapshot.builder(0, 0, -2, 10, 5, 5)
                    .fillFloor(0, BlockKind.SOLID)
                    .set(5, 1, 0, BlockKind.SOLID)
                    .setBlockId(5, 1, 0, "minecraft:iron_ore")
                    .build();
            PlanResult mineResult = planner.planMineSync(
                    mineWorld, defaultCtx, new BlockPos(0, 1, 0), BlockFilter.ofNames("iron_ore"), null, 100);
            assertTrue("planMine complete", mineResult.isComplete());
            double distToOre = Math.sqrt(mineResult.endPos().distToCenterSqr(5.5, 1.5, 0.5));
            assertTrue("endPos within reach of ore", distToOre <= 4.5);

            // 11. Time-budget PARTIAL pathing
            GridWorldSnapshot longWorld = GridWorldSnapshot.builder(0, 0, 0, 60, 5, 5)
                    .fillFloor(0, BlockKind.SOLID)
                    .build();
            CalculationContext budgetCtx = CalculationContext.builder()
                    .maxNodesExpanded(15)
                    .timeBudgetMs(1L)
                    .build();
            PlanResult partialResult = planner.planSync(longWorld, budgetCtx, new BlockPos(0, 1, 0), GoalSpec.exact(new BlockPos(50, 1, 0)), 1L);
            same("partial status", partialResult.status(), PlanResult.Status.PARTIAL);
            assertTrue("isPartial true", partialResult.isPartial());
            assertTrue("hasPath true", partialResult.hasPath());
            assertFalse("edges not empty", partialResult.edges().isEmpty());
            assertTrue("advanced toward goal", partialResult.endPos().getX() > 0);
        } finally {
            planner.shutdown();
        }
    }

    // ------------------------------------------------------------------ Mock PathEngine

    private static final class MockEngine implements PathEngine {
        private final String name;
        private EngineStatus status = EngineStatus.IDLE;
        private GoalSpec lastGoal;

        MockEngine(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void goTo(GoalSpec goal) {
            this.lastGoal = goal;
            this.status = EngineStatus.MOVING;
        }

        @Override
        public void mine(BlockFilter filter, Area box) {
            this.status = EngineStatus.MOVING;
        }

        @Override
        public void pause() {
            this.status = EngineStatus.IDLE;
        }

        @Override
        public void resume() {
            this.status = EngineStatus.MOVING;
        }

        @Override
        public void cancel() {
            this.lastGoal = null;
            this.status = EngineStatus.IDLE;
        }

        @Override
        public EngineStatus status() {
            return status;
        }

        void setStatus(EngineStatus s) {
            this.status = s;
        }

        GoalSpec lastGoal() {
            return lastGoal;
        }
    }

    private static void testItemSaverSettings() {
        System.out.println("  testItemSaverSettings...");
        MidtownEngine engine = new MidtownEngine();

        same("itemSaver default setting", engine.getSetting("itemSaver"), "false");
        same("itemSaverThreshold default setting", engine.getSetting("itemSaverThreshold"), "0");
        same("effective threshold default", engine.effectiveItemSaverThreshold(), 0);

        assertTrue("setSetting itemSaverThreshold 50", engine.setSetting("itemSaverThreshold", "50"));
        same("itemSaverThreshold updated", engine.getSetting("itemSaverThreshold"), "50");
        same("effective remains 0 when itemSaver false", engine.effectiveItemSaverThreshold(), 0);

        assertTrue("setSetting itemSaver true", engine.setSetting("itemSaver", "true"));
        same("itemSaver enabled", engine.getSetting("itemSaver"), "true");
        same("effective matches threshold when itemSaver true", engine.effectiveItemSaverThreshold(), 50);

        assertTrue("setSetting itemSaver false", engine.setSetting("itemSaver", "false"));
        same("itemSaver disabled", engine.getSetting("itemSaver"), "false");
        same("effective returns to 0 when itemSaver false", engine.effectiveItemSaverThreshold(), 0);

        assertThrows("negative itemSaverThreshold rejects", IllegalArgumentException.class,
                () -> engine.setSetting("itemSaverThreshold", "-1"));

        CalculationContext def = CalculationContext.defaults();
        same("CalculationContext default threshold", def.itemSaverThreshold(), CalculationContext.DEFAULT_ITEM_SAVER_THRESHOLD);

        CalculationContext custom = CalculationContext.builder().itemSaverThreshold(50).build();
        same("CalculationContext custom threshold", custom.itemSaverThreshold(), 50);

        assertThrows("negative CalculationContext threshold rejects", IllegalArgumentException.class,
                () -> CalculationContext.builder().itemSaverThreshold(-5));
    }

    private static void testMultiBoxProtectedArea() {
        System.out.println("Checking multi-box setProtectedArea in MidtownEngine and MidtownPlanner...");
        MidtownEngine engine = new MidtownEngine();
        Area b1 = Area.of(1, 1, -1, 1, 4, -1);
        Area b2 = Area.of(1, 1, 0, 1, 4, 0);
        Area b3 = Area.of(1, 1, 1, 1, 4, 1);
        engine.setProtectedArea("wall_group", List.of(b1, b2, b3));

        assertTrue("engine isProtectedAt b1", engine.isProtectedAt(new BlockPos(1, 1, -1)));
        assertTrue("engine isProtectedAt b2", engine.isProtectedAt(new BlockPos(1, 1, 0)));
        assertTrue("engine isProtectedAt b3", engine.isProtectedAt(new BlockPos(1, 2, 1)));
        assertFalse("engine isProtectedAt outside z=2", engine.isProtectedAt(new BlockPos(1, 1, 2)));
        assertFalse("engine isProtectedAt start", engine.isProtectedAt(new BlockPos(0, 1, 0)));

        // Multi-box planner test:
        // World with floor y=0 from x=0..3, z=-2..3.
        // Wall at x=1, y=1..4 from z=-1..1 (the 3 protected boxes).
        // Open passage at z=2..3.
        GridWorldSnapshot detourWorld = GridWorldSnapshot.builder(0, 0, -2, 3, 4, 3)
                .fillFloor(0, BlockKind.SOLID)
                .fillBox(1, 1, -1, 1, 4, -1, BlockKind.SOLID)
                .fillBox(1, 1, 0, 1, 4, 0, BlockKind.SOLID)
                .fillBox(1, 1, 1, 1, 4, 1, BlockKind.SOLID)
                .build();

        CalculationContext ctx = CalculationContext.builder()
                .allowBreak(true)
                .addProtectedArea("wall_group", b1)
                .addProtectedArea("wall_group", b2)
                .addProtectedArea("wall_group", b3)
                .build();

        MidtownPlanner planner = new MidtownPlanner();
        try {
            PlanResult res = planner.planSync(detourWorld, ctx, new BlockPos(0, 1, 0), GoalSpec.exact(new BlockPos(2, 1, 0)));
            assertTrue("plan with 3 protected boxes complete", res.isComplete());
            boolean touchesProtected = res.waypoints().stream().anyMatch(p ->
                    p.getX() == 1 && (p.getZ() == -1 || p.getZ() == 0 || p.getZ() == 1));
            assertFalse("no waypoints in any of the 3 protected boxes", touchesProtected);

            // When no detour exists (wall spans entire z bounds z=-2..2 from y=1..4), breaking is forbidden so NO_PATH
            GridWorldSnapshot blockedWorld = GridWorldSnapshot.builder(0, 0, -2, 3, 4, 2)
                    .fillFloor(0, BlockKind.SOLID)
                    .fillBox(1, 1, -2, 1, 4, 2, BlockKind.SOLID)
                    .build();
            Area b0 = Area.of(1, 1, -2, 1, 4, -2);
            Area b4 = Area.of(1, 1, 2, 1, 4, 2);
            CalculationContext fullWallCtx = CalculationContext.builder()
                    .allowBreak(true)
                    .addProtectedArea("wall_group", b0)
                    .addProtectedArea("wall_group", b1)
                    .addProtectedArea("wall_group", b2)
                    .addProtectedArea("wall_group", b3)
                    .addProtectedArea("wall_group", b4)
                    .build();
            PlanResult blockedRes = planner.planSync(blockedWorld, fullWallCtx, new BlockPos(0, 1, 0), GoalSpec.exact(new BlockPos(2, 1, 0)));
            same("fully protected wall blocks break with NO_PATH", blockedRes.status(), PlanResult.Status.NO_PATH);
        } finally {
            planner.shutdown();
        }

        // Test replacement in engine
        engine.setProtectedArea("wall_group", List.of());
        assertFalse("empty list clears protection", engine.isProtectedAt(new BlockPos(1, 1, 0)));
    }

    private static void testWaterToShoreCrossing() {
        System.out.println("Checking water -> shore and island -> shore crossing with allowPlace=false, allowBreak=false...");
        // Lake: x=0..25, z=0..16, floor at y=58, water from y=59..62; shore ring around outside at y=61 solid (standing y=62).
        GridWorldSnapshot.Builder b = GridWorldSnapshot.builder(-2, 58, -2, 27, 65, 18)
                .fillFloor(58, BlockKind.SOLID)
                .fillBox(0, 59, 0, 25, 62, 16, BlockKind.WATER);
        // Shore at z=-2..-1, z=17..18, x=-2..-1, x=26..27 has solid ground at y=61 (standing y=62)
        b.fillBox(-2, 59, -2, 27, 61, -1, BlockKind.SOLID);
        b.fillBox(-2, 59, 17, 27, 61, 18, BlockKind.SOLID);
        b.fillBox(-2, 59, 0, -1, 61, 16, BlockKind.SOLID);
        b.fillBox(26, 59, 0, 27, 61, 16, BlockKind.SOLID);
        GridWorldSnapshot lakeWorld = b.build();

        CalculationContext ctx = CalculationContext.builder()
                .allowBreak(false)
                .allowPlace(false)
                .build();

        MidtownPlanner planner = new MidtownPlanner();
        try {
            // 1. From y=61 in water across lake to shore at y=62
            PlanResult waterToShore = planner.planSync(
                    lakeWorld, ctx, new BlockPos(22, 61, 8), GoalSpec.near(new BlockPos(3, 62, 17), 1));
            assertTrue("water y=61 -> shore y=62 complete (" + waterToShore.status() + ")", waterToShore.isComplete());

            // 2. From 1-block dirt island at (22, 60, 8) (standing y=61) surrounded by y=62 water to shore at y=62
            GridWorldSnapshot islandWorld = GridWorldSnapshot.builder(-2, 58, -2, 27, 65, 18)
                    .fillFloor(58, BlockKind.SOLID)
                    .fillBox(0, 59, 0, 25, 62, 16, BlockKind.WATER)
                    .fillBox(-2, 59, -2, 27, 61, -1, BlockKind.SOLID)
                    .fillBox(-2, 59, 17, 27, 61, 18, BlockKind.SOLID)
                    .fillBox(-2, 59, 0, -1, 61, 16, BlockKind.SOLID)
                    .fillBox(26, 59, 0, 27, 61, 16, BlockKind.SOLID)
                    .fillBox(22, 59, 8, 22, 60, 8, BlockKind.SOLID)
                    .fillBox(22, 61, 8, 22, 62, 8, BlockKind.AIR)
                    .build();
            PlanResult islandToShore = planner.planSync(
                    islandWorld, ctx, new BlockPos(22, 61, 8), GoalSpec.near(new BlockPos(3, 62, 17), 1));
            assertTrue("1-block island y=61 -> shore y=62 complete (" + islandToShore.status() + ")", islandToShore.isComplete());
        } finally {
            planner.shutdown();
        }
    }

    private static void testNearGoalConsistency() {
        System.out.println("Checking MidtownCoordinator.isGoalSatisfied consistency with MidtownPlanner.NearGoal...");
        BlockPos target = new BlockPos(10, 64, 10);

        // range = 1: exact, cardinal (1,0,0), vertical (0,1,0) satisfy; diagonals (1,1,0) and (1,0,1) do NOT
        GoalSpec.Near near1 = GoalSpec.near(target, 1);
        assertTrue("near(1) exact satisfied", MidtownCoordinator.isGoalSatisfied(near1, 10.5, 64.0, 10.5));
        assertTrue("near(1) cardinal (1,0,0) satisfied", MidtownCoordinator.isGoalSatisfied(near1, 11.5, 64.0, 10.5));
        assertTrue("near(1) vertical (0,1,0) satisfied", MidtownCoordinator.isGoalSatisfied(near1, 10.5, 65.0, 10.5));
        assertFalse("near(1) diagonal (1,1,0) NOT satisfied", MidtownCoordinator.isGoalSatisfied(near1, 11.5, 65.0, 10.5));
        assertFalse("near(1) diagonal (1,0,1) NOT satisfied", MidtownCoordinator.isGoalSatisfied(near1, 11.5, 64.0, 11.5));

        // range = 2: cardinal (2,0,0) and diagonal (1,0,1) satisfy; (2,1,0) and (2,0,1) do NOT
        GoalSpec.Near near2 = GoalSpec.near(target, 2);
        assertTrue("near(2) cardinal (2,0,0) satisfied", MidtownCoordinator.isGoalSatisfied(near2, 12.5, 64.0, 10.5));
        assertTrue("near(2) diagonal (1,0,1) satisfied", MidtownCoordinator.isGoalSatisfied(near2, 11.5, 64.0, 11.5));
        assertFalse("near(2) diagonal (2,1,0) NOT satisfied", MidtownCoordinator.isGoalSatisfied(near2, 12.5, 65.0, 10.5));
        assertFalse("near(2) diagonal (2,0,1) NOT satisfied", MidtownCoordinator.isGoalSatisfied(near2, 12.5, 64.0, 11.5));

        // Cross-check with MidtownPlanner on a flat world: if planner produces edges, coordinator must not short-circuit at start
        GridWorldSnapshot flat = GridWorldSnapshot.builder(0, 0, 0, 15, 4, 15)
                .fillFloor(0, BlockKind.SOLID)
                .build();
        MidtownPlanner planner = new MidtownPlanner();
        try {
            BlockPos goalPos = new BlockPos(5, 1, 5);
            BlockPos diagStart1 = new BlockPos(6, 1, 6); // (1,0,1) offset from (5,1,5)
            PlanResult r1 = planner.planSync(flat, CalculationContext.defaults(), diagStart1, GoalSpec.near(goalPos, 1));
            assertTrue("planner moves from (1,0,1) for near(1)", r1.isComplete() && !r1.edges().isEmpty());
            assertFalse("coordinator agrees (1,0,1) not satisfied for near(1)",
                    MidtownCoordinator.isGoalSatisfied(GoalSpec.near(goalPos, 1), 6.5, 1.0, 6.5));

            BlockPos diagStart2 = new BlockPos(7, 1, 6); // (2,0,1) offset from (5,1,5)
            PlanResult r2 = planner.planSync(flat, CalculationContext.defaults(), diagStart2, GoalSpec.near(goalPos, 2));
            assertTrue("planner moves from (2,0,1) for near(2)", r2.isComplete() && !r2.edges().isEmpty());
            assertFalse("coordinator agrees (2,0,1) not satisfied for near(2)",
                    MidtownCoordinator.isGoalSatisfied(GoalSpec.near(goalPos, 2), 7.5, 1.0, 6.5));
        } finally {
            planner.shutdown();
        }
    }

    private static void testStartGoalWithPathActionPreservation() {
        System.out.println("Checking MidtownCoordinator.startGoalWithPath action preservation and LineOfSight tick behavior...");
        // 1. startGoalWithPath preserves BREAK_THROUGH node even when startPos already satisfies Exact(startPos)
        MidtownCoordinator coord = new MidtownCoordinator();
        BlockPos start = new BlockPos(2, 64, 2);
        BlockPos ore = new BlockPos(3, 64, 2);
        AtomicBoolean oreSolid = new AtomicBoolean(true);
        coord.setBlockSolidCheck(pos -> pos.equals(ore) && oreSolid.get());

        PathNode breakNode = new PathNode(start, PathNode.ActionType.BREAK_THROUGH, List.of(ore), false, false, 160);
        boolean started = coord.startGoalWithPath(GoalSpec.exact(start), start, List.of(breakNode));
        assertTrue("startGoalWithPath with BREAK_THROUGH started", started);
        same("status MOVING when path has BREAK_THROUGH", coord.status(), EngineStatus.MOVING);
        same("path preserved length 1", coord.path().size(), 1);
        same("path preserved BREAK_THROUGH action", coord.path().get(0).action(), PathNode.ActionType.BREAK_THROUGH);

        TickAction t1 = coord.tick(2.5, 64.0, 2.5);
        assertTrue("tick 1 emits MoveTo while block is solid", t1 instanceof TickAction.MoveTo);
        same("status still MOVING while breaking", coord.status(), EngineStatus.MOVING);
        same("noProgressTicks reset while breaking", coord.edgeTracker().noProgressTicks(), 0);

        oreSolid.set(false);
        TickAction t2 = coord.tick(2.5, 64.0, 2.5);
        assertTrue("tick 2 emits Done once block is broken", t2 instanceof TickAction.Done);
        same("status DONE after block broken", coord.status(), EngineStatus.DONE);

        // 2. Multi-step path [WALK(end), BREAK_THROUGH(end, ore)] does not complete at WALK node before breaking ore
        oreSolid.set(true);
        BlockPos walkStart = new BlockPos(0, 64, 2);
        List<PathNode> multi = List.of(
                PathNode.walk(new BlockPos(1, 64, 2)),
                PathNode.walk(start),
                breakNode
        );
        coord.startGoalWithPath(GoalSpec.exact(start), walkStart, multi);
        same("multi-step MOVING", coord.status(), EngineStatus.MOVING);
        coord.tick(1.5, 64.0, 2.5); // reaches node 0, advances to node 1
        coord.tick(2.5, 64.0, 2.5); // reaches node 1 (at start), must advance to node 2 (BREAK_THROUGH), NOT Done
        same("still MOVING at BREAK_THROUGH node", coord.status(), EngineStatus.MOVING);
        same("pathIndex at BREAK_THROUGH node", coord.pathIndex(), 2);
        oreSolid.set(false);
        TickAction doneMulti = coord.tick(2.5, 64.0, 2.5);
        assertTrue("multi-step completes after block broken", doneMulti instanceof TickAction.Done);

        // 3. LineOfSight goal does not short-circuit before reaching the end of the planned path
        BlockPos losTarget = new BlockPos(4, 64, 2);
        List<PathNode> losPath = List.of(
                PathNode.walk(new BlockPos(2, 64, 2)),
                PathNode.walk(new BlockPos(3, 64, 2))
        );
        coord.startGoalWithPath(GoalSpec.lineOfSight(losTarget, 4.5), new BlockPos(1, 64, 2), losPath);
        TickAction losMid = coord.tick(2.5, 64.0, 2.5); // within 4.5 range, but pathIndex < path.size()
        assertTrue("LineOfSight does not short-circuit mid-path", losMid instanceof TickAction.MoveTo);
        same("LineOfSight still MOVING mid-path", coord.status(), EngineStatus.MOVING);
        TickAction losEnd = coord.tick(3.5, 64.0, 2.5); // reaches final waypoint (pathIndex == path.size())
        assertTrue("LineOfSight completes at end of path within range", losEnd instanceof TickAction.Done);
        same("LineOfSight DONE at end of path", coord.status(), EngineStatus.DONE);
    }

    private static void testGoalChunkRadius() {
        System.out.println("Checking WorldSnapshot.goalChunkRadius for near, medium, far, and unbounded/null goals...");
        BlockPos start = new BlockPos(0, 64, 0);

        // Near spatial goals clamp at minimum 4 chunks
        same("exact near radius", WorldSnapshot.goalChunkRadius(start, GoalSpec.exact(new BlockPos(10, 64, 10))), 4);
        same("near close radius", WorldSnapshot.goalChunkRadius(start, GoalSpec.near(new BlockPos(15, 64, -15), 2)), 4);
        same("xz close radius", WorldSnapshot.goalChunkRadius(start, GoalSpec.xz(0, 0)), 4);
        same("los close radius", WorldSnapshot.goalChunkRadius(start, GoalSpec.lineOfSight(new BlockPos(20, 64, 5), 4.5)), 4);

        // Medium spatial goals scale linearly with (horizDist >> 4) + 3
        same("exact 80 blocks -> radius 8", WorldSnapshot.goalChunkRadius(start, GoalSpec.exact(new BlockPos(80, 64, 10))), 8);
        same("near 64 blocks -> radius 7", WorldSnapshot.goalChunkRadius(start, GoalSpec.near(new BlockPos(-64, 64, 32), 3)), 7);
        same("xz 48 blocks -> radius 6", WorldSnapshot.goalChunkRadius(start, GoalSpec.xz(12, -48)), 6);
        same("los 32 blocks -> radius 5", WorldSnapshot.goalChunkRadius(start, GoalSpec.lineOfSight(new BlockPos(32, 70, 16), 4.0)), 5);

        // Far spatial goals clamp at DEFAULT_CHUNK_RADIUS (12)
        same("exact far radius", WorldSnapshot.goalChunkRadius(start, GoalSpec.exact(new BlockPos(500, 64, 0))), WorldSnapshot.DEFAULT_CHUNK_RADIUS);
        same("xz far radius", WorldSnapshot.goalChunkRadius(start, GoalSpec.xz(-300, 300)), WorldSnapshot.DEFAULT_CHUNK_RADIUS);

        // Unbounded / null fall back to DEFAULT_CHUNK_RADIUS
        same("runAway radius", WorldSnapshot.goalChunkRadius(start, GoalSpec.runAway(start, 24.0)), WorldSnapshot.DEFAULT_CHUNK_RADIUS);
        same("null goal radius", WorldSnapshot.goalChunkRadius(start, null), WorldSnapshot.DEFAULT_CHUNK_RADIUS);
        same("null start radius", WorldSnapshot.goalChunkRadius(null, GoalSpec.exact(start)), WorldSnapshot.DEFAULT_CHUNK_RADIUS);
    }

    private static void testPlannerComponentsParity() {
        System.out.println("Checking AStarStorage, EdgeGenerator, GoalAdapters, and planner parity...");

        // 1. AStarStorage: heap ordering, decreaseKey, closed flags, and dynamic capacity growth
        AStarStorage storage = new AStarStorage(16);
        int n0 = storage.addNode(0, 64, 0, 0.0, 10.0, -1, (byte) 0, (byte) 0, 0L, Long.MIN_VALUE, Long.MIN_VALUE, 0.0, 0);
        int n1 = storage.addNode(1, 64, 0, 8.0, 6.0, n0, EdgeGenerator.EDGE_WALK, (byte) 0, 0L, Long.MIN_VALUE, Long.MIN_VALUE, 8.0, 0);
        int n2 = storage.addNode(2, 64, 0, 4.0, 5.0, n0, EdgeGenerator.EDGE_WALK, (byte) 0, 0L, Long.MIN_VALUE, Long.MIN_VALUE, 4.0, 0);
        storage.push(n0);
        storage.push(n1);
        storage.push(n2);
        same("heap pops lowest fScore (n2)", storage.popMin(), n2);
        // Relax n1 so its fScore beats n0 (new g=1.0 -> f = 1.0 + 1.1*6.0 = 7.6 < 11.0)
        storage.updateNode(n1, 1.0, n2, EdgeGenerator.EDGE_ASCEND, (byte) 0, 0L, Long.MIN_VALUE, Long.MIN_VALUE, 1.0, 0);
        same("heap pops relaxed n1 before n0", storage.popMin(), n1);
        same("n1 updated parentId", storage.parentId(n1), n2);
        same("n1 updated edgeType", storage.edgeType(n1), EdgeGenerator.EDGE_ASCEND);
        same("pos lookup finds n2", storage.getNodeId(BlockPos.asLong(2, 64, 0)), n2);
        same("pos lookup missing returns -1", storage.getNodeId(BlockPos.asLong(99, 64, 99)), -1);
        storage.markClosed(n2);
        assertTrue("n2 marked closed", storage.isClosed(n2));

        // Grow beyond initial capacity (16) to verify array growth + PosToNodeMap rehash
        for (int i = 0; i < 40; i++) {
            int id = storage.addNode(10 + i, 64, 0, i, 1.0, -1, (byte) 0, (byte) 0, 0L, Long.MIN_VALUE, Long.MIN_VALUE, 1.0, 0);
            storage.push(id);
            same("lookup after growth idx " + i, storage.getNodeId(BlockPos.asLong(10 + i, 64, 0)), id);
        }
        assertTrue("storage size grew past 16", storage.size() >= 43);

        // 2. EdgeGenerator: direct neighbor generation & isPlacedSupportEdge
        assertTrue("bridge is placed support", EdgeGenerator.isPlacedSupportEdge(EdgeGenerator.EDGE_BRIDGE, (byte) 0));
        assertTrue("pillar is placed support", EdgeGenerator.isPlacedSupportEdge(EdgeGenerator.EDGE_PILLAR, (byte) 0));
        assertTrue("parkour-place is placed support", EdgeGenerator.isPlacedSupportEdge(EdgeGenerator.EDGE_PARKOUR, (byte) 9));
        assertFalse("plain parkour not placed support", EdgeGenerator.isPlacedSupportEdge(EdgeGenerator.EDGE_PARKOUR, (byte) 1));
        assertFalse("walk not placed support", EdgeGenerator.isPlacedSupportEdge(EdgeGenerator.EDGE_WALK, (byte) 0));

        GridWorldSnapshot flat = GridWorldSnapshot.builder(0, 0, 0, 6, 6, 6)
                .fillFloor(0, BlockKind.SOLID)
                .build();
        EdgeGenerator.NeighborBuffer nb = new EdgeGenerator.NeighborBuffer();
        nb.clear(0);
        EdgeGenerator.generateNeighbors(flat, CalculationContext.defaults(), 2, 1, 2, 0, false, nb);
        assertTrue("flat world generates cardinal/diagonal/pillar neighbors", nb.count >= 8);

        // 3. GoalAdapters: adapter types, heuristics, and parity across all GoalSpec variants
        GoalAdapters.GoalAdapter exactAdapter = GoalAdapters.forGoal(flat, GoalSpec.exact(new BlockPos(4, 1, 4)));
        assertTrue("exactAdapter is ExactGoal", exactAdapter instanceof GoalAdapters.ExactGoal);
        assertTrue("exactAdapter isGoal at target", exactAdapter.isGoal(4, 1, 4));
        assertFalse("exactAdapter !isGoal elsewhere", exactAdapter.isGoal(3, 1, 4));

        GoalAdapters.GoalAdapter xzAdapter = GoalAdapters.forGoal(flat, GoalSpec.xz(4, 4));
        assertTrue("xzAdapter is XZGoal", xzAdapter instanceof GoalAdapters.XZGoal);
        assertTrue("xzAdapter ignores Y", xzAdapter.isGoal(4, 99, 4));

        GoalAdapters.GoalAdapter losAdapter = GoalAdapters.forGoal(flat, GoalSpec.lineOfSight(new BlockPos(4, 2, 4), 4.5));
        assertTrue("losAdapter is LineOfSightGoal", losAdapter instanceof GoalAdapters.LineOfSightGoal);
        assertTrue("losAdapter has clear sight", losAdapter.isGoal(2, 1, 2));

        GoalAdapters.GoalAdapter runAdapter = GoalAdapters.forGoal(flat, GoalSpec.runAway(new BlockPos(0, 1, 0), 3.0));
        assertTrue("runAdapter is RunAwayGoal", runAdapter instanceof GoalAdapters.RunAwayGoal);
        assertTrue("runAdapter satisfied at distance 4", runAdapter.isGoal(4, 1, 0));
        assertFalse("runAdapter unsatisfied at distance 2", runAdapter.isGoal(2, 1, 0));

        MidtownPlanner planner = new MidtownPlanner();
        try {
            CalculationContext ctx = CalculationContext.defaults();
            PlanResult xzRes = planner.planSync(flat, ctx, new BlockPos(0, 1, 0), GoalSpec.xz(5, 5));
            assertTrue("planner XZ goal complete", xzRes.isComplete());
            same("planner XZ end X", xzRes.endPos().getX(), 5);
            same("planner XZ end Z", xzRes.endPos().getZ(), 5);

            PlanResult runRes = planner.planSync(flat, ctx, new BlockPos(0, 1, 0), GoalSpec.runAway(new BlockPos(0, 1, 0), 4.0));
            assertTrue("planner RunAway goal complete", runRes.isComplete());
            assertTrue("planner RunAway moved >= 4 blocks",
                    Math.hypot(runRes.endPos().getX(), runRes.endPos().getZ()) >= 4.0);
        } finally {
            planner.shutdown();
        }
    }

    private static void testMineController() {
        System.out.println("Checking MineController state, attempt blacklisting, beginMine, and clear...");
        MineController mcCtrl = new MineController();
        BlockFilter oreFilter = BlockFilter.ofNames("minecraft:iron_ore");
        Area box = Area.of(0, 0, 0, 10, 10, 10);

        assertFalse("initially inactive", mcCtrl.isActive());
        same("initial filter null", mcCtrl.filter(), null);
        same("initial box null", mcCtrl.box(), null);
        assertTrue("initial blacklist empty", mcCtrl.blacklist().isEmpty());
        assertFalse("null target never blacklists", mcCtrl.recordAttemptAndCheckBlacklist(null));

        mcCtrl.beginMine(oreFilter, box);
        assertTrue("active after beginMine", mcCtrl.isActive());
        same("filter set after beginMine", mcCtrl.filter(), oreFilter);
        same("box set after beginMine", mcCtrl.box(), box);

        BlockPos target1 = new BlockPos(3, 64, 4);
        BlockPos target2 = new BlockPos(5, 64, 4);

        // Consecutive > 2 attempts blacklists target1 on the 3rd attempt
        assertFalse("attempt 1 does not blacklist", mcCtrl.recordAttemptAndCheckBlacklist(target1));
        assertFalse("not blacklisted after attempt 1", mcCtrl.isBlacklisted(target1));
        assertFalse("attempt 2 does not blacklist", mcCtrl.recordAttemptAndCheckBlacklist(target1));
        assertFalse("not blacklisted after attempt 2", mcCtrl.isBlacklisted(target1));
        assertTrue("attempt 3 blacklists target1", mcCtrl.recordAttemptAndCheckBlacklist(target1));
        assertTrue("target1 is blacklisted", mcCtrl.isBlacklisted(target1));
        same("blacklist size 1", mcCtrl.blacklist().size(), 1);

        // Switching targets resets consecutive counter before blacklisting
        assertFalse("attempt 1 on target2", mcCtrl.recordAttemptAndCheckBlacklist(target2));
        assertFalse("attempt 2 on target2", mcCtrl.recordAttemptAndCheckBlacklist(target2));
        assertFalse("switching back to target1 resets target2 streak", mcCtrl.recordAttemptAndCheckBlacklist(target1));
        assertFalse("target2 not blacklisted after interrupted streak", mcCtrl.isBlacklisted(target2));
        assertFalse("target2 attempt 1 after reset", mcCtrl.recordAttemptAndCheckBlacklist(target2));
        assertFalse("target2 attempt 2 after reset", mcCtrl.recordAttemptAndCheckBlacklist(target2));
        assertTrue("target2 attempt 3 blacklists target2", mcCtrl.recordAttemptAndCheckBlacklist(target2));
        assertTrue("target2 is blacklisted", mcCtrl.isBlacklisted(target2));

        // beginMine resets prior blacklist and attempt state
        mcCtrl.beginMine(oreFilter, null);
        assertTrue("beginMine clears blacklist", mcCtrl.blacklist().isEmpty());
        assertFalse("target1 no longer blacklisted", mcCtrl.isBlacklisted(target1));
        same("beginMine updates box to null", mcCtrl.box(), null);

        // Null Minecraft safety
        assertFalse("hasMatchingBlockInArea false with null mc",
                mcCtrl.hasMatchingBlockInArea(null, target1, oreFilter, box, p -> false, b -> false));
        same("findNearestMatchingInReach null with null mc",
                mcCtrl.findNearestMatchingInReach(null, oreFilter, box, target1, p -> false, b -> false), null);

        // clear() resets everything
        mcCtrl.recordAttemptAndCheckBlacklist(target1);
        mcCtrl.recordAttemptAndCheckBlacklist(target1);
        mcCtrl.recordAttemptAndCheckBlacklist(target1);
        assertTrue("target1 blacklisted before clear", mcCtrl.isBlacklisted(target1));
        mcCtrl.clear();
        assertFalse("inactive after clear", mcCtrl.isActive());
        same("filter null after clear", mcCtrl.filter(), null);
        same("box null after clear", mcCtrl.box(), null);
        assertTrue("blacklist empty after clear", mcCtrl.blacklist().isEmpty());
        assertFalse("isBlacklisted false for null", mcCtrl.isBlacklisted(null));
    }

    private static void testCaveAndDeathMemory() {
        System.out.println("Checking deep cave cover detection (64 blocks) and DeathMemory avoidance...");
        MidtownPlanner planner = new MidtownPlanner();
        try {
            CalculationContext defaultCtx = CalculationContext.defaults();
            // 1. Overhead roof 24 blocks above feet is detected as cave cover, while open sky is 0 penalty
            GridWorldSnapshot tallCaveWorld = GridWorldSnapshot.builder(0, 0, 0, 16, 30, 8)
                    .fillFloor(0, BlockKind.SOLID)
                    .fillBox(2, 25, 0, 14, 25, 2, BlockKind.SOLID)
                    .build();
            double cavePen = HazardCost.DEFAULT.nodePenalty(tallCaveWorld, defaultCtx, 8, 1, 1);
            assertTrue("24-block-high cave roof penalized (>= 20)", cavePen >= 20.0);
            same("open sky cell has 0 hazard penalty", HazardCost.DEFAULT.nodePenalty(tallCaveWorld, defaultCtx, 8, 1, 5), 0.0);

            PlanResult surfaceDetour = planner.planSync(
                    tallCaveWorld,
                    CalculationContext.builder().allowBreak(false).allowPlace(false).build(),
                    new BlockPos(0, 1, 1),
                    GoalSpec.exact(new BlockPos(16, 1, 1)));
            assertTrue("cave detour path complete", surfaceDetour.isComplete());
            assertTrue("detours into open sky instead of walking straight under cave roof",
                    surfaceDetour.waypoints().stream().anyMatch(p -> p.getZ() >= 3));

            // 2. DeathMemory: approach trail, jump reset, dimension isolation, escalation, decay, expiration, persistence
            DeathMemory mem = new DeathMemory();
            long t0 = 1_700_000_000_000L;
            String overworld = "minecraft:overworld";
            String nether = "minecraft:the_nether";

            for (int x = 0; x <= 36; x += 6) {
                mem.recordTrailPosition(overworld, x, 64, 0);
            }
            DeathMemory.RecordSummary first = mem.recordDeath(overworld, 42, 64, 0, t0);
            same("first death nearby count", first.nearbyDeaths(), 1);
            assertTrue("approach breadcrumbs recorded", first.breadcrumbs() >= 5);

            DeathMemory.DeathField f1 = mem.snapshot(overworld, t0);
            assertFalse("death field non-empty", f1.isEmpty());
            assertTrue("death site core penalized (>= 50)", f1.penaltyAt(42, 64, 0) >= 50.0);
            assertTrue("approach breadcrumb penalized", f1.penaltyAt(18, 64, 0) > 0.0);
            same("far outside radius has 0 penalty", f1.penaltyAt(150, 64, 150), 0.0);
            assertTrue("other dimension unaffected", mem.snapshot(nether, t0).isEmpty());

            // Respawn jump reset: moving 200+ blocks clears prior trail (only post-jump points remain)
            mem.recordTrailPosition(overworld, 300, 64, 300);
            mem.recordTrailPosition(overworld, 306, 64, 300);
            DeathMemory.RecordSummary jumped = mem.recordDeath(overworld, 312, 64, 300, t0 + 1000);
            same("jump-reset death only links post-jump breadcrumbs", jumped.breadcrumbs(), 2);

            // Second death at same location stacks super-linearly; verify isolated 1->2 death escalation too
            DeathMemory.RecordSummary second = mem.recordDeath(overworld, 42, 64, 0, t0 + 60_000L);
            same("second death nearby count", second.nearbyDeaths(), 2);
            DeathMemory.DeathField f2 = mem.snapshot(overworld, t0 + 60_000L);
            assertTrue("second death increases corridor penalty strongly",
                    f2.penaltyAt(42, 64, 0) > f1.penaltyAt(42, 64, 0) * 1.8);

            // Decay (45-min half-life), expiration (3h), and pure 1->2 death super-linear stacking (> 2.5x)
            DeathMemory decayMem = new DeathMemory();
            decayMem.recordDeath(overworld, 10, 64, 10, t0);
            double pStart = decayMem.snapshot(overworld, t0).penaltyAt(10, 64, 10);
            double pHalf = decayMem.snapshot(overworld, t0 + DeathMemory.HALF_LIFE_MS).penaltyAt(10, 64, 10);
            assertTrue("half-life decay ~50%", pHalf >= pStart * 0.45 && pHalf <= pStart * 0.55);
            assertTrue("expired after MAX_AGE_MS", decayMem.snapshot(overworld, t0 + DeathMemory.MAX_AGE_MS + 1).isEmpty());
            decayMem.recordDeath(overworld, 10, 64, 10, t0 + 1000L);
            double pStacked = decayMem.snapshot(overworld, t0 + 1000L).penaltyAt(10, 64, 10);
            assertTrue("repeated death escalates super-linearly (> 2.5x single)", pStacked > pStart * 2.5);

            // Save / load JSON persistence round-trip
            try {
                Path tmpDir = Files.createTempDirectory("manhattan-deathmem-test");
                Path file = tmpDir.resolve("world.json");
                mem.save(file);
                DeathMemory reloaded = new DeathMemory();
                reloaded.load(file);
                DeathMemory.DeathField rf = reloaded.snapshot(overworld, t0 + 60_000L);
                same("reloaded death site penalty matches", rf.penaltyAt(42, 64, 0), f2.penaltyAt(42, 64, 0));
                Files.deleteIfExists(file);
                Files.deleteIfExists(tmpDir);
            } catch (Exception e) {
                fails++;
                System.out.println("FAIL DeathMemory save/load threw: " + e.getMessage());
            }

            // 3. A* routes AROUND remembered death corridor, yet still reaches goal inside it if required
            GridWorldSnapshot arena = GridWorldSnapshot.builder(0, 0, 0, 50, 4, 34)
                    .fillFloor(0, BlockKind.SOLID)
                    .build();
            DeathMemory routeMem = new DeathMemory();
            routeMem.recordTrailPosition(overworld, 10, 1, 4);
            routeMem.recordTrailPosition(overworld, 18, 1, 4);
            routeMem.recordDeath(overworld, 25, 1, 4, t0);
            CalculationContext deathCtx = CalculationContext.builder()
                    .allowBreak(false)
                    .allowPlace(false)
                    .deathField(routeMem.snapshot(overworld, t0))
                    .build();

            PlanResult avoidPath = planner.planSync(arena, deathCtx, new BlockPos(0, 1, 4), GoalSpec.exact(new BlockPos(50, 1, 4)));
            assertTrue("death detour path complete", avoidPath.isComplete());
            assertTrue("routes around death site instead of walking through (25, 1, 4)",
                    avoidPath.waypoints().stream().noneMatch(wp -> wp.getX() == 25 && wp.getZ() == 4));
            assertTrue("swings wide to avoid high death heat",
                    avoidPath.waypoints().stream().anyMatch(wp -> wp.getZ() >= 18));

            PlanResult reachInside = planner.planSync(arena, deathCtx, new BlockPos(0, 1, 4), GoalSpec.exact(new BlockPos(25, 1, 4)));
            assertTrue("goal inside death zone remains reachable (soft cost, not hard block)", reachInside.isComplete());

            CalculationContext partialDeathCtx = CalculationContext.builder()
                    .allowBreak(false)
                    .allowPlace(false)
                    .deathField(routeMem.snapshot(overworld, t0))
                    .maxNodesExpanded(600)
                    .build();
            PlanResult partialAvoid = planner.planSync(arena, partialDeathCtx, new BlockPos(0, 1, 4), GoalSpec.xz(200, 4));
            assertTrue("partial path is PARTIAL and has nodes", partialAvoid.isPartial() && partialAvoid.hasPath());
            assertTrue("partial plan steers outward instead of straight into death core",
                    partialAvoid.waypoints().stream().anyMatch(wp -> wp.getZ() >= 6)
                            && partialAvoid.waypoints().stream().noneMatch(wp -> wp.getX() == 25 && wp.getZ() == 4));
        } finally {
            planner.shutdown();
        }
    }

    // ------------------------------------------------------------------ Assertion Helpers

    private static void same(String name, Object got, Object expected) {
        if (Objects.equals(got, expected)) {
            passes++;
            return;
        }
        fails++;
        System.out.println("FAIL " + name + "\n  expected: " + expected + "\n  got:      " + got);
    }

    private static void assertTrue(String name, boolean condition) {
        if (condition) {
            passes++;
            return;
        }
        fails++;
        System.out.println("FAIL " + name + "\n  expected: true\n  got:      false");
    }

    private static void assertFalse(String name, boolean condition) {
        if (!condition) {
            passes++;
            return;
        }
        fails++;
        System.out.println("FAIL " + name + "\n  expected: false\n  got:      true");
    }

    private static void assertThrows(String name, Class<? extends Throwable> expectedType, Runnable block) {
        try {
            block.run();
            fails++;
            System.out.println("FAIL " + name + "\n  expected exception: " + expectedType.getName() + "\n  got: none");
        } catch (Throwable t) {
            if (expectedType.isInstance(t)) {
                passes++;
            } else {
                fails++;
                System.out.println("FAIL " + name + "\n  expected exception: " + expectedType.getName()
                        + "\n  got: " + t.getClass().getName() + " (" + t.getMessage() + ")");
            }
        }
    }
}
