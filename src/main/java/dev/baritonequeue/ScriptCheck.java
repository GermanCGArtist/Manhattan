package dev.baritonequeue;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Test: reads the script without running anything. Finds what is certainly wrong (it does not parse, a name that is
 * never defined, a built-in function with the wrong number of values, an item or block name that does not exist, a #
 * line Baritone does not know) and what is probably a mistake. Whether the world lets it succeed only running shows.
 */
final class ScriptCheck {
    private ScriptCheck() {}

    enum Level { OK, WARN, ERROR }

    record Entry(Level level, int line, String text) {}

    private static final Set<String> ENDLESS = Set.of("farm", "follow", "explore", "explorefilter");
    private static final Set<String> SEL = Set.of("sel", "selection", "s");
    private static final Set<String> SEL_BLOCKS = Set.of("set", "fill", "s", "f", "walls", "w", "shell", "sphere", "hsphere",
            "cylinder", "hcylinder", "replace", "r");
    private static final Set<String> ITEM_ARG1 = Set.of("take", "take_upto", "put");
    private static final Set<String> ITEM_ARG0 = Set.of("fill_water");

    private final List<Entry> out = new ArrayList<>();
    private final Set<String> defined = new HashSet<>();
    private int errors, warnings;

    static List<Entry> run(List<String> script) {
        ScriptCheck c = new ScriptCheck();
        String text = String.join("\n", script);
        int lines = 0;
        for (String l : script) if (!Util.stripComment(l).isBlank()) lines++;
        try {
            List<Ast.Stmt> program = Parser.parse(text);
            c.defined.addAll(Suggest.definedNames(text));
            c.collect(program);
            c.block(program);
        } catch (Parser.ParseError e) {
            c.add(Level.ERROR, e.line, e.getMessage() + oldStyleHint(script, e.line));
        }
        String summary = lines + " line" + (lines == 1 ? "" : "s") + " checked: "
                + (c.errors == 0 ? "no errors" : c.errors + " error" + (c.errors == 1 ? "" : "s"))
                + (c.warnings > 0 ? ", " + c.warnings + " warning" + (c.warnings == 1 ? "" : "s") : "") + ".";
        c.out.add(new Entry(c.errors == 0 ? (c.warnings == 0 ? Level.OK : Level.WARN) : Level.ERROR, 0, summary));
        return c.out;
    }

    /** A line in the old one-command-per-line style: say how it is written now. */
    static String oldStyleHint(List<String> script, int line) {
        if (line < 1 || line > script.size()) return "";
        String l = Util.stripComment(script.get(line - 1)).trim().toLowerCase();
        String first = l.split("\\s+")[0];
        if (first.equals("sel") || first.equals("mine") || first.equals("goto") || Util.settingName(first) != null
                || first.equals("set") || first.equals("farm") || first.equals("tunnel") || first.equals("explore")) {
            return " (Baritone commands start with #, for example: #" + l + ")";
        }
        if (first.equals("chest")) return " (now: take(chest, \"dirt\", 64), take_upto(chest, \"dirt\", 512), put(chest, \"dirt\"), dump(chest))";
        if (first.equals("def_chest")) return " (now: let dirt = pos(x, y, z))";
        if (first.equals("area")) return " (now: area(\"name\"))";
        return "";
    }

    private void add(Level level, int line, String text) {
        out.add(new Entry(level, line, text));
        if (level == Level.ERROR) errors++;
        else if (level == Level.WARN) warnings++;
    }

    /** Names that come into being anywhere: assignments, parameters, loop variables. */
    private void collect(List<Ast.Stmt> stmts) {
        for (Ast.Stmt s : stmts) {
            if (s instanceof Ast.Let l) defined.add(l.name);
            else if (s instanceof Ast.FuncDef f) { defined.add(f.name); defined.addAll(f.params); collect(f.body); }
            else if (s instanceof Ast.Assign a && a.target instanceof Ast.Var v) defined.add(v.name);
            else if (s instanceof Ast.For f) { defined.add(f.a); if (f.b != null) defined.add(f.b); collect(f.body); }
            else if (s instanceof Ast.If i) { i.blocks.forEach(this::collect); if (i.otherwise != null) collect(i.otherwise); }
            else if (s instanceof Ast.While w) collect(w.body);
            else if (s instanceof Ast.Loop l) collect(l.body);
            else if (s instanceof Ast.On o) collect(o.body);
            else if (s instanceof Ast.Try t) { if (t.var != null) defined.add(t.var); collect(t.body); collect(t.handler); }
        }
    }

    private void block(List<Ast.Stmt> stmts) {
        for (int i = 0; i < stmts.size(); i++) {
            Ast.Stmt s = stmts.get(i);
            stmt(s, i == stmts.size() - 1);
        }
    }

    private void stmt(Ast.Stmt s, boolean last) {
        if (s instanceof Ast.Let l) { if (l.value != null) expr(l.value); }
        else if (s instanceof Ast.Assign a) { expr(a.target); expr(a.value); }
        else if (s instanceof Ast.ExprStmt e) {
            expr(e.e);
            if (e.e instanceof Ast.Binary b && b.op.equals("==")) add(Level.WARN, s.line, "This compares and throws the answer away. To give a value, use a single = (x = 5).");
        }
        else if (s instanceof Ast.If i) { i.conds.forEach(this::expr); i.blocks.forEach(this::block); if (i.otherwise != null) block(i.otherwise); }
        else if (s instanceof Ast.While w) { expr(w.cond); block(w.body); }
        else if (s instanceof Ast.Loop l) { if (l.times != null) expr(l.times); block(l.body); }
        else if (s instanceof Ast.For f) { expr(f.over); block(f.body); }
        else if (s instanceof Ast.On o) { expr(o.cond); block(o.body); }
        else if (s instanceof Ast.Try t) { block(t.body); block(t.handler); }
        else if (s instanceof Ast.WaitUntil w) expr(w.cond);
        else if (s instanceof Ast.Return r) { if (r.value != null) expr(r.value); }
        else if (s instanceof Ast.FuncDef f) block(f.body);
        else if (s instanceof Ast.Raw r) {
            if (r.chat) add(Level.WARN, s.line, "'/" + rawText(r) + "' is a chat command: it is run, but not checked.");
            else baritone(rawText(r), s.line, last);
        }
    }

    private static String rawText(Ast.Raw r) {
        if (r.text instanceof Ast.Lit l) return Values.str(l.value);
        if (r.text instanceof Ast.Concat c) {
            StringBuilder sb = new StringBuilder();
            for (Ast.Expr e : c.parts) sb.append(e instanceof Ast.Lit l ? Values.str(l.value) : "{...}");
            return sb.toString();
        }
        return "";
    }

    private void expr(Ast.Expr e) {
        if (e == null) return;
        if (e instanceof Ast.Var v) {
            if (!defined.contains(v.name) && !Builtins.all().containsKey(v.name)) {
                add(Level.ERROR, e.line, "'" + v.name + "' is not defined (no let " + v.name + " = ... and no built-in function of that name).");
            }
        } else if (e instanceof Ast.Call c) {
            if (c.callee instanceof Ast.Var v && !defined.contains(v.name)) {
                Builtins.Builtin b = Builtins.all().get(v.name);
                if (b == null) add(Level.ERROR, e.line, "There is no function '" + v.name + "'.");
                else {
                    int n = c.args.size();
                    if (n < b.min || (b.max >= 0 && n > b.max)) add(Level.ERROR, e.line, v.name + " is used like this: " + b.signature);
                    if (ITEM_ARG1.contains(v.name) && n > 1) itemArg(c.args.get(1));
                    if (ITEM_ARG0.contains(v.name) && n > 0) itemArg(c.args.get(0));
                    if ((v.name.equals("count_blocks")) && n > 0) for (Ast.Expr a : c.args) blockArg(a);
                    if (v.name.equals("set") && n > 0 && c.args.get(0) instanceof Ast.Lit l && l.value instanceof String s && Util.settingName(s) == null) {
                        add(Level.ERROR, e.line, "'" + s + "' is not a Baritone setting.");
                    }
                    if (v.name.equals("area") && n > 0 && c.args.get(0) instanceof Ast.Lit l && l.value instanceof String s
                            && !s.equalsIgnoreCase("last") && !Config.cfg.areas.containsKey(s)) {
                        add(Level.ERROR, e.line, "There is no saved area '" + s + "' (yet). /queue area save " + s + " saves the current selection.");
                    }
                }
            } else {
                expr(c.callee instanceof Ast.Member m ? m.obj : c.callee);
            }
            c.args.forEach(this::expr);
        } else if (e instanceof Ast.Binary b) { expr(b.a); expr(b.b); }
        else if (e instanceof Ast.Logic l) { expr(l.a); expr(l.b); }
        else if (e instanceof Ast.Unary u) expr(u.e);
        else if (e instanceof Ast.Index i) { expr(i.obj); expr(i.index); }
        else if (e instanceof Ast.Member m) expr(m.obj);
        else if (e instanceof Ast.ListLit l) l.items.forEach(this::expr);
        else if (e instanceof Ast.MapLit m) { m.keys.forEach(this::expr); m.values.forEach(this::expr); }
        else if (e instanceof Ast.Concat c) c.parts.forEach(this::expr);
        else if (e instanceof Ast.Lambda l) block(l.body);
    }

    private void itemArg(Ast.Expr a) {
        if (a instanceof Ast.Lit l && l.value instanceof String s && ChestOp.itemOf(s) == null) {
            add(Level.ERROR, a.line, "'" + s + "' is not an item name.");
        }
    }

    private void blockArg(Ast.Expr a) {
        if (a instanceof Ast.Lit l && l.value instanceof String s && !Syntax.isBlock(s)) {
            add(Level.ERROR, a.line, "'" + s + "' is not a block name.");
        }
    }

    // ------------------------------------------------------------------ # lines

    private void baritone(String c, int n, boolean last) {
        String[] w = c.trim().split("\\s+");
        if (w.length == 0 || w[0].isEmpty()) return;
        String label = w[0].toLowerCase();
        var b = Util.baritone();
        var command = b.getCommandManager().getCommand(label);
        if (command == null && Util.settingName(label) != null) {
            if (w.length < 2) add(Level.ERROR, n, "'" + w[0] + "' is a Baritone setting and needs a value, for example: #" + w[0] + " true");
            return;
        }
        if (command == null) {
            String hint = "";
            try {
                var maybe = b.getCommandManager().tabComplete(label).limit(3).toList();
                if (!maybe.isEmpty()) hint = " Did you mean: " + String.join(", ", maybe) + "?";
            } catch (Throwable ignored) {
            }
            add(Level.ERROR, n, "Unknown Baritone command '#" + w[0] + "'." + hint);
            return;
        }
        if (SEL.contains(label) && w.length > 1 && SEL_BLOCKS.contains(w[1].toLowerCase())) {
            for (int k = 2; k < w.length; k++) badBlock(w[k], n);
        } else if (label.equals("mine") || label.equals("m")) {
            boolean finite = false;
            for (int k = 1; k < w.length; k++) {
                if (w[k].matches("\\d+")) { finite = true; continue; }
                badBlock(w[k], n);
            }
            if (w.length == 1) add(Level.ERROR, n, "'#mine' needs a block, for example: #mine diamond_ore");
            else if (!finite && !last) add(Level.WARN, n, "'#mine' without a number never finishes, so what comes after it never starts (Skip moves on). Example: #mine 64 iron_ore");
        } else if (ENDLESS.contains(label) && !last) {
            add(Level.WARN, n, "'#" + label + "' never finishes by itself, so what comes after it never starts (Skip moves on).");
        }
    }

    private void badBlock(String word, int n) {
        String w = word.toLowerCase();
        int bracket = w.indexOf('[');
        if (bracket >= 0) w = w.substring(0, bracket);
        if (w.isEmpty() || w.matches("-?\\d+") || w.startsWith("{")) return;
        if (Set.of("all", "up", "down", "north", "south", "east", "west", "a", "d").contains(w)) return;
        Identifier id = Identifier.tryParse(w.contains(":") ? w : "minecraft:" + w);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) add(Level.ERROR, n, "'" + word + "' is not a block name.");
    }
}
