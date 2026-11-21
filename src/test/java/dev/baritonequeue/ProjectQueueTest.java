package dev.baritonequeue;

import dev.baritonequeue.project.LogisticsPlanner;
import dev.baritonequeue.project.PriorityScheduler;
import dev.baritonequeue.project.Project;
import dev.baritonequeue.project.ProjectLayerScheduler;
import dev.baritonequeue.project.ProjectQueue;
import dev.baritonequeue.project.ProjectStore;
import dev.baritonequeue.project.ProjectTask;
import dev.baritonequeue.project.ScopedOverrides;
import dev.baritonequeue.project.TaskQueue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Pure-Java headless tests for Wave 1 Project, ProjectStore, TaskQueue, LogisticsPlanner,
 * and PriorityScheduler. Zero Minecraft imports; no JUnit dependency.
 */
public class ProjectQueueTest {
    private static int passes = 0;
    private static int fails = 0;

    private static void check(String name, boolean cond) {
        if (cond) {
            passes++;
        } else {
            fails++;
            System.out.println("FAIL " + name);
        }
    }

    private static void eq(String name, Object got, Object expected) {
        if (Objects.equals(got, expected)) {
            passes++;
        } else {
            fails++;
            System.out.println("FAIL " + name + "\n  expected: " + expected + "\n  got:      " + got);
        }
    }

    private static void eqArr(String name, int[] got, int[] expected) {
        if (Arrays.equals(got, expected)) {
            passes++;
        } else {
            fails++;
            System.out.println("FAIL " + name + "\n  expected: " + Arrays.toString(expected)
                    + "\n  got:      " + Arrays.toString(got));
        }
    }

    public static void main(String[] args) throws Exception {
        testScopedOverrides();
        testProjectTaskForAreaRole();
        testProjectStoreRoundTrip();
        testCaptureImport();
        testTaskQueueLiveMutationsAndStableLedger();
        testLogisticsPlannerBatching();
        testSchedulerPreemptionStack();
        testSchedulerLogisticsPreemption();
        testSchedulerPreDuskGate();
        testSchedulerOverridesRestore();
        testSchedulerPartialPolicies();
        testSchedulerErrorPolicies();
        testSchedulerLiveControlsAndTelemetry();
        testSchedulerSuspendsBeforeStartingPreemption();
        testProjectQueueStoreAndOperations();
        testProjectLayerSequentialExecution();
        testProjectLayerLivePreemptionAndResume();
        testProjectLayerPreemptionWaitsForCombat();
        testProjectLayerErrorPolicies();

        System.out.println("ProjectQueueTest: " + passes + " passed, " + fails + " failed.");
        if (fails > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ 1. ScopedOverrides

    private static void testScopedOverrides() {
        ScopedOverrides empty = new ScopedOverrides();
        check("empty overrides isEmpty", empty.isEmpty());
        check("empty not suppressed", !empty.isSuppressed("eat"));

        ScopedOverrides base = new ScopedOverrides();
        base.allowBreak = false;
        base.keep.put("dirt", 64);
        base.want.put("@food", 16);
        base.suppressSurvival.add("Eat");

        check("non-empty overrides", !base.isEmpty());
        check("case-insensitive suppression", base.isSuppressed("eat") && base.isSuppressed("EAT"));
        check("other feature not suppressed", !base.isSuppressed("sleep"));

        ScopedOverrides copy = base.copy();
        eq("copy equals base", copy, base);
        copy.keep.put("stone", 32);
        copy.suppressSurvival.add("sleep");
        check("copy deep-isolated keep", !base.keep.containsKey("stone"));
        check("copy deep-isolated suppress", !base.isSuppressed("sleep"));

        ScopedOverrides overlay = new ScopedOverrides();
        overlay.allowPlace = false;
        overlay.keep.put("dirt", 128);
        overlay.want.put("diamond_pickaxe", 2);
        overlay.suppressSurvival.add("dump");

        ScopedOverrides merged = base.merge(overlay);
        eq("merged allowBreak from base", merged.allowBreak, Boolean.FALSE);
        eq("merged allowPlace from overlay", merged.allowPlace, Boolean.FALSE);
        eq("merged keep overridden", merged.keep.get("dirt"), 128);
        eq("merged want combined food", merged.want.get("@food"), 16);
        eq("merged want combined pickaxe", merged.want.get("diamond_pickaxe"), 2);
        check("merged suppress union", merged.isSuppressed("eat") && merged.isSuppressed("dump"));
    }

    // ------------------------------------------------------------------ 2. ProjectTask.forAreaRole

    private static void testProjectTaskForAreaRole() {
        ProjectTask dig = ProjectTask.forAreaRole("quarry_1", "dig");
        eq("forAreaRole dig kind", dig.kind, ProjectTask.Kind.CLEAR_AREA);
        eq("forAreaRole dig area", dig.areaName, "quarry_1");
        check("forAreaRole dig id", dig.id != null && dig.id.startsWith("t_"));
        check("forAreaRole dig label", dig.label != null && dig.label.contains("quarry_1"));

        ProjectTask clear = ProjectTask.forAreaRole("pit", "CLEAR");
        eq("forAreaRole clear kind", clear.kind, ProjectTask.Kind.CLEAR_AREA);

        ProjectTask fill = ProjectTask.forAreaRole("canal", "fill");
        eq("forAreaRole fill kind", fill.kind, ProjectTask.Kind.FILL_WATER);
        eq("forAreaRole fill default block", fill.block, "dirt");

        ProjectTask flood = ProjectTask.forAreaRole("basin", "flood");
        eq("forAreaRole flood kind", flood.kind, ProjectTask.Kind.FLOOD);

        ProjectTask trees = ProjectTask.forAreaRole("woods", "trees");
        eq("forAreaRole trees kind", trees.kind, ProjectTask.Kind.CLEAR_TREES);
        eq("forAreaRole trees area", trees.areaName, "woods");
        eq("forAreaRole trees label", trees.label, "Clear trees: woods");

        ProjectTask forest = ProjectTask.forAreaRole("pine_grove", "FOREST");
        eq("forAreaRole forest kind", forest.kind, ProjectTask.Kind.CLEAR_TREES);
        eq("forAreaRole forest label", forest.label, "Clear trees: pine_grove");

        ProjectTask fallback = ProjectTask.forAreaRole("misc", "unknown_role");
        eq("forAreaRole default kind", fallback.kind, ProjectTask.Kind.CLEAR_AREA);
        check("forAreaRole unique ids", !dig.id.equals(fill.id) && !fill.id.equals(flood.id) && !flood.id.equals(trees.id));
    }

    // ------------------------------------------------------------------ 3. ProjectStore

    private static void testProjectStoreRoundTrip() throws IOException {
        eq("sanitizeId normal", ProjectStore.sanitizeId("  Canal Project #1! "), "canal_project_1");
        eq("sanitizeId traversal", ProjectStore.sanitizeId("../../../etc/passwd"), "etc_passwd");
        eq("sanitizeId blank", ProjectStore.sanitizeId("   "), "default");
        eq("sanitizeId null", ProjectStore.sanitizeId(null), "default");

        Path tmp = Files.createTempDirectory("manhattan-project-store-test-");
        try {
            String world = "Survival World:25565";
            Project p1 = ProjectStore.createDefault(world, "Canal Build", "Canal Build");
            eq("default treeZoneDiameter", p1.survival.treeZoneDiameter, 9);
            eq("default showTreeZones", p1.survival.showTreeZones, true);
            p1.places.bed = new int[]{10, 64, -5};
            p1.places.toolChest = new int[]{12, 64, -5};
            p1.places.returnChest = new int[]{14, 64, -5};
            p1.places.dumpChest = new int[]{16, 64, -5};
            p1.places.scaffoldChest = new int[]{18, 64, -5};
            p1.places.materialChests.add(new int[]{20, 64, -5});
            p1.places.protectedAreas.put("spawn", List.of(new int[]{0, 60, 0, 15, 80, 15}));
            p1.survival.want.put("@food", 16);
            p1.survival.hotbar.put(0, "diamond_pickaxe");
            p1.survival.keep.put("dirt", 128);
            p1.survival.preDuskMarginTicks = 1500;
            p1.survival.treeZoneDiameter = 13;
            p1.survival.showTreeZones = false;

            ProjectTask t1 = ProjectTask.forAreaRole("lake_1", "fill");
            t1.id = "t_001";
            t1.expand = 1;
            t1.overrides.allowBreak = false;
            t1.overrides.suppressSurvival.add("dump");
            t1.partialPolicy = ProjectTask.PartialPolicy.STOP;
            t1.partialThreshold = 3;
            t1.errorPolicy = ProjectTask.ErrorPolicy.RETRY;
            t1.maxRetries = 2;
            p1.tasks.add(t1);

            ProjectTask t2 = ProjectTask.forAreaRole("north_woods", "trees");
            t2.id = "t_002";
            t2.treeZoneDiameter = 11;
            p1.tasks.add(t2);

            ProjectStore.save(tmp, p1);
            Project p2 = ProjectStore.createDefault(world, "alpha", "Alpha");
            ProjectStore.save(tmp, p2);

            eq("list sorted", ProjectStore.list(tmp, world), List.of("alpha", "canal_build"));
            eq("activeId unset initially", ProjectStore.activeId(tmp, world), null);
            ProjectStore.setActiveId(tmp, world, "Canal Build");
            eq("activeId saved", ProjectStore.activeId(tmp, world), "canal_build");

            Project loaded = ProjectStore.load(tmp, world, "canal_build");
            check("loaded non-null", loaded != null);
            eq("loaded id", loaded.id, "canal_build");
            eq("loaded name", loaded.name, "Canal Build");
            eqArr("loaded bed", loaded.places.bed, new int[]{10, 64, -5});
            eqArr("loaded scaffoldChest", loaded.places.scaffoldChest, new int[]{18, 64, -5});
            eq("loaded materialChests count", loaded.places.materialChests.size(), 1);
            eq("loaded protectedAreas count", loaded.places.protectedAreas.get("spawn").size(), 1);
            eq("loaded survival want", loaded.survival.want.get("@food"), 16);
            eq("loaded survival hotbar", loaded.survival.hotbar.get(0), "diamond_pickaxe");
            eq("loaded preDuskMarginTicks", loaded.survival.preDuskMarginTicks, 1500);
            eq("loaded treeZoneDiameter", loaded.survival.treeZoneDiameter, 13);
            eq("loaded showTreeZones", loaded.survival.showTreeZones, false);
            eq("loaded tasks count", loaded.tasks.size(), 2);
            ProjectTask lt = loaded.tasks.get(0);
            eq("loaded task id", lt.id, "t_001");
            eq("loaded task kind", lt.kind, ProjectTask.Kind.FILL_WATER);
            eq("loaded task expand", lt.expand, 1);
            eq("loaded task treeZoneDiameter null", lt.treeZoneDiameter, null);
            eq("loaded task allowBreak", lt.overrides.allowBreak, Boolean.FALSE);
            check("loaded task suppress dump", lt.overrides.isSuppressed("dump"));
            eq("loaded task partialPolicy", lt.partialPolicy, ProjectTask.PartialPolicy.STOP);
            eq("loaded task partialThreshold", lt.partialThreshold, 3);
            eq("loaded task errorPolicy", lt.errorPolicy, ProjectTask.ErrorPolicy.RETRY);
            eq("loaded task maxRetries", lt.maxRetries, 2);

            ProjectTask lt2 = loaded.tasks.get(1);
            eq("loaded tree task kind", lt2.kind, ProjectTask.Kind.CLEAR_TREES);
            eq("loaded tree task treeZoneDiameter", lt2.treeZoneDiameter, 11);

            // Legacy JSON without treeZoneDiameter/showTreeZones and invalid <3 diameter clamp
            com.google.gson.Gson gson = new com.google.gson.Gson();
            Project legacyProj = gson.fromJson("{\"id\":\"leg\",\"survival\":{}}", Project.class);
            legacyProj.ensureInitialized();
            eq("legacy json treeZoneDiameter default", legacyProj.survival.treeZoneDiameter, 9);
            eq("legacy json showTreeZones default", legacyProj.survival.showTreeZones, true);

            Project clampedProj = gson.fromJson("{\"id\":\"clamp\",\"survival\":{\"treeZoneDiameter\":1}}", Project.class);
            clampedProj.ensureInitialized();
            eq("invalid <3 treeZoneDiameter resets to 9", clampedProj.survival.treeZoneDiameter, 9);

            check("delete active project", ProjectStore.delete(tmp, world, "canal_build"));
            eq("activeId cleared after deleting active project", ProjectStore.activeId(tmp, world), null);
            eq("list after delete", ProjectStore.list(tmp, world), List.of("alpha"));
            eq("load deleted returns null", ProjectStore.load(tmp, world, "canal_build"), null);
        } finally {
            deleteRecursively(tmp);
        }
    }

    // ------------------------------------------------------------------ 4. Capture import

    private static void testCaptureImport() {
        String capture = """
                // Manhattan capture sheet: world1, set "main".
                // --- places
                let bed = pos(10, 64, -5)   // bed: sleep() uses it; red_bed
                let dump_chest = pos(12, 64, -5)   // dump: dump() puts the inventory in here; chest
                let tool_chest = pos(14, 64, -5)   // supply: restock() takes tools and food from here; chest
                let return_chest = pos(16, 64, -5)   // return: worn-out tools go here; chest
                let scaffold_chest = pos(18, 64, -5)   // scaffolding: blocks to bridge and climb with; chest
                let dirt_chest = pos(20, 64, -5)   // material: blocks for fill_water / building; chest; holds: 1200 dirt
                let stone_chest = pos(22, 64, -5)// material:cobblestone
                let material_chest_2 = pos(24, 64, -5)
                // let ignored_chest = pos(99, 64, 99)   // material: disabled line

                // --- areas
                protect("base", pos(0, 60, -10), pos(30, 80, 10))   // safe: protected
                protect("base", pos(40, 70, 20), pos(35, 65, 15))
                // protect("disabled_zone", pos(1, 2, 3), pos(4, 5, 6))
                """;

        Project p = ProjectStore.createDefault("world1", "p1", "P1");
        ProjectStore.ImportResult res = ProjectStore.importFromCaptureText(p, capture);

        eq("capture places count", res.placesImported(), 5);
        eq("capture material chests count", res.materialChestsImported(), 3);
        eq("capture protected areas count", res.protectedAreasImported(), 2);

        eqArr("capture bed", p.places.bed, new int[]{10, 64, -5});
        eqArr("capture dumpChest", p.places.dumpChest, new int[]{12, 64, -5});
        eqArr("capture toolChest", p.places.toolChest, new int[]{14, 64, -5});
        eqArr("capture returnChest", p.places.returnChest, new int[]{16, 64, -5});
        eqArr("capture scaffoldChest", p.places.scaffoldChest, new int[]{18, 64, -5});
        eq("capture materialChests size", p.places.materialChests.size(), 3);
        eqArr("capture materialChest 0", p.places.materialChests.get(0), new int[]{20, 64, -5});
        eqArr("capture materialChest 1", p.places.materialChests.get(1), new int[]{22, 64, -5});
        eqArr("capture materialChest 2", p.places.materialChests.get(2), new int[]{24, 64, -5});

        List<int[]> baseBoxes = p.places.protectedAreas.get("base");
        check("capture base protected present", baseBoxes != null && baseBoxes.size() == 2);
        eqArr("capture box 1 normalized", baseBoxes.get(1), new int[]{35, 65, 15, 40, 70, 20});
        check("capture disabled zone ignored", !p.places.protectedAreas.containsKey("disabled_zone"));

        // Re-importing same text should deduplicate material chests and protected boxes
        ProjectStore.ImportResult res2 = ProjectStore.importFromCaptureText(p, capture);
        eq("re-import dedups material chests", res2.materialChestsImported(), 0);
        eq("re-import dedups protected boxes", res2.protectedAreasImported(), 0);
        eq("materialChests still 3", p.places.materialChests.size(), 3);
    }

    // ------------------------------------------------------------------ 5. TaskQueue

    private static void testTaskQueueLiveMutationsAndStableLedger() {
        Project p = ProjectStore.createDefault("w", "q", "Queue Test");
        TaskQueue.InMemoryTaskLedger ledger = new TaskQueue.InMemoryTaskLedger();
        TaskQueue q = new TaskQueue(p, ledger);

        ProjectTask a = q.add(new ProjectTask(null, "Task A", ProjectTask.Kind.CLEAR_AREA));
        ProjectTask b = q.add(new ProjectTask(null, "Task B", ProjectTask.Kind.FILL_WATER));
        ProjectTask c = q.add(new ProjectTask(null, "Task C", ProjectTask.Kind.SCHEM_BUILD));

        eq("generated id A", a.id, "t_001");
        eq("generated id B", b.id, "t_002");
        eq("generated id C", c.id, "t_003");
        eq("totalEnabledCount", q.totalEnabledCount(), 3);
        eq("doneCount initial", q.doneCount(), 0);
        eq("nextRunnable initially A", q.nextRunnableTask().id, "t_001");

        // Mark A done, then reorder A to the end and C to index 0
        ledger.set(a.id, TaskQueue.TaskLedger.State.DONE, 1, 0, null);
        check("move A to end", q.move(0, 2));      // [B, C, A]
        check("move C to front", q.move(1, 0));    // [C, B, A]

        eq("order after moves [0]", q.tasks().get(0).id, "t_003");
        eq("order after moves [1]", q.tasks().get(1).id, "t_002");
        eq("order after moves [2]", q.tasks().get(2).id, "t_001");
        eq("stable ledger: A still DONE after reorder", q.stateOf(a.id), TaskQueue.TaskLedger.State.DONE);
        eq("stable ledger: C still PENDING after reorder", q.stateOf(c.id), TaskQueue.TaskLedger.State.PENDING);
        eq("doneCount still 1", q.doneCount(), 1);
        eq("nextRunnable after reorder is C", q.nextRunnableTask().id, "t_003");

        // Skip C, verify B becomes next runnable, then retry C -> C becomes runnable again
        check("skip C", q.skip(c.id));
        eq("C state DEFERRED", q.stateOf(c.id), TaskQueue.TaskLedger.State.DEFERRED);
        eq("nextRunnable after skipping C is B", q.nextRunnableTask().id, "t_002");

        check("retry C", q.retry(c.id));
        eq("C state PENDING after retry", q.stateOf(c.id), TaskQueue.TaskLedger.State.PENDING);
        eq("nextRunnable after retrying C is C", q.nextRunnableTask().id, "t_003");

        // Insert disabled task at front -> ignored by nextRunnableTask and totalEnabledCount
        ProjectTask disabled = new ProjectTask(null, "Disabled", ProjectTask.Kind.MINE);
        disabled.enabled = false;
        q.insertAt(0, disabled);
        eq("disabled assigned t_004", disabled.id, "t_004");
        eq("totalEnabledCount excludes disabled", q.totalEnabledCount(), 3);
        eq("nextRunnable ignores disabled", q.nextRunnableTask().id, "t_003");

        // Remove B, reset all
        check("remove B", q.remove(b.id));
        eq("tasks size after remove", q.tasks().size(), 3);
        check("reset clears ledger", q.reset(null) >= 1);
        eq("A back to PENDING after reset", q.stateOf(a.id), TaskQueue.TaskLedger.State.PENDING);
        eq("doneCount after reset", q.doneCount(), 0);
    }

    // ------------------------------------------------------------------ 6. LogisticsPlanner

    private static void testLogisticsPlannerBatching() {
        Project p = ProjectStore.createDefault("w", "log", "Logistics");
        p.places.returnChest = new int[]{0, 64, 0};
        p.places.dumpChest = new int[]{2, 64, 0};
        p.places.toolChest = new int[]{4, 64, 0};
        p.places.materialChests.add(new int[]{6, 64, 0});
        p.survival.dumpFreeSlotsBelow = 2;
        p.survival.batchBaseTrips = true;

        FakeInventory healthy = new FakeInventory(10, false, false, Map.of("dirt", 64));
        LogisticsPlanner.BaseTripPlan emptyPlan = LogisticsPlanner.planBaseTrip(p, null, healthy, Map.of());
        check("healthy inventory -> empty plan", emptyPlan.isEmpty());

        // Trigger all 4 steps with batchBaseTrips = true
        FakeInventory needy = new FakeInventory(0, true, true, Map.of("dirt", 0));
        LogisticsPlanner.BaseTripPlan batched = LogisticsPlanner.planBaseTrip(p, null, needy, Map.of("dirt", 128, "sand", 64));
        eq("batched all 4 steps in canonical order", batched.steps(), List.of(
                LogisticsPlanner.StepKind.RETURN_WORN,
                LogisticsPlanner.StepKind.DUMP_EXCESS,
                LogisticsPlanner.StepKind.RESTOCK_TOOLS_FOOD,
                LogisticsPlanner.StepKind.FETCH_MATERIALS
        ));
        eq("batched materialsToFetch dirt", batched.materialsToFetch().get("dirt"), 128);
        eq("batched materialsToFetch sand", batched.materialsToFetch().get("sand"), 64);

        // With batchBaseTrips = false -> only single highest-priority step
        p.survival.batchBaseTrips = false;
        LogisticsPlanner.BaseTripPlan unbatched = LogisticsPlanner.planBaseTrip(p, null, needy, Map.of("dirt", 128));
        eq("unbatched returns only first step", unbatched.steps(), List.of(LogisticsPlanner.StepKind.RETURN_WORN));
        check("unbatched non-material step has empty materialsToFetch", unbatched.materialsToFetch().isEmpty());

        // ScopedOverrides suppress dump + restock, still allows material fetch
        p.survival.batchBaseTrips = true;
        ScopedOverrides ov = new ScopedOverrides();
        ov.suppressSurvival.add("dump");
        ov.suppressSurvival.add("restock");
        LogisticsPlanner.BaseTripPlan suppressed = LogisticsPlanner.planBaseTrip(p, ov, needy, Map.of("dirt", 64));
        eq("suppressed dump/restock leaves only FETCH_MATERIALS", suppressed.steps(),
                List.of(LogisticsPlanner.StepKind.FETCH_MATERIALS));

        // ScopedOverrides want triggers restock even when inv.restockNeeded() is false
        ScopedOverrides wantOv = new ScopedOverrides();
        wantOv.want.put("torch", 32);
        LogisticsPlanner.BaseTripPlan wantTrip = LogisticsPlanner.planBaseTrip(p, wantOv, healthy, Map.of());
        eq("override want triggers RESTOCK_TOOLS_FOOD", wantTrip.steps(),
                List.of(LogisticsPlanner.StepKind.RESTOCK_TOOLS_FOOD));

        // Exhausted missing wanted item (e.g. supply chest out of diamond_hoe) must NOT re-trigger base trip
        p.survival.want.put("diamond_hoe", 1);
        FakeInventory missingHoe = new FakeInventory(20, false, false, Map.of("dirt", 64), Set.of());
        eq("unexhausted missing diamond_hoe triggers RESTOCK_TOOLS_FOOD",
                LogisticsPlanner.planBaseTrip(p, null, missingHoe, Map.of()).steps(),
                List.of(LogisticsPlanner.StepKind.RESTOCK_TOOLS_FOOD));

        FakeInventory exhaustedHoe = new FakeInventory(20, false, false, Map.of("dirt", 64), Set.of("diamond_hoe"));
        check("exhausted diamond_hoe skips base trip",
                LogisticsPlanner.planBaseTrip(p, null, exhaustedHoe, Map.of()).isEmpty());

        // Half-stock rule: 31/32 or 16/32 food does NOT trigger restock, only < 50% (15/32) does
        p.survival.want.clear();
        p.survival.want.put("food", 32);
        FakeInventory almostFullFood = new FakeInventory(20, false, false, Map.of("food", 31), Set.of());
        check("31/32 food skips base trip",
                LogisticsPlanner.planBaseTrip(p, null, almostFullFood, Map.of()).isEmpty());

        FakeInventory halfFood = new FakeInventory(20, false, false, Map.of("food", 16), Set.of());
        check("16/32 food skips base trip",
                LogisticsPlanner.planBaseTrip(p, null, halfFood, Map.of()).isEmpty());

        FakeInventory lowFood = new FakeInventory(20, false, false, Map.of("food", 15), Set.of());
        eq("15/32 food triggers RESTOCK_TOOLS_FOOD",
                LogisticsPlanner.planBaseTrip(p, null, lowFood, Map.of()).steps(),
                List.of(LogisticsPlanner.StepKind.RESTOCK_TOOLS_FOOD));
    }

    // ------------------------------------------------------------------ 7. PriorityScheduler: Preemption Stack

    private static void testSchedulerPreemptionStack() {
        Project p = baseProjectWithPlaces();
        ProjectTask t1 = new ProjectTask("t_001", "Clear Pit", ProjectTask.Kind.CLEAR_AREA);
        p.tasks.add(t1);

        MockEnv env = new MockEnv();
        FakeHandle work = new FakeHandle("work", 3);
        FakeHandle eat = new FakeHandle("eat", 2);
        FakeHandle defend = new FakeHandle("defend", 2);
        FakeHandle emergency = new FakeHandle("emergency", 1);

        env.taskFactory = (t, ov) -> work;
        env.eatFactory = () -> eat;
        env.defendFactory = () -> defend;
        env.emergencyFactory = () -> emergency;

        PriorityScheduler sched = new PriorityScheduler(env);
        sched.start(p, new TaskQueue.InMemoryTaskLedger());

        // Tick 1: starts P4_WORK
        sched.tick();
        eq("preempt t1 lane P4", sched.currentLane(), PriorityScheduler.Lane.P4_WORK);
        eq("preempt t1 work ticks", work.ticks, 1);

        // Tick 2: food drops -> P2_VITALS preempts P4_WORK
        env.food = 10;
        sched.tick();
        eq("preempt t2 lane P2", sched.currentLane(), PriorityScheduler.Lane.P2_VITALS);
        eq("preempt t2 work suspended", work.suspendCount, 1);
        eq("preempt t2 eat ticks", eat.ticks, 1);

        // Tick 3: threat appears -> P1_COMBAT preempts P2_VITALS
        env.threats = 2;
        sched.tick();
        eq("preempt t3 lane P1", sched.currentLane(), PriorityScheduler.Lane.P1_COMBAT);
        eq("preempt t3 eat suspended", eat.suspendCount, 1);
        eq("preempt t3 defend ticks", defend.ticks, 1);

        // Tick 4: lava emergency -> P0_EMERGENCY preempts P1_COMBAT and completes in 1 tick
        env.emergency = true;
        sched.tick();
        eq("preempt t4 emergency finished", emergency.ticks, 1);
        eq("preempt t4 defend suspended then resumed", defend.suspendCount + ":" + defend.resumeCount, "1:1");
        eq("preempt t4 back to P1", sched.currentLane(), PriorityScheduler.Lane.P1_COMBAT);

        // Tick 5: emergency cleared; P1_COMBAT finishes tick 2/2 and resumes P2_VITALS
        env.emergency = false;
        env.threats = 0;
        sched.tick();
        eq("preempt t5 defend finished", defend.ticks, 2);
        eq("preempt t5 eat resumed", eat.resumeCount, 1);
        eq("preempt t5 back to P2", sched.currentLane(), PriorityScheduler.Lane.P2_VITALS);

        // Tick 6: food restored; P2_VITALS finishes tick 2/2 and resumes P4_WORK
        env.food = 20;
        sched.tick();
        eq("preempt t6 eat finished", eat.ticks, 2);
        eq("preempt t6 work resumed", work.resumeCount, 1);
        eq("preempt t6 back to P4", sched.currentLane(), PriorityScheduler.Lane.P4_WORK);

        // Ticks 7-8: P4_WORK finishes ticks 2 and 3
        sched.tick();
        sched.tick();
        eq("preempt work done ticks", work.ticks, 3);
        eq("preempt task marked DONE", sched.queue().stateOf("t_001"), TaskQueue.TaskLedger.State.DONE);

        // Tick 9: queue finishes
        sched.tick();
        check("preempt scheduler stopped on completion", !sched.isRunning());
        check("preempt onQueueComplete success", env.completed && env.completedSuccess);
    }

    // ------------------------------------------------------------------ 8. PriorityScheduler: P3 Logistics Preemption

    private static void testSchedulerLogisticsPreemption() {
        Project p = baseProjectWithPlaces();
        p.tasks.add(new ProjectTask("t_001", "Fill Lake", ProjectTask.Kind.FILL_WATER));

        MockEnv env = new MockEnv();
        FakeHandle work = new FakeHandle("work", 3);
        FakeHandle trip = new FakeHandle("trip", 1);

        env.taskFactory = (t, ov) -> work;
        List<LogisticsPlanner.BaseTripPlan> plans = new ArrayList<>();
        env.baseTripFactory = plan -> {
            plans.add(plan);
            return trip;
        };

        PriorityScheduler sched = new PriorityScheduler(env);
        sched.start(p, new TaskQueue.InMemoryTaskLedger());

        // Tick 1: work runs 1/3
        sched.tick();
        eq("logistics t1 work ticks", work.ticks, 1);

        // Tick 2: work reports material deficit AND inventory is full -> batched P3 trip preempts work
        work.deficit = Map.of("dirt", 128);
        env.inventory = new FakeInventory(0, true, false, Map.of());
        sched.tick();

        eq("logistics trip planned once", plans.size(), 1);
        eq("logistics batched steps", plans.get(0).steps(), List.of(
                LogisticsPlanner.StepKind.DUMP_EXCESS,
                LogisticsPlanner.StepKind.RESTOCK_TOOLS_FOOD,
                LogisticsPlanner.StepKind.FETCH_MATERIALS
        ));
        eq("logistics work suspended and resumed after 1-tick trip", work.suspendCount + ":" + work.resumeCount, "1:1");

        // Clear deficit/inventory after base trip
        work.deficit = Map.of();
        env.inventory = new FakeInventory(20, false, false, Map.of("dirt", 128));
        sched.tick();
        sched.tick();
        eq("logistics task completed after resume", sched.queue().stateOf("t_001"), TaskQueue.TaskLedger.State.DONE);
    }

    // ------------------------------------------------------------------ 9. Pre-dusk Gate

    private static void testSchedulerPreDuskGate() {
        Project p = baseProjectWithPlaces();
        p.survival.preDuskGate = true;
        p.survival.preDuskMarginTicks = 1200;
        p.tasks.add(new ProjectTask("t_001", "Dig", ProjectTask.Kind.CLEAR_AREA));

        MockEnv env = new MockEnv();
        env.ticksUntilDusk = 600; // within margin -> must sleep first!
        FakeHandle sleep = new FakeHandle("sleep", 2);
        FakeHandle work = new FakeHandle("work", 1);
        int[] workStarts = {0};
        env.sleepFactory = () -> sleep;
        env.taskFactory = (t, ov) -> {
            workStarts[0]++;
            return work;
        };

        PriorityScheduler sched = new PriorityScheduler(env);
        sched.start(p, new TaskQueue.InMemoryTaskLedger());

        // Tick 1: pre-dusk gate pushes P2 sleep instead of starting P4 work
        sched.tick();
        eq("pre-dusk t1 lane P2", sched.currentLane(), PriorityScheduler.Lane.P2_VITALS);
        eq("pre-dusk t1 work not started yet", workStarts[0], 0);

        // Tick 2: sleep finishes; morning arrives
        env.ticksUntilDusk = 12000;
        sched.tick();
        eq("pre-dusk t2 sleep done", sleep.ticks, 2);

        // Tick 3: now work starts and finishes in 1 tick
        sched.tick();
        eq("pre-dusk t3 work started", workStarts[0], 1);
        eq("pre-dusk t3 work DONE", sched.queue().stateOf("t_001"), TaskQueue.TaskLedger.State.DONE);

        // Test Nether / unknown (-1) does NOT trigger pre-dusk gate
        Project pNether = baseProjectWithPlaces();
        pNether.tasks.add(new ProjectTask("t_001", "Nether Dig", ProjectTask.Kind.CLEAR_AREA));
        MockEnv netherEnv = new MockEnv();
        netherEnv.ticksUntilDusk = -1;
        int[] netherSleeps = {0};
        netherEnv.sleepFactory = () -> {
            netherSleeps[0]++;
            return new FakeHandle("sleep", 1);
        };
        netherEnv.taskFactory = (t, ov) -> new FakeHandle("netherWork", 1);

        PriorityScheduler netherSched = new PriorityScheduler(netherEnv);
        netherSched.start(pNether, new TaskQueue.InMemoryTaskLedger());
        netherSched.tick();
        eq("nether ticksUntilDusk=-1 skips pre-dusk sleep", netherSleeps[0], 0);
        eq("nether task completed immediately", netherSched.queue().stateOf("t_001"), TaskQueue.TaskLedger.State.DONE);
    }

    // ------------------------------------------------------------------ 10. ScopedOverrides Apply/Restore

    private static void testSchedulerOverridesRestore() {
        Project p = baseProjectWithPlaces();
        ProjectTask t1 = new ProjectTask("t_001", "No-eat build", ProjectTask.Kind.SCHEM_BUILD);
        t1.overrides.allowBreak = false;
        t1.overrides.suppressSurvival.add("eat");
        ProjectTask t2 = new ProjectTask("t_002", "Normal clear", ProjectTask.Kind.CLEAR_AREA);
        p.tasks.add(t1);
        p.tasks.add(t2);

        MockEnv env = new MockEnv();
        env.food = 10; // below eatFoodBelow (18)
        int[] eatStarts = {0};
        env.eatFactory = () -> {
            eatStarts[0]++;
            return new FakeHandle("eat", 1);
        };
        FakeHandle h1 = new FakeHandle("h1", 2);
        FakeHandle h2 = new FakeHandle("h2", 2);
        env.taskFactory = (t, ov) -> t.id.equals("t_001") ? h1 : h2;

        // First set food=20 so t1 starts cleanly, then drop food while t1 runs to prove eat suppression
        env.food = 20;
        PriorityScheduler sched = new PriorityScheduler(env);
        sched.start(p, new TaskQueue.InMemoryTaskLedger());

        sched.tick(); // starts t1, applies overrides
        eq("overrides applied on t1 start", env.applyOverridesCount, 1);
        eq("overrides not restored yet", env.restoreOverridesCount, 0);
        eq("applied allowBreak", env.lastAppliedOverrides.allowBreak, Boolean.FALSE);

        // Drop food while t1 is active -> suppressed by t1.overrides!
        env.food = 10;
        sched.tick(); // t1 finishes tick 2/2 and restores overrides
        eq("eat suppressed during t1", eatStarts[0], 0);
        eq("overrides restored when t1 ends", env.restoreOverridesCount, 1);

        // Next tick: t1 overrides are gone, so eat NOW triggers before t2!
        sched.tick();
        eq("eat triggers once t1 overrides restored", eatStarts[0], 1);

        // Also test override restore on stop() while task is mid-flight
        Project pStop = baseProjectWithPlaces();
        ProjectTask ts = new ProjectTask("t_001", "Stop mid-flight", ProjectTask.Kind.CLEAR_AREA);
        ts.overrides.allowPlace = false;
        pStop.tasks.add(ts);
        MockEnv stopEnv = new MockEnv();
        FakeHandle longHandle = new FakeHandle("long", 10);
        stopEnv.taskFactory = (t, ov) -> longHandle;
        PriorityScheduler stopSched = new PriorityScheduler(stopEnv);
        stopSched.start(pStop, new TaskQueue.InMemoryTaskLedger());
        stopSched.tick();
        eq("stop-test applied", stopEnv.applyOverridesCount, 1);
        stopSched.stop("user stop");
        eq("stop-test restored", stopEnv.restoreOverridesCount, 1);
        check("stop-test cancelled handle", longHandle.cancelled);
    }

    // ------------------------------------------------------------------ 11. PartialPolicy

    private static void testSchedulerPartialPolicies() {
        // Case A: PartialPolicy.STOP -> fails task, stops queue, does NOT start task 2
        Project pStop = baseProjectWithPlaces();
        pStop.survival.logoffOnError = true;
        ProjectTask t1 = new ProjectTask("t_001", "Fill 1", ProjectTask.Kind.FILL_WATER);
        t1.partialPolicy = ProjectTask.PartialPolicy.STOP;
        t1.partialThreshold = 2;
        ProjectTask t2 = new ProjectTask("t_002", "Fill 2", ProjectTask.Kind.FILL_WATER);
        pStop.tasks.add(t1);
        pStop.tasks.add(t2);

        MockEnv envStop = new MockEnv();
        int[] started = {0};
        envStop.taskFactory = (t, ov) -> {
            started[0]++;
            FakeHandle h = new FakeHandle(t.id, 1);
            h.remainingWork = 5; // > threshold (2)
            return h;
        };

        PriorityScheduler schedStop = new PriorityScheduler(envStop);
        schedStop.start(pStop, new TaskQueue.InMemoryTaskLedger());
        schedStop.tick();
        check("PartialPolicy.STOP halts scheduler", !schedStop.isRunning());
        eq("PartialPolicy.STOP marks FAILED", schedStop.queue().stateOf("t_001"), TaskQueue.TaskLedger.State.FAILED);
        eq("PartialPolicy.STOP never starts t2", started[0], 1);
        check("PartialPolicy.STOP reports failure + logoffOnError",
                envStop.completed && !envStop.completedSuccess && envStop.completedLogoff);

        // Case B: PartialPolicy.WARN_CONTINUE -> logs warning, marks DONE, continues to task 2
        Project pWarn = baseProjectWithPlaces();
        ProjectTask w1 = new ProjectTask("t_001", "Warn Fill", ProjectTask.Kind.FILL_WATER);
        w1.partialPolicy = ProjectTask.PartialPolicy.WARN_CONTINUE;
        w1.partialThreshold = 0;
        ProjectTask w2 = new ProjectTask("t_002", "Next Fill", ProjectTask.Kind.FILL_WATER);
        pWarn.tasks.add(w1);
        pWarn.tasks.add(w2);

        MockEnv envWarn = new MockEnv();
        envWarn.taskFactory = (t, ov) -> {
            FakeHandle h = new FakeHandle(t.id, 1);
            h.remainingWork = t.id.equals("t_001") ? 4 : 0;
            return h;
        };

        PriorityScheduler schedWarn = new PriorityScheduler(envWarn);
        schedWarn.start(pWarn, new TaskQueue.InMemoryTaskLedger());
        schedWarn.tick(); // w1 finishes partial -> WARN_CONTINUE
        check("PartialPolicy.WARN_CONTINUE stays running", schedWarn.isRunning());
        eq("PartialPolicy.WARN_CONTINUE marks w1 DONE", schedWarn.queue().stateOf("t_001"), TaskQueue.TaskLedger.State.DONE);
        check("PartialPolicy.WARN_CONTINUE logged warning",
                envWarn.logs.stream().anyMatch(m -> m.contains("Warning:")));
        schedWarn.tick(); // w2 runs and completes
        eq("PartialPolicy.WARN_CONTINUE runs w2", schedWarn.queue().stateOf("t_002"), TaskQueue.TaskLedger.State.DONE);
        schedWarn.tick(); // finishes queue
        check("PartialPolicy.WARN_CONTINUE completes successfully", envWarn.completed && envWarn.completedSuccess);
    }

    // ------------------------------------------------------------------ 12. ErrorPolicy

    private static void testSchedulerErrorPolicies() {
        // Case A: ErrorPolicy.RETRY (maxRetries = 1) succeeds on 2nd attempt
        Project pRetry = baseProjectWithPlaces();
        ProjectTask r1 = new ProjectTask("t_001", "Flaky Task", ProjectTask.Kind.CLEAR_AREA);
        r1.errorPolicy = ProjectTask.ErrorPolicy.RETRY;
        r1.maxRetries = 1;
        pRetry.tasks.add(r1);

        MockEnv envRetry = new MockEnv();
        int[] attempts = {0};
        envRetry.taskFactory = (t, ov) -> {
            attempts[0]++;
            FakeHandle h = new FakeHandle("attempt-" + attempts[0], 1);
            if (attempts[0] == 1) h.error = "transient timeout";
            return h;
        };

        PriorityScheduler schedRetry = new PriorityScheduler(envRetry);
        schedRetry.start(pRetry, new TaskQueue.InMemoryTaskLedger());
        schedRetry.tick(); // attempt 1 fails -> stays PENDING for retry
        check("ErrorPolicy.RETRY still running after 1st failure", schedRetry.isRunning());
        eq("ErrorPolicy.RETRY state PENDING for retry", schedRetry.queue().stateOf("t_001"), TaskQueue.TaskLedger.State.PENDING);
        schedRetry.tick(); // attempt 2 succeeds
        eq("ErrorPolicy.RETRY total attempts", attempts[0], 2);
        eq("ErrorPolicy.RETRY state DONE after 2nd attempt", schedRetry.queue().stateOf("t_001"), TaskQueue.TaskLedger.State.DONE);

        // Case B: ErrorPolicy.RETRY exhausts retries -> FAILED & stops
        Project pExhaust = baseProjectWithPlaces();
        ProjectTask e1 = new ProjectTask("t_001", "Always Fails", ProjectTask.Kind.CLEAR_AREA);
        e1.errorPolicy = ProjectTask.ErrorPolicy.RETRY;
        e1.maxRetries = 1;
        pExhaust.tasks.add(e1);

        MockEnv envExhaust = new MockEnv();
        int[] exhaustAttempts = {0};
        envExhaust.taskFactory = (t, ov) -> {
            exhaustAttempts[0]++;
            FakeHandle h = new FakeHandle("fail-" + exhaustAttempts[0], 1);
            h.error = "hard failure";
            return h;
        };
        PriorityScheduler schedExhaust = new PriorityScheduler(envExhaust);
        schedExhaust.start(pExhaust, new TaskQueue.InMemoryTaskLedger());
        schedExhaust.tick(); // attempt 1 -> retry
        schedExhaust.tick(); // attempt 2 -> exhausted
        eq("ErrorPolicy.RETRY exhausted attempts", exhaustAttempts[0], 2);
        check("ErrorPolicy.RETRY exhausted stops scheduler", !schedExhaust.isRunning());
        eq("ErrorPolicy.RETRY exhausted marks FAILED", schedExhaust.queue().stateOf("t_001"), TaskQueue.TaskLedger.State.FAILED);

        // Case C: ErrorPolicy.SKIP -> marks DEFERRED and runs next task
        Project pSkip = baseProjectWithPlaces();
        ProjectTask s1 = new ProjectTask("t_001", "Skippable", ProjectTask.Kind.CLEAR_AREA);
        s1.errorPolicy = ProjectTask.ErrorPolicy.SKIP;
        ProjectTask s2 = new ProjectTask("t_002", "Followup", ProjectTask.Kind.CLEAR_AREA);
        pSkip.tasks.add(s1);
        pSkip.tasks.add(s2);

        MockEnv envSkip = new MockEnv();
        envSkip.taskFactory = (t, ov) -> {
            FakeHandle h = new FakeHandle(t.id, 1);
            if (t.id.equals("t_001")) h.error = "missing area";
            return h;
        };
        PriorityScheduler schedSkip = new PriorityScheduler(envSkip);
        schedSkip.start(pSkip, new TaskQueue.InMemoryTaskLedger());
        schedSkip.tick(); // s1 errors -> skipped
        eq("ErrorPolicy.SKIP marks DEFERRED", schedSkip.queue().stateOf("t_001"), TaskQueue.TaskLedger.State.DEFERRED);
        check("ErrorPolicy.SKIP stays running", schedSkip.isRunning());
        schedSkip.tick(); // s2 runs and succeeds
        eq("ErrorPolicy.SKIP completes s2", schedSkip.queue().stateOf("t_002"), TaskQueue.TaskLedger.State.DONE);
        schedSkip.tick(); // finishes queue
        check("ErrorPolicy.SKIP queue completes cleanly", envSkip.completed && envSkip.completedSuccess);
    }

    // ------------------------------------------------------------------ 13. Live Controls & Telemetry

    private static void testSchedulerLiveControlsAndTelemetry() {
        Project p = baseProjectWithPlaces();
        ProjectTask t1 = new ProjectTask("t_001", "Long Clear", ProjectTask.Kind.CLEAR_AREA);
        t1.overrides.allowBreak = true;
        ProjectTask t2 = new ProjectTask("t_002", "Second Task", ProjectTask.Kind.FILL_WATER);
        p.tasks.add(t1);
        p.tasks.add(t2);

        MockEnv env = new MockEnv();
        FakeHandle h1 = new FakeHandle("h1", 5);
        FakeHandle h2 = new FakeHandle("h2", 1);
        env.taskFactory = (t, ov) -> t.id.equals("t_001") ? h1 : h2;

        PriorityScheduler sched = new PriorityScheduler(env);
        sched.start(p, new TaskQueue.InMemoryTaskLedger());
        sched.tick();

        PriorityScheduler.Telemetry tel = sched.telemetry();
        check("telemetry running", tel.running() && !tel.paused());
        eq("telemetry lane", tel.lane(), PriorityScheduler.Lane.P4_WORK);
        eq("telemetry taskId", tel.taskId(), "t_001");
        eq("telemetry taskIndex", tel.taskIndex(), 0);
        eq("telemetry totalTasks", tel.totalTasks(), 2);
        eq("telemetry doneTasks", tel.doneTasks(), 0);

        // Pause & resume
        sched.pause();
        check("paused state", sched.isPaused());
        eq("pause suspends handle", h1.suspendCount, 1);
        sched.tick(); // must not tick while paused
        eq("no tick while paused", h1.ticks, 1);
        sched.resume();
        eq("resume resumes handle", h1.resumeCount, 1);

        // skipCurrent cancels h1, restores overrides, skips t1, advances to t2
        check("skipCurrent returns true", sched.skipCurrent());
        check("skipCurrent cancelled h1", h1.cancelled);
        eq("skipCurrent restored overrides", env.restoreOverridesCount, 1);
        eq("skipCurrent marked t1 DEFERRED", sched.queue().stateOf("t_001"), TaskQueue.TaskLedger.State.DEFERRED);

        // Live enqueue t3 while running
        ProjectTask t3 = sched.enqueue(new ProjectTask(null, "Live Added", ProjectTask.Kind.DRAIN));
        eq("live enqueue id", t3.id, "t_003");
        eq("telemetry totalTasks updated", sched.telemetry().totalTasks(), 3);

        sched.tick(); // runs t2 (1 tick -> DONE)
        eq("t2 completed after skipCurrent", sched.queue().stateOf("t_002"), TaskQueue.TaskLedger.State.DONE);
    }

    private static void testSchedulerSuspendsBeforeStartingPreemption() {
        List<String> events = new ArrayList<>();
        Project p = baseProjectWithPlaces();
        p.tasks.add(new ProjectTask("t_001", "Work", ProjectTask.Kind.CLEAR_TREES));

        MockEnv env = new MockEnv();
        FakeHandle work = new FakeHandle("work", 10) {
            @Override public void suspend() { super.suspend(); events.add("work:suspend"); }
            @Override public void resume() { super.resume(); events.add("work:resume"); }
        };
        FakeHandle sleep = new FakeHandle("sleep", 5) {
            @Override public void suspend() { super.suspend(); events.add("sleep:suspend"); }
            @Override public void resume() { super.resume(); events.add("sleep:resume"); }
        };
        FakeHandle defend = new FakeHandle("defend", 2);
        env.taskFactory = (t, ov) -> work;
        env.sleepFactory = () -> { events.add("env:startSleep"); return sleep; };
        env.defendFactory = () -> { events.add("env:startDefend"); return defend; };

        PriorityScheduler sched = new PriorityScheduler(env);
        sched.start(p, new TaskQueue.InMemoryTaskLedger());
        sched.tick(); // starts P4_WORK

        // Preempt P4_WORK with P2_VITALS (sleep): work must suspend BEFORE startSleep() runs
        env.bedTime = true;
        sched.tick();
        eq("work suspended before startSleep", events.subList(0, 2), List.of("work:suspend", "env:startSleep"));

        // Preempt P2_VITALS (sleep) with P1_COMBAT (defend): sleep must suspend BEFORE startDefend() runs
        events.clear();
        env.threats = 1;
        sched.tick();
        eq("sleep suspended before startDefend", events.subList(0, 2), List.of("sleep:suspend", "env:startDefend"));

        // If canStartBaseTrip() is false, P4_WORK must NOT be suspended
        Project p2 = baseProjectWithPlaces();
        p2.tasks.add(new ProjectTask("t_001", "Work", ProjectTask.Kind.CLEAR_AREA));
        List<String> events2 = new ArrayList<>();
        MockEnv env2 = new MockEnv();
        env2.inventory = new FakeInventory(0, true, false, Map.of());
        env2.canStartBaseTrip = false;
        FakeHandle work2 = new FakeHandle("work2", 5) {
            @Override public void suspend() { super.suspend(); events2.add("work2:suspend"); }
            @Override public void resume() { super.resume(); events2.add("work2:resume"); }
        };
        env2.taskFactory = (t, ov) -> work2;
        PriorityScheduler sched2 = new PriorityScheduler(env2);
        sched2.start(p2, new TaskQueue.InMemoryTaskLedger());
        sched2.tick();
        eq("canStartBaseTrip=false leaves work unsuspended", work2.suspendCount, 0);

        // If preemption starter returns null after suspend, work is resumed immediately
        env2.canStartBaseTrip = true;
        env2.baseTripFactory = plan -> null;
        sched2.tick();
        eq("null preemption starter suspends and immediately resumes work",
                events2, List.of("work2:suspend", "work2:resume"));
        eq("lane stays P4_WORK after null starter", sched2.currentLane(), PriorityScheduler.Lane.P4_WORK);
    }

    // ------------------------------------------------------------------ Helpers & Mocks

    private static Project baseProjectWithPlaces() {
        Project p = ProjectStore.createDefault("test_world", "test_proj", "Test Project");
        p.places.bed = new int[]{0, 64, 0};
        p.places.toolChest = new int[]{2, 64, 0};
        p.places.returnChest = new int[]{4, 64, 0};
        p.places.dumpChest = new int[]{6, 64, 0};
        p.places.materialChests.add(new int[]{8, 64, 0});
        return p;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private static final class FakeInventory implements LogisticsPlanner.InventoryView {
        final int freeSlots;
        final boolean restockNeeded;
        final boolean wornTools;
        final Map<String, Integer> counts;
        final Set<String> exhausted;

        FakeInventory(int freeSlots, boolean restockNeeded, boolean wornTools, Map<String, Integer> counts) {
            this(freeSlots, restockNeeded, wornTools, counts, Set.of());
        }

        FakeInventory(
                int freeSlots,
                boolean restockNeeded,
                boolean wornTools,
                Map<String, Integer> counts,
                Set<String> exhausted
        ) {
            this.freeSlots = freeSlots;
            this.restockNeeded = restockNeeded;
            this.wornTools = wornTools;
            this.counts = new LinkedHashMap<>(counts);
            this.exhausted = Set.copyOf(exhausted);
        }

        @Override public int freeSlots() { return freeSlots; }
        @Override public int count(String item) { return counts.getOrDefault(item, 0); }
        @Override public boolean restockNeeded() { return restockNeeded; }
        @Override public boolean hasWornTools() { return wornTools; }
        @Override public boolean isRestockExhausted(String item) { return exhausted.contains(item); }
    }

    private static class FakeHandle implements PriorityScheduler.ActionHandle {
        final String name;
        final int ticksToFinish;
        int ticks;
        int suspendCount;
        int resumeCount;
        boolean cancelled;
        String error;
        int remainingWork;
        Map<String, Integer> deficit = Map.of();

        FakeHandle(String name, int ticksToFinish) {
            this.name = name;
            this.ticksToFinish = ticksToFinish;
        }

        @Override public boolean isDone() { return cancelled || ticks >= ticksToFinish; }
        @Override public String error() { return error; }
        @Override public int remainingWork() { return remainingWork; }
        @Override public void cancel() { cancelled = true; }
        @Override public void suspend() { suspendCount++; }
        @Override public void resume() { resumeCount++; }
        @Override public void tick() { ticks++; }
        @Override public Map<String, Integer> materialDeficit() { return deficit; }
    }

    private static final class MockEnv implements PriorityScheduler.Env {
        boolean recovering = false;
        boolean emergency = false;
        int threats = 0;
        double health = 20.0;
        int food = 20;
        boolean bedTime = false;
        int ticksUntilDusk = 10000;
        boolean canStartBaseTrip = true;
        LogisticsPlanner.InventoryView inventory = new FakeInventory(20, false, false, Map.of());

        java.util.function.Supplier<PriorityScheduler.ActionHandle> emergencyFactory = () -> new FakeHandle("emergency", 1);
        java.util.function.Supplier<PriorityScheduler.ActionHandle> defendFactory = () -> new FakeHandle("defend", 1);
        java.util.function.Supplier<PriorityScheduler.ActionHandle> eatFactory = () -> new FakeHandle("eat", 1);
        java.util.function.Supplier<PriorityScheduler.ActionHandle> sleepFactory = () -> new FakeHandle("sleep", 1);
        Function<LogisticsPlanner.BaseTripPlan, PriorityScheduler.ActionHandle> baseTripFactory = plan -> new FakeHandle("trip", 1);
        BiFunction<ProjectTask, ScopedOverrides, PriorityScheduler.ActionHandle> taskFactory = (t, ov) -> new FakeHandle(t.id, 1);

        int applyOverridesCount = 0;
        int restoreOverridesCount = 0;
        ScopedOverrides lastAppliedOverrides = null;
        final List<String> logs = new ArrayList<>();
        boolean completed = false;
        boolean completedSuccess = false;
        String completedSummary = null;
        boolean completedLogoff = false;

        @Override public boolean isRecovering() { return recovering; }
        @Override public boolean isEmergencyNeeded() { return emergency; }
        @Override public int threats(double range) { return threats; }
        @Override public double health() { return health; }
        @Override public int food() { return food; }
        @Override public boolean isBedTime() { return bedTime; }
        @Override public int ticksUntilDusk() { return ticksUntilDusk; }
        @Override public LogisticsPlanner.InventoryView inventoryView() { return inventory; }
        @Override public boolean canStartBaseTrip() { return canStartBaseTrip; }

        @Override public PriorityScheduler.ActionHandle startEmergency() { return emergencyFactory.get(); }
        @Override public PriorityScheduler.ActionHandle startDefend() { return defendFactory.get(); }
        @Override public PriorityScheduler.ActionHandle startEat() { return eatFactory.get(); }
        @Override public PriorityScheduler.ActionHandle startSleep() { return sleepFactory.get(); }
        @Override public PriorityScheduler.ActionHandle startBaseTrip(LogisticsPlanner.BaseTripPlan plan) { return baseTripFactory.apply(plan); }
        @Override public PriorityScheduler.ActionHandle startTask(ProjectTask task, ScopedOverrides overrides) { return taskFactory.apply(task, overrides); }

        @Override
        public void applyOverrides(ScopedOverrides overrides) {
            applyOverridesCount++;
            lastAppliedOverrides = overrides;
        }

        @Override
        public void restoreOverrides() {
            restoreOverridesCount++;
        }

        @Override
        public void onLog(String message) {
            logs.add(message);
        }

        @Override
        public void onQueueComplete(boolean success, String summary, boolean logoff) {
            completed = true;
            completedSuccess = success;
            completedSummary = summary;
            completedLogoff = logoff;
        }
    }

    // ------------------------------------------------------------------ 15. ProjectQueue & Layer Scheduler

    private static void testProjectQueueStoreAndOperations() throws IOException {
        ProjectQueue q = new ProjectQueue();
        check("add alpha", q.add("Alpha Build"));
        check("add beta", q.add("beta"));
        check("duplicate add rejected", !q.add("alpha_build"));
        check("insertAt 0 gamma", q.insertAt(0, "gamma"));

        eq("layer order [0]", q.snapshot().get(0).projectId, "gamma");
        eq("layer order [1]", q.snapshot().get(1).projectId, "alpha_build");
        eq("layer order [2]", q.snapshot().get(2).projectId, "beta");

        check("moveToTop beta", q.moveToTop("beta"));
        eq("beta now top", q.indexOf("beta"), 0);
        check("move 0 -> 2", q.move(0, 2));
        eq("beta now bottom", q.indexOf("beta"), 2);
        check("setEnabled alpha false", q.setEnabled("alpha_build", false));
        eq("enabledCount is 2", q.enabledCount(), 2);

        q.stopOnProjectError = false;
        q.logoffWhenAllDone = true;

        Path tmp = Files.createTempDirectory("manhattan-project-queue-test-");
        try {
            String world = "TestWorld";
            ProjectStore.save(tmp, ProjectStore.createDefault(world, "gamma", "Gamma"));
            ProjectStore.save(tmp, ProjectStore.createDefault(world, "alpha_build", "Alpha Build"));
            ProjectStore.save(tmp, ProjectStore.createDefault(world, "beta", "Beta"));
            ProjectStore.saveQueue(tmp, world, q);

            // _queue.json must NOT show up in ProjectStore.list()
            eq("list excludes _queue.json", ProjectStore.list(tmp, world), List.of("alpha_build", "beta", "gamma"));

            ProjectQueue loaded = ProjectStore.loadQueue(tmp, world);
            eq("queue round-trip equals", loaded, q);
            eq("loaded stopOnProjectError", loaded.stopOnProjectError, false);
            eq("loaded logoffWhenAllDone", loaded.logoffWhenAllDone, true);

            // Deleting a project removes it from _queue.json automatically
            check("delete gamma", ProjectStore.delete(tmp, world, "gamma"));
            ProjectQueue afterDel = ProjectStore.loadQueue(tmp, world);
            check("gamma removed from queue on delete", !afterDel.contains("gamma"));
            eq("remaining queue size", afterDel.snapshot().size(), 2);
        } finally {
            deleteRecursively(tmp);
        }
    }

    private static final class MockLayerHost implements ProjectLayerScheduler.Host {
        final Map<String, Project> projects = new LinkedHashMap<>();
        final Map<String, TaskQueue.InMemoryTaskLedger> ledgers = new LinkedHashMap<>();
        final List<String> events = new ArrayList<>();
        boolean allComplete;
        boolean allSuccess;
        String allSummary;
        boolean allLogoff;

        Project addProject(String id, String name, int taskCount) {
            Project p = ProjectStore.createDefault("w", id, name);
            TaskQueue.InMemoryTaskLedger ledger = new TaskQueue.InMemoryTaskLedger();
            TaskQueue tq = new TaskQueue(p, ledger);
            for (int i = 1; i <= taskCount; i++) {
                tq.add(new ProjectTask(null, id + "_task_" + i, ProjectTask.Kind.CLEAR_AREA));
            }
            projects.put(p.id, p);
            ledgers.put(p.id, ledger);
            return p;
        }

        @Override public Project loadProject(String projectId) { return projects.get(projectId); }
        @Override public TaskQueue.TaskLedger loadLedger(String projectId) { return ledgers.get(projectId); }
        @Override public void activateProject(Project project) { events.add("ACT:" + project.id); }
        @Override public void deactivateProject(Project project) { events.add("DEACT:" + project.id); }
        @Override public void onLog(String message) { events.add("LOG:" + message); }

        @Override
        public void onAllComplete(boolean success, String summary, boolean logoff) {
            allComplete = true;
            allSuccess = success;
            allSummary = summary;
            allLogoff = logoff;
        }
    }

    private static void testProjectLayerSequentialExecution() {
        MockEnv env = new MockEnv();
        MockLayerHost host = new MockLayerHost();
        Project a = host.addProject("a", "Project A", 1);
        a.survival.logoffOnDone = true; // should NOT log off before layer C finishes
        host.addProject("b", "Project B", 1);
        host.addProject("c", "Project C", 1);

        ProjectQueue pq = new ProjectQueue();
        pq.add("a");
        pq.add("b");
        pq.add("c");
        pq.setEnabled("b", false);
        pq.logoffWhenAllDone = true;

        List<String> startedTasks = new ArrayList<>();
        env.taskFactory = (t, ov) -> {
            startedTasks.add(t.label);
            return new FakeHandle(t.label, 2);
        };

        ProjectLayerScheduler ls = new ProjectLayerScheduler(env, host);
        eq("start layer queue succeeds", ls.start(pq), null);
        check("layer queue running", ls.isRunning());
        eq("initial layer is A", ls.currentProjectId(), "a");
        eq("status A is RUNNING", ls.layerStatus(pq.find("a")), ProjectLayerScheduler.LayerStatus.RUNNING);
        eq("status B is DISABLED", ls.layerStatus(pq.find("b")), ProjectLayerScheduler.LayerStatus.DISABLED);
        eq("status C is QUEUED", ls.layerStatus(pq.find("c")), ProjectLayerScheduler.LayerStatus.QUEUED);

        for (int i = 0; i < 20 && ls.isRunning(); i++) {
            ls.tick();
        }

        check("layer queue finished", !ls.isRunning());
        check("allComplete fired", host.allComplete);
        check("allSuccess true", host.allSuccess);
        check("logoffWhenAllDone respected at end", host.allLogoff);
        eq("started tasks skipped disabled B", startedTasks, List.of("a_task_1", "c_task_1"));
        eq("status A is DONE", ls.layerStatus(pq.find("a")), ProjectLayerScheduler.LayerStatus.DONE);
        eq("status C is DONE", ls.layerStatus(pq.find("c")), ProjectLayerScheduler.LayerStatus.DONE);

        List<String> actDeact = host.events.stream().filter(s -> s.startsWith("ACT:") || s.startsWith("DEACT:")).toList();
        eq("activation lifecycle A then C", actDeact, List.of("ACT:a", "DEACT:a", "ACT:c", "DEACT:c"));
    }

    private static void testProjectLayerLivePreemptionAndResume() {
        MockEnv env = new MockEnv();
        MockLayerHost host = new MockLayerHost();
        host.addProject("b", "Project B", 1);
        host.addProject("a", "Project A", 1);

        ProjectQueue pq = new ProjectQueue();
        pq.add("b");
        pq.add("a");

        List<String> startedOrder = new ArrayList<>();
        env.taskFactory = (t, ov) -> {
            startedOrder.add(t.label);
            return new FakeHandle(t.label, 3);
        };

        ProjectLayerScheduler ls = new ProjectLayerScheduler(env, host);
        eq("start preemption test", ls.start(pq), null);
        ls.tick(); // starts b_task_1 (tick 1 of 3)
        eq("running B before reorder", ls.currentProjectId(), "b");

        // User drags/promotes A to Layer 1 above B!
        check("promote A to top", pq.moveToTop("a"));
        ls.tick(); // B yields, A activates & runs tick 1 of 3
        eq("preempted to A", ls.currentProjectId(), "a");
        // B's task must still be RUNNING in its ledger (not FAILED or DONE) so it resumes after A finishes
        eq("B ledger preserved as RUNNING",
                host.ledgers.get("b").get("t_001").state(), TaskQueue.TaskLedger.State.RUNNING);

        for (int i = 0; i < 20 && ls.isRunning(); i++) {
            ls.tick();
        }

        check("all completed after preemption", host.allComplete && host.allSuccess);
        eq("B started, A preempted & finished, B resumed & finished",
                startedOrder, List.of("b_task_1", "a_task_1", "b_task_1"));
        eq("A ledger DONE", host.ledgers.get("a").get("t_001").state(), TaskQueue.TaskLedger.State.DONE);
        eq("B ledger DONE", host.ledgers.get("b").get("t_001").state(), TaskQueue.TaskLedger.State.DONE);
    }

    private static void testProjectLayerPreemptionWaitsForCombat() {
        MockEnv env = new MockEnv();
        MockLayerHost host = new MockLayerHost();
        host.addProject("b", "Project B", 1);
        host.addProject("a", "Project A", 1);

        ProjectQueue pq = new ProjectQueue();
        pq.add("b");
        pq.add("a");

        env.taskFactory = (t, ov) -> new FakeHandle(t.label, 5);
        env.defendFactory = () -> new FakeHandle("defend", 2);

        ProjectLayerScheduler ls = new ProjectLayerScheduler(env, host);
        eq("start combat safety test", ls.start(pq), null);
        ls.tick(); // starts b_task_1

        // Threat appears -> P1_COMBAT preempts B's work
        env.threats = 1;
        ls.tick(); // starts combat (tick 1 of 2)
        eq("lane is P1_COMBAT", ls.scheduler().currentLane(), PriorityScheduler.Lane.P1_COMBAT);

        // User promotes A to Layer 1 while bot is fighting!
        pq.moveToTop("a");
        env.threats = 0;
        ls.tick(); // combat tick 2 of 2 completes; MUST NOT have switched away mid-combat
        eq("did not switch mid-combat", ls.currentProjectId(), "b");

        // Next tick: lane is back to P4_WORK -> now safe to yield B and switch to A!
        ls.tick();
        eq("switched to A after combat ended", ls.currentProjectId(), "a");
    }

    private static void testProjectLayerErrorPolicies() {
        // 1. stopOnProjectError = true (default)
        {
            MockEnv env = new MockEnv();
            MockLayerHost host = new MockLayerHost();
            host.addProject("a", "Project A", 1);
            host.addProject("b", "Project B", 1);

            ProjectQueue pq = new ProjectQueue();
            pq.add("a");
            pq.add("b");
            pq.stopOnProjectError = true;

            List<String> started = new ArrayList<>();
            env.taskFactory = (t, ov) -> {
                started.add(t.label);
                FakeHandle h = new FakeHandle(t.label, 1);
                if (t.label.startsWith("a_")) h.error = "boom";
                return h;
            };

            ProjectLayerScheduler ls = new ProjectLayerScheduler(env, host);
            eq("start stopOnError", ls.start(pq), null);
            for (int i = 0; i < 10 && ls.isRunning(); i++) ls.tick();

            check("stopped on error", !ls.isRunning() && host.allComplete && !host.allSuccess);
            eq("B never started when stopOnProjectError=true", started, List.of("a_task_1"));
        }

        // 2. stopOnProjectError = false -> continues to B
        {
            MockEnv env = new MockEnv();
            MockLayerHost host = new MockLayerHost();
            host.addProject("a", "Project A", 1);
            host.addProject("b", "Project B", 1);

            ProjectQueue pq = new ProjectQueue();
            pq.add("a");
            pq.add("b");
            pq.stopOnProjectError = false;

            List<String> started = new ArrayList<>();
            env.taskFactory = (t, ov) -> {
                started.add(t.label);
                FakeHandle h = new FakeHandle(t.label, 1);
                if (t.label.startsWith("a_")) h.error = "boom";
                return h;
            };

            ProjectLayerScheduler ls = new ProjectLayerScheduler(env, host);
            eq("start continueOnError", ls.start(pq), null);
            for (int i = 0; i < 15 && ls.isRunning(); i++) ls.tick();

            check("finished queue after continuing", !ls.isRunning() && host.allComplete);
            check("overall success false because A failed", !host.allSuccess);
            eq("B ran after A failed", started, List.of("a_task_1", "b_task_1"));
            eq("A status FAILED", ls.layerStatus(pq.find("a")), ProjectLayerScheduler.LayerStatus.FAILED);
            eq("B status DONE", ls.layerStatus(pq.find("b")), ProjectLayerScheduler.LayerStatus.DONE);
        }
    }
}
