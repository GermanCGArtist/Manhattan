package dev.baritonequeue;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import static dev.baritonequeue.Config.cfg;

/** The block the player looks at (further than arm's reach), for picking chests, beds and corners into a script. */
final class Places {
    private Places() {}

    private static final double RANGE = 96;

    /** The block, its name, and the cell in front of the face that was looked at (where a block placed there would go). */
    record Target(Values.Pos pos, String name, Values.Pos front) {
        Target(Values.Pos pos, String name) {
            this(pos, name, pos);
        }
    }

    /** The block under the crosshair up to 96 blocks away, or null when looking at the sky. */
    static Target target() {
        Minecraft mc = Minecraft.getInstance();
        var cam = mc.getCameraEntity();
        if (mc.level == null || cam == null) return null;
        HitResult hit = cam.pick(RANGE, 1.0f, false);
        if (!(hit instanceof BlockHitResult b) || hit.getType() != HitResult.Type.BLOCK) return null;
        var block = mc.level.getBlockState(b.getBlockPos()).getBlock();
        return new Target(Values.Pos.of(b.getBlockPos()), BuiltInRegistries.BLOCK.getKey(block).getPath(),
                Values.Pos.of(b.getBlockPos().relative(b.getDirection())));
    }

    /** A short variable name for a picked block: "chest", "bed", "barrel" ... */
    static String varName(String block) {
        if (block.endsWith("_bed")) return "bed";
        if (block.contains("shulker_box")) return "shulker";
        if (block.equals("trapped_chest")) return "chest";
        String n = block.replaceAll("[^a-z0-9_]", "_");
        return n.isEmpty() || Character.isDigit(n.charAt(0)) ? "spot" : n;
    }

    /** Moves every saved chest by an offset; returns how many. */
    static int moveChests(int dx, int dy, int dz) {
        for (int[] c : cfg.chests.values()) { c[0] += dx; c[1] += dy; c[2] += dz; }
        if (!cfg.chests.isEmpty()) Config.save();
        return cfg.chests.size();
    }

    static String saveChest(String name, Values.Pos pos) {
        String n = name.trim().toLowerCase();
        if (!n.matches("[a-z0-9_]+")) return "A chest name is letters, digits and _ only, for example: dirt";
        cfg.chests.put(n, new int[]{pos.x(), pos.y(), pos.z()});
        Config.save();
        return null;
    }
}
