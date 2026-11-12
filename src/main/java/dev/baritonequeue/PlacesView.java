package dev.baritonequeue;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

import static dev.baritonequeue.Config.cfg;

/**
 * View-model over saved chests, beds, Litematica placements, and the {@link Capture} sheet/roles
 * for the Stage 3 Areas and Capture panels.
 */
public final class PlacesView {
    public record SavedSpot(String name, String kind, int x, int y, int z, String scriptExpr) {}

    public record SchematicEntry(int index, String name, int x, int y, int z, int regions, boolean enabled) {}

    private PlacesView() {}

    /** Saved chests (cfg.chests), survival bed/supply (dev.survivalkit.Config), and captured point entries. */
    public static List<SavedSpot> spots() {
        List<SavedSpot> out = new ArrayList<>();
        if (cfg != null && cfg.chests != null) {
            cfg.chests.forEach((name, c) -> {
                if (c != null && c.length >= 3) {
                    out.add(new SavedSpot(name, "chest", c[0], c[1], c[2], "chest(\"" + name + "\")"));
                }
            });
        }
        try {
            for (CaptureSheet.Entry e : Capture.entries()) {
                if (e.active() && e.pos() != null) {
                    int[] p = e.pos();
                    String kind = e.role().isEmpty() ? "spot" : e.role();
                    out.add(new SavedSpot(e.name(), kind, p[0], p[1], p[2],
                            "let " + e.name() + " = pos(" + p[0] + ", " + p[1] + ", " + p[2] + ")"));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Litematica placements currently available in this world. */
    public static List<SchematicEntry> schematics() {
        List<SchematicEntry> out = new ArrayList<>();
        try {
            List<SchemSource.Placement> list = SchemSource.list();
            for (int i = 0; i < list.size(); i++) {
                SchemSource.Placement p = list.get(i);
                var o = p.origin();
                out.add(new SchematicEntry(i + 1, p.name(), o.getX(), o.getY(), o.getZ(), p.subs().size(), p.enabled()));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static String lookedAtSummary() {
        try {
            Places.Target t = Places.target();
            if (t == null) return "nothing (aim at a block in the Game Viewport)";
            return t.name() + " at " + t.pos();
        } catch (Throwable ignored) {
            return "nothing";
        }
    }

    /** Fetch looked-at block/schematic into the current Capture sheet using the selected Capture role. */
    public static String fetchLookedAt() {
        Places.Target t = Places.target();
        String msg = Capture.capture(t);
        Util.msg(msg);
        return msg;
    }

    /** Enters Aim in world mode (press J in game to capture, K to stop aiming). */
    public static String startAimingInWorld() {
        Capture.aiming = true;
        Capture.corner1 = null;
        Capture.captured = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.screen != null) {
            mc.setScreen(null);
        }
        String msg = "Aiming in world for '" + Capture.type().label + "': look at a block in the Game Viewport and press J.";
        Util.msg(msg);
        return msg;
    }
}
