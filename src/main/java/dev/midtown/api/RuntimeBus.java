package dev.midtown.api;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Typed in-process runtime state bus replacing stringly-typed {@code System.setProperty} IPC
 * ({@code baritonequeue.*}, {@code survivalkit.*}, {@code buildtools.*}, {@code loadchest.*})
 * and duplicate {@code PauseWatch} flags across modules.
 * <p>
 * Pure Java with zero Minecraft client dependencies so it can be unit-tested headlessly.
 */
public final class RuntimeBus {

    private RuntimeBus() {}

    private static volatile boolean scriptActive;
    private static volatile String currentStep;
    private static volatile boolean paused;

    private static volatile Set<String> helpersOn = Set.of();
    private static volatile Set<String> addonsOff = Set.of();

    private static final AtomicReference<String> HALT_REASON = new AtomicReference<>();

    private static volatile boolean recovering;
    private static final AtomicBoolean SAFE_AFTER_RESPAWN = new AtomicBoolean();
    private static final AtomicReference<String> RECOVERY_GAVE_UP_REASON = new AtomicReference<>();
    private static volatile String finishedSummary;

    private static volatile boolean survivalKitBusy;
    private static volatile boolean buildToolsBusy;
    private static volatile boolean loadChestBusy;

    // ------------------------------------------------------------------ script & step state

    public static boolean isScriptActive() {
        return scriptActive;
    }

    public static void setScriptActive(boolean active) {
        scriptActive = active;
    }

    public static String currentStep() {
        return currentStep;
    }

    public static void setCurrentStep(String step) {
        currentStep = (step == null || step.isBlank()) ? null : step;
    }

    // ------------------------------------------------------------------ pause state

    public static boolean isPaused() {
        return paused;
    }

    public static void setPaused(boolean isPaused) {
        paused = isPaused;
    }

    /**
     * Handles a pause/resume/toggle command string (with or without a leading {@code #}).
     *
     * @param cmd command such as {@code "pause"}, {@code "p"}, {@code "pause toggle"}, {@code "toggle"},
     *            {@code "resume"}, {@code "r"}, {@code "unpause"}
     * @return {@code true} if the command was recognized and applied
     */
    public static boolean handlePauseCommand(String cmd) {
        if (cmd == null) {
            return false;
        }
        String m = cmd.trim();
        if (m.startsWith("#")) {
            m = m.substring(1).trim();
        }
        if (m.isEmpty()) {
            return false;
        }
        String[] words = m.toLowerCase(Locale.ROOT).split("\\s+");
        return switch (words[0]) {
            case "p", "pause", "paws" -> {
                paused = (words.length > 1 && "toggle".equals(words[1])) ? !paused : true;
                yield true;
            }
            case "toggle" -> {
                paused = !paused;
                yield true;
            }
            case "r", "resume", "unpause", "unp" -> {
                paused = false;
                yield true;
            }
            default -> false;
        };
    }

    // ------------------------------------------------------------------ helpers & addons overrides

    public static Set<String> helpersOn() {
        return helpersOn;
    }

    public static void setHelpersOn(Collection<String> features) {
        helpersOn = normalizeSet(features);
    }

    public static Set<String> addonsOff() {
        return addonsOff;
    }

    public static void setAddonsOff(Collection<String> features) {
        addonsOff = normalizeSet(features);
    }

    public static void clearHelpers() {
        helpersOn = Set.of();
        addonsOff = Set.of();
    }

    /**
     * Returns {@code true} if {@code feature} is force-enabled by the active script/project
     * (either explicitly or via {@code "all"}), otherwise falls back to {@code savedDefault}.
     */
    public static boolean isHelperOn(String feature, boolean savedDefault) {
        if (feature != null && !feature.isBlank()) {
            String key = feature.trim().toLowerCase(Locale.ROOT);
            Set<String> on = helpersOn;
            if (on.contains("all") || on.contains(key)) {
                return true;
            }
        }
        return savedDefault;
    }

    /**
     * Returns {@code true} if {@code feature} is disabled via {@link #addonsOff()}
     * (matching {@code "all"} or any substring key in {@code addonsOff}).
     */
    public static boolean isAddonOff(String feature) {
        if (feature == null || feature.isBlank()) {
            return false;
        }
        String f = feature.trim().toLowerCase(Locale.ROOT);
        Set<String> off = addonsOff;
        for (String k : off) {
            if ("all".equals(k) || (!k.isEmpty() && f.contains(k))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ halt signaling (consume-once)

    public static void requestHalt(String reason) {
        if (reason != null && !reason.isBlank()) {
            HALT_REASON.set(reason);
        }
    }

    public static String peekHaltReason() {
        return HALT_REASON.get();
    }

    /** Returns the pending halt reason and clears it atomically, or {@code null} if none. */
    public static String consumeHaltReason() {
        return HALT_REASON.getAndSet(null);
    }

    // ------------------------------------------------------------------ recovery & completion state

    public static boolean isRecovering() {
        return recovering;
    }

    public static void setRecovering(boolean isRecovering) {
        recovering = isRecovering;
    }

    public static boolean isSafeAfterRespawn() {
        return SAFE_AFTER_RESPAWN.get();
    }

    public static void setSafeAfterRespawn(boolean safe) {
        SAFE_AFTER_RESPAWN.set(safe);
    }

    /** Atomically reads and clears {@code safeAfterRespawn}. */
    public static boolean consumeSafeAfterRespawn() {
        return SAFE_AFTER_RESPAWN.getAndSet(false);
    }

    public static String recoveryGaveUpReason() {
        return RECOVERY_GAVE_UP_REASON.get();
    }

    public static void setRecoveryGaveUpReason(String reason) {
        RECOVERY_GAVE_UP_REASON.set((reason == null || reason.isBlank()) ? null : reason);
    }

    /** Atomically reads and clears {@code recoveryGaveUpReason}. */
    public static String consumeRecoveryGaveUpReason() {
        return RECOVERY_GAVE_UP_REASON.getAndSet(null);
    }

    public static String finishedSummary() {
        return finishedSummary;
    }

    public static void setFinishedSummary(String summary) {
        finishedSummary = (summary == null || summary.isBlank()) ? null : summary;
    }

    // ------------------------------------------------------------------ auxiliary busy flags

    public static boolean isSurvivalKitBusy() {
        return survivalKitBusy;
    }

    public static void setSurvivalKitBusy(boolean busy) {
        survivalKitBusy = busy;
    }

    public static boolean isBuildToolsBusy() {
        return buildToolsBusy;
    }

    public static void setBuildToolsBusy(boolean busy) {
        buildToolsBusy = busy;
    }

    public static boolean isLoadChestBusy() {
        return loadChestBusy;
    }

    public static void setLoadChestBusy(boolean busy) {
        loadChestBusy = busy;
    }

    // ------------------------------------------------------------------ reset

    /** Resets all runtime state to clean initial defaults. */
    public static void resetAll() {
        scriptActive = false;
        currentStep = null;
        paused = false;
        helpersOn = Set.of();
        addonsOff = Set.of();
        HALT_REASON.set(null);
        recovering = false;
        SAFE_AFTER_RESPAWN.set(false);
        RECOVERY_GAVE_UP_REASON.set(null);
        finishedSummary = null;
        survivalKitBusy = false;
        buildToolsBusy = false;
        loadChestBusy = false;
    }

    private static Set<String> normalizeSet(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null) continue;
            String trimmed = item.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out.isEmpty() ? Set.of() : Collections.unmodifiableSet(out);
    }
}
