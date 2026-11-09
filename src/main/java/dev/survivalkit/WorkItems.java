package dev.survivalkit;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.survivalkit.Config.cfg;

/**
 * Parsing of "work items": item, how many to carry, and optionally the enchantments it needs.
 *   minecraft:diamond_pickaxe 2 efficiency 5;mending
 *   food 16
 *   iron_axe 1
 */
final class WorkItems {
    private WorkItems() {}

    /** Enchantment ids (without namespace) the game knows, or empty when no world is loaded. */
    static Set<String> enchantIds() {
        Set<String> out = new HashSet<>();
        var level = Minecraft.getInstance().level;
        if (level == null) return out;
        level.registryAccess().lookup(Registries.ENCHANTMENT).ifPresent(reg ->
                reg.listElementIds().forEach(k -> out.add(k.identifier().getPath())));
        return out;
    }

    private static String itemToken(String t) {
        String l = t.toLowerCase();
        return l.equals("food") ? "@food" : l;
    }

    /** The stored key for the item/enchant part of a rule typed with or without a count (a count is ignored). */
    static String keyOf(String text) {
        String[] t = text.trim().split("\\s+", 3);
        String item = itemToken(t[0]);
        String rest = t.length == 3 ? t[2] : (t.length == 2 && !t[1].matches("\\d+") ? t[1] : "");
        return key(item, WantRule.enchants(rest));
    }

    private static String key(String item, List<WantRule.Ench> ench) {
        if (ench.isEmpty()) return item;
        List<String> parts = new ArrayList<>();
        for (WantRule.Ench e : ench) parts.add(e.level() > 1 ? e.id() + ":" + e.level() : e.id());
        return item + " " + String.join(";", parts);
    }

    /** Whether the item word names an item (exact id, or a part of one). */
    static boolean knownItem(String item) {
        if (item.equals("@food")) return true;
        if (item.contains(":")) {
            Identifier id = Identifier.tryParse(item);
            return id != null && BuiltInRegistries.ITEM.containsKey(id);
        }
        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            if (id.getPath().contains(item)) return true;
        }
        return false;
    }

    /** Adds the rule; returns an error message, or null when it was added. */
    static String add(String text) {
        String[] t = text.trim().split("\\s+", 3);
        if (t.length < 2) return "Format: <item> <count> [enchantment [level];enchantment ...]   e.g. minecraft:diamond_pickaxe 2 efficiency 5;mending";
        String item = itemToken(t[0]);
        if (!knownItem(item)) return "No item matches '" + t[0] + "'.";
        int n;
        try {
            n = Integer.parseInt(t[1]);
        } catch (NumberFormatException e) {
            return "'" + t[1] + "' is not a count.";
        }
        if (n < 1 || n > 2304) return "The count must be between 1 and 2304.";
        List<WantRule.Ench> ench = t.length == 3 ? WantRule.enchants(t[2]) : List.of();
        Set<String> known = enchantIds();
        if (!known.isEmpty()) {
            for (WantRule.Ench e : ench) {
                if (!known.contains(e.id())) return "Unknown enchantment '" + e.id() + "'.";
            }
        }
        String key = key(item, ench);
        cfg.want.put(key, n);
        Config.save();
        Util.msg("Carry " + n + " x " + key + " (restocks when below half).");
        return null;
    }
}
