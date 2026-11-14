package dev.midtown.api;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Selects the active {@link PathEngine} from {@code option("engine", ...)} or {@code -Dmanhattan.engine=baritone|midtown}.
 * <p>
 * Resolution order:
 * <ol>
 *   <li>Explicit runtime option set via {@link #setOption(String)}</li>
 *   <li>JVM system property {@code -Dmanhattan.engine=...}</li>
 *   <li>Default: {@value #DEFAULT_ENGINE}</li>
 * </ol>
 */
public final class EngineSelector {

    public static final String PROPERTY = "manhattan.engine";
    public static final String ENGINE_BARITONE = "baritone";
    public static final String ENGINE_MIDTOWN = "midtown";
    public static final String DEFAULT_ENGINE = ENGINE_MIDTOWN;

    private static final Map<String, Supplier<PathEngine>> FACTORIES = new ConcurrentHashMap<>();
    private static final Map<String, PathEngine> INSTANCES = new ConcurrentHashMap<>();
    private static volatile String optionOverride;

    private EngineSelector() {}

    /** Sets the runtime engine option (backing {@code option("engine", value)}). Pass {@code null} to clear. */
    public static void setOption(String engineName) {
        if (engineName == null || engineName.isBlank()) {
            optionOverride = null;
            return;
        }
        optionOverride = normalizeAndValidate(engineName);
    }

    /** Returns the current runtime engine option override, or {@code null} if unset. */
    public static String getOption() {
        return optionOverride;
    }

    /** Clears any runtime engine option override so {@code -Dmanhattan.engine} / default takes effect. */
    public static void clearOption() {
        optionOverride = null;
    }

    /** Registers a {@link PathEngine} factory for {@code engineName}. */
    public static void register(String engineName, Supplier<PathEngine> factory) {
        String key = normalizeAndValidate(engineName);
        Objects.requireNonNull(factory, "factory");
        FACTORIES.put(key, factory);
        INSTANCES.remove(key);
    }

    /** Returns the resolved engine name ({@code "baritone"} or {@code "midtown"}). */
    public static String selectedName() {
        String opt = optionOverride;
        if (opt != null) return opt;
        String prop = System.getProperty(PROPERTY);
        if (prop != null && !prop.isBlank()) {
            return normalizeAndValidate(prop);
        }
        return DEFAULT_ENGINE;
    }

    /** Returns the cached {@link PathEngine} for the currently selected engine. */
    public static PathEngine current() {
        return get(selectedName());
    }

    /** Returns the cached {@link PathEngine} for {@code engineName}. */
    public static PathEngine get(String engineName) {
        String key = normalizeAndValidate(engineName);
        return INSTANCES.computeIfAbsent(key, EngineSelector::create);
    }

    /** Instantiates a fresh {@link PathEngine} for {@code engineName}. */
    public static PathEngine create(String engineName) {
        String key = normalizeAndValidate(engineName);
        Supplier<PathEngine> factory = FACTORIES.get(key);
        if (factory != null) {
            return factory.get();
        }
        String className = switch (key) {
            case ENGINE_BARITONE, ENGINE_MIDTOWN -> "dev.midtown.exec.MidtownEngine";
            default -> throw new IllegalArgumentException("Unknown path engine: " + engineName);
        };
        try {
            Class<?> cls = Class.forName(className);
            return (PathEngine) cls.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("PathEngine '" + key + "' (" + className + ") is not available", e);
        }
    }

    /** Validates and normalizes an engine name to {@code "baritone"} or {@code "midtown"}. */
    public static String normalizeAndValidate(String engineName) {
        if (engineName == null) {
            throw new IllegalArgumentException("Engine name must not be null");
        }
        String norm = engineName.trim().toLowerCase(Locale.ROOT);
        if (!ENGINE_BARITONE.equals(norm) && !ENGINE_MIDTOWN.equals(norm)) {
            throw new IllegalArgumentException(
                    "Invalid engine '" + engineName + "': expected 'baritone' or 'midtown'");
        }
        return norm;
    }
}
