package dev.baritonequeue;

import baritone.api.command.ICommand;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Completions while typing. In code: the language's words, the built-in functions (with what they do) and the names
 * the script defines. Inside quotes: item names, block names, settings ... depending on the function. After '#':
 * Baritone's own completion. And, like /setblock, where a position fits: the block you were looking at.
 */
final class Suggest {
    private Suggest() {}

    /** One completion: the text that goes in, and what it does (may be empty). */
    record Item(String text, String desc) {}

    /** The completions, and where the word they replace starts. */
    record Result(List<Item> items, int from) {
        static final Result NONE = new Result(List.of(), 0);
    }

    private static final int MAX = 150;

    private static final Map<String, String> KEYWORDS = new TreeMap<>(Map.ofEntries(
            Map.entry("let", "make a variable: let home = pos(100, 64, -20)"),
            Map.entry("func", "a function of your own: func refill(n) ... end"),
            Map.entry("if", "if <condition> ... elif ... else ... end"),
            Map.entry("elif", "another case of an if"),
            Map.entry("else", "the other case of an if"),
            Map.entry("end", "closes if / while / for / repeat / loop / func / on / try"),
            Map.entry("while", "while <condition> ... end"),
            Map.entry("for", "for item in list ... end   /   for i in range(10) ... end"),
            Map.entry("repeat", "repeat 3 ... end"),
            Map.entry("loop", "loop ... end   runs forever (until break or stop)"),
            Map.entry("break", "leave the loop"),
            Map.entry("continue", "next round of the loop"),
            Map.entry("return", "leave the function (with a value)"),
            Map.entry("on", "on <condition> ... end   runs whenever it becomes true, interrupting what runs"),
            Map.entry("try", "try ... catch err ... end   carry on after an error"),
            Map.entry("catch", "what to do when the try part failed"),
            Map.entry("wait", "wait 30   /   wait until day"),
            Map.entry("until", "wait until <condition>"),
            Map.entry("and", "both must be true"),
            Map.entry("or", "one must be true"),
            Map.entry("not", "the opposite"),
            Map.entry("true", ""), Map.entry("false", ""), Map.entry("null", "nothing")));

    private static final Set<String> ITEM_FUNCS = Set.of("take", "take_upto", "put", "count", "has", "want", "keep", "fill_water", "find_item");
    private static final Set<String> BLOCK_FUNCS = Set.of("count_blocks");
    private static final List<String> FEATURES = List.of("escape", "drain", "scaffold", "fill", "sleep", "eat", "defence", "restock", "pickup", "autoempty");

    /** What the editor saw under the crosshair when it opened (or picked). */
    static Places.Target target;

    static Result complete(String text, int cursor, boolean force) {
        String before = text.substring(0, cursor);
        int lineStart = before.lastIndexOf('\n') + 1;
        String line = before.substring(lineStart);
        String trimmed = line.stripLeading();

        if (trimmed.startsWith("#")) return baritone(trimmed.substring(1), cursor, force);
        if (trimmed.startsWith("//")) return Result.NONE;
        if (trimmed.startsWith("/")) {
            if (target != null && lastWord(line).isEmpty() && line.endsWith(" ")) return new Result(List.of(coords(" ")), cursor);
            return Result.NONE;
        }
        if (Lexer.commentStart(line, 0) >= 0 && outsideQuotes(line, Lexer.commentStart(line, 0))) return Result.NONE;

        // inside "...": names that fit the function
        int quote = openQuote(line);
        if (quote >= 0) {
            String partial = line.substring(quote + 1);
            String fn = functionBefore(line.substring(0, quote));
            int argIndex = argIndex(line.substring(0, quote));
            List<String> options = quoted(fn, argIndex, partial);
            if (options == null) return Result.NONE;
            List<Item> out = new ArrayList<>();
            for (String o : options) {
                if (o.startsWith(partial) && !o.equals(partial)) out.add(new Item(o, ""));
                if (out.size() >= MAX) break;
            }
            return new Result(out, cursor - partial.length());
        }

        // code
        int i = line.length();
        while (i > 0 && (Character.isLetterOrDigit(line.charAt(i - 1)) || line.charAt(i - 1) == '_')) i--;
        String partial = line.substring(i);
        String head = line.substring(0, i).stripTrailing();
        char prev = head.isEmpty() ? '\n' : head.charAt(head.length() - 1);
        List<Item> out = new ArrayList<>();

        if (partial.isEmpty() && target != null && (prev == '(' || prev == ',' || prev == '=')) {   // like /setblock: the block you look at
            out.add(head.endsWith("pos(") ? coords(", ") : posItem());
            if (!force) return new Result(out, cursor);
        }
        if (partial.isEmpty() && !force) return new Result(out, cursor);
        if (prev == '.') return new Result(out, cursor);   // a field: no guesses
        if (Character.isDigit(partial.isEmpty() ? 'a' : partial.charAt(0))) return new Result(out, cursor);

        if (trimmed.equals(partial) && target != null) {   // a fresh line: offer to name the looked-at block
            String v = Places.varName(target.name());
            if (v.startsWith(partial) || force) out.add(new Item("let " + v + " = " + posItem().text(), "the " + target.name() + " you looked at, " + target.pos()));
        }
        Set<String> names = new LinkedHashSet<>();
        KEYWORDS.forEach((k, d) -> { if (k.startsWith(partial) && !k.equals(partial)) out.add(new Item(k, d)); });
        for (String n : definedNames(text)) {
            if (n.startsWith(partial) && !n.equals(partial) && names.add(n)) out.add(new Item(n, "defined in this script"));
        }
        for (Builtins.Builtin b : Builtins.all().values()) {
            if (!b.name.startsWith(partial) || b.name.equals(partial)) continue;
            String insert = b.getter ? b.name : b.name + "(";
            out.add(new Item(insert, b.signature + "  -  " + b.desc));
        }
        out.sort((a, b) -> {
            int pa = a.text().startsWith(partial) ? 0 : 1, pb = b.text().startsWith(partial) ? 0 : 1;
            return pa != pb ? pa - pb : a.text().compareTo(b.text());
        });
        return new Result(out.size() > MAX ? out.subList(0, MAX) : out, cursor - partial.length());
    }

    private static Item posItem() {
        Values.Pos p = target.pos();
        return new Item("pos(" + p.x() + ", " + p.y() + ", " + p.z() + ")", "the " + target.name() + " you look at");
    }

    private static Item coords(String sep) {
        Values.Pos p = target.pos();
        String end = sep.equals(", ") ? ")" : "";
        return new Item(p.x() + sep + p.y() + sep + p.z() + end, "the " + target.name() + " you look at");
    }

    // ------------------------------------------------------------------ inside quotes

    private static boolean outsideQuotes(String line, int upTo) {
        return openQuote(line.substring(0, upTo)) < 0;
    }

    /** Index of the quote that is still open at the end of the line, or -1. */
    private static int openQuote(String line) {
        int open = -1;
        char q = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (open >= 0) {
                if (c == '\\') { i++; continue; }
                if (c == q) open = -1;
            } else if (c == '"' || c == '\'') {
                open = i;
                q = c;
            } else if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                return -1;
            }
        }
        return open;
    }

    private static final Pattern CALL = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*\\($");

    /** The function whose argument list the text ends in: "take(chest, " -> take. */
    private static String functionBefore(String s) {
        int depth = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == ')' || c == ']') depth++;
            else if (c == '[') depth--;
            else if (c == '(') {
                if (depth == 0) {
                    Matcher m = CALL.matcher(s.substring(0, i + 1));
                    return m.find() ? m.group(1) : "";
                }
                depth--;
            }
        }
        return "";
    }

    private static int argIndex(String s) {
        int depth = 0, commas = 0;
        boolean inQuote = false;
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == '"') inQuote = !inQuote;
            if (inQuote) continue;
            if (c == ')' || c == ']') depth++;
            else if (c == '(' || c == '[') {
                if (depth == 0) return commas;
                depth--;
            } else if (c == ',' && depth == 0) commas++;
        }
        return commas;
    }

    private static List<String> quoted(String fn, int arg, String partial) {
        if (ITEM_FUNCS.contains(fn)) return registry(net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet(), partial, fn.equals("want") || fn.equals("keep") ? List.of("food") : List.of());
        if (BLOCK_FUNCS.contains(fn)) return registry(net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet(), partial, List.of());
        switch (fn) {
            case "set", "setting" -> {
                if (arg != 0) return List.of();
                List<String> out = new ArrayList<>();
                for (var s : baritone.api.BaritoneAPI.getSettings().allSettings) out.add(s.getName());
                out.sort(String.CASE_INSENSITIVE_ORDER);
                return out;
            }
            case "option" -> { return arg == 0 ? new ArrayList<>(Modules.all().keySet()) : List.of(); }
            case "area", "save_area" -> {
                List<String> out = new ArrayList<>(Config.cfg.areas.keySet());
                out.add("last");
                return out;
            }
            case "chest", "unload", "take", "put", "dump" -> { return new ArrayList<>(Config.cfg.chests.keySet()); }
            case "disable", "enable" -> { return FEATURES; }
            case "unprotect", "protect" -> { return List.of(); }
            default -> { return null; }
        }
    }

    private static List<String> registry(Set<net.minecraft.resources.Identifier> ids, String partial, List<String> extra) {
        List<String> out = new ArrayList<>(extra);
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {   // what you carry first
            for (int i = 0; i < 36; i++) {
                var st = mc.player.getInventory().getItem(i);
                if (st.isEmpty()) continue;
                String n = ChestOp.name(st.getItem());
                if (n.startsWith(partial) && !out.contains(n)) out.add(n);
            }
        }
        ids.stream().map(net.minecraft.resources.Identifier::getPath).filter(n -> n.startsWith(partial) && !out.contains(n)).sorted().limit(MAX).forEach(out::add);
        return out;
    }

    // ------------------------------------------------------------------ names in the script

    private static final Pattern DEFS = Pattern.compile("\\b(?:let|func|function|for)\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s*,\\s*([A-Za-z_][A-Za-z0-9_]*))?|\\bcatch\\s+([A-Za-z_][A-Za-z0-9_]*)|^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=[^=]", Pattern.MULTILINE);

    static Set<String> definedNames(String text) {
        Set<String> out = new java.util.TreeSet<>();
        Matcher m = DEFS.matcher(text);
        while (m.find()) {
            for (int g = 1; g <= 4; g++) if (m.group(g) != null) out.add(m.group(g));
        }
        out.removeAll(Lexer.KEYWORDS);
        return out;
    }

    // ------------------------------------------------------------------ # lines

    private static Result baritone(String line, int cursor, boolean force) {
        String partial = lastWord(line);
        String lower = line.toLowerCase().stripLeading();
        List<Item> out = new ArrayList<>();
        if (target != null && lower.contains(" ") && (partial.isEmpty() || partial.matches("-?\\d*"))) {
            out.add(coords(" "));
        }
        String settingFirst = lower.contains(" ") ? Util.settingName(lower.substring(0, lower.indexOf(' '))) : null;
        String asked = line.stripLeading();
        if (settingFirst != null && Util.baritone().getCommandManager().getCommand(lower.substring(0, lower.indexOf(' '))) == null) {
            asked = "set " + settingFirst + asked.substring(asked.indexOf(' '));
        }
        if (!lower.contains(" ")) {
            if (lower.isEmpty() && !force) return Result.NONE;
            return new Result(firstWords(lower, force), cursor - partial.length());
        }
        if (partial.isEmpty() && !force) return new Result(out, cursor);
        try {
            List<String> got = Util.baritone().getCommandManager().tabComplete(asked)
                    .filter(s -> s != null && !s.isBlank()).distinct().limit(MAX).collect(Collectors.toList());
            if (!(got.size() == 1 && got.get(0).equalsIgnoreCase(partial) && !force)) for (String g : got) out.add(new Item(g, ""));
        } catch (Throwable t) {
            // odd half-typed input: no suggestions rather than an error
        }
        return new Result(out, cursor - partial.length());
    }

    /** Every Baritone command (and setting) that starts with the partial word, with Baritone's description. */
    private static List<Item> firstWords(String partial, boolean force) {
        Map<String, String> all = new TreeMap<>();
        try {
            for (ICommand c : Util.baritone().getCommandManager().getRegistry().entries) {
                if (c.hiddenFromHelp()) continue;
                for (String name : c.getNames()) {
                    if (name.toLowerCase().startsWith(partial)) all.put(name, c.getShortDesc());
                }
            }
        } catch (Throwable ignored) {
        }
        if (!partial.isEmpty()) {
            try {
                for (var s : baritone.api.BaritoneAPI.getSettings().allSettings) {
                    if (s.getName().toLowerCase().startsWith(partial) && !all.containsKey(s.getName())) {
                        all.put(s.getName(), "setting, now " + s.value + " (default " + s.defaultValue + ")");
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (all.size() == 1 && all.containsKey(partial) && !force) return List.of();
        List<Item> out = new ArrayList<>();
        all.forEach((k, v) -> { if (out.size() < MAX) out.add(new Item(k, v == null ? "" : v)); });
        return out;
    }

    /** The word the cursor is in (empty after a space). */
    static String lastWord(String text) {
        int i = text.length();
        while (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) i--;
        return text.substring(i);
    }
}
