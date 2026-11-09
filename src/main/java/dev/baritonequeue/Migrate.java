package dev.baritonequeue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a script of the old one-command-per-line kind into the new language, line by line, keeping comments and
 * indentation:  sel ca -> #sel ca,  def_chest dirt 1 2 3 -> let dirt = pos(1, 2, 3),  chest dirt get dirt to 512 ->
 * take_upto(dirt, "dirt", 512),  /buildtools terminator dirt -> fill_water("dirt"),  if has dirt 64 -> if has("dirt", 64) ...
 */
final class Migrate {
    private Migrate() {}

    private static final Set<String> RESERVED_EXTRA = Set.of("chest", "pos", "area", "take", "put", "dump", "wait", "sleep", "eat");

    static List<String> convert(List<String> old) {
        Map<String, String> chestVars = new HashMap<>();
        for (String raw : old) {   // the chests the script names itself
            String[] w = Util.stripComment(raw).replace(",", " ").trim().split("\\s+");
            if (w.length == 5 && w[0].equalsIgnoreCase("def_chest")) chestVars.put(w[1].toLowerCase(), varFor(w[1].toLowerCase()));
        }
        List<String> out = new ArrayList<>();
        for (String raw : old) out.add(line(raw, chestVars));
        return out;
    }

    private static String varFor(String name) {
        String v = name.replaceAll("[^a-z0-9_]", "_");
        if (v.isEmpty() || Character.isDigit(v.charAt(0))) v = "chest_" + v;
        if (Lexer.KEYWORDS.contains(v) || RESERVED_EXTRA.contains(v) || Builtins.all().containsKey(v)) v = v + "_chest";
        return v;
    }

    private static String q(String s) {
        return "\"" + s.replace("\"", "") + "\"";
    }

    static String line(String raw, Map<String, String> chestVars) {
        int ci = Util.commentStart(raw);
        String code = ci < 0 ? raw : raw.substring(0, ci);
        String comment = ci < 0 ? "" : raw.substring(ci);
        if (code.isBlank()) return raw;
        int indentLen = code.length() - code.stripLeading().length();
        String indent = code.substring(0, indentLen);
        String trailing = code.substring(code.stripTrailing().length());
        String c = code.trim();
        boolean hashed = c.startsWith("#");
        if (hashed) c = c.substring(1).trim();
        String conv = hashed ? "#" + c : convert(c, chestVars);
        if (!comment.isEmpty() && trailing.isEmpty()) trailing = "   ";
        return indent + conv + trailing + comment;
    }

    private static String chestRef(String name, Map<String, String> chestVars) {
        String v = chestVars.get(name.toLowerCase());
        return v != null ? v : "chest(" + q(name.toLowerCase()) + ")";
    }

    private static String convert(String c, Map<String, String> chestVars) {
        String[] w = c.split("\\s+");
        String first = w[0].toLowerCase();
        switch (first) {
            case "repeat", "end", "else" -> { return c; }
            case "if" -> { return "if " + condition(c.substring(2).trim()); }
            case "timeout" -> { return w.length > 1 && w[1].equalsIgnoreCase("off") ? "timeout(null)" : "timeout(" + (w.length > 1 ? w[1] : "300") + ")"; }
            case "area" -> { return "area(" + q(c.substring(4).trim()) + ")"; }
            case "def_chest" -> {
                String[] d = c.replace(",", " ").trim().split("\\s+");
                if (d.length == 5) return "let " + varFor(d[1].toLowerCase()) + " = pos(" + d[2] + ", " + d[3] + ", " + d[4] + ")";
                return "// " + c;
            }
            case "chest" -> { return chest(w, c, chestVars); }
            case "addons" -> {
                if (w.length > 1 && w[1].equalsIgnoreCase("on")) return "enable()";
                List<String> names = new ArrayList<>();
                for (int i = 2; i < w.length; i++) if (!w[i].equalsIgnoreCase("all")) names.add(q(w[i].toLowerCase()));
                return "disable(" + String.join(", ", names) + ")";
            }
            case "wait" -> {
                if (w.length > 2 && w[1].equalsIgnoreCase("until")) {
                    String what = w[2].toLowerCase();
                    return switch (what) {
                        case "day" -> "wait until day";
                        case "night" -> "wait until night";
                        case "clear" -> "wait until not raining";
                        default -> "wait until clock == " + q(what);
                    };
                }
                return c;
            }
            default -> { }
        }
        if (c.startsWith("/")) {
            String[] cw = c.substring(1).trim().split("\\s+");
            if (cw.length >= 2 && cw[0].equalsIgnoreCase("buildtools") && cw[1].equalsIgnoreCase("terminator")) {
                return cw.length > 2 && !cw[2].equalsIgnoreCase("stop") ? "fill_water(" + q(cw[2].toLowerCase()) + ")" : "fill_water()";
            }
            return c;
        }
        return "#" + c;   // a Baritone command (or a setting typed like one)
    }

    private static String chest(String[] w, String c, Map<String, String> chestVars) {
        if (w.length < 3) return "// " + c;
        String ref = chestRef(w[1], chestVars);
        String verb = w[2].toLowerCase();
        switch (verb) {
            case "get", "take", "fetch" -> {
                if (w.length < 4) return "// " + c;
                String item = q(w[3].toLowerCase());
                if (w.length == 6 && w[4].equalsIgnoreCase("to")) return "take_upto(" + ref + ", " + item + ", " + w[5] + ")";
                if (w.length == 5) return "take(" + ref + ", " + item + ", " + (w[4].equalsIgnoreCase("all") ? "\"all\"" : w[4]) + ")";
                return "take(" + ref + ", " + item + ")";
            }
            case "put", "deposit", "store" -> {
                if (w.length < 4 || w[3].equalsIgnoreCase("rest") || (w[3].equalsIgnoreCase("all") && w.length == 4)) return "dump(" + ref + ")";
                String item = q(w[3].toLowerCase());
                if (w.length == 5 && !w[4].equalsIgnoreCase("all")) return "put(" + ref + ", " + item + ", " + w[4] + ")";
                return "put(" + ref + ", " + item + ")";
            }
            case "dump" -> { return "dump(" + ref + ")"; }
            default -> { return "// " + c; }
        }
    }

    private static String condition(String cond) {
        String[] w = cond.trim().split("\\s+");
        int i = 0;
        String not = "";
        if (w.length > 0 && w[0].equalsIgnoreCase("not")) { not = "not "; i = 1; }
        if (i >= w.length) return cond;
        String c = w[i].toLowerCase();
        switch (c) {
            case "full", "night", "day", "raining", "thundering" -> { return not + c; }
            case "has" -> {
                if (i + 1 >= w.length) return cond;
                return not + "has(" + q(w[i + 1].toLowerCase()) + (i + 2 < w.length ? ", " + w[i + 2] : "") + ")";
            }
            case "health", "food" -> {
                if (i + 2 >= w.length) return cond;
                String op = w[i + 1].equalsIgnoreCase("below") ? "<" : ">";
                return not + (not.isEmpty() ? "" : "(") + c + " " + op + " " + w[i + 2] + (not.isEmpty() ? "" : ")");
            }
            default -> { return cond; }
        }
    }
}
