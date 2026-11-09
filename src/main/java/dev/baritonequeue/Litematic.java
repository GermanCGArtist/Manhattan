package dev.baritonequeue;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a .litematic file (gzip NBT) ourselves: the regions with their block palette and packed block data. This is
 * all that is needed to build; where the schematic stands in the world (origin, rotation, mirror, which sub-regions
 * are on) comes from Litematica's placement (see SchemSource).
 */
final class Litematic {
    private Litematic() {}

    /** One sub-region. Position is where it sits in the schematic; the size may be negative (then it grows the other way). */
    static final class Region {
        String name;
        int px, py, pz;
        int sx, sy, sz;                 // as stored (signed)
        BlockState[] palette;
        long[] bits;
        int bitsPer;

        int ax() { return Math.abs(sx); }
        int ay() { return Math.abs(sy); }
        int az() { return Math.abs(sz); }

        /** Offset from Position to the region's lowest corner (non-zero for negative sizes). */
        int minX() { return sx < 0 ? sx + 1 : 0; }
        int minY() { return sy < 0 ? sy + 1 : 0; }
        int minZ() { return sz < 0 ? sz + 1 : 0; }

        long volume() { return (long) ax() * ay() * az(); }

        BlockState at(int lx, int ly, int lz) {
            long index = ((long) ly * az() + lz) * ax() + lx;
            if (bitsPer == 0 || bits == null) return palette.length > 0 ? palette[0] : Blocks.AIR.defaultBlockState();
            long start = index * bitsPer;
            int a = (int) (start >>> 6), b = (int) (((index + 1) * bitsPer - 1) >>> 6), off = (int) (start & 63);
            long mask = (1L << bitsPer) - 1;
            long v;
            if (a == b) v = (bits[a] >>> off) & mask;
            else v = ((bits[a] >>> off) | (bits[b] << (64 - off))) & mask;
            return v < palette.length ? palette[(int) v] : Blocks.AIR.defaultBlockState();
        }
    }

    static final class Schematic {
        String name = "";
        final Map<String, Region> regions = new LinkedHashMap<>();
        final Set<String> unknown = new LinkedHashSet<>();   // block names this Minecraft does not know (built as air)
    }

    static Schematic read(Path file) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        Schematic s = new Schematic();
        s.name = root.getCompoundOrEmpty("Metadata").getStringOr("Name", "");
        CompoundTag regions = root.getCompoundOrEmpty("Regions");
        for (String key : regions.keySet()) {
            CompoundTag r = regions.getCompoundOrEmpty(key);
            Region reg = new Region();
            reg.name = key;
            CompoundTag pos = r.getCompoundOrEmpty("Position"), size = r.getCompoundOrEmpty("Size");
            reg.px = pos.getIntOr("x", 0);
            reg.py = pos.getIntOr("y", 0);
            reg.pz = pos.getIntOr("z", 0);
            reg.sx = size.getIntOr("x", 0);
            reg.sy = size.getIntOr("y", 0);
            reg.sz = size.getIntOr("z", 0);
            ListTag pal = r.getListOrEmpty("BlockStatePalette");
            reg.palette = new BlockState[pal.size()];
            for (int i = 0; i < pal.size(); i++) reg.palette[i] = state(pal.getCompoundOrEmpty(i), s.unknown);
            reg.bits = r.getLongArray("BlockStates").orElse(new long[0]);
            reg.bitsPer = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, reg.palette.length - 1)));
            if (reg.palette.length <= 1 && reg.bits.length == 0) reg.bitsPer = 0;
            s.regions.put(key, reg);
        }
        return s;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState state(CompoundTag t, Set<String> unknown) {
        String name = t.getStringOr("Name", "minecraft:air");
        Identifier id = Identifier.tryParse(name);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            unknown.add(name);
            return Blocks.AIR.defaultBlockState();
        }
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        BlockState st = block.defaultBlockState();
        CompoundTag props = t.getCompoundOrEmpty("Properties");
        for (String k : props.keySet()) {
            Property<?> p = block.getStateDefinition().getProperty(k);
            if (p == null) continue;
            String v = props.getStringOr(k, "");
            var parsed = p.getValue(v);
            if (parsed.isPresent()) st = st.setValue((Property) p, (Comparable) parsed.get());
        }
        return st;
    }

    static List<String> regionNames(Schematic s) {
        return new ArrayList<>(s.regions.keySet());
    }
}
