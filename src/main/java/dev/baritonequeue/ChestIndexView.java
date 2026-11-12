package dev.baritonequeue;

import dev.survivalkit.Api;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static dev.baritonequeue.Config.cfg;

/**
 * View-model over {@code dev.survivalkit.ChestIndex} ({@link Api#indexedChests()}) for the Stage 3 Chests panel
 * and unit/in-game tests: searchable, sortable rows (item, count, chest, status), category filters, low-stock filter,
 * and detail pane actions (Show in world, Copy position as pos(...), Go there via Baritone).
 */
public final class ChestIndexView {
    public static final int LOW_STOCK_THRESHOLD = 16;

    public enum Category {
        ALL("All"),
        BLOCKS("Blocks"),
        TOOLS("Tools"),
        FOOD("Food"),
        ORES("Ores"),
        REDSTONE("Redstone"),
        OTHER("Other");

        public final String label;

        Category(String label) {
            this.label = label;
        }
    }

    public enum SortColumn {
        ITEM, COUNT, CHEST, STATUS
    }

    public record Row(
            String itemId,
            String itemName,
            Category category,
            int count,
            String chestName,
            int x, int y, int z,
            String status,
            boolean lowStock,
            String age,
            Map<String, Integer> allChestItems
    ) {
        public String posExpr() {
            return "pos(" + x + ", " + y + ", " + z + ")";
        }

        public String coordsText() {
            return x + ", " + y + ", " + z;
        }
    }

    private static BlockPos highlightedChest;
    private static long highlightUntilMs;
    private static String lastCopiedPos = "";

    private ChestIndexView() {}

    /** Categorizes an item ID (e.g. "minecraft:diamond_pickaxe" or "dirt") for filtering. */
    public static Category categorize(String rawItem) {
        String id = shortItemName(rawItem).toLowerCase(Locale.ROOT);
        if (id.contains("pickaxe") || id.contains("shovel") || id.contains("axe") || id.contains("hoe")
                || id.contains("sword") || id.contains("shears") || id.contains("bucket") || id.contains("bow")
                || id.contains("crossbow") || id.contains("flint_and_steel") || id.contains("helmet")
                || id.contains("chestplate") || id.contains("leggings") || id.contains("boots") || id.contains("shield")) {
            return Category.TOOLS;
        }
        if (id.contains("bread") || id.contains("apple") || id.contains("beef") || id.contains("porkchop")
                || id.contains("mutton") || id.contains("chicken") || id.contains("cod") || id.contains("salmon")
                || id.contains("carrot") || id.contains("potato") || id.contains("steak") || id.contains("stew")
                || id.contains("berries") || id.contains("melon") || id.contains("pie") || id.contains("cookie")
                || id.equals("food")) {
            return Category.FOOD;
        }
        if (id.contains("diamond") || id.contains("emerald") || id.contains("gold") || id.contains("iron")
                || id.contains("copper") || id.contains("coal") || id.contains("lapis") || id.contains("quartz")
                || id.contains("netherite") || id.contains("amethyst") || id.endsWith("_ore") || id.endsWith("_ingot")) {
            return Category.ORES;
        }
        if (id.contains("redstone") || id.contains("repeater") || id.contains("comparator") || id.contains("piston")
                || id.contains("observer") || id.contains("hopper") || id.contains("dropper") || id.contains("dispenser")
                || id.contains("lever") || id.contains("rail")) {
            return Category.REDSTONE;
        }
        if (id.contains("dirt") || id.contains("stone") || id.contains("cobble") || id.contains("planks")
                || id.contains("log") || id.contains("wood") || id.contains("sand") || id.contains("gravel")
                || id.contains("brick") || id.contains("glass") || id.contains("slab") || id.contains("stairs")
                || id.contains("wall") || id.contains("fence") || id.contains("deepslate") || id.contains("granite")
                || id.contains("diorite") || id.contains("andesite") || id.contains("tuff") || id.contains("concrete")
                || id.contains("terracotta") || id.contains("wool")) {
            return Category.BLOCKS;
        }
        return Category.OTHER;
    }

    public static String shortItemName(String itemId) {
        if (itemId == null) return "";
        int colon = itemId.indexOf(':');
        return colon >= 0 ? itemId.substring(colon + 1) : itemId;
    }

    /** Resolves a human-friendly chest label from saved chests / capture sheet / coordinates. */
    public static String chestLabel(int x, int y, int z) {
        if (cfg != null && cfg.chests != null) {
            for (var e : cfg.chests.entrySet()) {
                int[] c = e.getValue();
                if (c != null && c.length >= 3 && c[0] == x && c[1] == y && c[2] == z) {
                    return e.getKey() + " (" + x + " " + y + " " + z + ")";
                }
            }
        }
        try {
            for (CaptureSheet.Entry ce : Capture.entries()) {
                if (ce.active() && ce.pos() != null && ce.pos()[0] == x && ce.pos()[1] == y && ce.pos()[2] == z && !ce.name().isEmpty()) {
                    return ce.name() + " (" + x + " " + y + " " + z + ")";
                }
            }
        } catch (Throwable ignored) {
        }
        return x + " " + y + " " + z;
    }

    public static boolean isLowStock(String shortName, int count, Map<String, Integer> wantList) {
        if (wantList != null) {
            for (var e : wantList.entrySet()) {
                String k = e.getKey().replace("@", "");
                if (shortName.contains(k) && count < e.getValue()) return true;
            }
        }
        return count < LOW_STOCK_THRESHOLD;
    }

    private static String checkWorldStatus(int x, int y, int z, boolean lowStock) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                BlockPos pos = new BlockPos(x, y, z);
                if (mc.level.hasChunkAt(pos)) {
                    if (!Util.blockedText(mc.level, pos).isEmpty()) return "Blocked";
                }
            }
        } catch (Throwable ignored) {
        }
        return lowStock ? "Low stock" : "OK";
    }

    /** Queries and sorts rows from the current ChestIndex. */
    public static List<Row> query(String search, Category category, boolean lowStockOnly, SortColumn sortCol, boolean ascending) {
        return filterAndSort(Api.indexedChests(), search, category, lowStockOnly, sortCol, ascending);
    }

    /** Pure helper over a list of IndexedChests (testable offline in LangTest as well as in-game). */
    public static List<Row> filterAndSort(
            List<Api.IndexedChest> chests,
            String search,
            Category category,
            boolean lowStockOnly,
            SortColumn sortCol,
            boolean ascending
    ) {
        String q = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        Category cat = category == null ? Category.ALL : category;
        Map<String, Integer> want = null;
        try { want = Api.wantList(); } catch (Throwable ignored) {}

        List<Row> rows = new ArrayList<>();
        if (chests != null) {
            for (Api.IndexedChest c : chests) {
                String label = chestLabel(c.x(), c.y(), c.z());
                if (c.items() == null || c.items().isEmpty()) continue;
                for (var it : c.items().entrySet()) {
                    String rawItem = it.getKey();
                    int count = it.getValue() == null ? 0 : it.getValue();
                    if (count <= 0) continue;
                    String shortName = shortItemName(rawItem);
                    Category itemCat = categorize(shortName);
                    if (cat != Category.ALL && itemCat != cat) continue;
                    boolean low = isLowStock(shortName, count, want);
                    if (lowStockOnly && !low) continue;
                    String status = checkWorldStatus(c.x(), c.y(), c.z(), low);
                    if (!q.isEmpty()) {
                        boolean match = shortName.toLowerCase(Locale.ROOT).contains(q)
                                || label.toLowerCase(Locale.ROOT).contains(q)
                                || status.toLowerCase(Locale.ROOT).contains(q);
                        if (!match) continue;
                    }
                    rows.add(new Row(rawItem, shortName, itemCat, count, label, c.x(), c.y(), c.z(), status, low, c.age(), c.items()));
                }
            }
        }

        SortColumn col = sortCol == null ? SortColumn.ITEM : sortCol;
        Comparator<Row> cmp = switch (col) {
            case ITEM -> Comparator.comparing(Row::itemName, String.CASE_INSENSITIVE_ORDER).thenComparingInt(Row::count);
            case COUNT -> Comparator.comparingInt(Row::count).thenComparing(Row::itemName, String.CASE_INSENSITIVE_ORDER);
            case CHEST -> Comparator.comparing(Row::chestName, String.CASE_INSENSITIVE_ORDER).thenComparing(Row::itemName, String.CASE_INSENSITIVE_ORDER);
            case STATUS -> Comparator.comparing(Row::status, String.CASE_INSENSITIVE_ORDER).thenComparingInt(Row::count);
        };
        if (!ascending) cmp = cmp.reversed();
        rows.sort(cmp);
        return rows;
    }

    /** Detail action 1: highlights the chest in the world with particles for 10 seconds and prints a chat note. */
    public static void showInWorld(int x, int y, int z) {
        highlightedChest = new BlockPos(x, y, z);
        highlightUntilMs = System.currentTimeMillis() + 10_000L;
        spawnHighlightParticles();
        Util.msg("Showing chest at " + x + " " + y + " " + z + " in world.");
    }

    public static BlockPos highlightedChest() {
        if (highlightedChest != null && System.currentTimeMillis() > highlightUntilMs) {
            highlightedChest = null;
        }
        return highlightedChest;
    }

    public static void tickHighlight() {
        if (highlightedChest == null) return;
        if (System.currentTimeMillis() > highlightUntilMs) {
            highlightedChest = null;
            return;
        }
        spawnHighlightParticles();
    }

    private static void spawnHighlightParticles() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null || highlightedChest == null) return;
            Level level = mc.level;
            int x = highlightedChest.getX(), y = highlightedChest.getY(), z = highlightedChest.getZ();
            for (double dx = 0.1; dx <= 0.9; dx += 0.4) {
                for (double dz = 0.1; dz <= 0.9; dz += 0.4) {
                    level.addParticle(ParticleTypes.HAPPY_VILLAGER, x + dx, y + 1.15, z + dz, 0, 0.02, 0);
                }
            }
            level.addParticle(ParticleTypes.END_ROD, x + 0.5, y + 1.5, z + 0.5, 0, 0.03, 0);
        } catch (Throwable ignored) {
        }
    }

    /** Detail action 2: copies "pos(x, y, z)" to clipboard and returns it. */
    public static String copyPos(int x, int y, int z) {
        String expr = "pos(" + x + ", " + y + ", " + z + ")";
        lastCopiedPos = expr;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.keyboardHandler != null) {
                mc.keyboardHandler.setClipboard(expr);
            }
        } catch (Throwable ignored) {
        }
        Util.msg("Copied " + expr + " to clipboard.");
        return expr;
    }

    public static String lastCopiedPos() {
        return lastCopiedPos;
    }

    /** Detail action 3: paths to the chest with Baritone (#goto x y z / GoalNear). */
    public static void goThere(int x, int y, int z) {
        Util.msg("#goto " + x + " " + y + " " + z);
        try {
            Util.baritone().getCustomGoalProcess().setGoalAndPath(
                    new baritone.api.pathing.goals.GoalNear(new BlockPos(x, y, z), 2));
        } catch (Throwable t) {
            Util.warn("chest-goto", "Could not start Baritone #goto: " + t.getMessage());
        }
    }
}
