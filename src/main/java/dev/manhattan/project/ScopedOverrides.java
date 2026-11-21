package dev.manhattan.project;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-task scoped overrides applied when a task starts and automatically restored when it finishes,
 * fails, is skipped, or the scheduler stops. Pure Java + Gson (no Minecraft imports).
 */
public final class ScopedOverrides {
    public Boolean allowBreak;
    public Boolean allowPlace;
    public Map<String, Integer> keep = new LinkedHashMap<>();
    public Map<String, Integer> want = new LinkedHashMap<>();
    public Set<String> suppressSurvival = new LinkedHashSet<>();

    public ScopedOverrides() {
    }

    /** Ensures collection fields are non-null (e.g. after Gson deserialization of sparse JSON). */
    public void ensureInitialized() {
        if (keep == null) keep = new LinkedHashMap<>();
        if (want == null) want = new LinkedHashMap<>();
        if (suppressSurvival == null) suppressSurvival = new LinkedHashSet<>();
    }

    /** True when no override field is set and all override maps/sets are empty. */
    public boolean isEmpty() {
        return allowBreak == null
                && allowPlace == null
                && (keep == null || keep.isEmpty())
                && (want == null || want.isEmpty())
                && (suppressSurvival == null || suppressSurvival.isEmpty());
    }

    /** Returns true if the given survival feature name is suppressed (case-insensitive). */
    public boolean isSuppressed(String feature) {
        if (feature == null || feature.isBlank() || suppressSurvival == null || suppressSurvival.isEmpty()) {
            return false;
        }
        if (suppressSurvival.contains(feature)) return true;
        String lower = feature.toLowerCase(Locale.ROOT);
        for (String entry : suppressSurvival) {
            if (entry != null && entry.toLowerCase(Locale.ROOT).equals(lower)) {
                return true;
            }
        }
        return false;
    }

    /** Returns a deep copy of this overrides object. */
    public ScopedOverrides copy() {
        ScopedOverrides out = new ScopedOverrides();
        out.allowBreak = this.allowBreak;
        out.allowPlace = this.allowPlace;
        if (this.keep != null) out.keep.putAll(this.keep);
        if (this.want != null) out.want.putAll(this.want);
        if (this.suppressSurvival != null) out.suppressSurvival.addAll(this.suppressSurvival);
        return out;
    }

    /**
     * Returns a new {@link ScopedOverrides} combining {@code this} (base) with {@code other} (overlay).
     * Non-null booleans and map entries in {@code other} take precedence; {@code suppressSurvival} sets are unioned.
     */
    public ScopedOverrides merge(ScopedOverrides other) {
        ScopedOverrides out = this.copy();
        if (other == null) return out;
        if (other.allowBreak != null) out.allowBreak = other.allowBreak;
        if (other.allowPlace != null) out.allowPlace = other.allowPlace;
        if (other.keep != null) out.keep.putAll(other.keep);
        if (other.want != null) out.want.putAll(other.want);
        if (other.suppressSurvival != null) out.suppressSurvival.addAll(other.suppressSurvival);
        return out;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ScopedOverrides that)) return false;
        return Objects.equals(allowBreak, that.allowBreak)
                && Objects.equals(allowPlace, that.allowPlace)
                && Objects.equals(keep, that.keep)
                && Objects.equals(want, that.want)
                && Objects.equals(suppressSurvival, that.suppressSurvival);
    }

    @Override
    public int hashCode() {
        return Objects.hash(allowBreak, allowPlace, keep, want, suppressSurvival);
    }

    @Override
    public String toString() {
        return "ScopedOverrides{allowBreak=" + allowBreak
                + ", allowPlace=" + allowPlace
                + ", keep=" + keep
                + ", want=" + want
                + ", suppressSurvival=" + suppressSurvival + '}';
    }
}
