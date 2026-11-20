package dev.baritonequeue.project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java planner for base logistics trips. Combines worn-tool return, excess inventory dumping,
 * tool/food restocking, and material fetching into a single ordered base trip when
 * {@link Project.SurvivalPolicy#batchBaseTrips} is enabled. Zero Minecraft imports.
 */
public final class LogisticsPlanner {
    private LogisticsPlanner() {
    }

    /** Pure-Java read-only view of player inventory state for logistics planning. */
    public interface InventoryView {
        int freeSlots();

        int count(String item);

        boolean restockNeeded();

        boolean hasWornTools();

        /** True when the supply chest recently ran short on {@code item} and is in backoff. */
        default boolean isRestockExhausted(String item) {
            return false;
        }
    }

    /** Ordered steps that can be performed during a single base trip. */
    public enum StepKind {
        RETURN_WORN,
        DUMP_EXCESS,
        RESTOCK_TOOLS_FOOD,
        FETCH_MATERIALS
    }

    /** Immutable base-trip plan with ordered steps and requested material quantities. */
    public static final class BaseTripPlan {
        private static final BaseTripPlan EMPTY = new BaseTripPlan(List.of(), Map.of());

        private final List<StepKind> steps;
        private final Map<String, Integer> materialsToFetch;

        public BaseTripPlan(List<StepKind> steps, Map<String, Integer> materialsToFetch) {
            this.steps = steps == null || steps.isEmpty()
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(steps));
            this.materialsToFetch = materialsToFetch == null || materialsToFetch.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(materialsToFetch));
        }

        public static BaseTripPlan empty() {
            return EMPTY;
        }

        public List<StepKind> steps() {
            return steps;
        }

        public Map<String, Integer> materialsToFetch() {
            return materialsToFetch;
        }

        public boolean isEmpty() {
            return steps.isEmpty();
        }

        @Override
        public String toString() {
            return "BaseTripPlan{steps=" + steps + ", materialsToFetch=" + materialsToFetch + '}';
        }
    }

    /**
     * Evaluates whether a base logistics trip is needed and returns an ordered {@link BaseTripPlan}.
     * When {@link Project.SurvivalPolicy#batchBaseTrips} is true, all triggered steps are combined into
     * one ordered trip ({@code RETURN_WORN -> DUMP_EXCESS -> RESTOCK_TOOLS_FOOD -> FETCH_MATERIALS}).
     * When {@code batchBaseTrips} is false, only the single highest-priority triggered step is returned.
     * Returns an empty plan if nothing is needed or required chests are unconfigured.
     */
    public static BaseTripPlan planBaseTrip(
            Project project,
            ScopedOverrides activeOverrides,
            InventoryView inv,
            Map<String, Integer> materialDeficit
    ) {
        if (project == null || inv == null) return BaseTripPlan.empty();
        project.ensureInitialized();

        Project.SurvivalPolicy survival = project.survival;
        Project.BasePlaces places = project.places;

        boolean suppressLogistics = activeOverrides != null && activeOverrides.isSuppressed("logistics");
        boolean suppressDump = suppressLogistics || (activeOverrides != null && activeOverrides.isSuppressed("dump"));
        boolean suppressRestock = suppressLogistics || (activeOverrides != null && activeOverrides.isSuppressed("restock"));
        boolean suppressReturn = suppressRestock || (activeOverrides != null && activeOverrides.isSuppressed("return"));

        boolean needReturn = survival.restockEnabled
                && !suppressReturn
                && survival.itemSaver
                && inv.hasWornTools()
                && (places.returnChest != null || places.toolChest != null);

        boolean needDump = survival.dumpEnabled
                && !suppressDump
                && places.dumpChest != null
                && inv.freeSlots() < survival.dumpFreeSlotsBelow;

        boolean needRestock = survival.restockEnabled
                && !suppressRestock
                && places.toolChest != null
                && (inv.restockNeeded() || hasUnexhaustedWantDeficit(survival, activeOverrides, inv));

        Map<String, Integer> positiveDeficit = filterPositive(materialDeficit);
        boolean needMaterials = !positiveDeficit.isEmpty()
                && places.materialChests != null
                && !places.materialChests.isEmpty();

        if (!needReturn && !needDump && !needRestock && !needMaterials) {
            return BaseTripPlan.empty();
        }

        List<StepKind> steps = new ArrayList<>(4);
        Map<String, Integer> fetchMap = new LinkedHashMap<>();

        if (survival.batchBaseTrips) {
            if (needReturn) steps.add(StepKind.RETURN_WORN);
            if (needDump) steps.add(StepKind.DUMP_EXCESS);
            if (needRestock) steps.add(StepKind.RESTOCK_TOOLS_FOOD);
            if (needMaterials) {
                steps.add(StepKind.FETCH_MATERIALS);
                fetchMap.putAll(positiveDeficit);
            }
        } else {
            if (needReturn) {
                steps.add(StepKind.RETURN_WORN);
            } else if (needDump) {
                steps.add(StepKind.DUMP_EXCESS);
            } else if (needRestock) {
                steps.add(StepKind.RESTOCK_TOOLS_FOOD);
            } else {
                steps.add(StepKind.FETCH_MATERIALS);
                fetchMap.putAll(positiveDeficit);
            }
        }

        return new BaseTripPlan(steps, fetchMap);
    }

    /**
     * Returns true when any effective wanted rule is unexhausted and below half its target quantity.
     */
    public static boolean hasUnexhaustedWantDeficit(
            Project.SurvivalPolicy survival,
            ScopedOverrides activeOverrides,
            InventoryView inv
    ) {
        if (survival == null || inv == null) return false;
        Map<String, Integer> effectiveWant = new LinkedHashMap<>();
        if (survival.want != null) effectiveWant.putAll(survival.want);
        if (activeOverrides != null && activeOverrides.want != null) {
            effectiveWant.putAll(activeOverrides.want);
        }
        for (Map.Entry<String, Integer> e : effectiveWant.entrySet()) {
            String item = e.getKey();
            Integer needed = e.getValue();
            if (item == null || item.isBlank() || needed == null || needed <= 0) continue;
            if (inv.isRestockExhausted(item)) continue;
            if (inv.count(item) * 2 < needed) return true;
        }
        return false;
    }

    private static Map<String, Integer> filterPositive(Map<String, Integer> deficit) {
        if (deficit == null || deficit.isEmpty()) return Map.of();
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : deficit.entrySet()) {
            String item = e.getKey();
            Integer qty = e.getValue();
            if (item != null && !item.isBlank() && qty != null && qty > 0) {
                out.put(item, qty);
            }
        }
        return out;
    }
}
