package dev.baritonequeue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Splits script text into tokens. Tolerant: it never throws, a broken piece becomes an ERROR token, so the editor can
 * colour half-typed text. Two kinds of whole-line tokens exist: a line whose first character is '#' is a Baritone
 * command, a line starting with a single '/' is a chat command; both run to the end of the line (or a " //" comment).
 */
final class Lexer {
    enum T { NUM, STR, IDENT, KEYWORD, OP, NEWLINE, BARITONE, CHAT, COMMENT, ERROR, EOF }

    record Tok(T type, String text, int line, int start, int end) {
        boolean is(String s) {
            return (type == T.OP || type == T.KEYWORD) && text.equals(s);
        }

        @Override
        public String toString() {
            return switch (type) {
                case EOF -> "the end of the script";
                case NEWLINE -> "the end of the line";
                case STR -> "a text";
                case NUM -> "the number " + text;
                default -> "'" + text + "'";
            };
        }
    }

    static final Set<String> KEYWORDS = Set.of("let", "func", "function", "if", "then", "elif", "elseif", "else", "end",
            "while", "do", "for", "in", "repeat", "times", "loop", "break", "continue", "return", "and", "or", "not",
            "true", "false", "null", "on", "try", "catch", "wait", "until");

    private static final String[] OPS = {"==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "&&", "||",
            "+", "-", "*", "/", "%", "<", ">", "=", "(", ")", "[", "]", "{", "}", ",", ".", ":", ";", "!"};

    private final String src;
    private int pos, line = 1, depth;
    private boolean lineStart = true;
    final boolean keepComments;

    private Lexer(String src, boolean keepComments) {
        this.src = src;
        this.keepComments = keepComments;
    }

    static List<Tok> lex(String src) {
        return new Lexer(src, false).run(1);
    }

    /** Also returns comments (for colouring). */
    static List<Tok> lexAll(String src) {
        return new Lexer(src, true).run(1);
    }

    /** Lexes a piece of a line (inside {...} of a text) as an expression; line numbers start at `line`. */
    static List<Tok> lexPiece(String src, int line) {
        Lexer l = new Lexer(src, false);
        l.lineStart = false;
        return l.run(line);
    }

    /** Where a " //" comment starts in a raw line (at the start or after a space), or -1. */
    static int commentStart(String line, int from) {
        int i = line.indexOf("//", from);
        while (i >= 0) {
            if (i == from || Character.isWhitespace(line.charAt(i - 1))) return i;
            i = line.indexOf("//", i + 2);
        }
        return -1;
    }

    private List<Tok> run(int firstLine) {
        line = firstLine;
        List<Tok> out = new ArrayList<>();
        int n = src.length();
        while (pos < n) {
            char c = src.charAt(pos);
            if (c == '\n') {
                if (depth == 0) addNewline(out);
                pos++;
                line++;
                lineStart = true;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\r') { pos++; continue; }
            int s = pos;
            if (c == '/' && pos + 1 < n && src.charAt(pos + 1) == '/') {   // comment
                int e = src.indexOf('\n', pos);
                if (e < 0) e = n;
                if (keepComments) out.add(new Tok(T.COMMENT, src.substring(s, e), line, s, e));
                pos = e;
                continue;
            }
            if (c == '/' && pos + 1 < n && src.charAt(pos + 1) == '*') {   // block comment
                int e = src.indexOf("*/", pos + 2);
                e = e < 0 ? n : e + 2;
                for (int k = s; k < e; k++) if (src.charAt(k) == '\n') line++;
                if (keepComments) out.add(new Tok(T.COMMENT, src.substring(s, e), line, s, e));
                pos = e;
                continue;
            }
            if (lineStart && depth == 0 && (c == '#' || c == '/')) {   // a whole-line command
                int e = src.indexOf('\n', pos);
                if (e < 0) e = n;
                int cm = commentStart(src.substring(0, e), pos + 1);
                int codeEnd = cm < 0 ? e : cm;
                int te = codeEnd;
                while (te > s && Character.isWhitespace(src.charAt(te - 1))) te--;
                out.add(new Tok(c == '#' ? T.BARITONE : T.CHAT, src.substring(s + 1, te).trim(), line, s, te));
                if (cm >= 0 && keepComments) out.add(new Tok(T.COMMENT, src.substring(cm, e), line, cm, e));
                pos = e;
                lineStart = false;
                continue;
            }
            lineStart = false;
            if (Character.isDigit(c) || (c == '.' && pos + 1 < n && Character.isDigit(src.charAt(pos + 1)))) {
                while (pos < n && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
                if (pos + 1 < n && src.charAt(pos) == '.' && Character.isDigit(src.charAt(pos + 1))) {
                    pos++;
                    while (pos < n && Character.isDigit(src.charAt(pos))) pos++;
                }
                out.add(new Tok(T.NUM, src.substring(s, pos).replace("_", ""), line, s, pos));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                while (pos < n && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
                String w = src.substring(s, pos);
                out.add(new Tok(KEYWORDS.contains(w) ? T.KEYWORD : T.IDENT, w, line, s, pos));
                continue;
            }
            if (c == '"' || c == '\'') {
                pos++;
                StringBuilder sb = new StringBuilder();
                boolean closed = false;
                while (pos < n) {
                    char d = src.charAt(pos);
                    if (d == '\n') break;
                    if (d == c) { closed = true; pos++; break; }
                    if (d == '\\' && pos + 1 < n) {
                        char e = src.charAt(pos + 1);
                        switch (e) {
                            case 'n' -> sb.append('\n');
                            case 't' -> sb.append('\t');
                            case '{' -> sb.append("{{");   // a literal brace, kept escaped for the interpolation step
                            case '}' -> sb.append("}}");
                            default -> sb.append(e);
                        }
                        pos += 2;
                        continue;
                    }
                    sb.append(d);
                    pos++;
                }
                // single quotes never interpolate: escape the braces now
                String text = c == '\'' ? sb.toString().replace("{", "{{").replace("}", "}}") : sb.toString();
                out.add(new Tok(closed ? T.STR : T.ERROR, closed ? text : "unclosed text", line, s, pos));
                continue;
            }
            String op = null;
            for (String o : OPS) {
                if (src.startsWith(o, pos)) { op = o; break; }
            }
            if (op != null) {
                pos += op.length();
                if (op.equals("(") || op.equals("[") || op.equals("{")) depth++;
                if ((op.equals(")") || op.equals("]") || op.equals("}")) && depth > 0) depth--;
                out.add(new Tok(T.OP, op, line, s, pos));
                continue;
            }
            pos++;
            out.add(new Tok(T.ERROR, String.valueOf(c), line, s, pos));
        }
        addNewline(out);
        out.add(new Tok(T.EOF, "", line, n, n));
        return out;
    }

    private void addNewline(List<Tok> out) {
        if (out.isEmpty()) return;
        T last = out.get(out.size() - 1).type;
        if (last == T.NEWLINE) return;
        if (last == T.COMMENT && out.size() > 1 && out.get(out.size() - 2).type == T.NEWLINE) return;
        out.add(new Tok(T.NEWLINE, "\n", line, pos, pos));
    }
}
