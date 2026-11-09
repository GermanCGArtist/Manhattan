package dev.baritonequeue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The values of the script language: null, true/false, numbers (always double), texts, lists, maps, positions and
 * functions. Plain Java objects; this class has the rules that work on them.
 */
final class Values {
    private Values() {}

    /** A block position. Shows as "x y z", so it can go straight into a Baritone command: #goto {home}. */
    record Pos(int x, int y, int z) {
        net.minecraft.core.BlockPos block() {
            return new net.minecraft.core.BlockPos(x, y, z);
        }

        static Pos of(net.minecraft.core.BlockPos b) {
            return new Pos(b.getX(), b.getY(), b.getZ());
        }

        @Override
        public String toString() {
            return x + " " + y + " " + z;
        }
    }

    /** Something that can be called: a function written in the script, or one built in. */
    interface Callable {
        Object call(Interp in, List<Object> args, int line);

        String name();
    }

    /** A runtime error in the script; try/catch can catch it. */
    static final class ScriptError extends RuntimeException {
        final int line;

        ScriptError(String message, int line) {
            super(message, null, false, false);
            this.line = line;
        }
    }

    static ScriptError error(String message, int line) {
        return new ScriptError(message, line);
    }

    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Double d) return d != 0;
        if (v instanceof String s) return !s.isEmpty();
        if (v instanceof List<?> l) return !l.isEmpty();
        if (v instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }

    static String typeName(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean) return "bool";
        if (v instanceof Double) return "number";
        if (v instanceof String) return "text";
        if (v instanceof List<?>) return "list";
        if (v instanceof Map<?, ?>) return "map";
        if (v instanceof Pos) return "pos";
        if (v instanceof Callable) return "function";
        return v.getClass().getSimpleName();
    }

    static String num(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        String s = String.format(java.util.Locale.ROOT, "%.4f", d);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** How a value reads in a text or a message. */
    static String str(Object v) {
        if (v == null) return "null";
        if (v instanceof Double d) return num(d);
        if (v instanceof String s) return s;
        if (v instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(repr(l.get(i)));
            }
            return sb.append(']').toString();
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(str(e.getKey())).append(": ").append(repr(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (v instanceof Callable c) return "function " + c.name();
        return String.valueOf(v);
    }

    static String repr(Object v) {
        return v instanceof String s ? "\"" + s + "\"" : str(v);
    }

    static boolean eq(Object a, Object b) {
        if (a instanceof Double x && b instanceof Double y) return x.doubleValue() == y.doubleValue();
        return Objects.equals(a, b);
    }

    static double toNum(Object v, String what, int line) {
        if (v instanceof Double d) return d;
        if (v instanceof Boolean b) return b ? 1 : 0;
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw error(what + " must be a number, not the text \"" + s + "\".", line);
            }
        }
        throw error(what + " must be a number, not " + typeName(v) + ".", line);
    }

    static int toInt(Object v, String what, int line) {
        return (int) Math.floor(toNum(v, what, line));
    }

    static int compare(Object a, Object b, int line) {
        if (a instanceof Double x && b instanceof Double y) return Double.compare(x, y);
        if (a instanceof String x && b instanceof String y) return x.compareTo(y);
        throw error("Cannot compare " + typeName(a) + " with " + typeName(b) + ".", line);
    }

    static Object add(Object a, Object b, int line) {
        if (a instanceof Double x && b instanceof Double y) return x + y;
        if (a instanceof String || b instanceof String) return str(a) + str(b);
        if (a instanceof List<?> x && b instanceof List<?> y) {
            List<Object> out = new ArrayList<>(x);
            out.addAll(y);
            return out;
        }
        if (a instanceof Pos p && b instanceof Pos q) return new Pos(p.x + q.x, p.y + q.y, p.z + q.z);
        throw error("Cannot add " + typeName(a) + " and " + typeName(b) + ".", line);
    }

    static Object sub(Object a, Object b, int line) {
        if (a instanceof Pos p && b instanceof Pos q) return new Pos(p.x - q.x, p.y - q.y, p.z - q.z);
        return toNum(a, "The left side of -", line) - toNum(b, "The right side of -", line);
    }

    static Object arith(String op, Object a, Object b, int line) {
        switch (op) {
            case "+" -> { return add(a, b, line); }
            case "-" -> { return sub(a, b, line); }
            case "*" -> {
                if (a instanceof String s && b instanceof Double n) return s.repeat(Math.max(0, n.intValue()));
                return toNum(a, "The left side of *", line) * toNum(b, "The right side of *", line);
            }
            case "/" -> {
                double d = toNum(b, "The right side of /", line);
                if (d == 0) throw error("Division by zero.", line);
                return toNum(a, "The left side of /", line) / d;
            }
            case "%" -> {
                double d = toNum(b, "The right side of %", line);
                if (d == 0) throw error("Division by zero.", line);
                double r = toNum(a, "The left side of %", line) % d;
                return r < 0 ? r + Math.abs(d) : r;
            }
            default -> throw error("Unknown operator " + op, line);
        }
    }

    static boolean contains(Object container, Object item, int line) {
        if (container instanceof List<?> l) {
            for (Object o : l) if (eq(o, item)) return true;
            return false;
        }
        if (container instanceof Map<?, ?> m) return m.containsKey(key(item));
        if (container instanceof String s) return s.contains(str(item));
        throw error("'in' needs a list, map or text on its right, not " + typeName(container) + ".", line);
    }

    /** Map keys: numbers that are whole become the same key whether written 1 or 1.0. */
    static Object key(Object k) {
        return k;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object v, String what, int line) {
        if (v instanceof List<?> l) return (List<Object>) l;
        throw error(what + " must be a list, not " + typeName(v) + ".", line);
    }

    @SuppressWarnings("unchecked")
    static Map<Object, Object> map(Object v, String what, int line) {
        if (v instanceof Map<?, ?> m) return (Map<Object, Object>) m;
        throw error(what + " must be a map, not " + typeName(v) + ".", line);
    }

    static Map<Object, Object> newMap() {
        return new LinkedHashMap<>();
    }
}
