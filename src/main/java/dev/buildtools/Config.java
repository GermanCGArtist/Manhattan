package dev.buildtools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.core.BlockPos;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Persisted settings (config/buildtools.json). */
final class Config {
    /** When the builder pauses itself because of flowing water, plug/drain it and resume. */
    boolean drainAuto = true;

    /** With a selection: also plug the water just outside the selection's edge so it cannot flow back in. */
    boolean dam = true;

    /** The water fill dives for cells too deep to reach from the shore (open water straight up, air watched). */
    boolean dive = true;

    /** Swim to dry land when Baritone is stuck going in circles in water. */
    boolean escape = true;

    /** Safe areas: Baritone may walk through them but can never break or place blocks inside them. */
    boolean protect = true;

    /** While safe areas are on: Baritone plans around chests, hoppers, beds, furnaces, redstone parts (never breaks them). */
    boolean avoidBlocks = true;

    /** Remember the throwaway blocks Baritone places (bridges, pillars) so they can be picked up in one sweep. */
    boolean scaffoldRecord = true;

    /** Pick them up by itself once this many are standing near you while Baritone is busy (see scaffoldBatch). */
    boolean scaffoldAuto = true;
    int scaffoldBatch = 25;

    /** Cut #sel selections around safe areas, so the builder never targets anything inside them. */
    boolean trim = true;
    List<Zone> zones = new ArrayList<>();

    /** Corners being set by hand (protect pos1 / pos2), {x, y, z}; null = not set. */
    int[] corner1, corner2;

    /** How close to a safe area (blocks from its edge) still counts as "able to reach into it". */
    int reachBlocks = 6;

    /** A box of blocks (both corners inclusive). */
    static final class Zone {
        String name;
        int x1, y1, z1, x2, y2, z2;

        /** May Baritone break / place blocks inside, and while you are inside or near (reach) the area? false = no. */
        boolean allowBreak = false, allowPlace = false;
        /** Also apply the two rules above while you are within reach of the area, not only inside it. */
        boolean reach = true;

        Zone() {}

        Zone(String name, BlockPos a, BlockPos b) {
            this.name = name;
            x1 = Math.min(a.getX(), b.getX());
            y1 = Math.min(a.getY(), b.getY());
            z1 = Math.min(a.getZ(), b.getZ());
            x2 = Math.max(a.getX(), b.getX());
            y2 = Math.max(a.getY(), b.getY());
            z2 = Math.max(a.getZ(), b.getZ());
        }

        boolean contains(BlockPos p) {
            return p.getX() >= x1 && p.getX() <= x2 && p.getY() >= y1 && p.getY() <= y2 && p.getZ() >= z1 && p.getZ() <= z2;
        }

        /** Within `blocks` of the area (inside counts). */
        boolean within(BlockPos p, int blocks) {
            return p.getX() >= x1 - blocks && p.getX() <= x2 + blocks && p.getY() >= y1 - blocks && p.getY() <= y2 + blocks
                    && p.getZ() >= z1 - blocks && p.getZ() <= z2 + blocks;
        }

        String describe() {
            return name + " (" + x1 + " " + y1 + " " + z1 + " to " + x2 + " " + y2 + " " + z2 + ") mining "
                    + (allowBreak ? "allowed" : "denied") + ", bridging " + (allowPlace ? "allowed" : "denied")
                    + ", reach " + (reach ? "on" : "off");
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static Config cfg = new Config();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("buildtools.json");
    }

    static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                try (Reader r = Files.newBufferedReader(p)) {
                    Config c = GSON.fromJson(r, Config.class);
                    if (c != null) cfg = c;
                    if (cfg.zones == null) cfg.zones = new ArrayList<>();
                }
            } else {
                save();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static void save() {
        try (Writer w = Files.newBufferedWriter(path())) {
            GSON.toJson(cfg, w);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
