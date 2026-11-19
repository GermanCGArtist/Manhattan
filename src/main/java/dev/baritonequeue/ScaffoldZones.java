package dev.baritonequeue;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The places where clear_trees may take scaffolding down: a vertical cylinder around each tree it cuts, from the
 * tree's lowest log (one block lower) to its highest (one higher). Scaffolding outside every zone is never touched
 * (it is dropped from the list), so the bot does not wander into caves to dig up its own pillars.
 * The zones are drawn in the world while {@code showTreeZones} (buildtools option) is on.
 */
final class ScaffoldZones {
    private ScaffoldZones() {}

    static final class Zone {
        final double cx, cz, r;
        final int y0, y1;
        volatile boolean pending;   // scaffolding still stands inside
        volatile boolean current;   // the tree is being cut now

        Zone(double cx, double cz, double r, int y0, int y1) {
            this.cx = cx;
            this.cz = cz;
            this.r = r;
            this.y0 = y0;
            this.y1 = y1;
        }

        boolean contains(BlockPos p) {
            if (p.getY() < y0 || p.getY() > y1) return false;
            double dx = p.getX() + 0.5 - cx, dz = p.getZ() + 0.5 - cz;
            return dx * dx + dz * dz <= r * r;
        }
    }

    private static final List<Zone> zones = new CopyOnWriteArrayList<>();

    static void clear() {
        zones.clear();
    }

    /** The zone for a tree whose logs span x0..x1, y0..y1, z0..z1 (block coordinates). Wider trunks get a wider zone. */
    static Zone add(int x0, int y0, int z0, int x1, int y1, int z1) {
        double cx = (x0 + x1 + 1) / 2.0, cz = (z0 + z1 + 1) / 2.0;
        double half = Math.max(x1 - x0 + 1, z1 - z0 + 1) / 2.0;
        double r = Math.max(dev.buildtools.TreeZoneOptions.diameter() / 2.0, half + 1.5);
        Zone z = new Zone(cx, cz, r, y0 - 1, y1 + 1);
        zones.add(z);
        return z;
    }

    static boolean inAny(BlockPos p) {
        for (Zone z : zones) if (z.contains(p)) return true;
        return false;
    }

    static List<Zone> all() {
        return zones;
    }

    // ------------------------------------------------------------------ drawing

    private static final int CURRENT = 0xFF30D5FF;
    private static final int PENDING = 0xFFFFA126;
    private static final int CLEAN = 0x6637D67A;
    private static final int SEGMENTS = 24;
    private static final int POSTS = 8;
    private static final double MAX_DIST = 160;
    private static final int MAX_DRAWN = 120;

    static boolean wanted() {
        return !zones.isEmpty() && dev.buildtools.TreeZoneOptions.show();
    }

    /** Draws the zones; the caller holds the frame's gizmo collection (PathRender.render, one for all of it). */
    static void draw(Minecraft mc) {
        Vec3 me = mc.player.position();
        int drawn = 0;
        for (int i = zones.size() - 1; i >= 0 && drawn < MAX_DRAWN; i--) {
            Zone z = zones.get(i);
            double dx = z.cx - me.x, dz = z.cz - me.z;
            if (dx * dx + dz * dz > MAX_DIST * MAX_DIST) continue;
            drawn++;
            int color = z.current ? CURRENT : z.pending ? PENDING : CLEAN;
            float w = z.current ? 3.0f : 2.0f;
            double yLo = z.y0, yHi = z.y1 + 1;
            for (int k = 0; k < SEGMENTS; k++) {
                double a0 = 2 * Math.PI * k / SEGMENTS, a1 = 2 * Math.PI * (k + 1) / SEGMENTS;
                double x0 = z.cx + z.r * Math.cos(a0), zz0 = z.cz + z.r * Math.sin(a0);
                double x1 = z.cx + z.r * Math.cos(a1), zz1 = z.cz + z.r * Math.sin(a1);
                Gizmos.line(new Vec3(x0, yLo, zz0), new Vec3(x1, yLo, zz1), color, w);
                Gizmos.line(new Vec3(x0, yHi, zz0), new Vec3(x1, yHi, zz1), color, w);
                if (k % (SEGMENTS / POSTS) == 0) Gizmos.line(new Vec3(x0, yLo, zz0), new Vec3(x0, yHi, zz0), color, w);
            }
        }
    }
}
