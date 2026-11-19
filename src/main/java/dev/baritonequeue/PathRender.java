package dev.baritonequeue;

import dev.midtown.api.EngineSelector;
import dev.midtown.api.PathEngine;
import dev.midtown.exec.MidtownEngine;
import dev.midtown.exec.PathNode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The planned path in the world, like Baritone's: a red line from the bot along the nodes still ahead (a pale
 * one for the part already walked), green boxes on blocks it will break, orange on blocks it will place and a
 * blue box on the end of the path. Off with {@code showPath} in the config file.
 */
final class PathRender {
    private PathRender() {}

    private static final int AHEAD = 0xFFFF3B3B;
    private static final int BEHIND = 0x55FF9A9A;
    private static final int BREAK = 0xFF2BE05A;
    private static final int PLACE = 0xFFFFA126;
    private static final int GOAL = 0xFF3B7BFF;
    private static final int MAX_NODES = 600;

    static void render() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer == null || mc.player == null) return;
        boolean zones = ScaffoldZones.wanted();   // the tree zones of clear_trees (their own switch: buildtools.showTreeZones)
        MidtownEngine me = null;
        List<PathNode> path = List.of();
        if (Config.cfg.showPath && EngineSelector.current() instanceof MidtownEngine e) {
            me = e;
            path = e.coordinator().path();
        }
        if (!zones && path.isEmpty()) return;
        // one gizmo collection for everything drawn this frame
        try (var ignored = mc.levelRenderer.collectPerFrameGizmos()) {
            if (zones) ScaffoldZones.draw(mc);
            if (path.isEmpty() || me == null) return;
            int idx = Math.max(0, Math.min(me.coordinator().pathIndex(), path.size()));
            int from = Math.max(0, idx - 3);
            int to = Math.min(path.size(), idx + MAX_NODES);
            Vec3 me0 = mc.player.position().add(0, 0.1, 0);
            Vec3 prev = from > 0 ? centre(path.get(from - 1).pos()) : me0;
            for (int i = from; i < to; i++) {
                PathNode n = path.get(i);
                Vec3 c = centre(n.pos());
                Gizmos.line(i == idx ? me0 : prev, c, i < idx ? BEHIND : AHEAD, 3.0f);
                prev = c;
                if (i >= idx) {
                    for (BlockPos b : n.targetBlocks()) {
                        int color = n.action() == PathNode.ActionType.BREAK_THROUGH ? BREAK : PLACE;
                        Gizmos.cuboid(new AABB(b).inflate(0.002), GizmoStyle.stroke(color, 2.0f));
                    }
                }
            }
            if (to == path.size()) {
                Gizmos.cuboid(new AABB(path.get(path.size() - 1).pos()).inflate(0.002), GizmoStyle.stroke(GOAL, 3.0f));
            }
        }
    }

    private static Vec3 centre(BlockPos p) {
        return new Vec3(p.getX() + 0.5, p.getY() + 0.1, p.getZ() + 0.5);
    }
}
