package dev.baritonequeue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text of a capture sheet, without the game: lines go into their section ("// --- places", "// --- areas",
 * "// --- schematics"), names are kept unique, and the positions and boxes of the lines that are not commented out
 * can be read back (for the outlines in the world and the area statistics). The sheet is plain script, so it can be
 * pasted into a script or handed to an LLM as it is.
 */
final class CaptureSheet {
    private CaptureSheet() {}

    static final String PLACES = "places", AREAS = "areas", SCHEMATICS = "schematics";

    private static final Pattern POS = Pattern.compile("pos\\(\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\)");
    private static final Pattern LET = Pattern.compile("^\\s*let\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=");
    private static final Pattern BOX = Pattern.compile("^\\s*(area_box|protect)\\s*\\(\\s*\"([^\"]*)\"");

    /** A line with a position (let x = pos(...)) or a box (area_box / protect); role is the first word of the comment. */
    record Entry(int line, String name, String role, int[] pos, int[] box, boolean active) {}

    static String header(String world, String set) {
        return "// Manhattan capture sheet: " + world + ", set \"" + set + "\". Plain script: edit it, // switches a line off.\n";
    }

    /** Puts the line at the end of its section (made when missing) and returns the new text. */
    static String add(String text, String section, String line) {
        String marker = "// --- " + section;
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
        int head = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).trim().equals(marker)) { head = i; break; }
        if (head < 0) {
            if (!lines.isEmpty()) lines.add("");
            lines.add(marker);
            lines.add(line);
        } else {
            int end = head + 1;   // after the last non-blank line of the section
            int last = head;
            while (end < lines.size() && !lines.get(end).trim().startsWith("// --- ")) {
                if (!lines.get(end).isBlank()) last = end;
                end++;
            }
            lines.add(last + 1, line);
        }
        return String.join("\n", lines) + "\n";
    }

    /** Every name the sheet uses already: let names, area and safe-area names (commented-out lines too). */
    static Set<String> names(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String raw : text.split("\n")) {
            String l = uncomment(raw);
            Matcher m = LET.matcher(l);
            if (m.find()) out.add(m.group(1));
            m = BOX.matcher(l);
            if (m.find()) out.add(m.group(2));
        }
        return out;
    }

    /** base, or base_2, base_3 ... when taken. With numbered, always a number: mine_1, mine_2 ... */
    static String unique(String base, Set<String> taken, boolean numbered) {
        if (!numbered && !taken.contains(base)) return base;
        for (int i = numbered ? 1 : 2; ; i++) {
            String n = base + "_" + i;
            if (!taken.contains(n)) return n;
        }
    }

    /** A name a script can use: lower case letters, digits and _. */
    static String cleanName(String s) {
        String n = s.trim().toLowerCase().replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        if (n.isEmpty()) return "";
        return Character.isDigit(n.charAt(0)) ? "_" + n : n;
    }

    private static String uncomment(String raw) {
        String t = raw.stripLeading();
        while (t.startsWith("//")) t = t.substring(2).stripLeading();
        return t;
    }

    static List<Entry> entries(String text) {
        List<Entry> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            boolean active = !raw.stripLeading().startsWith("//");
            String l = uncomment(raw);
            if (l.startsWith("--- ") || l.isBlank()) continue;
            int cut = Util.commentStart(l);
            String code = cut < 0 ? l : l.substring(0, cut);
            String comment = cut < 0 ? "" : l.substring(cut + 2).trim();
            String role = comment.split("[;:,\\s]", 2)[0].toLowerCase();
            List<int[]> ps = new ArrayList<>();
            Matcher pm = POS.matcher(code);
            while (pm.find()) ps.add(new int[]{Integer.parseInt(pm.group(1)), Integer.parseInt(pm.group(2)), Integer.parseInt(pm.group(3))});
            if (ps.isEmpty()) continue;
            Matcher bm = BOX.matcher(code);
            if (bm.find() && ps.size() >= 2) {
                int[] a = ps.get(0), b = ps.get(1);
                int[] box = {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]), Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])};
                out.add(new Entry(i + 1, bm.group(2), bm.group(1).equals("protect") ? "safe" : role, null, box, active));
                continue;
            }
            Matcher lm = LET.matcher(code);
            out.add(new Entry(i + 1, lm.find() ? lm.group(1) : "", role, ps.get(0), null, active));
        }
        return out;
    }

    static String box(int[] b) {
        return (b[3] - b[0] + 1) + "x" + (b[4] - b[1] + 1) + "x" + (b[5] - b[2] + 1) + " = "
                + ((long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1)) + " blocks";
    }

    static String pos(int x, int y, int z) {
        return "pos(" + x + ", " + y + ", " + z + ")";
    }
}
