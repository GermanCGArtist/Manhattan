package dev.baritonequeue;

import dev.baritonequeue.ScriptCheck.Level;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The second half of Test: looks at the world the script will run in. Only what can be known without running it:
 * positions written as pos(x, y, z) (directly or through a variable set once), texts set once, the Litematica
 * placements, the inventory and the chests seen open so far. Problems here are warnings unless they are certain.
 */
final class WorldCheck {
    interface Sink {
        void add(Level level, int line, String text);
    }

    private static final Set<String> CHEST_ARG0 = Set.of("take", "take_upto", "put", "dump", "chest_contents");
    private static final Set<String> SCHEM = Set.of("schem_build", "schem_needed", "schem_check", "schem_materials");

    private final Sink sink;
    private final Map<String, BlockPos> positions = new HashMap<>();
    private final Map<String, String> texts = new HashMap<>();
    private final Set<String> assignedTwice = new HashSet<>();
    private final List<Ast.Call> calls = new ArrayList<>();
    private final Set<String> done = new HashSet<>();   // each message once

    private WorldCheck(Sink sink) {
        this.sink = sink;
    }

    static void run(List<Ast.Stmt> program, Sink sink) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) return;   // not in a world: nothing to look at
        WorldCheck w = new WorldCheck(sink);
        w.stmts(program);
        for (String n : w.assignedTwice) { w.positions.remove(n); w.texts.remove(n); }
        w.check();
    }

    // ------------------------------------------------------------------ collecting

    private void define(String name, Ast.Expr value) {
        if (positions.containsKey(name) || texts.containsKey(name)) assignedTwice.add(name);
        BlockPos p = posOf(value);
        if (p != null) positions.put(name, p);
        else if (value instanceof Ast.Lit l && l.value instanceof String s) texts.put(name, s);
        else assignedTwice.add(name);   // set to something not known before running
    }

    private void stmts(List<Ast.Stmt> list) {
        if (list != null) for (Ast.Stmt s : list) stmt(s);
    }

    private void stmt(Ast.Stmt s) {
        if (s instanceof Ast.Let l) { if (l.value != null) { expr(l.value); define(l.name, l.value); } }
        else if (s instanceof Ast.Assign a) { expr(a.value); if (a.target instanceof Ast.Var v) define(v.name, a.op.equals("=") ? a.value : null); }
        else if (s instanceof Ast.ExprStmt e) expr(e.e);
        else if (s instanceof Ast.If i) { i.conds.forEach(this::expr); i.blocks.forEach(this::stmts); stmts(i.otherwise); }
        else if (s instanceof Ast.While w) { expr(w.cond); stmts(w.body); }
        else if (s instanceof Ast.Loop l) { if (l.times != null) expr(l.times); stmts(l.body); }
        else if (s instanceof Ast.For f) { expr(f.over); stmts(f.body); }
        else if (s instanceof Ast.On o) { expr(o.cond); stmts(o.body); }
        else if (s instanceof Ast.Try t) { stmts(t.body); stmts(t.handler); }
        else if (s instanceof Ast.WaitUntil w) expr(w.cond);
        else if (s instanceof Ast.Return r) { if (r.value != null) expr(r.value); }
        else if (s instanceof Ast.FuncDef f) stmts(f.body);
    }

    private void expr(Ast.Expr e) {
        if (e == null) return;
        if (e instanceof Ast.Call c) { calls.add(c); c.args.forEach(this::expr); if (c.callee instanceof Ast.Member m) expr(m.obj); }
        else if (e instanceof Ast.Binary b) { expr(b.a); expr(b.b); }
        else if (e instanceof Ast.Logic l) { expr(l.a); expr(l.b); }
        else if (e instanceof Ast.Unary u) expr(u.e);
        else if (e instanceof Ast.Index i) { expr(i.obj); expr(i.index); }
        else if (e instanceof Ast.Member m) expr(m.obj);
        else if (e instanceof Ast.ListLit l) l.items.forEach(this::expr);
        else if (e instanceof Ast.MapLit m) { m.keys.forEach(this::expr); m.values.forEach(this::expr); }
        else if (e instanceof Ast.Concat c) c.parts.forEach(this::expr);
        else if (e instanceof Ast.Lambda l) stmts(l.body);
    }

    // ------------------------------------------------------------------ resolving

    private static Double num(Ast.Expr e) {
        if (e instanceof Ast.Lit l && l.value instanceof Double d) return d;
        if (e instanceof Ast.Unary u && u.op.equals("-")) { Double d = num(u.e); return d == null ? null : -d; }
        return null;
    }

    private static BlockPos posOf(Ast.Expr e) {
        if (!(e instanceof Ast.Call c) || !(c.callee instanceof Ast.Var v) || !v.name.equals("pos") || c.args.size() != 3) return null;
        Double x = num(c.args.get(0)), y = num(c.args.get(1)), z = num(c.args.get(2));
        if (x == null || y == null || z == null) return null;
        return BlockPos.containing(x, y, z);
    }

    private BlockPos pos(Ast.Expr e) {
        BlockPos p = posOf(e);
        if (p == null && e instanceof Ast.Var v) p = positions.get(v.name);
        return p;
    }

    private String text(Ast.Expr e) {
        if (e instanceof Ast.Lit l && l.value instanceof String s) return s;
        if (e instanceof Ast.Lit l && l.value instanceof Double d) return String.valueOf((int) Math.floor(d));
        if (e instanceof Ast.Var v) return texts.get(v.name);
        return null;
    }

    private void warn(int line, String text) {
        if (done.add(text)) sink.add(Level.WARN, line, text);
    }

    private void error(int line, String text) {
        if (done.add(text)) sink.add(Level.ERROR, line, text);
    }

    // ------------------------------------------------------------------ checking

    private void check() {
        boolean flood = false;
        for (Ast.Call c : calls) {
            if (!(c.callee instanceof Ast.Var v)) continue;
            String f = v.name;
            int n = c.args.size();
            if (CHEST_ARG0.contains(f) && n > 0) {
                BlockPos p = pos(c.args.get(0));
                if (p != null && storage(p, c.line, f + "()") && (f.equals("take") || f.equals("take_upto")) && n > 1) contains(p, text(c.args.get(1)), c.line);
            }
            if (f.equals("fill_water") && n > 1) {
                BlockPos p = pos(c.args.get(1));
                if (p != null && storage(p, c.line, "fill_water()")) contains(p, text(c.args.get(0)), c.line);
            }
            if (f.equals("option") && n > 1) {
                String key = text(c.args.get(0));
                BlockPos p = pos(c.args.get(1));
                if (key != null && p != null) {
                    if (key.equalsIgnoreCase("survival.bed")) bed(p, c.line);
                    else if (key.equalsIgnoreCase("survival.supply") || key.equalsIgnoreCase("survival.returnChest") || key.equalsIgnoreCase("loadchest.chest")) storage(p, c.line, key);
                }
            }
            if (SCHEM.contains(f)) schem(c, f);
            if (f.equals("flood")) flood = true;
        }
        if (flood) water(lineOf("flood"));
    }

    private int lineOf(String fn) {
        for (Ast.Call c : calls) if (c.callee instanceof Ast.Var v && v.name.equals(fn)) return c.line;
        return 0;
    }

    /** Is there a chest, barrel or shulker box? Warns when the chunk is not loaded. */
    private boolean storage(BlockPos p, int line, String what) {
        var level = Minecraft.getInstance().level;
        String at = p.getX() + " " + p.getY() + " " + p.getZ();
        if (!level.hasChunkAt(p)) { warn(line, "Cannot see " + at + " from here (not loaded): go closer and Test again to check the chest for " + what + "."); return false; }
        Block b = level.getBlockState(p).getBlock();
        if (b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock) return true;
        error(line, "There is no chest, barrel or shulker box at " + at + " (for " + what + "), but " + name(b) + ".");
        return false;
    }

    private static String name(Block b) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath();
    }

    private void bed(BlockPos p, int line) {
        var level = Minecraft.getInstance().level;
        String at = p.getX() + " " + p.getY() + " " + p.getZ();
        if (!level.hasChunkAt(p)) { warn(line, "Cannot see the bed at " + at + " from here (not loaded)."); return; }
        if (!(level.getBlockState(p).getBlock() instanceof BedBlock)) error(line, "There is no bed at " + at + ", but " + name(level.getBlockState(p).getBlock()) + ".");
    }

    /** The chest was seen open and had none of the item. */
    private void contains(BlockPos p, String item, int line) {
        if (item == null) return;
        Map<String, Integer> m = dev.survivalkit.Api.chestContents(p);
        if (m == null) return;   // never opened: unknown
        int have = 0;
        for (var e : m.entrySet()) if (e.getKey().contains(item.toLowerCase())) have += e.getValue();
        if (have == 0) warn(line, "The chest at " + p.getX() + " " + p.getY() + " " + p.getZ() + " had no " + item + " when it was last open.");
    }

    private void schem(Ast.Call c, String f) {
        String sel = null, mode = f.equals("schem_build") ? "add" : "fix";
        for (Ast.Expr a : c.args) {
            String s = text(a);
            if (s == null) return;   // decided while running: cannot check
            if (Schem.Mode.of(s) != null) mode = s.toLowerCase();
            else if (s.equalsIgnoreCase("all") || s.matches("[0-9]+")) sel = s;
        }
        if (!done.add("schem " + sel + " " + mode + " " + f.equals("schem_build"))) return;
        String[] err = new String[1];
        var pls = SchemCmd.select(sel, err);
        if (pls == null) { error(c.line, err[0]); return; }
        if (!f.equals("schem_build")) return;
        Schem.Plan plan = Schem.plan(pls, err);
        if (plan == null) { error(c.line, err[0]); return; }
        Schem.Counts counts = Schem.count(plan, Schem.Mode.of(mode), Minecraft.getInstance().level);
        List<String> nowhere = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : counts.items.entrySet()) {
            String name = Schem.itemName(e.getKey());
            if (Schem.have(e.getKey()) > 0) continue;
            if (!dev.survivalkit.Api.findItem(name).isEmpty()) continue;
            nowhere.add(name + " x" + e.getValue());
        }
        if (counts.unloaded > 0) warn(c.line, plan.name + ": " + counts.unloaded + " blocks are in chunks that are not loaded; Test counted only the rest.");
        if (!nowhere.isEmpty()) {
            warn(c.line, plan.name + " (" + mode + ") needs items that are neither in your inventory nor in any chest seen open: "
                    + String.join(", ", nowhere.subList(0, Math.min(12, nowhere.size()))) + (nowhere.size() > 12 ? " ..." : "")
                    + ". With \"skip\" those blocks are left out. (Chests never opened are not known: open them once.)");
        }
    }

    private void water(int line) {
        Item full = net.minecraft.world.item.Items.WATER_BUCKET, empty = net.minecraft.world.item.Items.BUCKET;
        boolean water = Schem.have(full) > 0 || !dev.survivalkit.Api.findItem("water_bucket").isEmpty();
        boolean bucket = water || Schem.have(empty) > 0 || !dev.survivalkit.Api.findItem("minecraft:bucket").isEmpty();
        if (!bucket) warn(line, "flood() needs a bucket: there is none in your inventory or in any chest seen open.");
        else if (!water) warn(line, "flood() needs water for its first bucket: a full water bucket, or water within 16 blocks of where it starts. None is in your inventory or the chests seen open.");
    }
}
