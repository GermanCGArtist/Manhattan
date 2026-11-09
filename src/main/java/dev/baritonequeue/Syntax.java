package dev.baritonequeue;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Colours for the editor, one per character, like a code editor. */
final class Syntax {
    private Syntax() {}

    static final int DEFAULT = 0xFFD4D4D4;
    static final int COMMENT = 0xFF6A9955;    // green
    static final int KEYWORD = 0xFFC586C0;    // purple: if, while, end ...
    static final int FUNCTION = 0xFFDCDCAA;   // yellow: built-in functions
    static final int GETTER = 0xFF4FC1FF;     // light blue: health, night ...
    static final int VARIABLE = 0xFF9CDCFE;
    static final int NUMBER = 0xFFB5CEA8;     // light green
    static final int TEXT = 0xFFCE9178;       // orange
    static final int BARITONE = 0xFF569CD6;   // blue: # lines
    static final int BLOCK = 0xFF4EC9B0;      // teal: block names in # lines
    static final int CHAT = 0xFFD7BA7D;       // gold: / lines
    static final int ERROR = 0xFFF44747;      // red

    private static final Map<String, Boolean> blockCache = new HashMap<>();
    private static final Set<String> CONSTANTS = Set.of("true", "false", "null");

    static int[] colors(String value) {
        int[] c = new int[value.length()];
        Arrays.fill(c, DEFAULT);
        Map<String, Builtins.Builtin> builtins = Builtins.all();
        for (Lexer.Tok t : Lexer.lexAll(value)) {
            int s = Math.min(t.start(), c.length), e = Math.min(t.end(), c.length);
            switch (t.type()) {
                case COMMENT -> Arrays.fill(c, s, e, COMMENT);
                case KEYWORD -> Arrays.fill(c, s, e, CONSTANTS.contains(t.text()) ? BARITONE : KEYWORD);
                case NUM -> Arrays.fill(c, s, e, NUMBER);
                case STR -> colorText(value, s, e, c);
                case ERROR -> Arrays.fill(c, s, e, ERROR);
                case IDENT -> {
                    Builtins.Builtin b = builtins.get(t.text());
                    Arrays.fill(c, s, e, b == null ? VARIABLE : b.getter ? GETTER : FUNCTION);
                }
                case BARITONE -> colorBaritone(value, s, e, c);
                case CHAT -> Arrays.fill(c, s, e, CHAT);
                default -> { }
            }
        }
        return c;
    }

    /** A text, with its {expr} parts in the normal colour. */
    private static void colorText(String v, int s, int e, int[] c) {
        Arrays.fill(c, s, e, TEXT);
        if (v.charAt(s) != '"') return;
        int depth = 0;
        for (int i = s + 1; i < e - 1; i++) {
            char ch = v.charAt(i);
            if (ch == '{' && i + 1 < e && v.charAt(i + 1) == '{' && depth == 0) { i++; continue; }
            if (ch == '{') { depth++; c[i] = KEYWORD; continue; }
            if (ch == '}' && depth > 0) { depth--; c[i] = KEYWORD; continue; }
            if (depth > 0) c[i] = VARIABLE;
        }
    }

    /** "#sel replace water dirt": the command blue, blocks teal, numbers green, {expr} parts as code. */
    private static void colorBaritone(String v, int s, int e, int[] c) {
        int i = s;
        int index = 0;
        String label = "";
        while (i < e) {
            while (i < e && Character.isWhitespace(v.charAt(i))) i++;
            if (i >= e) break;
            int ts = i;
            while (i < e && !Character.isWhitespace(v.charAt(i))) i++;
            String tok = v.substring(ts, i);
            int color;
            if (index == 0) {
                label = tok.replace("#", "").toLowerCase();
                color = label.isEmpty() || known(label) ? BARITONE : ERROR;
            } else if (tok.startsWith("{") && tok.endsWith("}")) {
                color = VARIABLE;
            } else if (tok.matches("-?\\d+(\\.\\d+)?|~-?\\d*")) {
                color = NUMBER;
            } else if (isBlock(tok)) {
                color = BLOCK;
            } else {
                color = index == 1 ? KEYWORD : DEFAULT;
            }
            Arrays.fill(c, ts, i, color);
            index++;
        }
    }

    private static boolean known(String label) {
        try {
            return Util.baritone().getCommandManager().getCommand(label) != null || Util.settingName(label) != null;
        } catch (Throwable t) {
            return true;   // no judgement when Baritone can not be asked
        }
    }

    static boolean isBlock(String word) {
        String w = word.toLowerCase();
        int bracket = w.indexOf('[');
        if (bracket >= 0) w = w.substring(0, bracket);
        if (w.isEmpty() || !w.matches("[a-z0-9_:./-]+")) return false;
        Boolean cached = blockCache.get(w);
        if (cached != null) return cached;
        Identifier id = Identifier.tryParse(w.contains(":") ? w : "minecraft:" + w);
        boolean ok = id != null && BuiltInRegistries.BLOCK.containsKey(id);
        blockCache.put(w, ok);
        return ok;
    }
}
