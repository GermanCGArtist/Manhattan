package dev.survivalkit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persisted settings (config/survivalkit.json). */
final class Config {
    boolean eat = true;
    boolean defence = true;
    boolean respawn = true;

    /** After dying with a task running: get healthy, then run the same Baritone command again. */
    boolean resume = true;
    boolean sleep = true;

    /** Leave for the bed early enough to be there before mobs spawn, computed from the distance (instead of at dark). */
    boolean bedPlan = true;
    /** Extra seconds of slack on top of the walking time (eating, restocking, detours). */
    int bedLead = 20;
    boolean restock = true;

    /** Use a golden apple when health is low and the food bar is already full (food alone cannot heal then). */
    boolean golden = true;

    /** Keep running when the game window loses focus (second monitor): turns off vanilla's pause-on-lost-focus. */
    boolean background = true;

    /** Eat when the food level (0-20) is at or below this. */
    int eatBelow = 14;

    /**
     * Off (default): every feature stays idle unless a Baritone task is running (#mine, #farm, #build, ...), so the
     * bot never interferes with you playing by hand. On: also act when Baritone is idle.
     */
    boolean always = false;

    /** At the base (within baseRadius of a chest or the bed) also top up stock that is only somewhat low. */
    boolean topUp = true;

    /** Eat while Baritone keeps walking (plain walking only) instead of stopping. */
    boolean walkEat = true;
    int baseRadius = 24;

    /** Thunderstorms count like night: monsters spawn in daylight and beds work, so go to bed. */
    boolean weather = true;
    /** Learned walking pace to the bed (ticks per block of straight-line distance, detours and stops included). */
    double ticksPerBlock = 5.5;

    /** Reconnect by itself after the server dropped the connection, then carry on with the task. */
    boolean reconnect = true;

    int[] bed = null;
    int[] supply = null;
    /** More supply chests (the first one is "supply"); the chest index decides which to visit first. */
    java.util.List<int[]> extraSupplies = new java.util.ArrayList<>();

    /** Where worn-out tools are dropped off (optional; without it they go into the supply chest). */
    int[] returnChest = null;

    /** Restock rules: item id fragment (or @food) -> how many to carry. */
    Map<String, Integer> want = new LinkedHashMap<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static Config cfg = new Config();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("survivalkit.json");
    }

    static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                try (Reader r = Files.newBufferedReader(p)) {
                    Config c = GSON.fromJson(r, Config.class);
                    if (c != null) cfg = c;
                }
            } else {
                cfg.want.put("@food", 16);
                save();
            }
            if (cfg.want == null) cfg.want = new LinkedHashMap<>();
            if (cfg.extraSupplies == null) cfg.extraSupplies = new java.util.ArrayList<>();
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
