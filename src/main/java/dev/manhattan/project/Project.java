package dev.manhattan.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level Manhattan Project definition: base places, survival policy, and an ordered list of tasks.
 * Gson-serializable POJO with zero Minecraft dependencies so it can be persisted and tested headlessly.
 */
public final class Project {
    public int version = 1;
    public String id;
    public String name;
    public String world;
    /** Optional chunk size override (0 = inherit local config; embedded on Fleet submit so all workers match). */
    public int chunkSize = 0;
    /** Optional Y-band height override (0 = inherit local config; embedded on Fleet submit so all workers match). */
    public int chunkSizeY = 0;
    public BasePlaces places = new BasePlaces();
    public SurvivalPolicy survival = new SurvivalPolicy();
    public List<ProjectTask> tasks = new ArrayList<>();

    public Project() {
    }

    public Project(String id, String name, String world) {
        this.id = id;
        this.name = name;
        this.world = world;
    }

    /** Ensures all nested structures and collections are initialized after Gson deserialization. */
    public void ensureInitialized() {
        if (places == null) places = new BasePlaces();
        places.ensureInitialized();
        if (survival == null) survival = new SurvivalPolicy();
        survival.ensureInitialized();
        if (tasks == null) tasks = new ArrayList<>();
        for (ProjectTask task : tasks) {
            if (task != null) task.ensureInitialized();
        }
    }

    /**
     * Saved world coordinates for a project's base infrastructure and protected zones.
     * Positions are 3-element arrays {@code {x, y, z}}; boxes are 6-element normalized arrays
     * {@code {x1, y1, z1, x2, y2, z2}}.
     */
    public static final class BasePlaces {
        public int[] bed;
        /** Optional backup beds ({@code [x, y, z]} entries) used automatically if the primary bed is missing or destroyed. */
        public List<int[]> extraBeds = new ArrayList<>();
        /** Optional multi-bot camp bed pool ({@code [x, y, z]} entries) for Fleet Server bed assignment. */
        public List<int[]> bedPool = new ArrayList<>();
        public int[] toolChest;
        /** Optional additional tool/food supply chests ({@code [x, y, z]} entries). */
        public List<int[]> extraToolChests = new ArrayList<>();
        /** Optional supply chest area boxes ({@code [x1, y1, z1, x2, y2, z2]}) whose chests/barrels are auto-discovered. */
        public List<int[]> supplyAreas = new ArrayList<>();
        public int[] returnChest;
        /** Optional additional worn-tool return chests ({@code [x, y, z]} entries) for overflow failover. */
        public List<int[]> extraReturnChests = new ArrayList<>();
        /** Optional return chest area boxes ({@code [x1, y1, z1, x2, y2, z2]}) whose chests/barrels are auto-discovered. */
        public List<int[]> returnAreas = new ArrayList<>();
        public int[] dumpChest;
        public int[] scaffoldChest;
        /** Optional additional scaffolding chests ({@code [x, y, z]} entries). */
        public List<int[]> extraScaffoldChests = new ArrayList<>();
        /** Optional scaffolding chest area boxes ({@code [x1, y1, z1, x2, y2, z2]}) whose chests/barrels are auto-discovered. */
        public List<int[]> scaffoldAreas = new ArrayList<>();
        public List<int[]> materialChests = new ArrayList<>();
        /** Optional build-supply / material chest area boxes ({@code [x1, y1, z1, x2, y2, z2]}) for schematics & fill tasks. */
        public List<int[]> materialAreas = new ArrayList<>();
        public Map<String, List<int[]>> protectedAreas = new LinkedHashMap<>();
        /** Saved task areas embedded when submitting a Project to a Fleet Server so workers inherit area bounds automatically. */
        public Map<String, List<int[]>> areas = new LinkedHashMap<>();

        public BasePlaces() {
        }

        public void ensureInitialized() {
            if (extraBeds == null) extraBeds = new ArrayList<>();
            if (bedPool == null) bedPool = new ArrayList<>();
            if (extraToolChests == null) extraToolChests = new ArrayList<>();
            if (supplyAreas == null) supplyAreas = new ArrayList<>();
            if (extraReturnChests == null) extraReturnChests = new ArrayList<>();
            if (returnAreas == null) returnAreas = new ArrayList<>();
            if (extraScaffoldChests == null) extraScaffoldChests = new ArrayList<>();
            if (scaffoldAreas == null) scaffoldAreas = new ArrayList<>();
            if (materialChests == null) materialChests = new ArrayList<>();
            if (materialAreas == null) materialAreas = new ArrayList<>();
            if (protectedAreas == null) protectedAreas = new LinkedHashMap<>();
            if (areas == null) areas = new LinkedHashMap<>();
        }

        /** Ordered, deduplicated list of all configured beds: primary {@code bed}, then {@code extraBeds}, then {@code bedPool}. */
        public List<int[]> allBeds() {
            ensureInitialized();
            List<int[]> out = new ArrayList<>();
            addUniquePos(out, bed);
            for (int[] p : extraBeds) addUniquePos(out, p);
            for (int[] p : bedPool) addUniquePos(out, p);
            return out;
        }

        /** Ordered, deduplicated list of all explicitly configured tool/food supply chests. */
        public List<int[]> allToolChests() {
            ensureInitialized();
            List<int[]> out = new ArrayList<>();
            addUniquePos(out, toolChest);
            for (int[] p : extraToolChests) addUniquePos(out, p);
            return out;
        }

        /** Ordered, deduplicated list of all explicitly configured worn-tool return chests. */
        public List<int[]> allReturnChests() {
            ensureInitialized();
            List<int[]> out = new ArrayList<>();
            addUniquePos(out, returnChest);
            for (int[] p : extraReturnChests) addUniquePos(out, p);
            return out;
        }

        /** Ordered, deduplicated list of all explicitly configured scaffolding chests. */
        public List<int[]> allScaffoldChests() {
            ensureInitialized();
            List<int[]> out = new ArrayList<>();
            addUniquePos(out, scaffoldChest);
            for (int[] p : extraScaffoldChests) addUniquePos(out, p);
            return out;
        }

        public boolean hasAnyBed() {
            return !allBeds().isEmpty();
        }

        public boolean hasAnyToolSupply() {
            ensureInitialized();
            return toolChest != null || !extraToolChests.isEmpty() || !supplyAreas.isEmpty();
        }

        public boolean hasAnyReturnStorage() {
            ensureInitialized();
            return returnChest != null || !extraReturnChests.isEmpty() || !returnAreas.isEmpty();
        }

        public boolean hasAnyScaffoldStorage() {
            ensureInitialized();
            return scaffoldChest != null || !extraScaffoldChests.isEmpty() || !scaffoldAreas.isEmpty();
        }

        public boolean hasAnyMaterialStorage() {
            ensureInitialized();
            return !materialChests.isEmpty() || !materialAreas.isEmpty();
        }

        private static void addUniquePos(List<int[]> out, int[] p) {
            if (p == null || p.length < 3) return;
            for (int[] existing : out) {
                if (existing[0] == p[0] && existing[1] == p[1] && existing[2] == p[2]) return;
            }
            out.add(new int[]{p[0], p[1], p[2]});
        }
    }

    /** Project-level survival, logistics, safety, and session-control settings. */
    public static final class SurvivalPolicy {
        public boolean defendEnabled = true;
        public double defendMinHealth = 8.0;
        public double defendRange = 7.0;

        public boolean eatEnabled = true;
        public int eatFoodBelow = 18;
        public double eatHealHealthBelow = 16.0;

        public boolean sleepEnabled = true;
        public int bedLeadSeconds = 5;
        public boolean sleepWeather = true;
        public boolean logoffOnMissingBed = false;

        public boolean restockEnabled = true;
        public Map<String, Integer> want = new LinkedHashMap<>();
        public Map<Integer, String> hotbar = new LinkedHashMap<>();
        public boolean itemSaver = true;
        public int itemSaverThreshold = 50;

        public boolean dumpEnabled = true;
        public int dumpFreeSlotsBelow = 1;
        public Map<String, Integer> keep = new LinkedHashMap<>();

        public boolean respawn = true;
        public boolean resumeAfterDeath = true;
        public boolean reconnect = true;
        public int maxDeaths = 3;

        public boolean logoffOnDone = false;
        public boolean logoffOnError = false;

        public boolean escapeEnabled = true;
        public boolean pickupEnabled = true;

        public boolean batchBaseTrips = true;
        public boolean preDuskGate = true;
        public int preDuskMarginTicks = 1200;

        public int treeZoneDiameter = 9;
        public boolean showTreeZones = true;

        public SurvivalPolicy() {
        }

        public void ensureInitialized() {
            if (want == null) want = new LinkedHashMap<>();
            if (hotbar == null) hotbar = new LinkedHashMap<>();
            if (keep == null) keep = new LinkedHashMap<>();
            if (treeZoneDiameter < 3) treeZoneDiameter = 9;
        }
    }
}
