package dev.survivalkit;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One entry of the want-list: an item (an id such as minecraft:diamond_pickaxe, a part of an id, or @food) and
 * optionally enchantments it must have, as written in the key: "diamond_pickaxe efficiency:5;mending".
 */
final class WantRule {
    record Ench(String id, int level) {}

    final String item;
    final boolean exact;
    final List<Ench> ench;

    private static final Map<String, WantRule> CACHE = new HashMap<>();

    private WantRule(String item, boolean exact, List<Ench> ench) {
        this.item = item;
        this.exact = exact;
        this.ench = ench;
    }

    static WantRule of(String key) {
        return CACHE.computeIfAbsent(key, WantRule::parse);
    }

    private static WantRule parse(String key) {
        String k = key.trim();
        int sp = k.indexOf(' ');
        String item = (sp < 0 ? k : k.substring(0, sp)).toLowerCase();
        String rest = sp < 0 ? "" : k.substring(sp + 1);
        return new WantRule(item, item.contains(":"), enchants(rest));
    }

    /** "efficiency 5;mending" or "efficiency:5;mending" -> [efficiency>=5, mending>=1]. */
    static List<Ench> enchants(String text) {
        List<Ench> out = new ArrayList<>();
        for (String part : text.split(";")) {
            String[] t = part.trim().toLowerCase().split("[\\s:]+");
            if (t.length == 0 || t[0].isEmpty()) continue;
            String id = t[0].startsWith("minecraft:") ? t[0].substring(10) : t[0];
            int level = 1;
            if (t.length > 1) {
                try {
                    level = Integer.parseInt(t[1]);
                } catch (NumberFormatException ignored) {
                }
            }
            out.add(new Ench(id, Math.max(1, level)));
        }
        return out;
    }

    boolean matches(ItemStack st) {
        if (st.isEmpty()) return false;
        if (item.equals("@food")) return Util.isGoodFood(st);
        Identifier id = BuiltInRegistries.ITEM.getKey(st.getItem());
        if (exact ? !id.toString().equals(item) : !id.getPath().contains(item)) return false;
        for (Ench e : ench) {
            if (levelOf(st, e.id()) < e.level()) return false;
        }
        return true;
    }

    private static int levelOf(ItemStack st, String path) {
        for (var entry : st.getEnchantments().entrySet()) {
            var key = entry.getKey().unwrapKey();
            if (key.isPresent() && key.get().identifier().getPath().equals(path)) return entry.getIntValue();
        }
        return 0;
    }

    /** The item part of a stored key, for code that only needs to know which items a rule is about. */
    static String itemOf(String key) {
        return of(key).item;
    }
}
