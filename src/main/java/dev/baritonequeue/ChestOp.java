package dev.baritonequeue;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * One chest job of a script: take(chest, "dirt", 64), take_upto(chest, "dirt", 512), put(chest, "dirt"), dump(chest).
 */
final class ChestOp {
    enum Kind { GET, PUT, DUMP }

    final String chest;    // how the chest is called in messages
    final Kind kind;
    final Item item;       // null for DUMP
    final int count;       // -1 = all, 0 = one stack (get), otherwise that many
    final boolean topUp;   // get ... up to count

    ChestOp(String chest, Kind kind, Item item, int count, boolean topUp) {
        this.chest = chest;
        this.kind = kind;
        this.item = item;
        this.count = count;
        this.topUp = topUp;
    }

    static Item itemOf(String word) {
        String w = word.toLowerCase().trim();
        Identifier id = Identifier.tryParse(w.contains(":") ? w : "minecraft:" + w);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return null;
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return item == Items.AIR ? null : item;
    }

    String describe() {
        return switch (kind) {
            case GET -> "take " + (topUp ? "up to " + count : count < 0 ? "all" : count == 0 ? "a stack of" : count) + " " + name(item) + " from " + chest;
            case PUT -> "put " + (count < 0 ? "all" : count) + " " + name(item) + " into " + chest;
            case DUMP -> "empty the inventory (keeping the wanted tools, good food and the keep rules) into " + chest;
        };
    }

    static String name(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
