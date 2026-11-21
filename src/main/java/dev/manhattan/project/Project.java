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
        /** Optional multi-bot camp bed pool ({@code [x, y, z]} entries) for Fleet Server bed assignment. */
        public List<int[]> bedPool = new ArrayList<>();
        public int[] toolChest;
        public int[] returnChest;
        public int[] dumpChest;
        public int[] scaffoldChest;
        public List<int[]> materialChests = new ArrayList<>();
        public Map<String, List<int[]>> protectedAreas = new LinkedHashMap<>();
        /** Saved task areas embedded when submitting a Project to a Fleet Server so workers inherit area bounds automatically. */
        public Map<String, List<int[]>> areas = new LinkedHashMap<>();

        public BasePlaces() {
        }

        public void ensureInitialized() {
            if (bedPool == null) bedPool = new ArrayList<>();
            if (materialChests == null) materialChests = new ArrayList<>();
            if (protectedAreas == null) protectedAreas = new LinkedHashMap<>();
            if (areas == null) areas = new LinkedHashMap<>();
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
