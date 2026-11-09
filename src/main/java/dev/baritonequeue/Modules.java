package dev.baritonequeue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The helpers that used to be separate addons and now live in this mod: SurvivalKit (eat, sleep, defence, restock,
 * respawn, reconnect), BuildTools (water fill, drain, scaffolding, safe areas, water escape) and LoadChest (unload,
 * pickup). This class reaches their settings for option() and says whether any of them is busy.
 */
final class Modules {
    private Modules() {}

    /** An action that ended without doing its job: the script gets an error (catchable with try/catch). */
    record Failure(String why) {}

    static boolean busy() {
        return dev.survivalkit.Api.busy() || dev.buildtools.Api.busy() || dev.loadchest.LoadChestMod.unloading();
    }

    // ------------------------------------------------------------------ option("group.name", value)

    private record Group(String name, java.util.function.Supplier<Object> config, Runnable save) {}

    private static List<Group> groups() {
        return List.of(
                new Group("survival", dev.survivalkit.Api::config, dev.survivalkit.Api::save),
                new Group("buildtools", dev.buildtools.Api::config, dev.buildtools.Api::save),
                new Group("loadchest", dev.loadchest.LoadChestMod::config, dev.loadchest.LoadChestMod::saveSettings),
                new Group("queue", () -> Config.cfg, Config::save));
    }

    private static boolean settable(Field f) {
        if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) return false;
        Class<?> t = f.getType();
        return t == boolean.class || t == int.class || t == double.class || t == String.class || t == int[].class;
    }

    /** Every option: "group.name" -> current value as text. */
    static Map<String, String> all() {
        Map<String, String> out = new TreeMap<>();
        for (Group g : groups()) {
            Object cfg = g.config.get();
            for (Field f : cfg.getClass().getDeclaredFields()) {
                if (!settable(f)) continue;
                if (g.name.equals("queue") && !f.getName().equals("stallMinutes")) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(cfg);
                    out.put(g.name + "." + f.getName(), v instanceof int[] a ? a[0] + " " + a[1] + " " + a[2] : String.valueOf(v));
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    private static Object[] find(String key, int line) {
        String k = key.trim();
        int dot = k.indexOf('.');
        if (dot < 0) throw Values.error("An option is named group.name, for example \"survival.eat\". Groups: survival, buildtools, loadchest. option_list() shows all.", line);
        String group = k.substring(0, dot).toLowerCase(), name = k.substring(dot + 1);
        if (group.equals("survivalkit")) group = "survival";
        for (Group g : groups()) {
            if (!g.name.equals(group)) continue;
            Object cfg = g.config.get();
            for (Field f : cfg.getClass().getDeclaredFields()) {
                if (settable(f) && f.getName().equalsIgnoreCase(name)) {
                    f.setAccessible(true);
                    return new Object[]{cfg, f, g};
                }
            }
            List<String> names = new ArrayList<>();
            for (Field f : cfg.getClass().getDeclaredFields()) if (settable(f)) names.add(f.getName());
            throw Values.error("'" + group + "' has no option '" + name + "'. It has: " + String.join(", ", names), line);
        }
        throw Values.error("Unknown option group '" + group + "'. Groups: survival, buildtools, loadchest.", line);
    }

    static Object get(String key, int line) {
        Object[] r = find(key, line);
        try {
            Object v = ((Field) r[1]).get(r[0]);
            if (v instanceof Integer i) return (double) i;
            if (v instanceof int[] a) return new Values.Pos(a[0], a[1], a[2]);
            return v;
        } catch (IllegalAccessException e) {
            throw Values.error("Cannot read " + key + ".", line);
        }
    }

    static void set(String key, Object value, int line) {
        Object[] r = find(key, line);
        Field f = (Field) r[1];
        Class<?> t = f.getType();
        try {
            if (t == boolean.class) {
                if (!(value instanceof Boolean b)) throw Values.error(key + " is on/off: use true or false.", line);
                f.setBoolean(r[0], b);
            } else if (t == int.class) {
                f.setInt(r[0], Values.toInt(value, key, line));
            } else if (t == double.class) {
                f.setDouble(r[0], Values.toNum(value, key, line));
            } else if (t == String.class) {
                f.set(r[0], value == null ? null : Values.str(value));
            } else if (t == int[].class) {
                if (value == null) f.set(r[0], null);
                else if (value instanceof Values.Pos p) f.set(r[0], new int[]{p.x(), p.y(), p.z()});
                else throw Values.error(key + " is a place: use pos(x, y, z), or null to clear it.", line);
            }
        } catch (IllegalAccessException e) {
            throw Values.error("Cannot change " + key + ".", line);
        }
        ((Group) r[2]).save.run();
    }
}
