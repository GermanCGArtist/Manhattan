package dev.baritonequeue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.baritonequeue.Schem.Mode;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /schem list | build [n|all] [add|fix|exact] [skip|strict] | materials [n|all] [mode] | check [n|all] [mode] | stop
 * and the same things as script functions (schem_list(), schem_build(...) ...), which call the methods here.
 */
final class SchemCmd {
    private SchemCmd() {}

    // ------------------------------------------------------------------ choosing placements

    /** The placements the words name ("all" = every enabled one, "2" = the second one, nothing = the only enabled one). */
    static List<SchemSource.Placement> select(String sel, String[] err) {
        List<SchemSource.Placement> all = SchemSource.list();
        if (all.isEmpty()) {
            err[0] = "No Litematica placements found (" + SchemSource.lastSource + "). Load a schematic in Litematica first.";
            return null;
        }
        List<SchemSource.Placement> out = new ArrayList<>();
        if (sel == null || sel.isEmpty()) {
            for (SchemSource.Placement p : all) if (p.enabled()) out.add(p);
            if (out.size() != 1) {
                err[0] = out.isEmpty() ? "No placement is enabled. Name one: /schem list, then /schem build <n>."
                        : out.size() + " placements are enabled; say which one: /schem build <n>, or /schem build all.";
                return null;
            }
        } else if (sel.equalsIgnoreCase("all")) {
            for (SchemSource.Placement p : all) if (p.enabled()) out.add(p);
            if (out.isEmpty()) {
                err[0] = "No placement is enabled.";
                return null;
            }
        } else {
            int n;
            try {
                n = Integer.parseInt(sel);
            } catch (NumberFormatException e) {
                err[0] = "'" + sel + "' is not a placement number or \"all\". /schem list shows them.";
                return null;
            }
            if (n < 1 || n > all.size()) {
                err[0] = "There is no placement " + n + " (there are " + all.size() + "). /schem list shows them.";
                return null;
            }
            out.add(all.get(n - 1));
        }
        return out;
    }

    // ------------------------------------------------------------------ the actions

    static int list() {
        List<SchemSource.Placement> all = SchemSource.list();
        if (all.isEmpty()) {
            Util.msg("No Litematica placements found (" + SchemSource.lastSource + ").");
            return 0;
        }
        Util.msg(all.size() + " placements (from " + SchemSource.lastSource + "):");
        for (int i = 0; i < all.size(); i++) {
            SchemSource.Placement p = all.get(i);
            long off = p.subs().stream().filter(s -> !s.enabled()).count();
            Util.msg((i + 1) + ". " + p.name() + (p.enabled() ? "" : " [off]") + "  at " + p.origin().getX() + " " + p.origin().getY() + " " + p.origin().getZ()
                    + (p.rotation() != net.minecraft.world.level.block.Rotation.NONE ? "  " + p.rotation().name().toLowerCase() : "")
                    + (p.mirror() != net.minecraft.world.level.block.Mirror.NONE ? "  mirror " + p.mirror().name().toLowerCase() : "")
                    + "  " + p.subs().size() + " region" + (p.subs().size() == 1 ? "" : "s") + (off > 0 ? " (" + off + " off)" : ""));
        }
        return all.size();
    }

    /** Prints the material list; returns the number of items still missing from the inventory. */
    static int materials(String sel, Mode mode) {
        String[] err = new String[1];
        List<SchemSource.Placement> pls = select(sel, err);
        if (pls == null) { Util.msg(err[0]); return -1; }
        Schem.Plan plan = Schem.plan(pls, err);
        if (plan == null) { Util.msg(err[0]); return -1; }
        Schem.Counts c = Schem.count(plan, mode, Minecraft.getInstance().level);
        List<Map.Entry<Item, Integer>> items = new ArrayList<>(c.items.entrySet());
        items.sort((a, b) -> b.getValue() - a.getValue());
        Util.msg("Materials for " + plan.name + " (" + mode.label() + "), minus what is already built:");
        int missingTotal = 0, shown = 0;
        for (Map.Entry<Item, Integer> e : items) {
            int have = Schem.have(e.getKey());
            boolean reusable = c.reusable.contains(e.getKey());
            int miss = Math.max(0, e.getValue() - have);
            missingTotal += miss;
            if (shown++ < 40) Util.msg("  " + Schem.itemName(e.getKey()) + " x" + e.getValue() + (reusable ? " (reusable, refill at a source)" : "")
                    + "   have " + have + (miss > 0 ? ", MISSING " + miss : ", ok"));
        }
        if (items.size() > 40) Util.msg("  ... and " + (items.size() - 40) + " more kinds");
        if (c.noItem > 0) Util.msg("  " + c.noItem + " blocks have no item (crops, fire ...): they cannot be placed by Baritone");
        Util.msg(items.isEmpty() ? "Nothing more is needed." : missingTotal == 0 ? "The inventory has everything." : missingTotal + " items are missing from the inventory.");
        return missingTotal;
    }

    /** Prints what differs between the schematic and the world; returns the blocks that still need work. */
    static int check(String sel, Mode mode) {
        String[] err = new String[1];
        List<SchemSource.Placement> pls = select(sel, err);
        if (pls == null) { Util.msg(err[0]); return -1; }
        Schem.Plan plan = Schem.plan(pls, err);
        if (plan == null) { Util.msg(err[0]); return -1; }
        Schem.Counts c = Schem.count(plan, mode, Minecraft.getInstance().level);
        Util.msg("Check " + plan.name + " (" + mode.label() + "): " + c.correct + " correct, " + c.missing + " missing, " + c.wrong + " wrong" + (c.wrongState > 0 ? " (" + c.wrongState + " of them the right block in another orientation/state)" : "")
                + (mode == Mode.EXACT ? ", " + c.extra + " to dig out" : "") + (c.unloaded > 0 ? ", " + c.unloaded + " in unloaded chunks" : "")
                + (c.excluded > 0 ? ", " + c.excluded + " in safe areas (skipped)" : "") + ".");
        if (!c.todoByPhase.isEmpty()) {
            StringBuilder sb = new StringBuilder("  by phase:");
            c.todoByPhase.forEach((k, v) -> sb.append(' ').append(k.name().toLowerCase()).append('=').append(v));
            Util.msg(sb.toString());
        }
        for (String e : c.examples) Util.msg("  " + e);
        if (!plan.fluids.isEmpty()) Util.msg("  fluids (not built yet): " + plan.fluids);
        for (String n : plan.notes) Util.msg("  note: " + n);
        return c.todoTotal();
    }

    static int stop() {
        if (QueueMod.schem.working()) QueueMod.schem.cancel();
        else Util.msg("No schematic is being built.");
        return 1;
    }

    // ------------------------------------------------------------------ chat

    /** "build 2 fix", "check all", "materials exact" ... : the selection and the mode in any order. */
    private static String[] words(String args) {
        String sel = null;
        String mode = null;
        String missing = "skip";
        for (String w : args.trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            if (Mode.of(w) != null) mode = w;
            else if (w.equalsIgnoreCase("skip") || w.equalsIgnoreCase("strict")) missing = w.toLowerCase();
            else sel = w;
        }
        return new String[]{sel, mode, missing};
    }

    private static int run(String verb, String args, Mode defaultMode) {
        String[] w = words(args);
        Mode mode = w[1] == null ? defaultMode : Mode.of(w[1]);
        switch (verb) {
            case "build" -> {
                String[] err = new String[1];
                List<SchemSource.Placement> pls = select(w[0], err);
                if (pls == null) { Util.msg(err[0]); return 0; }
                QueueMod.schem.skipMissing(!"strict".equals(w[2]));
                String e = QueueMod.schem.begin(pls, mode);
                if (e != null && !e.isEmpty()) Util.msg(e);
                return e == null || e.isEmpty() ? 1 : 0;
            }
            case "materials" -> { return materials(w[0], mode) >= 0 ? 1 : 0; }
            case "check" -> { return check(w[0], mode) >= 0 ? 1 : 0; }
            default -> { return 0; }
        }
    }

    static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(ClientCommands.literal("schem")
                .executes(c -> { Util.msg("/schem list | build [n|all] [add|fix|exact] [skip|strict] | materials [n|all] [mode] | check [n|all] [mode] | stop"); return 1; })
                .then(ClientCommands.literal("list").executes(c -> list() > 0 ? 1 : 0))
                .then(ClientCommands.literal("stop").executes(c -> stop()))
                .then(verb("build", Mode.ADD))
                .then(verb("materials", Mode.FIX))
                .then(verb("check", Mode.FIX)));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> verb(String name, Mode def) {
        return ClientCommands.literal(name)
                .executes(c -> run(name, "", def))
                .then(ClientCommands.argument("args", StringArgumentType.greedyString())
                        .executes(c -> run(name, StringArgumentType.getString(c, "args"), def)));
    }
}
