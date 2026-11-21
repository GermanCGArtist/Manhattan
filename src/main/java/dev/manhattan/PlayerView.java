package dev.manhattan;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** The player's 36 inventory slots and worn tools for the workspace tiles (read at most 4 times a second). */
final class PlayerView {
    private PlayerView() {}

    record Slot(String id, String label, int count, int durability, int maxDurability) {
        boolean empty() { return id.isEmpty(); }
        boolean damageable() { return maxDurability > 0; }
    }

    record Snapshot(List<Slot> slots, int freeSlots, int selected, int wornThreshold) {}

    private static Snapshot last = new Snapshot(List.of(), 36, 0, 5);
    private static long lastAt;

    static Snapshot get() {
        long now = System.currentTimeMillis();
        if (now - lastAt < 250) return last;
        lastAt = now;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) return last;
            var inv = mc.player.getInventory();
            List<Slot> out = new ArrayList<>(36);
            int free = 0;
            for (int i = 0; i < 36; i++) {
                ItemStack st = inv.getItem(i);
                if (st == null || st.isEmpty()) {
                    out.add(new Slot("", "", 0, 0, 0));
                    free++;
                    continue;
                }
                String id = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
                int max = st.isDamageableItem() ? st.getMaxDamage() : 0;
                int dur = max > 0 ? max - st.getDamageValue() : 0;
                out.add(new Slot(id, label(id), st.getCount(), dur, max));
            }
            last = new Snapshot(out, free, inv.getSelectedSlot(), wornThreshold());
        } catch (Throwable ignored) {
        }
        return last;
    }

    /** Tools (damageable items) with their durability, worst first. */
    static List<Slot> tools() {
        List<Slot> out = new ArrayList<>();
        for (Slot s : get().slots()) if (!s.empty() && s.damageable()) out.add(s);
        out.sort((a, b) -> Integer.compare(a.durability(), b.durability()));
        return out;
    }

    /** Durability at which itemSaver retires a tool (5 if itemSaver is off). */
    static int wornThreshold() {
        int t = 5;
        try {
            var engine = dev.midtown.api.EngineSelector.current();
            if ("true".equalsIgnoreCase(engine.getSetting("itemSaver"))) {
                String raw = engine.getSetting("itemSaverThreshold");
                if (raw != null) t = Math.max(t, Integer.parseInt(raw.trim()));
            }
        } catch (Throwable ignored) {
        }
        return t;
    }

    /** Two-letter slot label: "diamond_pickaxe" -> "Pk", "cobblestone" -> "Co". */
    static String label(String id) {
        String[] parts = id.split("_");
        String last = parts[parts.length - 1];
        String l = switch (last) {
            case "pickaxe" -> "Pk";
            case "shovel" -> "Sh";
            case "axe" -> "Ax";
            case "sword" -> "Sw";
            case "hoe" -> "Ho";
            case "bucket" -> parts.length > 1 ? "B" + Character.toLowerCase(parts[0].charAt(0)) : "Bu";
            default -> last.length() >= 2 ? Character.toUpperCase(last.charAt(0)) + last.substring(1, 2) : last.toUpperCase();
        };
        return l;
    }

    static String pretty(String id) {
        String s = id.replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
