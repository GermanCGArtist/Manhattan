package dev.baritonequeue;

import dev.baritonequeue.Values.Callable;
import dev.baritonequeue.Values.Pos;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static dev.baritonequeue.Values.error;
import static dev.baritonequeue.Values.truthy;

/** The parsed script: statements and expressions, each able to run itself. */
final class Ast {
    private Ast() {}

    // ================================================================== signals

    static final class BreakSig extends RuntimeException {
        static final BreakSig I = new BreakSig();
        private BreakSig() { super(null, null, false, false); }
    }

    static final class ContinueSig extends RuntimeException {
        static final ContinueSig I = new ContinueSig();
        private ContinueSig() { super(null, null, false, false); }
    }

    static final class ReturnSig extends RuntimeException {
        final Object value;
        ReturnSig(Object value) { super(null, null, false, false); this.value = value; }
    }

    // ================================================================== statements

    abstract static class Stmt {
        final int line;
        Stmt(int line) { this.line = line; }
        abstract void exec(Interp in, Env env);
    }

    static void run(List<Stmt> block, Interp in, Env env) {
        for (Stmt s : block) {
            in.step(s.line);
            s.exec(in, env);
        }
    }

    static final class Let extends Stmt {
        final String name;
        final Expr value;
        Let(int line, String name, Expr value) { super(line); this.name = name; this.value = value; }
        void exec(Interp in, Env env) { env.define(name, value == null ? null : value.eval(in, env)); }
    }

    static final class Assign extends Stmt {
        final Expr target, value;
        final String op;   // "=", "+=", ...
        Assign(int line, Expr target, String op, Expr value) { super(line); this.target = target; this.op = op; this.value = value; }

        void exec(Interp in, Env env) {
            Object v = value.eval(in, env);
            if (!op.equals("=")) v = Values.arith(op.substring(0, 1), target.eval(in, env), v, line);
            if (target instanceof Var var) {
                env.assign(var.name, v);
            } else if (target instanceof Index ix) {
                Object o = ix.obj.eval(in, env);
                Object k = ix.index.eval(in, env);
                if (o instanceof List<?>) {
                    List<Object> l = Values.list(o, "", line);
                    int i = Values.toInt(k, "A list index", line);
                    if (i < 0) i += l.size();
                    if (i < 0 || i >= l.size()) throw error("Index " + i + " is outside the list (it has " + l.size() + " items).", line);
                    l.set(i, v);
                } else if (o instanceof Map<?, ?>) {
                    Values.map(o, "", line).put(Values.key(k), v);
                } else {
                    throw error("Only lists and maps can be changed with [...], not " + Values.typeName(o) + ".", line);
                }
            } else if (target instanceof Member m) {
                Object o = m.obj.eval(in, env);
                if (o instanceof Map<?, ?>) Values.map(o, "", line).put(m.name, v);
                else throw error("Only maps have fields to set, not " + Values.typeName(o) + ".", line);
            } else {
                throw error("Cannot assign to this.", line);
            }
        }
    }

    static final class ExprStmt extends Stmt {
        final Expr e;
        ExprStmt(int line, Expr e) { super(line); this.e = e; }
        void exec(Interp in, Env env) {
            Object v = e.eval(in, env);
            // a bare function name on its own line ("eat") means: call it
            if (e instanceof Var && v instanceof Callable c && !(v instanceof Builtins.Builtin b && b.getter)) c.call(in, List.of(), line);
        }
    }

    static final class If extends Stmt {
        final List<Expr> conds;
        final List<List<Stmt>> blocks;
        final List<Stmt> otherwise;
        If(int line, List<Expr> conds, List<List<Stmt>> blocks, List<Stmt> otherwise) {
            super(line); this.conds = conds; this.blocks = blocks; this.otherwise = otherwise;
        }

        void exec(Interp in, Env env) {
            for (int i = 0; i < conds.size(); i++) {
                if (truthy(conds.get(i).eval(in, env))) { run(blocks.get(i), in, env); return; }
            }
            if (otherwise != null) run(otherwise, in, env);
        }
    }

    static final class While extends Stmt {
        final Expr cond;
        final List<Stmt> body;
        While(int line, Expr cond, List<Stmt> body) { super(line); this.cond = cond; this.body = body; }

        void exec(Interp in, Env env) {
            while (truthy(cond.eval(in, env))) {
                in.step(line);
                try {
                    run(body, in, env);
                } catch (BreakSig b) {
                    return;
                } catch (ContinueSig c) {
                    // next round
                }
            }
        }
    }

    static final class Loop extends Stmt {
        final Expr times;   // null = forever
        final List<Stmt> body;
        Loop(int line, Expr times, List<Stmt> body) { super(line); this.times = times; this.body = body; }

        void exec(Interp in, Env env) {
            long n = times == null ? Long.MAX_VALUE : (long) Values.toNum(times.eval(in, env), "The number after 'repeat'", line);
            for (long i = 0; i < n; i++) {
                in.step(line);
                try {
                    run(body, in, env);
                } catch (BreakSig b) {
                    return;
                } catch (ContinueSig c) {
                    // next round
                }
            }
        }
    }

    static final class For extends Stmt {
        final String a, b;   // "for x in ..." or "for i, x in ..."
        final Expr over;
        final List<Stmt> body;
        For(int line, String a, String b, Expr over, List<Stmt> body) { super(line); this.a = a; this.b = b; this.over = over; this.body = body; }

        void exec(Interp in, Env env) {
            Object o = over.eval(in, env);
            Iterator<?> keys;
            List<?> values = null;
            Map<?, ?> map = null;
            if (o instanceof List<?> l) {
                values = new ArrayList<>(l);   // changing the list inside the loop does not disturb it
                keys = null;
            } else if (o instanceof Map<?, ?> m) {
                map = m;
                keys = new ArrayList<>(m.keySet()).iterator();
            } else if (o instanceof Double d) {
                List<Object> r = new ArrayList<>();
                for (int i = 0; i < d; i++) r.add((double) i);
                values = r;
                keys = null;
            } else if (o instanceof String s) {
                List<Object> r = new ArrayList<>();
                for (char c : s.toCharArray()) r.add(String.valueOf(c));
                values = r;
                keys = null;
            } else {
                throw error("'for' needs a list, map, text or number to go through, not " + Values.typeName(o) + ".", line);
            }
            int i = 0;
            while (true) {
                Object first, second;
                if (values != null) {
                    if (i >= values.size()) break;
                    first = (double) i;
                    second = values.get(i);
                } else {
                    if (!keys.hasNext()) break;
                    first = keys.next();
                    second = map.get(first);
                }
                i++;
                if (b == null) env.assign(a, map != null ? first : second);
                else { env.assign(a, first); env.assign(b, second); }
                in.step(line);
                try {
                    run(body, in, env);
                } catch (BreakSig x) {
                    return;
                } catch (ContinueSig x) {
                    // next round
                }
            }
        }
    }

    static final class On extends Stmt {
        final Expr cond;
        final List<Stmt> body;
        On(int line, Expr cond, List<Stmt> body) { super(line); this.cond = cond; this.body = body; }
        void exec(Interp in, Env env) { in.runner().addHandler(this, env); }
    }

    static final class Try extends Stmt {
        final List<Stmt> body, handler;
        final String var;
        Try(int line, List<Stmt> body, String var, List<Stmt> handler) { super(line); this.body = body; this.var = var; this.handler = handler; }

        void exec(Interp in, Env env) {
            try {
                run(body, in, env);
            } catch (Values.ScriptError e) {
                if (var != null) env.assign(var, e.getMessage());
                run(handler, in, env);
            }
        }
    }

    static final class WaitUntil extends Stmt {
        final Expr cond;
        WaitUntil(int line, Expr cond) { super(line); this.cond = cond; }
        void exec(Interp in, Env env) {
            in.activity = "waiting until " + in.lineText(line);
            while (!truthy(cond.eval(in, env))) in.yieldTick();
            in.activity = null;
        }
    }

    static final class Simple extends Stmt {
        final int kind;   // 0 break, 1 continue
        Simple(int line, int kind) { super(line); this.kind = kind; }
        void exec(Interp in, Env env) { throw kind == 0 ? BreakSig.I : ContinueSig.I; }
    }

    static final class Return extends Stmt {
        final Expr value;
        Return(int line, Expr value) { super(line); this.value = value; }
        void exec(Interp in, Env env) { throw new ReturnSig(value == null ? null : value.eval(in, env)); }
    }

    static final class FuncDef extends Stmt {
        final String name;
        final List<String> params;
        final List<Stmt> body;
        FuncDef(int line, String name, List<String> params, List<Stmt> body) { super(line); this.name = name; this.params = params; this.body = body; }
        void exec(Interp in, Env env) { env.define(name, new UserFunc(name, params, body, env, line)); }
    }

    /** "#sel ca" or "/say hi": the text, with {expr} parts filled in. */
    static final class Raw extends Stmt {
        final boolean chat;
        final Expr text;
        Raw(int line, boolean chat, Expr text) { super(line); this.chat = chat; this.text = text; }

        void exec(Interp in, Env env) {
            String t = Values.str(text.eval(in, env)).trim();
            if (chat) in.await(() -> new Actions.ChatCommand(t), "/" + t, line);
            else in.await(() -> new Actions.BaritoneCommand(t), t, line);
        }
    }

    // ================================================================== expressions

    abstract static class Expr {
        final int line;
        Expr(int line) { this.line = line; }
        abstract Object eval(Interp in, Env env);
    }

    static final class Lit extends Expr {
        final Object value;
        Lit(int line, Object value) { super(line); this.value = value; }
        Object eval(Interp in, Env env) { return value; }
    }

    /** A text with {expr} parts. */
    static final class Concat extends Expr {
        final List<Expr> parts;
        Concat(int line, List<Expr> parts) { super(line); this.parts = parts; }
        Object eval(Interp in, Env env) {
            StringBuilder sb = new StringBuilder();
            for (Expr p : parts) sb.append(Values.str(p.eval(in, env)));
            return sb.toString();
        }
    }

    static final class Var extends Expr {
        final String name;
        Var(int line, String name) { super(line); this.name = name; }
        Object eval(Interp in, Env env) {
            Object v = env.get(name, line);
            if (v instanceof Builtins.Builtin b && b.getter) return b.call(in, List.of(), line);
            return v;
        }
    }

    static final class Unary extends Expr {
        final String op;
        final Expr e;
        Unary(int line, String op, Expr e) { super(line); this.op = op; this.e = e; }
        Object eval(Interp in, Env env) {
            Object v = e.eval(in, env);
            if (op.equals("not")) return !truthy(v);
            if (v instanceof Pos p) return new Pos(-p.x(), -p.y(), -p.z());
            return -Values.toNum(v, "The value after -", line);
        }
    }

    static final class Logic extends Expr {
        final boolean and;
        final Expr a, b;
        Logic(int line, boolean and, Expr a, Expr b) { super(line); this.and = and; this.a = a; this.b = b; }
        Object eval(Interp in, Env env) {
            Object x = a.eval(in, env);
            if (and ? !truthy(x) : truthy(x)) return x;
            return b.eval(in, env);
        }
    }

    static final class Binary extends Expr {
        final String op;
        final Expr a, b;
        Binary(int line, String op, Expr a, Expr b) { super(line); this.op = op; this.a = a; this.b = b; }
        Object eval(Interp in, Env env) {
            Object x = a.eval(in, env), y = b.eval(in, env);
            return switch (op) {
                case "==" -> Values.eq(x, y);
                case "!=" -> !Values.eq(x, y);
                case "<" -> Values.compare(x, y, line) < 0;
                case "<=" -> Values.compare(x, y, line) <= 0;
                case ">" -> Values.compare(x, y, line) > 0;
                case ">=" -> Values.compare(x, y, line) >= 0;
                case "in" -> Values.contains(y, x, line);
                case "not in" -> !Values.contains(y, x, line);
                default -> Values.arith(op, x, y, line);
            };
        }
    }

    static final class Call extends Expr {
        final Expr callee;
        final List<Expr> args;
        Call(int line, Expr callee, List<Expr> args) { super(line); this.callee = callee; this.args = args; }

        Object eval(Interp in, Env env) {
            Object f;
            List<Object> values = new ArrayList<>();
            if (callee instanceof Member m) {
                // obj.f(a): a function stored in a map, else f(obj, a) - so list.push(x) works like push(list, x)
                Object obj = m.obj.eval(in, env);
                Object inMap = obj instanceof Map<?, ?> map ? map.get(m.name) : null;
                if (inMap instanceof Callable) {
                    f = inMap;
                } else {
                    f = env.get(m.name, line);
                    values.add(obj);
                }
            } else if (callee instanceof Var v) {
                f = env.get(v.name, line);
            } else {
                f = callee.eval(in, env);
            }
            for (Expr a : args) values.add(a.eval(in, env));
            if (!(f instanceof Callable c)) throw error(describe(callee) + " is not a function (it is " + Values.typeName(f) + ").", line);
            return c.call(in, values, line);
        }

        private static String describe(Expr e) {
            if (e instanceof Var v) return "'" + v.name + "'";
            if (e instanceof Member m) return "'" + m.name + "'";
            return "This";
        }
    }

    static final class Index extends Expr {
        final Expr obj, index;
        Index(int line, Expr obj, Expr index) { super(line); this.obj = obj; this.index = index; }
        Object eval(Interp in, Env env) {
            Object o = obj.eval(in, env), k = index.eval(in, env);
            if (o instanceof List<?> l) {
                int i = Values.toInt(k, "A list index", line);
                if (i < 0) i += l.size();
                if (i < 0 || i >= l.size()) throw error("Index " + i + " is outside the list (it has " + l.size() + " items, counted from 0).", line);
                return l.get(i);
            }
            if (o instanceof Map<?, ?> m) return m.get(Values.key(k));
            if (o instanceof String s) {
                int i = Values.toInt(k, "A text index", line);
                if (i < 0) i += s.length();
                if (i < 0 || i >= s.length()) throw error("Index " + i + " is outside the text.", line);
                return String.valueOf(s.charAt(i));
            }
            throw error("Cannot use [...] on " + Values.typeName(o) + ".", line);
        }
    }

    static final class Member extends Expr {
        final Expr obj;
        final String name;
        Member(int line, Expr obj, String name) { super(line); this.obj = obj; this.name = name; }
        Object eval(Interp in, Env env) {
            Object o = obj.eval(in, env);
            if (o instanceof Pos p) {
                return switch (name) {
                    case "x" -> (double) p.x();
                    case "y" -> (double) p.y();
                    case "z" -> (double) p.z();
                    default -> throw error("A position has x, y and z, not '" + name + "'.", line);
                };
            }
            if (o instanceof Map<?, ?> m) return m.get(name);
            if (o instanceof List<?> l && (name.equals("length") || name.equals("size"))) return (double) l.size();
            if (o instanceof String s && name.equals("length")) return (double) s.length();
            throw error(Values.typeName(o) + " has no field '" + name + "'.", line);
        }
    }

    static final class ListLit extends Expr {
        final List<Expr> items;
        ListLit(int line, List<Expr> items) { super(line); this.items = items; }
        Object eval(Interp in, Env env) {
            List<Object> out = new ArrayList<>();
            for (Expr e : items) out.add(e.eval(in, env));
            return out;
        }
    }

    static final class MapLit extends Expr {
        final List<Expr> keys, values;
        MapLit(int line, List<Expr> keys, List<Expr> values) { super(line); this.keys = keys; this.values = values; }
        Object eval(Interp in, Env env) {
            Map<Object, Object> m = Values.newMap();
            for (int i = 0; i < keys.size(); i++) m.put(Values.key(keys.get(i).eval(in, env)), values.get(i).eval(in, env));
            return m;
        }
    }

    static final class Lambda extends Expr {
        final List<String> params;
        final List<Stmt> body;
        Lambda(int line, List<String> params, List<Stmt> body) { super(line); this.params = params; this.body = body; }
        Object eval(Interp in, Env env) { return new UserFunc("(function)", params, body, env, line); }
    }

    // ================================================================== functions and variables

    static final class UserFunc implements Callable {
        final String name;
        final List<String> params;
        final List<Stmt> body;
        final Env closure;
        final int line;

        UserFunc(String name, List<String> params, List<Stmt> body, Env closure, int line) {
            this.name = name; this.params = params; this.body = body; this.closure = closure; this.line = line;
        }

        @Override
        public String name() { return name; }

        @Override
        public Object call(Interp in, List<Object> args, int callLine) {
            if (args.size() > params.size()) {
                throw error(name + "() takes " + params.size() + " value" + (params.size() == 1 ? "" : "s") + ", not " + args.size() + ".", callLine);
            }
            Env env = new Env(closure);
            for (int i = 0; i < params.size(); i++) env.define(params.get(i), i < args.size() ? args.get(i) : null);
            if (++in.depth > 200) { in.depth = 0; throw error("Too many calls inside each other (a function that never stops calling itself?).", callLine); }
            try {
                run(body, in, env);
                return null;
            } catch (ReturnSig r) {
                return r.value;
            } finally {
                in.depth--;
            }
        }
    }

    static final class Env {
        final Map<String, Object> vars = new java.util.HashMap<>();
        final Env parent;

        Env(Env parent) { this.parent = parent; }

        void define(String name, Object v) { vars.put(name, v); }

        Object get(String name, int line) {
            for (Env e = this; e != null; e = e.parent) {
                if (e.vars.containsKey(name)) return e.vars.get(name);
            }
            throw error("'" + name + "' is not defined. Give it a value first (let " + name + " = ...).", line);
        }

        boolean has(String name) {
            for (Env e = this; e != null; e = e.parent) if (e.vars.containsKey(name)) return true;
            return false;
        }

        /** Changes the variable where it lives; a new name becomes a variable here. Built-in names cannot be replaced by accident. */
        void assign(String name, Object v) {
            for (Env e = this; e != null; e = e.parent) {
                if (e.vars.containsKey(name)) {
                    if (e.parent == null && e.vars.get(name) instanceof Builtins.Builtin) break;   // the builtins' scope: shadow, never overwrite
                    e.vars.put(name, v);
                    return;
                }
            }
            vars.put(name, v);
        }
    }
}
