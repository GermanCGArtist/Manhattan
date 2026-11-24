package dev.midtown;

import dev.midtown.api.EngineStatus;
import dev.midtown.api.GoalSpec;
import dev.midtown.exec.MidtownCoordinator;
import dev.midtown.exec.MidtownCoordinator.TickAction;
import dev.midtown.plan.BlockKind;
import dev.midtown.plan.CalculationContext;
import dev.midtown.plan.GridWorldSnapshot;
import dev.midtown.plan.MidtownPlanner;
import dev.midtown.plan.PeerTraffic;
import dev.midtown.plan.PlanResult;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Multi-bot awareness in Midtown: {@link PeerTraffic} fields, planner costs around other bots, break and place
 * protection for cells bots stand in or on, and the coordinator's yield rule (right of way by id, detours that
 * do not count as failures, edge timers restarted after a wait).
 */
public final class MidtownPeerTest {
    private static int passes;
    private static int fails;

    public static void main(String[] args) {
        testPeerTrafficStore();
        testPlannerRoutesAroundPeer();
        testPlannerNeverBreaksUnderPeer();
        testYieldHighRankWaitsThenDetours();
        testYieldLowRankGivesWayQuickly();
        testYieldEndsCleanlyWhenPeerMoves();
        testNoPlaceAreaDigsStepOutOfPit();
        System.out.println("MidtownPeerTest summary: " + passes + " passed, " + fails + " failed.");
        if (fails > 0) System.exit(1);
    }

    private static void testPeerTrafficStore() {
        PeerTraffic t = new PeerTrafficProbe().store;
        long now = 1_000_000L;
        t.observe("b", "Bob", "minecraft:overworld", 10.5, 64.0, 10.5, now);
        check("peer body at feet", "b".equals(t.peerAt("a", "minecraft:overworld", 10, 64, 10, now)));
        check("peer body at head", "b".equals(t.peerAt("a", "minecraft:overworld", 10, 65, 10, now)));
        check("not above head", t.peerAt("a", "minecraft:overworld", 10, 66, 10, now) == null);
        check("self is never a peer", t.peerAt("b", "minecraft:overworld", 10, 64, 10, now) == null);
        check("other dimension ignored", t.peerAt("a", "minecraft:the_nether", 10, 64, 10, now) == null);
        check("sighting expires", t.peerAt("a", "minecraft:overworld", 10, 64, 10, now + 2_000L) == null);

        t.intent("c", "Cy", "minecraft:overworld", 0.5, 64, 0.5, List.of(new int[]{1, 64, 0}, new int[]{2, 64, 0}), now);
        PeerTraffic.PeerField f = t.field("a", "minecraft:overworld", now);
        check("occupied cell costs a lot", f.penaltyAt(10, 64, 10) == PeerTraffic.OCCUPIED_PENALTY);
        check("head-only overlap costs too", f.penaltyAt(10, 63, 10) == PeerTraffic.OCCUPIED_PENALTY);
        check("intent cell costs a little", f.penaltyAt(2, 64, 0) == PeerTraffic.INTENT_PENALTY);
        check("free cell costs nothing", f.penaltyAt(5, 64, 5) == 0.0);
        check("block under a peer is protected", f.protectsBreak(10, 63, 10));
        check("peer body is protected from placing", f.protectsPlace(10, 65, 10));
        check("intents expire", t.field("a", "minecraft:overworld", now + 5_000L).penaltyAt(2, 64, 0) == 0.0);
        check("break check at runtime: under feet", t.protectsBreak("a", "minecraft:overworld", 10, 63, 10, now));
        check("break check at runtime: elsewhere", !t.protectsBreak("a", "minecraft:overworld", 11, 63, 10, now));
        t.clear();
        check("cleared field is empty", t.field("a", "minecraft:overworld", now).isEmpty());
    }

    private static void testPlannerRoutesAroundPeer() {
        MidtownPlanner planner = new MidtownPlanner();
        try {
            // a 3-wide corridor along x; a bot stands in the middle lane at x = 4
            GridWorldSnapshot world = GridWorldSnapshot.builder(0, 0, -2, 9, 5, 2)
                    .fillFloor(0, BlockKind.SOLID)
                    .fillBox(0, 1, -2, 9, 4, -2, BlockKind.SOLID)
                    .fillBox(0, 1, 2, 9, 4, 2, BlockKind.SOLID)
                    .build();
            CalculationContext alone = CalculationContext.builder().allowBreak(false).allowPlace(false).build();
            PlanResult straight = planner.planSync(world, alone, new BlockPos(0, 1, 0), GoalSpec.exact(new BlockPos(8, 1, 0)));
            check("alone: path complete", straight.isComplete());
            check("alone: walks the middle lane", straight.waypoints().stream().anyMatch(p -> p.getX() == 4 && p.getZ() == 0));

            PeerTraffic.PeerField peer = PeerTraffic.PeerField.ofFeet(List.<int[]>of(new int[]{4, 1, 0}), null);
            CalculationContext withPeer = CalculationContext.builder().allowBreak(false).allowPlace(false)
                    .peerField(peer).build();
            PlanResult around = planner.planSync(world, withPeer, new BlockPos(0, 1, 0), GoalSpec.exact(new BlockPos(8, 1, 0)));
            check("with peer: path complete", around.isComplete());
            check("with peer: steps around the bot",
                    around.waypoints().stream().noneMatch(p -> p.getX() == 4 && p.getZ() == 0 && p.getY() == 1));

            // 1-wide tunnel: no way around, the path still exists (the bot will wait or the peer moves)
            GridWorldSnapshot tunnel = GridWorldSnapshot.builder(0, 0, -1, 9, 5, 1)
                    .fillFloor(0, BlockKind.SOLID)
                    .fillBox(0, 1, -1, 9, 4, -1, BlockKind.SOLID)
                    .fillBox(0, 1, 1, 9, 4, 1, BlockKind.SOLID)
                    .fillBox(0, 3, 0, 9, 4, 0, BlockKind.SOLID)
                    .build();
            PlanResult through = planner.planSync(tunnel, withPeer, new BlockPos(0, 1, 0), GoalSpec.exact(new BlockPos(8, 1, 0)));
            check("1-wide tunnel with a peer still plans (no false NO_PATH)", through.isComplete());
        } finally {
            planner.shutdown();
        }
    }

    private static void testPlannerNeverBreaksUnderPeer() {
        PeerTraffic.PeerField peer = PeerTraffic.PeerField.ofFeet(List.<int[]>of(new int[]{2, 1, 0}), null);
        CalculationContext ctx = CalculationContext.builder().allowBreak(true).peerField(peer).build();
        GridWorldSnapshot world = GridWorldSnapshot.builder(0, 0, -2, 5, 5, 2).fillFloor(0, BlockKind.SOLID).build();
        check("block under a peer may not be broken", ctx.isBreakProtected(2, 0, 0, 1, 1, 0, world));
        check("block next to it may", !ctx.isBreakProtected(3, 0, 0, 1, 1, 0, world));
        check("no placing into a peer", ctx.isPlaceProtected(2, 2, 0, 1, 1, 0));
    }

    private static void testYieldHighRankWaitsThenDetours() {
        MidtownCoordinator c = new MidtownCoordinator();
        BlockPos peerCell = new BlockPos(1, 64, 0);
        c.setPeerLookup(p -> p.equals(peerCell) ? "peer-b" : null, "peer-a"); // "peer-a" < "peer-b": right of way
        c.startGoal(GoalSpec.exact(new BlockPos(5, 64, 0)), new BlockPos(0, 64, 0));
        int idle = 0;
        TickAction last = null;
        for (int i = 0; i < MidtownCoordinator.YIELD_WAIT_TICKS_HIGH - 1; i++) {
            last = c.tick(0.5, 64.0, 0.5);
            if (last instanceof TickAction.Idle) idle++;
        }
        check("waits (idle) while the peer blocks the next step: " + idle, idle == MidtownCoordinator.YIELD_WAIT_TICKS_HIGH - 1);
        check("never reported stuck while waiting", c.status() == EngineStatus.MOVING);
        check("one yield counted", c.yieldsTotal() == 1);
        last = c.tick(0.5, 64.0, 0.5);
        check("then detours (replan), not a failure: " + last, last instanceof TickAction.MoveTo);
        check("detour does not count as a failed replan", c.replanCount() <= 1);
    }

    private static void testYieldLowRankGivesWayQuickly() {
        MidtownCoordinator c = new MidtownCoordinator();
        BlockPos peerCell = new BlockPos(1, 64, 0);
        c.setPeerLookup(p -> p.equals(peerCell) ? "peer-b" : null, "peer-z"); // outranked
        c.startGoal(GoalSpec.exact(new BlockPos(5, 64, 0)), new BlockPos(0, 64, 0));
        int ticksUntilDetour = 0;
        for (int i = 0; i < 100; i++) {
            ticksUntilDetour++;
            if (c.tick(0.5, 64.0, 0.5) instanceof TickAction.MoveTo) break;
        }
        check("outranked bot gives way after " + MidtownCoordinator.YIELD_WAIT_TICKS_LOW + " ticks (" + ticksUntilDetour + ")",
                ticksUntilDetour == MidtownCoordinator.YIELD_WAIT_TICKS_LOW);
        // stuck forever behind a bot that never moves: after the detours it falls back to normal stuck handling
        TickAction last = null;
        for (int i = 0; i < 2_000 && c.status() == EngineStatus.MOVING; i++) last = c.tick(0.5, 64.0, 0.5);
        check("never yields forever: ends " + c.status() + " / " + last, c.status() != EngineStatus.MOVING);
    }

    private static void testYieldEndsCleanlyWhenPeerMoves() {
        MidtownCoordinator c = new MidtownCoordinator();
        boolean[] blocked = {true};
        BlockPos peerCell = new BlockPos(1, 64, 0);
        c.setPeerLookup(p -> blocked[0] && p.equals(peerCell) ? "peer-b" : null, "peer-a");
        c.startGoal(GoalSpec.exact(new BlockPos(5, 64, 0)), new BlockPos(0, 64, 0));
        for (int i = 0; i < 30; i++) c.tick(0.5, 64.0, 0.5);
        check("yielding while blocked", c.isYielding());
        blocked[0] = false;
        TickAction a = c.tick(0.5, 64.0, 0.5);
        check("moves on once the peer has gone: " + a, a instanceof TickAction.MoveTo);
        check("not yielding any more", !c.isYielding());
        // the 30 waiting ticks must not count against the edge timeout: progress resumes normally
        double x = 0.5;
        TickAction b = null;
        for (int i = 0; i < 12; i++) {
            x += 0.1;
            b = c.tick(x, 64.0, 0.5);
        }
        check("no timeout right after the wait: " + b, b instanceof TickAction.MoveTo || b instanceof TickAction.Done);
    }

    /** clear_area's climb-out: with placing forbidden in the cleared pit, Midtown digs a step into the rim. */
    private static void testNoPlaceAreaDigsStepOutOfPit() {
        MidtownPlanner planner = new MidtownPlanner();
        try {
            // ground: solid up to y = 2 (stand at y = 3); a pit x 3..5, z -1..1, y 1..2 dug out (stand at y = 1)
            GridWorldSnapshot world = GridWorldSnapshot.builder(-2, 0, -4, 10, 6, 4)
                    .fillBox(-2, 0, -4, 10, 2, 4, BlockKind.SOLID)
                    .fillBox(3, 1, -1, 5, 2, 1, BlockKind.AIR)
                    .build();
            dev.midtown.api.Area pit = new dev.midtown.api.Area(3, 1, -1, 5, 2, 1);
            CalculationContext keepClear = CalculationContext.builder().allowBreak(true).allowPlace(true)
                    .addProtectedRegion(new CalculationContext.ProtectedRegion("keep", pit, true, false, false, 0))
                    .build();
            PlanResult out = planner.planSync(world, keepClear, new BlockPos(4, 1, 0), GoalSpec.near(new BlockPos(8, 3, 0), 1));
            check("climbs out of the pit without placing in it: " + out.status(), out.isComplete());
            check("no pillar or bridge edges",
                    out.edges().stream().noneMatch(e -> e instanceof dev.midtown.plan.MoveEdge.PillarEdge
                            || e instanceof dev.midtown.plan.MoveEdge.BridgeEdge));
            CalculationContext noBreak = CalculationContext.builder().allowBreak(false).allowPlace(true).build();
            PlanResult pillar = planner.planSync(world, noBreak, new BlockPos(4, 1, 0), GoalSpec.near(new BlockPos(8, 3, 0), 1));
            check("without breaking it would have to place (the old bug)",
                    pillar.isComplete() && pillar.edges().stream().anyMatch(e -> e instanceof dev.midtown.plan.MoveEdge.PillarEdge
                            || e instanceof dev.midtown.plan.MoveEdge.BridgeEdge));
        } finally {
            planner.shutdown();
        }
    }

    /** A private store per test (the global one is shared by the game). */
    private static final class PeerTrafficProbe {
        final PeerTraffic store = newStore();

        private static PeerTraffic newStore() {
            try {
                var ctor = PeerTraffic.class.getDeclaredConstructor();
                ctor.setAccessible(true);
                return ctor.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passes++;
        } else {
            fails++;
            System.out.println("FAIL: " + what);
        }
    }
}
