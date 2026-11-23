package dev.manhattan;

import dev.manhattan.fleet.FleetClient;
import dev.manhattan.fleet.FleetProtocol;
import dev.manhattan.project.GameProjectRuntime;
import dev.manhattan.project.Project;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static dev.manhattan.Config.cfg;

/**
 * Client-side 3D world visualization for editors and bots (LOCAL / EDITOR / WORKER / BOTH):
 * draws configured beds, chests, safe (protected) areas, and task/saved areas with live client-side
 * block verification, per-element visibility controls, and clean handling of overlapping/adjacent areas.
 *
 * <p>When a bed or chest is destroyed (e.g. by a creeper), its exact block coordinate pulses red with
 * an X-cross and a {@code [MISSING!]} label. As soon as it is placed back, the client verifies the
 * live block state, switches the marker to green {@code [OK]}, notifies chat, and clears any sleep
 * retry cooldown so the bot can use it immediately.
 */
public final class WorldOverlay {
    private WorldOverlay() {}

    public enum Category {
        BED,
        CHEST,
        SPOT
    }

    public enum Role {
        BED,
        BED_POOL,
        ASSIGNED_BED,
        TOOL_CHEST,
        RETURN_CHEST,
        DUMP_CHEST,
        SCAFFOLD_CHEST,
        MATERIAL_CHEST,
        NAMED_CHEST,
        SPOT
    }

    public enum Status {
        OK,
        MISSING,
        WRONG,
        BLOCKED,
        UNLOADED,
        INFO
    }

    public record PointMarker(int x, int y, int z, String label, Category category, Role role) {
        public String key() {
            return pointKey(category, x, y, z);
        }
    }

    public record AreaMarker(int x1, int y1, int z1, int x2, int y2, int z2, String label, boolean safe, String role) {
        public String key() {
            return areaKey(label);
        }

        public boolean overlaps(AreaMarker other) {
            return x1 <= other.x2 && x2 >= other.x1
                    && y1 <= other.y2 && y2 >= other.y1
                    && z1 <= other.z2 && z2 >= other.z1;
        }

        public boolean contains(AreaMarker other) {
            return x1 <= other.x1 && x2 >= other.x2
                    && y1 <= other.y1 && y2 >= other.y2
                    && z1 <= other.z1 && z2 >= other.z2;
        }
    }

    /** Area marker with resolved overlap layer, Z-fighting inset offset, and distinct outline color. */
    public record StyledArea(AreaMarker area, int layerIndex, boolean overlapping, int color, double inflate) {}

    public record CheckResult(Status status, String detail) {
        public boolean isProblem() {
            return status == Status.MISSING || status == Status.WRONG || status == Status.BLOCKED;
        }
    }

    /** Pure-Java snapshot of all data sources, allowing headless unit testing without Minecraft. */
    public record Sources(
            Project runningProject,
            Project activeProject,
            Project fleetProject,
            int[] fleetAssignedBed,
            List<FleetProtocol.BotInfo> fleetBots,
            int[] survivalBed,
            int[] survivalSupply,
            List<int[]> survivalExtraSupplies,
            int[] survivalReturnChest,
            int[] loadChestPos,
            Map<String, int[]> savedChests,
            List<dev.buildtools.Api.ProtectedZone> protectedZones,
            boolean protectEnabled,
            int[] pendingProtectCorner1,
            int[] pendingProtectCorner2,
            Map<String, List<int[]>> savedAreas,
            Map<String, String> savedAreaRoles
    ) {}

    // Distances
    private static final double MAX_POINT_DIST = 128.0;
    private static final double MAX_AREA_DIST = 160.0;
    private static final double MAX_LABEL_DIST = 48.0;
    private static final double MAX_PROBLEM_LABEL_DIST = 72.0;

    // Colors (ARGB)
    private static final int COL_OK_BED = 0xFF37E07D;
    private static final int COL_TOOL_CHEST = 0xFFFFC107;
    private static final int COL_RETURN_CHEST = 0xFFFF8A33;
    private static final int COL_DUMP_CHEST = 0xFF29D4FF;
    private static final int COL_SCAFFOLD_CHEST = 0xFFC084FC;
    private static final int COL_MATERIAL_CHEST = 0xFFFACC15;
    private static final int COL_NAMED_CHEST = 0xFF60A5FA;
    private static final int COL_SPOT = 0xFFCBD5E1;

    private static final int COL_MISSING_A = 0xFFFF2B3D;
    private static final int COL_MISSING_B = 0xFFFF6E7A;
    private static final int COL_BLOCKED = 0xFFFFAA00;
    private static final int COL_UNLOADED = 0x9994A3B8;

    private static final int COL_SAFE = 0xFF22D3A0;
    private static final int COL_SAFE_OFF = 0x7794A3B8;
    private static final int COL_AREA_DIG = 0xCCFB923C;
    private static final int COL_AREA_FILL = 0xCC38BDF8;
    private static final int COL_AREA_BUILD = 0xCCA78BFA;
    private static final int COL_AREA_CUSTOM = 0xAA67E8F9;

    // Distinct palettes when multiple areas overlap so their outlines stay clearly separable
    private static final int[] OVERLAP_SAFE_PALETTE = {
            0xFF22D3A0, // emerald
            0xFF34D399, // mint
            0xFF2DD4BF, // teal
            0xFF86EFAC  // light green
    };
    private static final int[] OVERLAP_TASK_PALETTE = {
            0xDD38BDF8, // sky
            0xDDFB923C, // orange
            0xDDA78BFA, // violet
            0xDDF472B6, // pink
            0xDDFACC15, // yellow
            0xDD60A5FA  // blue
    };

    // Live status transition tracking (world/dimension scoped)
    private static Object trackedLevel;
    private static String trackedDimension = "";
    private static final Map<String, Status> lastStatuses = new LinkedHashMap<>();

    // ------------------------------------------------------------------ Per-element visibility API

    public static String pointKey(Category category, int x, int y, int z) {
        return (category == null ? Category.SPOT : category).name() + ":" + x + "," + y + "," + z;
    }

    public static String areaKey(String label) {
        return label == null ? "" : label.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isPointVisible(PointMarker pt) {
        if (pt == null) return false;
        return isPointVisible(pt.category(), pt.x(), pt.y(), pt.z());
    }

    public static boolean isPointVisible(Category category, int[] coords) {
        if (coords == null || coords.length < 3) return true;
        return isPointVisible(category, coords[0], coords[1], coords[2]);
    }

    public static boolean isPointVisible(Category category, int x, int y, int z) {
        if (cfg == null || cfg.overlayHiddenPoints == null) return true;
        return !cfg.overlayHiddenPoints.contains(pointKey(category, x, y, z));
    }

    public static void setPointVisible(Category category, int x, int y, int z, boolean visible) {
        if (cfg == null) return;
        if (cfg.overlayHiddenPoints == null) cfg.overlayHiddenPoints = new LinkedHashSet<>();
        String key = pointKey(category, x, y, z);
        if (visible) cfg.overlayHiddenPoints.remove(key);
        else cfg.overlayHiddenPoints.add(key);
        Config.save();
    }

    public static boolean isAreaVisible(String label) {
        if (cfg == null || cfg.overlayHiddenAreas == null || label == null) return true;
        return !cfg.overlayHiddenAreas.contains(areaKey(label));
    }

    public static void setAreaVisible(String label, boolean visible) {
        if (cfg == null || label == null || label.isBlank()) return;
        if (cfg.overlayHiddenAreas == null) cfg.overlayHiddenAreas = new LinkedHashSet<>();
        String key = areaKey(label);
        if (visible) cfg.overlayHiddenAreas.remove(key);
        else cfg.overlayHiddenAreas.add(key);
        Config.save();
    }

    public static void resetHidden() {
        if (cfg == null) return;
        if (cfg.overlayHiddenAreas != null) cfg.overlayHiddenAreas.clear();
        if (cfg.overlayHiddenPoints != null) cfg.overlayHiddenPoints.clear();
        Config.save();
    }

    public static int hiddenCount() {
        if (cfg == null) return 0;
        int a = cfg.overlayHiddenAreas != null ? cfg.overlayHiddenAreas.size() : 0;
        int p = cfg.overlayHiddenPoints != null ? cfg.overlayHiddenPoints.size() : 0;
        return a + p;
    }

    /** Hides or shows matching areas and/or point markers by name/label (case-insensitive substring/exact). */
    public static int setVisibleByName(String name, boolean visible) {
        if (name == null || name.isBlank()) return 0;
        String q = name.trim().toLowerCase(Locale.ROOT);
        Sources src = gatherLiveSources();
        int changed = 0;

        Set<String> matchedAreas = new LinkedHashSet<>();
        for (AreaMarker a : collectAreas(src, true, true)) {
            if (a.label().toLowerCase(Locale.ROOT).equals(q) || a.label().toLowerCase(Locale.ROOT).contains(q)) {
                if (matchedAreas.add(areaKey(a.label()))) {
                    setAreaVisible(a.label(), visible);
                    changed++;
                }
            }
        }
        for (PointMarker p : collectPoints(src)) {
            if (p.label().toLowerCase(Locale.ROOT).equals(q) || p.label().toLowerCase(Locale.ROOT).contains(q)) {
                setPointVisible(p.category(), p.x(), p.y(), p.z(), visible);
                changed++;
            }
        }
        // Even if not currently active, allow direct area-name unhide/hide
        if (changed == 0) {
            setAreaVisible(name, visible);
            changed = 1;
        }
        return changed;
    }

    /** Current deduplicated point markers for UI panels. */
    public static List<PointMarker> currentPoints() {
        return collectPoints(gatherLiveSources());
    }

    /** Current deduplicated & merged area markers for UI panels. */
    public static List<AreaMarker> currentAreas() {
        return mergeAdjacentAreas(collectAreas(gatherLiveSources(), true, true));
    }

    // ------------------------------------------------------------------ Rendering entry point

    public static void render() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null || mc.levelRenderer == null) return;
        syncWorld(mc);
        if (cfg == null || !cfg.showWorldOverlay) return;

        Sources src = gatherLiveSources();
        boolean problemsOnly = cfg.showOverlayProblemsOnly;
        List<PointMarker> allPoints = collectPoints(src);

        List<AreaMarker> visibleAreas = List.of();
        if (!problemsOnly && (cfg.showOverlaySafeAreas || cfg.showOverlayTaskAreas)) {
            List<AreaMarker> rawAreas = collectAreas(src, cfg.showOverlaySafeAreas, cfg.showOverlayTaskAreas);
            List<AreaMarker> filtered = new ArrayList<>(rawAreas.size());
            for (AreaMarker a : rawAreas) {
                if (isAreaVisible(a.label())) filtered.add(a);
            }
            visibleAreas = mergeAdjacentAreas(filtered);
        }

        if (allPoints.isEmpty() && visibleAreas.isEmpty()) return;

        Vec3 me = mc.player.position();
        String dim = mc.level.dimension().identifier().toString();
        boolean pulse = ((System.currentTimeMillis() / 350L) & 1L) == 0L;

        // Filter areas by distance first, then resolve overlaps only among visible nearby areas
        List<AreaMarker> nearbyAreas = new ArrayList<>();
        for (AreaMarker a : visibleAreas) {
            if (withinAreaDist(me, a, MAX_AREA_DIST)) {
                nearbyAreas.add(a);
            }
        }
        List<StyledArea> styledAreas = resolveOverlaps(nearbyAreas, src.protectEnabled());

        try (var ignored = mc.levelRenderer.collectPerFrameRenderThreadGizmos()) {
            // 1. Safe areas & task/saved areas (wireframe-only by default; overlap-aware)
            List<Vec3> placedAreaLabels = new ArrayList<>();
            for (StyledArea sa : styledAreas) {
                drawArea(sa, src.protectEnabled(), me, placedAreaLabels);
            }

            // 2. Beds & chests (live block verification always runs so transitions notify & reset cooldown)
            for (PointMarker pt : allPoints) {
                double dx = (pt.x() + 0.5) - me.x;
                double dz = (pt.z() + 0.5) - me.z;
                double distSq = dx * dx + dz * dz;
                if (distSq > MAX_POINT_DIST * MAX_POINT_DIST) continue;

                BlockPos pos = new BlockPos(pt.x(), pt.y(), pt.z());
                CheckResult check = verifyBlock(pt.category(), pos, mc.level);
                recordTransition(dim, pt, check);

                if (pt.category() == Category.BED && !cfg.showOverlayBeds) continue;
                if (pt.category() == Category.CHEST && !cfg.showOverlayChests) continue;
                if (!isPointVisible(pt)) continue;
                if (problemsOnly && !check.isProblem()) continue;

                String fleetState = null;
                if (pt.category() == Category.CHEST) {
                    fleetState = FleetClient.instance().chestSyncState(dim, pos);
                }

                drawPoint(pt, check, fleetState, distSq, pulse);
            }
        }
    }

    private static void syncWorld(Minecraft mc) {
        String dim = mc.level.dimension().identifier().toString();
        if (mc.level != trackedLevel || !dim.equals(trackedDimension)) {
            trackedLevel = mc.level;
            trackedDimension = dim;
            lastStatuses.clear();
        }
    }

    // ------------------------------------------------------------------ Live verification

    /**
     * Verifies whether the expected block (bed or storage container) currently exists at {@code pos}
     * in the client's loaded world.
     */
    public static CheckResult verifyBlock(Category category, BlockPos pos, Level level) {
        if (level == null || pos == null) return new CheckResult(Status.UNLOADED, "no world");
        if (category == Category.SPOT) return new CheckResult(Status.INFO, "");
        if (!level.hasChunkAt(pos)) return new CheckResult(Status.UNLOADED, "unloaded");

        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        String blockName = BuiltInRegistries.BLOCK.getKey(block).getPath();

        if (category == Category.BED) {
            if (block instanceof BedBlock) return new CheckResult(Status.OK, blockName);
            if (state.isAir()) return new CheckResult(Status.MISSING, "air");
            return new CheckResult(Status.WRONG, blockName);
        }

        if (category == Category.CHEST) {
            if (block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock) {
                if (block instanceof ChestBlock && ChestBlock.isChestBlockedAt(level, pos)) {
                    return new CheckResult(Status.BLOCKED, "lid blocked");
                }
                return new CheckResult(Status.OK, blockName);
            }
            if (state.isAir()) return new CheckResult(Status.MISSING, "air");
            return new CheckResult(Status.WRONG, blockName);
        }

        return new CheckResult(Status.INFO, blockName);
    }

    /** Convenience check for UI panels (e.g. ProjectPanel Base & Survival badges). */
    public static CheckResult checkPlace(int[] coords, Category category) {
        if (coords == null || coords.length < 3) return null;
        Minecraft mc = null;
        try {
            mc = Minecraft.getInstance();
        } catch (Throwable ignored) {
        }
        if (mc == null || mc.level == null) return new CheckResult(Status.UNLOADED, "no world");
        return verifyBlock(category, new BlockPos(coords[0], coords[1], coords[2]), mc.level);
    }

    /**
     * Pure-Java status transition classifier: returns a human-readable notification message when a
     * loaded marker breaks (OK -> MISSING/WRONG/BLOCKED) or is repaired (MISSING/WRONG/BLOCKED -> OK),
     * or {@code null} when no transition occurred (including UNLOADED -> anything).
     */
    static String evaluateTransition(Status previous, Status current, PointMarker marker, String detail) {
        if (previous == null || previous == current || current == Status.UNLOADED || previous == Status.UNLOADED) {
            return null;
        }
        String coords = marker.x() + " " + marker.y() + " " + marker.z();
        boolean wasBad = previous == Status.MISSING || previous == Status.WRONG || previous == Status.BLOCKED;
        boolean nowBad = current == Status.MISSING || current == Status.WRONG || current == Status.BLOCKED;
        if (wasBad && current == Status.OK) {
            return marker.label() + " at " + coords + " recognised again (" + detail + ") — ready!";
        }
        if (previous == Status.OK && nowBad) {
            return marker.label() + " at " + coords + " is now " + current.name()
                    + (detail == null || detail.isBlank() ? "" : " (" + detail + ")") + "!";
        }
        return null;
    }

    private static void recordTransition(String dim, PointMarker pt, CheckResult check) {
        if (pt.category() == Category.SPOT) return;
        String key = dim + ":" + pt.category().name() + ":" + pt.x() + "," + pt.y() + "," + pt.z();
        Status prev = lastStatuses.put(key, check.status());
        String notice = evaluateTransition(prev, check.status(), pt, check.detail());
        if (notice != null) {
            Util.msg(notice);
            if (pt.category() == Category.BED && check.status() == Status.OK) {
                dev.survivalkit.Api.resetSleepCooldown();
            }
        }
    }

    /** Formats a live report of all tracked beds, chests, and safe areas for {@code /queue overlay check}. */
    public static List<String> checkReport() {
        List<String> lines = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        Sources src = gatherLiveSources();
        List<PointMarker> points = collectPoints(src);
        List<AreaMarker> areas = mergeAdjacentAreas(collectAreas(src, true, true));

        if (points.isEmpty() && areas.isEmpty()) {
            lines.add("No beds, chests, or safe areas are configured yet.");
            return lines;
        }

        int ok = 0, problems = 0, unloaded = 0;
        for (PointMarker pt : points) {
            CheckResult res = (mc != null && mc.level != null)
                    ? verifyBlock(pt.category(), new BlockPos(pt.x(), pt.y(), pt.z()), mc.level)
                    : new CheckResult(Status.UNLOADED, "no world");
            if (res.status() == Status.OK) ok++;
            else if (res.isProblem()) problems++;
            else if (res.status() == Status.UNLOADED) unloaded++;

            String statusText = switch (res.status()) {
                case OK -> "OK (" + res.detail() + ")";
                case MISSING -> "MISSING (" + res.detail() + ")";
                case WRONG -> "WRONG BLOCK (" + res.detail() + ")";
                case BLOCKED -> "BLOCKED (" + res.detail() + ")";
                case UNLOADED -> "unloaded";
                case INFO -> "info";
            };
            String visNote = isPointVisible(pt) ? "" : " [hidden]";
            lines.add("  " + pt.label() + " at " + pt.x() + " " + pt.y() + " " + pt.z() + ": " + statusText + visNote);
        }

        int safeCount = 0;
        int taskCount = 0;
        for (AreaMarker a : areas) {
            if (a.safe()) safeCount++;
            else taskCount++;
        }
        lines.add(0, "Overlay check: " + ok + " OK, " + problems + " problem(s), " + unloaded + " unloaded"
                + " | " + safeCount + " safe area(s), " + taskCount + " task area(s)"
                + (hiddenCount() > 0 ? " (" + hiddenCount() + " hidden)" : "") + ".");
        return lines;
    }

    // ------------------------------------------------------------------ Adjacent merging & overlap resolution

    /**
     * Collapses adjacent/touching boxes of the same area (same label + safe flag) into minimal cuboids,
     * and drops boxes completely contained inside another box of the same area.
     */
    public static List<AreaMarker> mergeAdjacentAreas(List<AreaMarker> input) {
        if (input == null || input.size() <= 1) return input == null ? List.of() : new ArrayList<>(input);
        List<AreaMarker> list = new ArrayList<>(input);
        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (int i = 0; i < list.size(); i++) {
                AreaMarker a = list.get(i);
                for (int j = i + 1; j < list.size(); j++) {
                    AreaMarker b = list.get(j);
                    if (a.safe() != b.safe() || !a.label().equalsIgnoreCase(b.label())) continue;
                    AreaMarker merged = tryMergeSameArea(a, b);
                    if (merged != null) {
                        list.set(i, merged);
                        list.remove(j);
                        changed = true;
                        break outer;
                    }
                }
            }
        }
        return list;
    }

    private static AreaMarker tryMergeSameArea(AreaMarker a, AreaMarker b) {
        if (a.contains(b)) return a;
        if (b.contains(a)) return b;
        // Touching or overlapping along X with identical Y & Z
        if (a.y1() == b.y1() && a.y2() == b.y2() && a.z1() == b.z1() && a.z2() == b.z2()
                && a.x1() <= b.x2() + 1 && b.x1() <= a.x2() + 1) {
            return new AreaMarker(Math.min(a.x1(), b.x1()), a.y1(), a.z1(),
                    Math.max(a.x2(), b.x2()), a.y2(), a.z2(), a.label(), a.safe(), a.role());
        }
        // Touching or overlapping along Z with identical X & Y
        if (a.x1() == b.x1() && a.x2() == b.x2() && a.y1() == b.y1() && a.y2() == b.y2()
                && a.z1() <= b.z2() + 1 && b.z1() <= a.z2() + 1) {
            return new AreaMarker(a.x1(), a.y1(), Math.min(a.z1(), b.z1()),
                    a.x2(), a.y2(), Math.max(a.z2(), b.z2()), a.label(), a.safe(), a.role());
        }
        // Touching or overlapping along Y with identical X & Z
        if (a.x1() == b.x1() && a.x2() == b.x2() && a.z1() == b.z1() && a.z2() == b.z2()
                && a.y1() <= b.y2() + 1 && b.y1() <= a.y2() + 1) {
            return new AreaMarker(a.x1(), Math.min(a.y1(), b.y1()), a.z1(),
                    a.x2(), Math.max(a.y2(), b.y2()), a.z2(), a.label(), a.safe(), a.role());
        }
        return null;
    }

    /**
     * Assigns non-colliding layer indices (via greedy graph coloring of overlapping boxes) so overlapping
     * areas get distinct outline colors and slight Z-fighting-free inflate offsets.
     */
    public static List<StyledArea> resolveOverlaps(List<AreaMarker> areas, boolean protectEnabled) {
        if (areas == null || areas.isEmpty()) return List.of();
        int n = areas.size();
        int[] layers = new int[n];
        boolean[] hasOverlap = new boolean[n];

        for (int i = 0; i < n; i++) {
            AreaMarker a = areas.get(i);
            Set<Integer> used = new LinkedHashSet<>();
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                if (a.overlaps(areas.get(j))) {
                    hasOverlap[i] = true;
                    if (j < i) used.add(layers[j]);
                }
            }
            int layer = 0;
            while (used.contains(layer)) layer++;
            layers[i] = layer;
        }

        List<StyledArea> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            AreaMarker a = areas.get(i);
            int layer = layers[i];
            boolean overlap = hasOverlap[i];
            int color;
            if (a.safe()) {
                color = !protectEnabled ? COL_SAFE_OFF
                        : overlap ? OVERLAP_SAFE_PALETTE[layer % OVERLAP_SAFE_PALETTE.length] : COL_SAFE;
            } else {
                color = overlap ? OVERLAP_TASK_PALETTE[layer % OVERLAP_TASK_PALETTE.length] : areaRoleColor(a.role());
            }
            double inflate = 0.003 + 0.008 * layer;
            out.add(new StyledArea(a, layer, overlap, color, inflate));
        }
        return out;
    }

    // ------------------------------------------------------------------ Drawing

    private static void drawPoint(PointMarker pt, CheckResult check, String fleetState, double distSq, boolean pulse) {
        AABB box = new AABB(pt.x(), pt.y(), pt.z(), pt.x() + 1, pt.y() + 1, pt.z() + 1).inflate(0.004);
        boolean problem = check.isProblem();
        boolean alwaysOnTop = problem || (cfg != null && cfg.showOverlayThroughWalls);

        int stroke;
        int fill;
        float width;
        if (check.status() == Status.MISSING || check.status() == Status.WRONG) {
            stroke = pulse ? COL_MISSING_A : COL_MISSING_B;
            fill = pulse ? 0x55FF2233 : 0x33FF2233;
            width = 3.5f;
        } else if (check.status() == Status.BLOCKED) {
            stroke = COL_BLOCKED;
            fill = 0x38FFAA00;
            width = 3.0f;
        } else if (check.status() == Status.UNLOADED) {
            stroke = COL_UNLOADED;
            fill = 0x1494A3B8;
            width = 1.5f;
        } else {
            stroke = roleColor(pt.role());
            fill = withAlpha(stroke, 0x24);
            width = 2.5f;
        }

        GizmoProperties props = Gizmos.cuboid(box, GizmoStyle.strokeAndFill(stroke, width, fill));
        if (alwaysOnTop) props.setAlwaysOnTop();

        // For missing/wrong beds & chests: draw an X-cross inside the exact block and a short beacon post
        // so the player can place the bed/chest back at the exact right coordinate.
        if (check.status() == Status.MISSING || check.status() == Status.WRONG) {
            double x0 = pt.x() + 0.05, y0 = pt.y() + 0.05, z0 = pt.z() + 0.05;
            double x1 = pt.x() + 0.95, y1 = pt.y() + 0.95, z1 = pt.z() + 0.95;
            Gizmos.line(new Vec3(x0, y0, z0), new Vec3(x1, y1, z1), stroke, 2.5f).setAlwaysOnTop();
            Gizmos.line(new Vec3(x1, y0, z0), new Vec3(x0, y1, z1), stroke, 2.5f).setAlwaysOnTop();
            Gizmos.line(new Vec3(x0, y0, z1), new Vec3(x1, y1, z0), stroke, 2.5f).setAlwaysOnTop();
            Gizmos.line(new Vec3(x1, y0, z1), new Vec3(x0, y1, z0), stroke, 2.5f).setAlwaysOnTop();
            Gizmos.line(new Vec3(pt.x() + 0.5, pt.y() + 1.0, pt.z() + 0.5),
                    new Vec3(pt.x() + 0.5, pt.y() + 2.2, pt.z() + 0.5), stroke, 3.0f).setAlwaysOnTop();
        }

        double maxLabelDist = problem ? MAX_PROBLEM_LABEL_DIST : MAX_LABEL_DIST;
        if (cfg != null && cfg.showOverlayLabels && distSq <= maxLabelDist * maxLabelDist) {
            String text = formatPointLabel(pt, check, fleetState);
            int textColor = switch (check.status()) {
                case OK -> pt.category() == Category.BED ? 0xFF55FF88 : stroke;
                case MISSING, WRONG -> 0xFFFF5566;
                case BLOCKED -> COL_BLOCKED;
                case UNLOADED -> 0xFFCBD5E1;
                case INFO -> COL_SPOT;
            };
            float scale = problem ? 0.36f : 0.28f;
            double labelY = problem ? pt.y() + 2.45 : pt.y() + 1.35;
            GizmoProperties tp = Gizmos.billboardText(
                    text,
                    new Vec3(pt.x() + 0.5, labelY, pt.z() + 0.5),
                    TextGizmo.Style.forColorAndCentered(textColor).withScale(scale)
            );
            if (alwaysOnTop) tp.setAlwaysOnTop();
        }
    }

    private static void drawArea(StyledArea sa, boolean protectEnabled, Vec3 me, List<Vec3> placedLabels) {
        AreaMarker area = sa.area();
        AABB box = new AABB(area.x1(), area.y1(), area.z1(), area.x2() + 1, area.y2() + 1, area.z2() + 1)
                .inflate(sa.inflate());
        int stroke = sa.color();
        float width = area.safe() ? 2.5f : 2.0f;

        // Wireframe-only by default (prevents stacked opaque cyan walls on big/overlapping areas).
        // When Area Fill is explicitly enabled, keep alpha very light and only fill layer 0 so overlapping fills never stack.
        boolean wantFill = cfg != null && cfg.showOverlayAreaFill && (!sa.overlapping() || sa.layerIndex() == 0);
        GizmoStyle style = wantFill
                ? GizmoStyle.strokeAndFill(stroke, width, withAlpha(stroke, 0x0A))
                : GizmoStyle.stroke(stroke, width);
        Gizmos.cuboid(box, style);

        if (cfg != null && cfg.showOverlayLabels) {
            double cx = (area.x1() + area.x2() + 1) / 2.0;
            double cz = (area.z1() + area.z2() + 1) / 2.0;
            double dx = cx - me.x, dz = cz - me.z;
            if (dx * dx + dz * dz <= (MAX_LABEL_DIST * 1.5) * (MAX_LABEL_DIST * 1.5)) {
                double labelY = Math.min(area.y2() + 1.35, Math.max(area.y1() + 1.35, me.y + 2.5));
                // Stack vertically if another area label is already near this position
                for (Vec3 prev : placedLabels) {
                    double ldx = prev.x - cx, ldz = prev.z - cz;
                    if (ldx * ldx + ldz * ldz < 16.0 && Math.abs(prev.y - labelY) < 0.65) {
                        labelY = prev.y + 0.65;
                    }
                }
                Vec3 pos = new Vec3(cx, labelY, cz);
                placedLabels.add(pos);

                String text = area.safe()
                        ? (protectEnabled ? "SAFE: " : "SAFE (off): ") + area.label()
                        : area.label() + (area.role() == null || area.role().isBlank() || "custom".equalsIgnoreCase(area.role())
                            ? "" : " [" + area.role() + "]");
                Gizmos.billboardText(
                        text,
                        pos,
                        TextGizmo.Style.forColorAndCentered(stroke).withScale(0.30f)
                ).setAlwaysOnTop();
            }
        }
    }

    static String formatPointLabel(PointMarker pt, CheckResult check, String fleetState) {
        String badge = switch (check.status()) {
            case OK -> "[OK]";
            case MISSING -> "[MISSING!]";
            case WRONG -> "[WRONG: " + check.detail() + "]";
            case BLOCKED -> "[BLOCKED]";
            case UNLOADED -> "[unloaded]";
            case INFO -> "";
        };
        StringBuilder sb = new StringBuilder(pt.label());
        if (!badge.isEmpty()) sb.append(' ').append(badge);
        if (fleetState != null && !fleetState.isBlank() && !"AVAILABLE".equalsIgnoreCase(fleetState)) {
            sb.append(" (").append(fleetState.toUpperCase(Locale.ROOT)).append(')');
        }
        return sb.toString();
    }

    private static int roleColor(Role role) {
        return switch (role) {
            case BED, BED_POOL, ASSIGNED_BED -> COL_OK_BED;
            case TOOL_CHEST -> COL_TOOL_CHEST;
            case RETURN_CHEST -> COL_RETURN_CHEST;
            case DUMP_CHEST -> COL_DUMP_CHEST;
            case SCAFFOLD_CHEST -> COL_SCAFFOLD_CHEST;
            case MATERIAL_CHEST -> COL_MATERIAL_CHEST;
            case NAMED_CHEST -> COL_NAMED_CHEST;
            case SPOT -> COL_SPOT;
        };
    }

    private static int areaRoleColor(String role) {
        if (role == null) return COL_AREA_CUSTOM;
        return switch (role.toLowerCase(Locale.ROOT)) {
            case "dig", "clear", "mine" -> COL_AREA_DIG;
            case "fill", "flood", "drain" -> COL_AREA_FILL;
            case "build" -> COL_AREA_BUILD;
            case "supply_area" -> COL_TOOL_CHEST;
            case "return_area" -> COL_RETURN_CHEST;
            case "material_area" -> COL_MATERIAL_CHEST;
            case "scaffold_area" -> COL_SCAFFOLD_CHEST;
            default -> COL_AREA_CUSTOM;
        };
    }

    private static int withAlpha(int argb, int alpha) {
        return ((alpha & 0xFF) << 24) | (argb & 0x00FFFFFF);
    }

    private static boolean withinAreaDist(Vec3 me, AreaMarker a, double maxDist) {
        double dx = Math.max(0.0, Math.max(a.x1() - me.x, me.x - (a.x2() + 1)));
        double dz = Math.max(0.0, Math.max(a.z1() - me.z, me.z - (a.z2() + 1)));
        return dx * dx + dz * dz <= maxDist * maxDist;
    }

    // ------------------------------------------------------------------ Source gathering & deduplication

    public static Sources gatherLiveSources() {
        Project running = null;
        Project active = null;
        try {
            GameProjectRuntime rt = GameProjectRuntime.instance();
            if (rt != null) {
                running = rt.runningProject();
                active = rt.activeProject();
            }
        } catch (Throwable ignored) {
        }

        Project fleetProject = null;
        int[] fleetAssignedBed = null;
        List<FleetProtocol.BotInfo> fleetBots = List.of();
        try {
            FleetClient fc = FleetClient.instance();
            if (fc != null && fc.isConnected()) {
                fleetProject = fc.lastFleetProject();
                fleetAssignedBed = fc.assignedBedPos();
                FleetProtocol.FleetSnapshot snap = fc.snapshot();
                if (snap != null && snap.bots != null) {
                    fleetBots = snap.bots;
                }
            }
        } catch (Throwable ignored) {
        }

        Map<String, String> areaRoles = new LinkedHashMap<>();
        if (cfg != null && cfg.areas != null) {
            for (String name : cfg.areas.keySet()) {
                areaRoles.put(name, Areas.roleOf(name));
            }
        }

        return new Sources(
                running,
                active,
                fleetProject,
                fleetAssignedBed,
                fleetBots,
                dev.survivalkit.Api.bedPos(),
                dev.survivalkit.Api.supplyPos(),
                dev.survivalkit.Api.extraSupplyPositions(),
                dev.survivalkit.Api.returnChestPos(),
                dev.loadchest.LoadChestMod.chestPos(),
                cfg != null && cfg.chests != null ? cfg.chests : Map.of(),
                dev.buildtools.Api.protectedZones(),
                dev.buildtools.Api.protectEnabled(),
                dev.buildtools.Api.pendingCorner1(),
                dev.buildtools.Api.pendingCorner2(),
                cfg != null && cfg.areas != null ? cfg.areas : Map.of(),
                areaRoles
        );
    }

    /**
     * Collects and deduplicates all point markers (beds & chests) across running project, active project,
     * fleet state, SurvivalKit, LoadChest, and saved script chests.
     */
    public static List<PointMarker> collectPoints(Sources src) {
        Map<String, PointAccumulator> acc = new LinkedHashMap<>();

        // 1. Assigned fleet bed (highest specificity on a worker bot)
        addPoint(acc, src.fleetAssignedBed(), "Assigned Bed", Category.BED, Role.ASSIGNED_BED);

        // 2. Running project -> Fleet project -> Active project
        collectProjectPoints(acc, src.runningProject());
        if (src.fleetProject() != null && src.fleetProject() != src.runningProject()) {
            collectProjectPoints(acc, src.fleetProject());
        }
        if (src.activeProject() != null && src.activeProject() != src.runningProject()) {
            collectProjectPoints(acc, src.activeProject());
        }

        // 3. Fleet peer bot beds (so Editors can see every bot's bed at the camp!)
        if (src.fleetBots() != null) {
            for (FleetProtocol.BotInfo bot : src.fleetBots()) {
                if (bot == null) continue;
                String botName = (bot.name != null && !bot.name.isBlank()) ? bot.name : "bot";
                if (validPos(bot.assignedBed)) {
                    addPoint(acc, bot.assignedBed, "Bed (" + botName + ")", Category.BED, Role.BED_POOL);
                } else if (validPos(bot.localBed)) {
                    addPoint(acc, bot.localBed, "Bed (" + botName + ")", Category.BED, Role.BED);
                }
            }
        }

        // 4. SurvivalKit & LoadChest (deduplicated against project places at same coords)
        addPoint(acc, src.survivalBed(), "Bed", Category.BED, Role.BED);
        addPoint(acc, src.survivalSupply(), "Supply Chest", Category.CHEST, Role.TOOL_CHEST);
        if (src.survivalExtraSupplies() != null) {
            for (int i = 0; i < src.survivalExtraSupplies().size(); i++) {
                addPoint(acc, src.survivalExtraSupplies().get(i), "Supply Chest #" + (i + 2), Category.CHEST, Role.TOOL_CHEST);
            }
        }
        addPoint(acc, src.survivalReturnChest(), "Return Chest", Category.CHEST, Role.RETURN_CHEST);
        addPoint(acc, src.loadChestPos(), "Dump Chest", Category.CHEST, Role.DUMP_CHEST);

        // 5. Named script chests (cfg.chests)
        if (src.savedChests() != null) {
            for (var e : src.savedChests().entrySet()) {
                addPoint(acc, e.getValue(), "Chest: " + e.getKey(), Category.CHEST, Role.NAMED_CHEST);
            }
        }

        List<PointMarker> out = new ArrayList<>(acc.size());
        for (PointAccumulator a : acc.values()) {
            out.add(a.build());
        }
        return out;
    }

    private static void collectProjectPoints(Map<String, PointAccumulator> acc, Project p) {
        if (p == null || p.places == null) return;
        Project.BasePlaces pl = p.places;
        addPoint(acc, pl.bed, "Bed", Category.BED, Role.BED);
        if (pl.extraBeds != null) {
            for (int i = 0; i < pl.extraBeds.size(); i++) {
                addPoint(acc, pl.extraBeds.get(i), "Backup Bed #" + (i + 1), Category.BED, Role.BED);
            }
        }
        if (pl.bedPool != null) {
            for (int i = 0; i < pl.bedPool.size(); i++) {
                addPoint(acc, pl.bedPool.get(i), "Bed #" + (i + 1), Category.BED, Role.BED_POOL);
            }
        }
        addPoint(acc, pl.toolChest, "Tool Chest", Category.CHEST, Role.TOOL_CHEST);
        if (pl.extraToolChests != null) {
            for (int i = 0; i < pl.extraToolChests.size(); i++) {
                addPoint(acc, pl.extraToolChests.get(i), "Tool Chest #" + (i + 2), Category.CHEST, Role.TOOL_CHEST);
            }
        }
        addPoint(acc, pl.returnChest, "Return Chest", Category.CHEST, Role.RETURN_CHEST);
        if (pl.extraReturnChests != null) {
            for (int i = 0; i < pl.extraReturnChests.size(); i++) {
                addPoint(acc, pl.extraReturnChests.get(i), "Return Chest #" + (i + 2), Category.CHEST, Role.RETURN_CHEST);
            }
        }
        addPoint(acc, pl.dumpChest, "Dump Chest", Category.CHEST, Role.DUMP_CHEST);
        addPoint(acc, pl.scaffoldChest, "Scaffold Chest", Category.CHEST, Role.SCAFFOLD_CHEST);
        if (pl.extraScaffoldChests != null) {
            for (int i = 0; i < pl.extraScaffoldChests.size(); i++) {
                addPoint(acc, pl.extraScaffoldChests.get(i), "Scaffold Chest #" + (i + 2), Category.CHEST, Role.SCAFFOLD_CHEST);
            }
        }
        if (pl.materialChests != null) {
            for (int i = 0; i < pl.materialChests.size(); i++) {
                addPoint(acc, pl.materialChests.get(i), "Material Chest #" + (i + 1), Category.CHEST, Role.MATERIAL_CHEST);
            }
        }
    }

    /**
     * Collects and deduplicates all safe/protected areas and task/saved areas across BuildTools,
     * running/fleet/active projects, and saved Manhattan areas.
     */
    public static List<AreaMarker> collectAreas(Sources src, boolean includeSafe, boolean includeTask) {
        Map<String, AreaMarker> dedup = new LinkedHashMap<>();

        if (includeSafe) {
            // 1. BuildTools active protected zones
            if (src.protectedZones() != null) {
                for (dev.buildtools.Api.ProtectedZone z : src.protectedZones()) {
                    String label = (z.group() != null && !z.group().isBlank()) ? z.group() : z.name();
                    addArea(dedup, z.x1(), z.y1(), z.z1(), z.x2(), z.y2(), z.z2(), label, true, "protect");
                }
            }
            // 2. Project protectedAreas (in case project is not currently running, e.g. Editor mode)
            collectProjectProtectedAreas(dedup, src.runningProject());
            if (src.fleetProject() != null && src.fleetProject() != src.runningProject()) {
                collectProjectProtectedAreas(dedup, src.fleetProject());
            }
            if (src.activeProject() != null && src.activeProject() != src.runningProject()) {
                collectProjectProtectedAreas(dedup, src.activeProject());
            }
            // 3. Pending manual protect corners (/buildtools protect pos1 / pos2)
            if (validPos(src.pendingProtectCorner1()) && validPos(src.pendingProtectCorner2())) {
                int[] c1 = src.pendingProtectCorner1();
                int[] c2 = src.pendingProtectCorner2();
                addArea(dedup, c1[0], c1[1], c1[2], c2[0], c2[1], c2[2], "new safe area", true, "protect");
            }
        }

        // 4. Saved areas (protect-role areas count as safe; others count as task areas)
        if (src.savedAreas() != null) {
            for (var e : src.savedAreas().entrySet()) {
                String name = e.getKey();
                List<int[]> boxes = e.getValue();
                if (boxes == null) continue;
                String role = src.savedAreaRoles() != null ? src.savedAreaRoles().getOrDefault(name, "custom") : "custom";
                boolean isSafe = "protect".equalsIgnoreCase(role);
                if (isSafe && !includeSafe) continue;
                if (!isSafe && !includeTask) continue;
                for (int[] b : boxes) {
                    if (b != null && b.length >= 6) {
                        addArea(dedup, b[0], b[1], b[2], b[3], b[4], b[5], name, isSafe, role);
                    }
                }
            }
        }

        // 5. Embedded fleet/project task areas & storage areas
        if (includeTask) {
            collectProjectTaskAreas(dedup, src.runningProject());
            collectProjectStorageAreas(dedup, src.runningProject());
            if (src.fleetProject() != null && src.fleetProject() != src.runningProject()) {
                collectProjectTaskAreas(dedup, src.fleetProject());
                collectProjectStorageAreas(dedup, src.fleetProject());
            }
            if (src.activeProject() != null && src.activeProject() != src.runningProject()) {
                collectProjectStorageAreas(dedup, src.activeProject());
            }
        }

        return new ArrayList<>(dedup.values());
    }

    private static void collectProjectStorageAreas(Map<String, AreaMarker> dedup, Project p) {
        if (p == null || p.places == null) return;
        addStorageAreaList(dedup, p.places.supplyAreas, "Supply Area", "supply_area");
        addStorageAreaList(dedup, p.places.returnAreas, "Return Area", "return_area");
        addStorageAreaList(dedup, p.places.materialAreas, "Build Supply Area", "material_area");
        addStorageAreaList(dedup, p.places.scaffoldAreas, "Scaffolding Area", "scaffold_area");
    }

    private static void addStorageAreaList(Map<String, AreaMarker> dedup, List<int[]> boxes, String baseLabel, String role) {
        if (boxes == null) return;
        for (int i = 0; i < boxes.size(); i++) {
            int[] b = boxes.get(i);
            if (b != null && b.length >= 6) {
                String lbl = boxes.size() == 1 ? baseLabel : baseLabel + " #" + (i + 1);
                addArea(dedup, b[0], b[1], b[2], b[3], b[4], b[5], lbl, false, role);
            }
        }
    }

    private static void collectProjectProtectedAreas(Map<String, AreaMarker> dedup, Project p) {
        if (p == null || p.places == null || p.places.protectedAreas == null) return;
        for (var e : p.places.protectedAreas.entrySet()) {
            if (e.getValue() == null) continue;
            for (int[] b : e.getValue()) {
                if (b != null && b.length >= 6) {
                    addArea(dedup, b[0], b[1], b[2], b[3], b[4], b[5], e.getKey(), true, "protect");
                }
            }
        }
    }

    private static void collectProjectTaskAreas(Map<String, AreaMarker> dedup, Project p) {
        if (p == null || p.places == null || p.places.areas == null) return;
        for (var e : p.places.areas.entrySet()) {
            if (e.getValue() == null) continue;
            for (int[] b : e.getValue()) {
                if (b != null && b.length >= 6) {
                    addArea(dedup, b[0], b[1], b[2], b[3], b[4], b[5], e.getKey(), false, "task");
                }
            }
        }
    }

    private static void addArea(Map<String, AreaMarker> dedup,
                                int ax1, int ay1, int az1, int ax2, int ay2, int az2,
                                String label, boolean safe, String role) {
        int x1 = Math.min(ax1, ax2), y1 = Math.min(ay1, ay2), z1 = Math.min(az1, az2);
        int x2 = Math.max(ax1, ax2), y2 = Math.max(ay1, ay2), z2 = Math.max(az1, az2);
        String key = x1 + "," + y1 + "," + z1 + ":" + x2 + "," + y2 + "," + z2;
        AreaMarker existing = dedup.get(key);
        if (existing == null || (!existing.safe() && safe)) {
            dedup.put(key, new AreaMarker(x1, y1, z1, x2, y2, z2, label == null ? "area" : label, safe, role));
        }
    }

    private static boolean validPos(int[] p) {
        return p != null && p.length >= 3;
    }

    private static void addPoint(Map<String, PointAccumulator> acc, int[] p, String label, Category cat, Role role) {
        if (!validPos(p)) return;
        String key = cat.name() + ":" + p[0] + "," + p[1] + "," + p[2];
        PointAccumulator existing = acc.get(key);
        if (existing == null) {
            acc.put(key, new PointAccumulator(p[0], p[1], p[2], label, cat, role));
        } else {
            existing.mergeLabel(label);
        }
    }

    private static final class PointAccumulator {
        final int x, y, z;
        final Category category;
        final Role role;
        final Set<String> labels = new LinkedHashSet<>();

        PointAccumulator(int x, int y, int z, String label, Category category, Role role) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.category = category;
            this.role = role;
            if (label != null && !label.isBlank()) this.labels.add(label);
        }

        void mergeLabel(String label) {
            if (label == null || label.isBlank()) return;
            for (String existing : labels) {
                if (existing.equalsIgnoreCase(label)) return;
                if (category == Category.BED && "Bed".equalsIgnoreCase(label)) return;
                if (category == Category.CHEST && "Supply Chest".equalsIgnoreCase(label)
                        && existing.equalsIgnoreCase("Tool Chest")) return;
            }
            if (labels.size() < 2) {
                labels.add(label);
            }
        }

        PointMarker build() {
            String combined = labels.isEmpty() ? category.name() : String.join(" / ", labels);
            return new PointMarker(x, y, z, combined, category, role);
        }
    }
}
