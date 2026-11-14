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
import dev.midtown.exec.PathNode;
import dev.midtown.exec.PathNode.EdgeKey;
import dev.midtown.exec.Planner;
import dev.midtown.plan.BlockKind;
import dev.midtown.plan.CalculationContext;
import dev.midtown.plan.CalculationContext.ProtectedRegion;
import dev.midtown.plan.GridWorldSnapshot;
import dev.midtown.plan.HazardCost;
import dev.midtown.plan.MidtownPlanner;
import dev.midtown.plan.MoveEdge;
import dev.midtown.plan.PlanResult;
import net.minecraft.core.BlockPos;

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
        testHazardCost();
        testMidtownPlanner();
        testItemSaverSettings();

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
            EngineSelector.register("midtown", () -> mock);
            EngineSelector.setOption("midtown");
            same("current() returns registered mock", EngineSelector.current(), mock);
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
