package dev.midtown.api;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Filter describing which blocks a {@link PathEngine#mine(BlockFilter, Area)} call should target.
 */
public final class BlockFilter {

    private final Set<Block> blocks;
    private final List<String> blockIds;
    private final Predicate<BlockState> predicate;

    private BlockFilter(Set<Block> blocks, List<String> blockIds, Predicate<BlockState> predicate) {
        this.blocks = blocks;
        this.blockIds = blockIds;
        this.predicate = predicate;
    }

    /** Matches any of the given {@link Block} instances. */
    public static BlockFilter of(Block... blocks) {
        Objects.requireNonNull(blocks, "blocks");
        return of(List.of(blocks));
    }

    /** Matches any of the given {@link Block} instances. */
    public static BlockFilter of(Collection<Block> blocks) {
        Objects.requireNonNull(blocks, "blocks");
        Set<Block> copy = Collections.unmodifiableSet(new LinkedHashSet<>(blocks));
        List<String> ids = copy.stream()
                .map(b -> BuiltInRegistries.BLOCK.getKey(b).toString())
                .toList();
        return new BlockFilter(copy, ids, state -> copy.contains(state.getBlock()));
    }

    /** Matches blocks by registry ID (e.g. {@code "minecraft:iron_ore"} or {@code "iron_ore"}). */
    public static BlockFilter ofNames(String... blockIds) {
        Objects.requireNonNull(blockIds, "blockIds");
        return ofNames(List.of(blockIds));
    }

    /** Matches blocks by registry ID. */
    public static BlockFilter ofNames(Collection<String> blockIds) {
        Objects.requireNonNull(blockIds, "blockIds");
        List<String> ids = List.copyOf(blockIds);
        Set<String> idSet = Set.copyOf(ids);
        return new BlockFilter(
                Set.of(),
                ids,
                state -> {
                    var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    return idSet.contains(key.toString()) || idSet.contains(key.getPath());
                });
    }

    /** Matches blocks satisfying an arbitrary {@link BlockState} predicate. */
    public static BlockFilter matching(Predicate<BlockState> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return new BlockFilter(Set.of(), List.of(), predicate);
    }

    /** Returns true if {@code state} matches this filter. */
    public boolean matches(BlockState state) {
        return predicate.test(state);
    }

    /** Explicit block set if constructed from {@link Block} instances, otherwise empty. */
    public Set<Block> blocks() {
        return blocks;
    }

    /** Registry IDs if constructed from blocks or names, otherwise empty. */
    public List<String> blockIds() {
        return blockIds;
    }

    /** True if no blocks or names were specified (predicate-only filters return false). */
    public boolean isEmpty() {
        return blocks.isEmpty() && blockIds.isEmpty() && predicate == null;
    }
}
