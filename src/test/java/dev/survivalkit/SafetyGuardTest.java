package dev.survivalkit;

import dev.manhattan.fleet.FleetClient;
import net.minecraft.core.BlockPos;

/**
 * Offline unit tests for the missing-bed / world-spawn respawn safety guards and fleet safety halt latch.
 */
public final class SafetyGuardTest {
    private static int passed = 0;

    public static void main(String[] args) {
        testRespawnNearBedIsSafe();
        testRespawnAtWorldSpawnHalts();
        testRespawnOutsideOverworldHalts();
        testRespawnNoBedNearBaseIsSafe();
        testRespawnNoBedFarAwayHalts();
        testFleetSafetyHaltLatch();
        System.out.println("SafetyGuardTest: ALL " + passed + " tests passed.");
    }

    private static void testRespawnNearBedIsSafe() {
        int[] bed = {1870, 66, 1216};
        assertNull(Recovery.evaluateRespawnSafety(new BlockPos(1870, 66, 1216), true, bed, null, null, null),
                "Exact bed respawn is safe");
        assertNull(Recovery.evaluateRespawnSafety(new BlockPos(1873, 66, 1218), true, bed, null, null, null),
                "Adjacent bed respawn is safe");
        assertNull(Recovery.evaluateRespawnSafety(new BlockPos(1894, 66, 1216), true, bed, null, null, null),
                "Within 24 blocks of bed is safe");
        passed++;
    }

    private static void testRespawnAtWorldSpawnHalts() {
        int[] bed = {1870, 66, 1216};
        String reason = Recovery.evaluateRespawnSafety(new BlockPos(807, 69, 213), true, bed, null, null, new BlockPos(1863, 72, 1211));
        assertTrue(reason != null && reason.contains("bed") && reason.contains("Halting"),
                "Respawn at world spawn far from bed must halt, got: " + reason);

        String justOver = Recovery.evaluateRespawnSafety(new BlockPos(1895, 66, 1216), true, bed, null, null, null);
        assertTrue(justOver != null, "25 blocks from bed must halt");
        passed++;
    }

    private static void testRespawnOutsideOverworldHalts() {
        int[] bed = {1870, 66, 1216};
        String reason = Recovery.evaluateRespawnSafety(new BlockPos(1870, 66, 1216), false, bed, null, null, null);
        assertTrue(reason != null && reason.contains("Overworld"),
                "Respawn outside Overworld with bed configured must halt, got: " + reason);
        passed++;
    }

    private static void testRespawnNoBedNearBaseIsSafe() {
        int[] supply = {1868, 71, 1217};
        assertNull(Recovery.evaluateRespawnSafety(new BlockPos(1850, 70, 1200), true, null, supply, null, null),
                "No bed, respawn near supply chest is safe");
        assertNull(Recovery.evaluateRespawnSafety(new BlockPos(1850, 70, 1200), true, null, null, null, new BlockPos(1860, 70, 1205)),
                "No bed, respawn near death pos is safe");
        passed++;
    }

    private static void testRespawnNoBedFarAwayHalts() {
        int[] supply = {1868, 71, 1217};
        String reason = Recovery.evaluateRespawnSafety(new BlockPos(0, 64, 0), true, null, supply, null, new BlockPos(1860, 70, 1205));
        assertTrue(reason != null && reason.contains("world spawn"),
                "No bed, respawn far from base and death must halt, got: " + reason);
        passed++;
    }

    private static void testFleetSafetyHaltLatch() {
        FleetClient fc = FleetClient.instance();
        fc.clearSafetyHalt();
        assertTrue(!fc.isSafetyHalted(), "Initially not safety halted");

        fc.engageSafetyHalt("Bed missing at 1870, 66, 1216");
        assertTrue(fc.isSafetyHalted(), "Safety halt latched");
        assertTrue("Bed missing at 1870, 66, 1216".equals(fc.safetyHaltReason()), "Reason preserved");
        assertTrue(!fc.isWorkerActive(), "Worker inactive while safety halted");

        fc.clearSafetyHalt();
        assertTrue(!fc.isSafetyHalted(), "Safety halt cleared");
        assertNull(fc.safetyHaltReason(), "Reason cleared");
        passed++;
    }

    private static void assertNull(Object v, String msg) {
        if (v != null) throw new AssertionError(msg + " (was: " + v + ")");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("Assertion failed: " + msg);
    }
}
