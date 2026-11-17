package dev.baritonequeue.api;

import dev.baritonequeue.Areas;
import dev.buildtools.Api;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Public API for Heavymatica (optional addon) to push, query, select, and remove named areas in Manhattan.
 * <p>
 * All methods must be called on the Minecraft client thread.
 */
public final class ManhattanAreas {

    public static final int API_VERSION = 2;   // 2: Role.STAIRS
    public static final int MAX_BOXES = 4096;

    private ManhattanAreas() {}

    public enum Role {
        DIG, CLEAR, FLOOD, FILL, PROTECT, STAIRS, CUSTOM;

        static Role parse(String s) {
            if (s == null || s.isBlank()) return CUSTOM;
            try {
                return Role.valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return CUSTOM;
            }
        }
    }

    public enum Owner {
        MANUAL, SCRIPT, HEAVYMATICA;

        static Owner parse(String s) {
            if (s == null || s.isBlank()) return MANUAL;
            try {
                return Owner.valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return MANUAL;
            }
        }
    }

    public enum Result {
        OK, SCRIPT_OWNED, IN_USE, INVALID, NOT_IN_WORLD
    }

    /** Box = {x1,y1,z1,x2,y2,z2}, any corner order (normalized on the way in). */
    public record AreaInfo(String name, List<int[]> boxes, Role role, Owner owner, long updated) {}

    private static void checkClientThread() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && !mc.isSameThread()) {
            throw new IllegalStateException("ManhattanAreas must be called on the client thread");
        }
    }

    /**
     * Saves (replaces) a named area. Client thread only.
     * Never changes the current selection.
     */
    public static Result put(String name, List<int[]> boxes, Role role, boolean replaceScriptOwned) {
        checkClientThread();
        if (name == null || name.isBlank() || role == null || boxes == null || boxes.isEmpty() || boxes.size() > MAX_BOXES) {
            return Result.INVALID;
        }
        String n = name.trim();
        if (n.equalsIgnoreCase("last")) {
            return Result.INVALID;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.level == null) {
            return Result.NOT_IN_WORLD;
        }

        List<int[]> normalized = new ArrayList<>(boxes.size());
        for (int[] b : boxes) {
            if (b == null || b.length != 6) {
                return Result.INVALID;
            }
            int minX = Math.min(b[0], b[3]);
            int minY = Math.min(b[1], b[4]);
            int minZ = Math.min(b[2], b[5]);
            int maxX = Math.max(b[0], b[3]);
            int maxY = Math.max(b[1], b[4]);
            int maxZ = Math.max(b[2], b[5]);
            if (mc != null && mc.level != null) {
                if (minY < mc.level.getMinY() || maxY >= mc.level.getMaxY()) {
                    return Result.INVALID;
                }
            }
            normalized.add(new int[]{minX, minY, minZ, maxX, maxY, maxZ});
        }

        if (role == Role.STAIRS) {   // the stair shaft is its first box: a spiral needs at least 2 x 2 blocks
            int[] f = normalized.get(0);
            if (f[3] - f[0] < 1 || f[5] - f[2] < 1) return Result.INVALID;
        }
        if (Areas.inUse(n)) {
            return Result.IN_USE;
        }
        if (!replaceScriptOwned && "script".equalsIgnoreCase(Areas.ownerOf(n))) {
            return Result.SCRIPT_OWNED;
        }

        boolean wasProtect = Areas.getBoxes(n) != null && Role.parse(Areas.roleOf(n)) == Role.PROTECT;
        if (wasProtect && role != Role.PROTECT) {
            Api.unprotect(n);
        }

        Areas.putOwned(n, normalized, role.name().toLowerCase(Locale.ROOT));
        if (role == Role.PROTECT) {
            Api.protectGroup(n, normalized);
        }
        return Result.OK;
    }

    public static Optional<AreaInfo> get(String name) {
        checkClientThread();
        if (name == null || name.isBlank()) return Optional.empty();
        String n = name.trim();
        List<int[]> boxes = Areas.getBoxes(n);
        if (boxes == null) return Optional.empty();
        return Optional.of(new AreaInfo(
                n,
                List.copyOf(boxes),
                Role.parse(Areas.roleOf(n)),
                Owner.parse(Areas.ownerOf(n)),
                Areas.updatedOf(n)));
    }

    public static Set<String> names() {
        checkClientThread();
        return Areas.savedNames();
    }

    /** Removes an area, but only one Heavymatica owns; false otherwise. Also drops its protection. */
    public static boolean remove(String name) {
        checkClientThread();
        if (name == null || name.isBlank()) return false;
        String n = name.trim();
        if (Areas.getBoxes(n) == null) return false;
        if (!"heavymatica".equalsIgnoreCase(Areas.ownerOf(n))) return false;
        if (Areas.inUse(n)) return false;
        boolean wasProtect = Role.parse(Areas.roleOf(n)) == Role.PROTECT;
        if (wasProtect) {
            Api.unprotect(n);
        }
        return Areas.delete(n);
    }

    /** Makes the area the current selection, like {@code area("name")}. */
    public static boolean select(String name) {
        checkClientThread();
        if (name == null || name.isBlank()) return false;
        return Areas.load(name.trim()) == null;
    }

    /** Copy of the current selection, for "New Heavymatica from Selection". */
    public static List<int[]> selection() {
        checkClientThread();
        return Areas.read();
    }

    /** Every protected area known, for the export's "overlaps a protected area" flag. */
    public static List<AreaInfo> protectedAreas() {
        checkClientThread();
        List<AreaInfo> out = new ArrayList<>();
        for (String name : Areas.savedNames()) {
            if (Role.parse(Areas.roleOf(name)) == Role.PROTECT) {
                get(name).ifPresent(out::add);
            }
        }
        return List.copyOf(out);
    }

    /** Called on the client thread whenever a saved area changes (name, new owner); null owner = deleted. */
    public static void addListener(BiConsumer<String, Owner> listener) {
        checkClientThread();
        if (listener == null) return;
        Areas.addMetaListener((name, ownerStr) ->
                listener.accept(name, ownerStr == null ? null : Owner.parse(ownerStr)));
    }
}
