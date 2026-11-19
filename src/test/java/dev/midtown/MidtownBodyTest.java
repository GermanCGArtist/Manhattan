package dev.midtown;

import dev.midtown.api.EngineStatus;
import dev.midtown.api.GoalSpec;
import dev.midtown.api.RuntimeBus;
import dev.midtown.body.HotbarController;
import dev.midtown.body.MotorLease;
import dev.midtown.exec.EdgeTracker;
import dev.midtown.exec.MidtownCoordinator;
import dev.midtown.exec.MidtownCoordinator.ResumeOutcome;
import dev.midtown.exec.MidtownCoordinator.TickAction;
import dev.midtown.exec.MidtownEngine;
import dev.midtown.exec.PathNode;
import dev.midtown.exec.Planner;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Offline unit tests for RuntimeBus, MotorLease, HotbarController swap-slot reservation,
 * and MidtownCoordinator async replan / lookahead / resume drift behavior.
 * Plain Java {@code main()} without JUnit.
 */
public class MidtownBodyTest {

    private static int passes = 0;
    private static int fails = 0;

    public static void main(String[] args) {
        System.out.println("Running MidtownBodyTest...");

        testRuntimeBus();
        testMotorLease();
        testHotbarReservedSlotSelection();
        testCoordinatorAsyncNeedsReplan();
        testCoordinatorApplyReplannedPathPreservesCount();
        testCoordinatorMaxReplansExhaustion();
        testCoordinatorLookaheadReplan();
        testCoordinatorAsyncResumeDrift();
        testMidtownEngineAsyncModeDefault();

        System.out.println("\nMidtownBodyTest summary: " + passes + " passed, " + fails + " failed.");
        if (fails > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ RuntimeBus

    private static void testRuntimeBus() {
        System.out.println("Checking RuntimeBus helpers, pause commands, consume-once halt, and recovery flags...");
        RuntimeBus.resetAll();

        assertFalse("initial scriptActive false", RuntimeBus.isScriptActive());
        same("initial currentStep null", RuntimeBus.currentStep(), null);
        assertFalse("initial paused false", RuntimeBus.isPaused());
        assertFalse("initial recovering false", RuntimeBus.isRecovering());
        assertFalse("initial safeAfterRespawn false", RuntimeBus.isSafeAfterRespawn());
        same("initial recoveryGaveUpReason null", RuntimeBus.recoveryGaveUpReason(), null);
        same("initial finishedSummary null", RuntimeBus.finishedSummary(), null);
        same("initial haltReason null", RuntimeBus.consumeHaltReason(), null);

        // Script & step state
        RuntimeBus.setScriptActive(true);
        RuntimeBus.setCurrentStep("mine diamond_ore");
        assertTrue("scriptActive true", RuntimeBus.isScriptActive());
        same("currentStep set", RuntimeBus.currentStep(), "mine diamond_ore");
        RuntimeBus.setCurrentStep("   ");
        same("blank currentStep normalized to null", RuntimeBus.currentStep(), null);

        // Pause commands
        assertTrue("handlePauseCommand pause", RuntimeBus.handlePauseCommand("#pause"));
        assertTrue("paused after #pause", RuntimeBus.isPaused());
        assertTrue("handlePauseCommand resume", RuntimeBus.handlePauseCommand("resume"));
        assertFalse("unpaused after resume", RuntimeBus.isPaused());
        assertTrue("handlePauseCommand pause toggle on", RuntimeBus.handlePauseCommand("pause toggle"));
        assertTrue("paused after pause toggle", RuntimeBus.isPaused());
        assertTrue("handlePauseCommand toggle off", RuntimeBus.handlePauseCommand("#toggle"));
        assertFalse("unpaused after #toggle", RuntimeBus.isPaused());
        assertTrue("handlePauseCommand p", RuntimeBus.handlePauseCommand("p"));
        assertTrue("paused after p", RuntimeBus.isPaused());
        assertTrue("handlePauseCommand r", RuntimeBus.handlePauseCommand("r"));
        assertFalse("unpaused after r", RuntimeBus.isPaused());
        assertFalse("unknown pause command ignored", RuntimeBus.handlePauseCommand("unknown"));

        // Helpers & addons
        assertFalse("isHelperOn defaults to savedDefault false", RuntimeBus.isHelperOn("eat", false));
        assertTrue("isHelperOn defaults to savedDefault true", RuntimeBus.isHelperOn("eat", true));
        RuntimeBus.setHelpersOn(List.of(" Eat ", "DEFENCE"));
        assertTrue("isHelperOn matches eat", RuntimeBus.isHelperOn("eat", false));
        assertTrue("isHelperOn matches defence", RuntimeBus.isHelperOn("defence", false));
        assertFalse("isHelperOn does not match sleep", RuntimeBus.isHelperOn("sleep", false));
        RuntimeBus.setHelpersOn(List.of("all"));
        assertTrue("isHelperOn all enables any feature", RuntimeBus.isHelperOn("sleep", false));

        RuntimeBus.setAddonsOff(List.of("eat", "restock"));
        assertTrue("isAddonOff matches SurvivalKit eat", RuntimeBus.isAddonOff("SurvivalKit eat"));
        assertTrue("isAddonOff matches restock", RuntimeBus.isAddonOff("restock"));
        assertFalse("isAddonOff false for defence", RuntimeBus.isAddonOff("SurvivalKit defence"));
        RuntimeBus.setAddonsOff(List.of("all"));
        assertTrue("isAddonOff all disables any feature", RuntimeBus.isAddonOff("SurvivalKit defence"));

        RuntimeBus.clearHelpers();
        assertTrue("helpersOn cleared", RuntimeBus.helpersOn().isEmpty());
        assertTrue("addonsOff cleared", RuntimeBus.addonsOff().isEmpty());
        assertFalse("isHelperOn back to savedDefault", RuntimeBus.isHelperOn("eat", false));
        assertFalse("isAddonOff false after clear", RuntimeBus.isAddonOff("eat"));

        // Halt consume-once
        RuntimeBus.requestHalt("water loop detected");
        same("peekHaltReason keeps value", RuntimeBus.peekHaltReason(), "water loop detected");
        same("consumeHaltReason returns reason once", RuntimeBus.consumeHaltReason(), "water loop detected");
        same("consumeHaltReason second call is null", RuntimeBus.consumeHaltReason(), null);

        // Recovery & finished summary
        RuntimeBus.setRecovering(true);
        RuntimeBus.setSafeAfterRespawn(true);
        RuntimeBus.setRecoveryGaveUpReason("died 3 times");
        RuntimeBus.setFinishedSummary("done|12345");
        assertTrue("recovering true", RuntimeBus.isRecovering());
        assertTrue("safeAfterRespawn true", RuntimeBus.isSafeAfterRespawn());
        assertTrue("consumeSafeAfterRespawn true once", RuntimeBus.consumeSafeAfterRespawn());
        assertFalse("consumeSafeAfterRespawn false second time", RuntimeBus.consumeSafeAfterRespawn());
        same("recoveryGaveUpReason set", RuntimeBus.recoveryGaveUpReason(), "died 3 times");
        same("consumeRecoveryGaveUpReason once", RuntimeBus.consumeRecoveryGaveUpReason(), "died 3 times");
        same("consumeRecoveryGaveUpReason second null", RuntimeBus.consumeRecoveryGaveUpReason(), null);
        same("finishedSummary set", RuntimeBus.finishedSummary(), "done|12345");

        // resetAll clears everything
        RuntimeBus.setPaused(true);
        RuntimeBus.requestHalt("stop");
        RuntimeBus.setHelpersOn(List.of("eat"));
        RuntimeBus.resetAll();
        assertFalse("resetAll clears scriptActive", RuntimeBus.isScriptActive());
        assertFalse("resetAll clears paused", RuntimeBus.isPaused());
        assertFalse("resetAll clears recovering", RuntimeBus.isRecovering());
        same("resetAll clears finishedSummary", RuntimeBus.finishedSummary(), null);
        same("resetAll clears haltReason", RuntimeBus.consumeHaltReason(), null);
        assertTrue("resetAll clears helpersOn", RuntimeBus.helpersOn().isEmpty());
    }

    // ------------------------------------------------------------------ MotorLease

    private static void testMotorLease() {
        System.out.println("Checking MotorLease owner switching, priority, and release semantics...");
        MotorLease lease = new MotorLease();
        AtomicInteger releases = new AtomicInteger();

        assertFalse("initially unowned", lease.isOwned());
        same("initial owner null", lease.currentOwner(), null);

        // First acquire does not invoke switch-release
        assertTrue("midtown acquires", lease.acquire("midtown", 10, releases::incrementAndGet));
        same("owner is midtown", lease.currentOwner(), "midtown");
        same("priority is 10", lease.currentPriority(), 10);
        assertTrue("timestamp set", lease.acquiredAtMillis() > 0);
        same("no release on initial acquire", releases.get(), 0);

        // Re-acquiring by same owner does not trigger release
        assertTrue("midtown re-acquires", lease.acquire("midtown", 10, releases::incrementAndGet));
        same("no release on same-owner acquire", releases.get(), 0);

        // Lower priority owner is denied
        assertFalse("lower priority denied", lease.acquire("background", 5, releases::incrementAndGet));
        same("owner still midtown", lease.currentOwner(), "midtown");
        same("no release on denied acquire", releases.get(), 0);

        // Higher priority owner preempts and triggers switch-release
        assertTrue("defence preempts midtown", lease.acquire("defence", 30, releases::incrementAndGet));
        same("owner is defence", lease.currentOwner(), "defence");
        same("priority is 30", lease.currentPriority(), 30);
        same("release invoked once on switch", releases.get(), 1);

        // Equal priority peer handoff succeeds and triggers switch-release
        assertTrue("eat takes over at equal priority", lease.acquire("eat", 30, releases::incrementAndGet));
        same("owner is eat", lease.currentOwner(), "eat");
        same("release invoked on equal-priority switch", releases.get(), 2);

        // releaseIfOwner ignores non-owners
        assertFalse("midtown cannot release eat's lease", lease.releaseIfOwner("midtown", releases::incrementAndGet));
        same("owner remains eat", lease.currentOwner(), "eat");
        same("release count unchanged", releases.get(), 2);

        // releaseIfOwner releases matching owner
        assertTrue("eat releases own lease", lease.releaseIfOwner("eat", releases::incrementAndGet));
        assertFalse("lease now unowned", lease.isOwned());
        same("owner null after release", lease.currentOwner(), null);
        same("release invoked on owner release", releases.get(), 3);

        // forceAcquire overrides higher priority
        assertTrue("high prio acquires", lease.acquire("emergency", 100, releases::incrementAndGet));
        lease.forceAcquire("override", 1, releases::incrementAndGet);
        same("forceAcquire switched owner", lease.currentOwner(), "override");
        same("forceAcquire triggered switch release", releases.get(), 4);

        // releaseAll clears unconditionally
        lease.releaseAll(releases::incrementAndGet);
        assertFalse("unowned after releaseAll", lease.isOwned());
        same("releaseAll invoked callback", releases.get(), 5);

        assertThrows("blank owner rejected", IllegalArgumentException.class, () -> lease.acquire("   "));
    }

    // ------------------------------------------------------------------ HotbarController

    private static void testHotbarReservedSlotSelection() {
        System.out.println("Checking HotbarController reserved hotbar slot mask selection...");
        HotbarController.clearReservedSlots();
        HotbarController.reserveSlot(8);
        assertTrue("slot 8 reserved", HotbarController.isSlotReserved(8));
        assertFalse("slot 7 not reserved", HotbarController.isSlotReserved(7));

        // Only slot 8 is empty, slot 3 is non-protected occupied, slot 0 is selected -> should prefer non-reserved non-protected (slot 3) over reserved empty slot 8?
        // Actually: preference order is empty+unreserved, then non-selected+unreserved+non-protected, then empty(reserved).
        boolean[] empty = new boolean[9];
        boolean[] prot = new boolean[9];
        for (int i = 0; i < 9; i++) prot[i] = true;
        empty[8] = true;
        prot[8] = false;
        prot[3] = false; // expendable block in slot 3
        int picked = HotbarController.pickSwapSlotIndex(empty, prot, 0, HotbarController.reservedSlotMask());
        same("avoids reserved slot 8 when unreserved non-protected slot 3 exists", picked, 3);

        // When unreserved empty slot 5 exists, picks slot 5 first
        empty[5] = true;
        prot[5] = false;
        same("picks unreserved empty slot 5", HotbarController.pickSwapSlotIndex(empty, prot, 0, HotbarController.reservedSlotMask()), 5);

        HotbarController.unreserveSlot(8);
        assertFalse("slot 8 unreserved", HotbarController.isSlotReserved(8));
        HotbarController.clearReservedSlots();
    }

    // ------------------------------------------------------------------ MidtownCoordinator async replan

    private static void testCoordinatorAsyncNeedsReplan() {
        System.out.println("Checking MidtownCoordinator async NeedsReplan mode...");
        AtomicInteger syncPlannerCalls = new AtomicInteger();
        Planner countingPlanner = (goal, start, bl) -> {
            syncPlannerCalls.incrementAndGet();
            return List.of(PathNode.walk(start.east()));
        };
        EdgeTracker tracker = new EdgeTracker(3, 80, 100, 0.05);
        MidtownCoordinator coord = new MidtownCoordinator(tracker, countingPlanner);
        coord.setAsyncReplanMode(true);
        assertTrue("asyncReplanMode enabled", coord.isAsyncReplanMode());

        GoalSpec goal = GoalSpec.exact(new BlockPos(10, 64, 0));
        BlockPos start = new BlockPos(0, 64, 0);
        coord.startGoalWithPath(goal, start, List.of(
                PathNode.walk(new BlockPos(1, 64, 0)),
                PathNode.walk(new BlockPos(2, 64, 0))));
        same("status MOVING", coord.status(), EngineStatus.MOVING);

        // Stand still for 3 ticks to trigger stuck
        assertTrue("tick 1 MoveTo", coord.tick(0.5, 64.0, 0.5) instanceof TickAction.MoveTo);
        assertTrue("tick 2 MoveTo", coord.tick(0.5, 64.0, 0.5) instanceof TickAction.MoveTo);
        TickAction stuckAction = coord.tick(0.5, 64.0, 0.5);

        assertTrue("stuck emits NeedsReplan in async mode", stuckAction instanceof TickAction.NeedsReplan);
        TickAction.NeedsReplan nr = (TickAction.NeedsReplan) stuckAction;
        same("NeedsReplan goal matches", nr.goal(), goal);
        same("NeedsReplan start matches", nr.start(), start);
        assertTrue("NeedsReplan reason mentions Stuck", nr.reason().contains("Stuck"));
        same("status transitioned to PLANNING", coord.status(), EngineStatus.PLANNING);
        same("replanCount incremented to 1", coord.replanCount(), 1);
        same("sync planner was NEVER invoked", syncPlannerCalls.get(), 0);
        assertTrue("stuck edge blacklisted", tracker.isBlacklisted(start, new BlockPos(1, 64, 0), coord.tickCounter()));

        // While PLANNING, ticks emit Idle
        assertTrue("tick while PLANNING emits Idle", coord.tick(0.5, 64.0, 0.5) instanceof TickAction.Idle);
    }

    private static void testCoordinatorApplyReplannedPathPreservesCount() {
        System.out.println("Checking applyReplannedPath preserves replanCount and handles empty path...");
        EdgeTracker tracker = new EdgeTracker(2, 80, 100, 0.05);
        MidtownCoordinator coord = new MidtownCoordinator(tracker, new Planner.DirectStepPlanner());
        coord.setAsyncReplanMode(true);

        GoalSpec goal = GoalSpec.exact(new BlockPos(10, 64, 0));
        BlockPos start = new BlockPos(0, 64, 0);
        coord.startGoalWithPath(goal, start, List.of(PathNode.walk(new BlockPos(1, 64, 0))));

        // Trigger stuck replan #1
        coord.tick(0.5, 64.0, 0.5);
        TickAction a1 = coord.tick(0.5, 64.0, 0.5);
        assertTrue("replan #1 emitted NeedsReplan", a1 instanceof TickAction.NeedsReplan);
        same("replanCount is 1", coord.replanCount(), 1);

        // Apply replanned path: must restore MOVING while preserving replanCount == 1
        boolean applied = coord.applyReplannedPath(start, List.of(
                PathNode.walk(new BlockPos(0, 64, 1)),
                PathNode.walk(new BlockPos(1, 64, 1))));
        assertTrue("applyReplannedPath succeeded", applied);
        same("status restored to MOVING", coord.status(), EngineStatus.MOVING);
        same("replanCount preserved at 1", coord.replanCount(), 1);
        same("new path target applied", coord.currentTarget().pos(), new BlockPos(0, 64, 1));

        // Empty replanned path marks FAILED
        boolean appliedEmpty = coord.applyReplannedPath(start, List.of());
        assertFalse("empty applyReplannedPath returns false", appliedEmpty);
        same("status FAILED on empty replan", coord.status(), EngineStatus.FAILED);
        same("replanCount still preserved at 1", coord.replanCount(), 1);
    }

    private static void testCoordinatorMaxReplansExhaustion() {
        System.out.println("Checking maxReplans exhaustion in async replan mode...");
        EdgeTracker tracker = new EdgeTracker(2, 80, 100, 0.05);
        MidtownCoordinator coord = new MidtownCoordinator(tracker, new Planner.DirectStepPlanner());
        coord.setAsyncReplanMode(true);
        coord.setMaxReplans(2);

        GoalSpec goal = GoalSpec.exact(new BlockPos(10, 64, 0));
        BlockPos start = new BlockPos(0, 64, 0);
        coord.startGoalWithPath(goal, start, List.of(PathNode.walk(new BlockPos(1, 64, 0))));

        // Replan #1
        coord.tick(0.5, 64.0, 0.5);
        assertTrue("replan #1 NeedsReplan", coord.tick(0.5, 64.0, 0.5) instanceof TickAction.NeedsReplan);
        assertTrue("apply replan #1", coord.applyReplannedPath(start, List.of(PathNode.walk(new BlockPos(0, 64, 1)))));

        // Replan #2
        coord.tick(0.5, 64.0, 0.5);
        assertTrue("replan #2 NeedsReplan", coord.tick(0.5, 64.0, 0.5) instanceof TickAction.NeedsReplan);
        assertTrue("apply replan #2", coord.applyReplannedPath(start, List.of(PathNode.walk(new BlockPos(0, 64, -1)))));

        // Replan #3 exceeds maxReplans=2 -> Failed
        coord.tick(0.5, 64.0, 0.5);
        TickAction exceeded = coord.tick(0.5, 64.0, 0.5);
        assertTrue("replan #3 emits Failed", exceeded instanceof TickAction.Failed);
        same("status FAILED after maxReplans", coord.status(), EngineStatus.FAILED);
        assertTrue("failure reason mentions max replans", coord.lastFailureReason().contains("Exceeded max replans"));
    }

    private static void testCoordinatorLookaheadReplan() {
        System.out.println("Checking requestLookaheadReplan transitions and maxReplans cap...");
        MidtownCoordinator coord = new MidtownCoordinator();
        coord.setAsyncReplanMode(true);
        coord.setMaxReplans(1);

        // Returns false when IDLE
        assertFalse("lookahead rejected when IDLE", coord.requestLookaheadReplan("test"));

        BlockPos start = new BlockPos(0, 64, 0);
        coord.startGoalWithPath(GoalSpec.exact(new BlockPos(5, 64, 0)), start, List.of(PathNode.walk(new BlockPos(1, 64, 0))));
        assertTrue("first lookahead accepted", coord.requestLookaheadReplan("lava appeared"));
        same("status PLANNING after lookahead", coord.status(), EngineStatus.PLANNING);
        same("replanCount 1 after lookahead", coord.replanCount(), 1);

        // Apply new path and trigger second lookahead -> exceeds maxReplans=1
        assertTrue("apply replan after lookahead", coord.applyReplannedPath(start, List.of(PathNode.walk(new BlockPos(0, 64, 1)))));
        assertFalse("second lookahead exceeds maxReplans", coord.requestLookaheadReplan("wall appeared"));
        same("status FAILED after lookahead exhaustion", coord.status(), EngineStatus.FAILED);
    }

    private static void testCoordinatorAsyncResumeDrift() {
        System.out.println("Checking async resume drift handling...");
        AtomicInteger syncPlannerCalls = new AtomicInteger();
        Planner countingPlanner = (goal, start, bl) -> {
            syncPlannerCalls.incrementAndGet();
            return List.of(PathNode.walk(start.east()));
        };
        MidtownCoordinator coord = new MidtownCoordinator(new EdgeTracker(), countingPlanner);
        coord.setAsyncReplanMode(true);

        BlockPos start = new BlockPos(0, 64, 0);
        GoalSpec goal = GoalSpec.exact(new BlockPos(10, 64, 0));
        coord.startGoalWithPath(goal, start, List.of(
                PathNode.walk(new BlockPos(1, 64, 0)),
                PathNode.walk(new BlockPos(2, 64, 0))));

        // Pause and resume near edge -> CONTINUE_EDGE
        coord.pause();
        same("resume near edge continues", coord.resume(0.5, 64.0, 0.5), ResumeOutcome.CONTINUE_EDGE);
        same("status MOVING on continue", coord.status(), EngineStatus.MOVING);

        // Pause and resume far away (> resumeMaxDrift) -> NEEDS_REPLAN without calling sync planner
        coord.pause();
        ResumeOutcome driftOutcome = coord.resume(25.5, 64.0, 0.5);
        same("resume with drift returns NEEDS_REPLAN in async mode", driftOutcome, ResumeOutcome.NEEDS_REPLAN);
        same("status PLANNING after drift resume", coord.status(), EngineStatus.PLANNING);
        same("sync planner never called on async drift resume", syncPlannerCalls.get(), 0);
        same("activeGoal preserved for async replan", coord.activeGoal(), goal);
    }

    private static void testMidtownEngineAsyncModeDefault() {
        System.out.println("Checking MidtownEngine default constructor enables asyncReplanMode...");
        MidtownEngine defaultEngine = new MidtownEngine();
        assertTrue("default MidtownEngine enables asyncReplanMode", defaultEngine.coordinator().isAsyncReplanMode());

        MidtownEngine customEngine = new MidtownEngine(new Planner.DirectStepPlanner());
        assertFalse("custom-planner MidtownEngine keeps sync mode", customEngine.coordinator().isAsyncReplanMode());
    }

    // ------------------------------------------------------------------ Assertion Helpers

    private static void same(String label, Object actual, Object expected) {
        if (Objects.equals(actual, expected)) {
            passes++;
        } else {
            fails++;
            System.out.println("  FAIL: " + label + " | expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertTrue(String label, boolean condition) {
        if (condition) {
            passes++;
        } else {
            fails++;
            System.out.println("  FAIL: " + label + " | expected true");
        }
    }

    private static void assertFalse(String label, boolean condition) {
        if (!condition) {
            passes++;
        } else {
            fails++;
            System.out.println("  FAIL: " + label + " | expected false");
        }
    }

    private static void assertThrows(String label, Class<? extends Throwable> expected, Runnable action) {
        try {
            action.run();
            fails++;
            System.out.println("  FAIL: " + label + " | expected " + expected.getSimpleName() + " but nothing thrown");
        } catch (Throwable t) {
            if (expected.isInstance(t)) {
                passes++;
            } else {
                fails++;
                System.out.println("  FAIL: " + label + " | expected " + expected.getSimpleName() + " got " + t);
            }
        }
    }
}
