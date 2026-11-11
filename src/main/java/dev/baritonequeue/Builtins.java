package dev.baritonequeue;

import dev.baritonequeue.Values.Callable;
import dev.baritonequeue.Values.Pos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.baritonequeue.Values.error;
import static dev.baritonequeue.Values.str;
import static dev.baritonequeue.Values.toInt;
import static dev.baritonequeue.Values.toNum;

/** The functions every script has: the general ones here, the game ones in GameBuiltins. */
final class Builtins {
    private Builtins() {}

    interface Fn {
        Object call(Interp in, List<Object> a, int line);
    }

    /** A built-in function. A getter (health, night ...) is read without (): "if night". */
    static final class Builtin implements Callable {
        final String name, signature, desc, group;
        final int min, max;
        final boolean getter;
        final Fn fn;

        Builtin(String group, String name, int min, int max, boolean getter, String signature, String desc, Fn fn) {
            this.group = group;
            this.name = name;
            this.min = min;
            this.max = max;
            this.getter = getter;
            this.signature = signature;
            this.desc = desc;
            this.fn = fn;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Object call(Interp in, List<Object> args, int line) {
            if (args.size() < min || (max >= 0 && args.size() > max)) {
                throw error(name + " is used like this: " + signature, line);
            }
            // Functions that change the game must run on its main thread (chat messages, settings, the selection ...).
            // The script has its own thread, so it hands them over and waits one tick.
            if (onGameThread() && in.fiber != null && !net.minecraft.client.Minecraft.getInstance().isSameThread()) {
                return in.await(() -> new Actions.Now(() -> fn.call(in, args, line)), name, line);
            }
            return fn.call(in, args, line);
        }

        private boolean onGameThread() {
            return group.equals("areas") || group.equals("helpers") || group.equals("settings") || group.equals("schem") || name.equals("cancel");
        }
    }

    private static final Map<String, Builtin> ALL = new LinkedHashMap<>();
    private static Ast.Env root;

    static Map<String, Builtin> all() {
        if (ALL.isEmpty()) {
            core();
            GameBuiltins.register();
        }
        return ALL;
    }

    static Ast.Env root() {
        if (root == null) {
            root = new Ast.Env(null);
            all().forEach(root::define);
        }
        return root;
    }

    static void def(String group, String name, int min, int max, String signature, String desc, Fn fn) {
        ALL.put(name, new Builtin(group, name, min, max, false, signature, desc, fn));
    }

    static void getter(String group, String name, String desc, Fn fn) {
        ALL.put(name, new Builtin(group, name, 0, 0, true, name, desc, fn));
    }

    static void alias(String alias, String name) {
        Builtin b = ALL.get(name);
        ALL.put(alias, new Builtin(b.group, alias, b.min, b.max, b.getter, b.signature.replaceFirst("^" + name, alias), b.desc + " (same as " + name + ")", b.fn));
    }

    // ------------------------------------------------------------------ general

    private static void core() {
        String g = "general";
        def(g, "print", 0, -1, "print(value, ...)", "write a message in the chat", (in, a, l) -> {
            List<String> parts = new ArrayList<>();
            for (Object o : a) parts.add(str(o));
            Util.msg(String.join(" ", parts));
            return null;
        });
        def(g, "log", 0, -1, "log(value, ...)", "write a line into the game log only (logs/latest.log)", (in, a, l) -> {
            List<String> parts = new ArrayList<>();
            for (Object o : a) parts.add(str(o));
            System.out.println("[Queue script] " + String.join(" ", parts));
            return null;
        });
        def(g, "str", 1, 1, "str(value)", "the value as text", (in, a, l) -> str(a.get(0)));
        def(g, "num", 1, 1, "num(text)", "the text as a number", (in, a, l) -> toNum(a.get(0), "num()", l));
        def(g, "int", 1, 1, "int(value)", "the whole number part (rounds down)", (in, a, l) -> Math.floor(toNum(a.get(0), "int()", l)));
        def(g, "type", 1, 1, "type(value)", "\"number\", \"text\", \"list\", \"map\", \"pos\", \"bool\", \"null\" or \"function\"", (in, a, l) -> Values.typeName(a.get(0)));
        def(g, "len", 1, 1, "len(list | text | map)", "how many items or letters", (in, a, l) -> {
            Object o = a.get(0);
            if (o instanceof List<?> x) return (double) x.size();
            if (o instanceof String s) return (double) s.length();
            if (o instanceof Map<?, ?> m) return (double) m.size();
            throw error("len() needs a list, text or map, not " + Values.typeName(o) + ".", l);
        });
        def(g, "range", 1, 3, "range(n) | range(from, to) | range(from, to, step)", "a list of numbers: range(3) is [0, 1, 2], range(1, 4) is [1, 2, 3]", (in, a, l) -> {
            double from = 0, to, step = 1;
            if (a.size() == 1) to = toNum(a.get(0), "range()", l);
            else { from = toNum(a.get(0), "range()", l); to = toNum(a.get(1), "range()", l); }
            if (a.size() == 3) step = toNum(a.get(2), "The step of range()", l);
            if (step == 0) throw error("The step of range() cannot be 0.", l);
            List<Object> out = new ArrayList<>();
            for (double v = from; step > 0 ? v < to : v > to; v += step) {
                out.add(v);
                if (out.size() > 1_000_000) throw error("range() would make more than a million numbers.", l);
            }
            return out;
        });
        def(g, "push", 2, 2, "push(list, value)", "add a value at the end of a list", (in, a, l) -> {
            Values.list(a.get(0), "push()", l).add(a.get(1));
            return a.get(0);
        });
        alias("append", "push");
        def(g, "pop", 1, 2, "pop(list [, index])", "take the last (or that) item out of a list and return it", (in, a, l) -> {
            List<Object> x = Values.list(a.get(0), "pop()", l);
            if (x.isEmpty()) throw error("pop() on an empty list.", l);
            int i = a.size() > 1 ? toInt(a.get(1), "pop()", l) : x.size() - 1;
            if (i < 0) i += x.size();
            if (i < 0 || i >= x.size()) throw error("Index " + i + " is outside the list.", l);
            return x.remove(i);
        });
        def(g, "insert", 3, 3, "insert(list, index, value)", "put a value into a list at that place", (in, a, l) -> {
            List<Object> x = Values.list(a.get(0), "insert()", l);
            int i = Math.max(0, Math.min(x.size(), toInt(a.get(1), "insert()", l)));
            x.add(i, a.get(2));
            return a.get(0);
        });
        def(g, "remove", 2, 2, "remove(list | map, value | key)", "take a value out of a list, or a key out of a map; true if it was there", (in, a, l) -> {
            Object c = a.get(0);
            if (c instanceof Map<?, ?>) return Values.map(c, "", l).remove(Values.key(a.get(1))) != null;
            List<Object> x = Values.list(c, "remove()", l);
            for (int i = 0; i < x.size(); i++) if (Values.eq(x.get(i), a.get(1))) { x.remove(i); return true; }
            return false;
        });
        def(g, "contains", 2, 2, "contains(list | map | text, value)", "true if it is in there (same as: value in list)", (in, a, l) -> Values.contains(a.get(0), a.get(1), l));
        def(g, "index_of", 2, 2, "index_of(list | text, value)", "where it is (from 0), or -1", (in, a, l) -> {
            Object c = a.get(0);
            if (c instanceof String s) return (double) s.indexOf(str(a.get(1)));
            List<Object> x = Values.list(c, "index_of()", l);
            for (int i = 0; i < x.size(); i++) if (Values.eq(x.get(i), a.get(1))) return (double) i;
            return -1.0;
        });
        def(g, "keys", 1, 1, "keys(map)", "the keys of a map, as a list", (in, a, l) -> new ArrayList<>(Values.map(a.get(0), "keys()", l).keySet()));
        def(g, "values", 1, 1, "values(map)", "the values of a map, as a list", (in, a, l) -> new ArrayList<>(Values.map(a.get(0), "values()", l).values()));
        def(g, "join", 1, 2, "join(list [, between])", "the items as one text", (in, a, l) -> {
            List<String> parts = new ArrayList<>();
            for (Object o : Values.list(a.get(0), "join()", l)) parts.add(str(o));
            return String.join(a.size() > 1 ? str(a.get(1)) : ", ", parts);
        });
        def(g, "split", 1, 2, "split(text [, separator])", "cut a text into a list (at spaces, or at the separator)", (in, a, l) -> {
            String s = str(a.get(0));
            List<Object> out = new ArrayList<>();
            if (a.size() < 2) { for (String p : s.trim().split("\\s+")) if (!p.isEmpty()) out.add(p); }
            else out.addAll(List.of(s.split(java.util.regex.Pattern.quote(str(a.get(1))), -1)));
            return out;
        });
        def(g, "upper", 1, 1, "upper(text)", "in capital letters", (in, a, l) -> str(a.get(0)).toUpperCase());
        def(g, "lower", 1, 1, "lower(text)", "in small letters", (in, a, l) -> str(a.get(0)).toLowerCase());
        def(g, "trim", 1, 1, "trim(text)", "without spaces at the start and end", (in, a, l) -> str(a.get(0)).trim());
        def(g, "replace", 3, 3, "replace(text, old, new)", "every old part replaced", (in, a, l) -> str(a.get(0)).replace(str(a.get(1)), str(a.get(2))));
        def(g, "starts_with", 2, 2, "starts_with(text, start)", "true if the text starts like that", (in, a, l) -> str(a.get(0)).startsWith(str(a.get(1))));
        def(g, "ends_with", 2, 2, "ends_with(text, end)", "true if the text ends like that", (in, a, l) -> str(a.get(0)).endsWith(str(a.get(1))));
        def(g, "sort", 1, 1, "sort(list)", "a sorted copy of the list", (in, a, l) -> {
            List<Object> x = new ArrayList<>(Values.list(a.get(0), "sort()", l));
            x.sort((p, q) -> Values.compare(p, q, l));
            return x;
        });
        def(g, "reverse", 1, 1, "reverse(list)", "a reversed copy of the list", (in, a, l) -> {
            List<Object> x = new ArrayList<>(Values.list(a.get(0), "reverse()", l));
            Collections.reverse(x);
            return x;
        });
        def(g, "copy", 1, 1, "copy(list | map)", "a copy, so changing one does not change the other", (in, a, l) -> {
            Object o = a.get(0);
            if (o instanceof List<?> x) return new ArrayList<Object>(x);
            if (o instanceof Map<?, ?> m) return new LinkedHashMap<Object, Object>(m);
            return o;
        });
        def(g, "sum", 1, 1, "sum(list)", "all numbers added up", (in, a, l) -> {
            double s = 0;
            for (Object o : Values.list(a.get(0), "sum()", l)) s += toNum(o, "sum()", l);
            return s;
        });
        def(g, "min", 1, -1, "min(a, b, ...) | min(list)", "the smallest", (in, a, l) -> extreme(a, l, -1));
        def(g, "max", 1, -1, "max(a, b, ...) | max(list)", "the biggest", (in, a, l) -> extreme(a, l, 1));
        def(g, "abs", 1, 1, "abs(n)", "without the minus sign", (in, a, l) -> Math.abs(toNum(a.get(0), "abs()", l)));
        def(g, "floor", 1, 1, "floor(n)", "rounded down", (in, a, l) -> Math.floor(toNum(a.get(0), "floor()", l)));
        def(g, "ceil", 1, 1, "ceil(n)", "rounded up", (in, a, l) -> Math.ceil(toNum(a.get(0), "ceil()", l)));
        def(g, "round", 1, 2, "round(n [, digits])", "rounded", (in, a, l) -> {
            double f = Math.pow(10, a.size() > 1 ? toInt(a.get(1), "round()", l) : 0);
            return Math.round(toNum(a.get(0), "round()", l) * f) / f;
        });
        def(g, "sqrt", 1, 1, "sqrt(n)", "square root", (in, a, l) -> Math.sqrt(toNum(a.get(0), "sqrt()", l)));
        def(g, "random", 0, 2, "random() | random(to) | random(from, to)", "random() is 0..1; random(1, 6) is a whole number from 1 to 6", (in, a, l) -> {
            var r = java.util.concurrent.ThreadLocalRandom.current();
            if (a.isEmpty()) return r.nextDouble();
            int from = a.size() == 2 ? toInt(a.get(0), "random()", l) : 0;
            int to = toInt(a.get(a.size() - 1), "random()", l);
            if (to < from) throw error("random(from, to) needs from <= to.", l);
            return (double) r.nextInt(from, to + 1);
        });
        def(g, "error", 1, 1, "error(text)", "stop with this message (try/catch can catch it)", (in, a, l) -> { throw error(str(a.get(0)), l); });
        def(g, "assert", 1, 2, "assert(condition [, text])", "stop with a message if the condition is false", (in, a, l) -> {
            if (!Values.truthy(a.get(0))) throw error(a.size() > 1 ? str(a.get(1)) : "assert failed.", l);
            return null;
        });
        def(g, "now", 0, 0, "now()", "milliseconds since 1970: (now() - start) / 1000 is seconds passed", (in, a, l) -> (double) System.currentTimeMillis());
        getter(g, "clock", "the real time as \"HH:MM\" (wait until clock == \"06:30\")", (in, a, l) -> {
            var t = java.time.LocalTime.now();
            return String.format("%02d:%02d", t.getHour(), t.getMinute());
        });
        def(g, "stop", 0, 1, "stop([text])", "end the script here (with a message)", (in, a, l) -> {
            if (!a.isEmpty()) Util.msg(str(a.get(0)));
            throw new Fiber.Exit();
        });
        alias("exit", "stop");
    }

    private static Object extreme(List<Object> a, int line, int sign) {
        List<?> items = a.size() == 1 && a.get(0) instanceof List<?> l ? l : a;
        if (items.isEmpty()) throw error("min()/max() of nothing.", line);
        Object best = items.get(0);
        for (Object o : items) if (Values.compare(o, best, line) * sign > 0) best = o;
        return best;
    }

    // ------------------------------------------------------------------ helpers for the game functions

    static Pos toPos(List<Object> a, int from, String what, int line) {
        if (a.size() > from && a.get(from) instanceof Pos p) return p;
        if (a.size() >= from + 3) {
            return new Pos(toInt(a.get(from), what, line), toInt(a.get(from + 1), what, line), toInt(a.get(from + 2), what, line));
        }
        if (a.size() > from && a.get(from) instanceof List<?> l && l.size() == 3) {
            return new Pos(toInt(l.get(0), what, line), toInt(l.get(1), what, line), toInt(l.get(2), what, line));
        }
        throw error(what + " needs a position: pos(x, y, z), or x, y, z.", line);
    }
}
