package dev.baritonequeue;

import dev.baritonequeue.Ast.Expr;
import dev.baritonequeue.Ast.Stmt;
import dev.baritonequeue.Lexer.T;
import dev.baritonequeue.Lexer.Tok;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns tokens into Ast. Errors say which line and what was expected, in plain words; the first one stops parsing.
 *
 *   let x = 5                      x += 1            items[0] = "dirt"
 *   if x > 3 then ... elif ... else ... end         (then / do are optional)
 *   while cond ... end    for item in list ... end    for i, item in list ... end
 *   repeat 3 ... end      loop ... end (forever)      break, continue
 *   func name(a, b) ... return a + b end
 *   on food < 8 ... end            runs when the condition becomes true (interrupting what runs)
 *   try ... catch err ... end      wait 5      wait until day
 *   #sel ca                        a Baritone command; {expr} fills in a value: #goto {home}
 *   /say hello                     a chat command
 */
final class Parser {
    static final class ParseError extends RuntimeException {
        final int line;
        ParseError(String message, int line) {
            super(message, null, false, false);
            this.line = line;
        }
    }

    private static final Set<String> BLOCK_END = Set.of("end", "else", "elif", "elseif", "catch");

    private final List<Tok> toks;
    private int i;

    private Parser(List<Tok> toks) {
        this.toks = toks;
    }

    static List<Stmt> parse(String src) {
        Parser p = new Parser(Lexer.lex(src));
        List<Stmt> out = p.block();
        if (p.peek().type() != T.EOF) {
            Tok t = p.peek();
            throw new ParseError("'" + t.text() + "' without a matching start (if / while / for / repeat / loop / func / on / try).", t.line());
        }
        return out;
    }

    /** Parses an expression on its own (the {...} part of a text). */
    static Expr expression(String src, int line) {
        Parser p = new Parser(Lexer.lexPiece(src, line));
        Expr e = p.expr();
        p.skipNewlines();
        if (p.peek().type() != T.EOF) throw new ParseError("Unexpected " + p.peek() + " inside {" + src + "}.", line);
        return e;
    }

    // ------------------------------------------------------------------ helpers

    private Tok peek() {
        return toks.get(i);
    }

    private Tok peek(int ahead) {
        return toks.get(Math.min(i + ahead, toks.size() - 1));
    }

    private Tok next() {
        Tok t = toks.get(i);
        if (t.type() != T.EOF) i++;
        return t;
    }

    private boolean at(String s) {
        return peek().is(s);
    }

    private boolean accept(String s) {
        if (at(s)) { next(); return true; }
        return false;
    }

    private Tok expect(String s, String context) {
        if (at(s)) return next();
        throw new ParseError("Expected '" + s + "' " + context + ", found " + peek() + ".", peek().line());
    }

    private String ident(String context) {
        Tok t = peek();
        if (t.type() == T.IDENT) return next().text();
        if (t.type() == T.KEYWORD) throw new ParseError("'" + t.text() + "' is a word of the language and cannot be used as a name " + context + ".", t.line());
        throw new ParseError("Expected a name " + context + ", found " + t + ".", t.line());
    }

    private void skipNewlines() {
        while (peek().type() == T.NEWLINE || at(";")) next();
    }

    private boolean atBlockEnd() {
        Tok t = peek();
        return t.type() == T.EOF || (t.type() == T.KEYWORD && BLOCK_END.contains(t.text()));
    }

    // ------------------------------------------------------------------ statements

    private List<Stmt> block() {
        List<Stmt> out = new ArrayList<>();
        while (true) {
            skipNewlines();
            if (atBlockEnd()) return out;
            out.add(statement());
            Tok t = peek();
            if (t.type() == T.NEWLINE || t.is(";")) continue;
            if (atBlockEnd()) continue;   // one-liners: if night then sleep() end
            throw new ParseError("Unexpected " + t + " after the statement (one statement per line, or separate them with ;).", t.line());
        }
    }

    private List<Stmt> blockUntilEnd(String what, int line) {
        List<Stmt> b = block();
        if (!accept("end")) throw new ParseError("'" + what + "' (line " + line + ") is never closed with 'end'.", line);
        return b;
    }

    private Stmt statement() {
        Tok t = peek();
        int line = t.line();
        if (t.type() == T.BARITONE) {
            next();
            if (t.text().isEmpty()) throw new ParseError("A # line needs a Baritone command, for example: #sel ca", line);
            return new Ast.Raw(line, false, template(t.text(), line, true));
        }
        if (t.type() == T.CHAT) {
            next();
            if (t.text().isEmpty()) throw new ParseError("A / line needs a command, for example: /time query daytime", line);
            return new Ast.Raw(line, true, template(t.text(), line, true));
        }
        if (t.type() == T.KEYWORD) {
            switch (t.text()) {
                case "let" -> {
                    next();
                    String name = ident("after 'let'");
                    Expr v = null;
                    if (accept("=")) v = expr();
                    return new Ast.Let(line, name, v);
                }
                case "func", "function" -> {
                    if (peek(1).type() == T.IDENT) {
                        next();
                        String name = next().text();
                        List<String> params = params();
                        return new Ast.FuncDef(line, name, params, blockUntilEnd("func " + name, line));
                    }
                }
                case "if" -> { next(); return ifRest(line); }
                case "while" -> {
                    next();
                    Expr c = cond();
                    accept("do");
                    return new Ast.While(line, c, blockUntilEnd("while", line));
                }
                case "for" -> {
                    next();
                    String a = ident("after 'for'");
                    String b = null;
                    if (accept(",")) b = ident("after 'for " + a + ",'");
                    expect("in", "in 'for " + a + " in ...'");
                    Expr over = expr();
                    accept("do");
                    return new Ast.For(line, a, b, over, blockUntilEnd("for", line));
                }
                case "repeat" -> {
                    next();
                    Expr n = expr();
                    accept("times");
                    accept("do");
                    return new Ast.Loop(line, n, blockUntilEnd("repeat", line));
                }
                case "loop" -> {
                    next();
                    accept("do");
                    return new Ast.Loop(line, null, blockUntilEnd("loop", line));
                }
                case "on" -> {
                    next();
                    Expr c = cond();
                    if (!accept("do")) accept("then");
                    return new Ast.On(line, c, blockUntilEnd("on", line));
                }
                case "try" -> {
                    next();
                    List<Stmt> body = block();
                    String var = null;
                    List<Stmt> handler = List.of();
                    if (accept("catch")) {
                        if (peek().type() == T.IDENT) var = next().text();
                        handler = block();
                    }
                    if (!accept("end")) throw new ParseError("'try' (line " + line + ") is never closed with 'end'.", line);
                    return new Ast.Try(line, body, var, handler);
                }
                case "wait" -> {
                    next();
                    if (accept("until")) return new Ast.WaitUntil(line, cond());
                    Expr secs = expr();
                    return new Ast.ExprStmt(line, new Ast.Call(line, new Ast.Var(line, "wait"), List.of(secs)));
                }
                case "break" -> { next(); return new Ast.Simple(line, 0); }
                case "continue" -> { next(); return new Ast.Simple(line, 1); }
                case "return" -> {
                    next();
                    Expr v = null;
                    if (peek().type() != T.NEWLINE && !at(";") && !atBlockEnd()) v = expr();
                    return new Ast.Return(line, v);
                }
                case "end" -> throw new ParseError("'end' without a matching start (if / while / for / repeat / loop / func / on / try).", line);
                case "else", "elif", "elseif" -> throw new ParseError("'" + t.text() + "' without a matching 'if'.", line);
                default -> { }
            }
        }
        Expr e = expr();
        Tok op = peek();
        if (op.type() == T.OP && (op.text().equals("=") || op.text().equals("+=") || op.text().equals("-=") || op.text().equals("*=") || op.text().equals("/="))) {
            next();
            if (!(e instanceof Ast.Var || e instanceof Ast.Index || e instanceof Ast.Member)) {
                throw new ParseError("The left side of '" + op.text() + "' must be a name, item[...] or map.field.", line);
            }
            return new Ast.Assign(line, e, op.text(), expr());
        }
        return new Ast.ExprStmt(line, e);
    }

    private Stmt ifRest(int line) {
        List<Expr> conds = new ArrayList<>();
        List<List<Stmt>> blocks = new ArrayList<>();
        conds.add(cond());
        accept("then");
        blocks.add(block());
        List<Stmt> otherwise = null;
        while (true) {
            if (accept("elif") || accept("elseif")) {
                conds.add(cond());
                accept("then");
                blocks.add(block());
            } else if (at("else") && peek(1).is("if")) {
                next();
                next();
                conds.add(cond());
                accept("then");
                blocks.add(block());
            } else if (accept("else")) {
                otherwise = block();
                if (!accept("end")) throw new ParseError("'if' (line " + line + ") is never closed with 'end'.", line);
                break;
            } else {
                if (!accept("end")) throw new ParseError("'if' (line " + line + ") is never closed with 'end'.", line);
                break;
            }
        }
        return new Ast.If(line, conds, blocks, otherwise);
    }

    private List<String> params() {
        expect("(", "after the function name");
        List<String> out = new ArrayList<>();
        if (!at(")")) {
            do {
                out.add(ident("for a parameter"));
            } while (accept(","));
        }
        expect(")", "after the parameters");
        return out;
    }

    // ------------------------------------------------------------------ expressions

    Expr expr() {
        return or();
    }

    private Expr or() {
        Expr e = and();
        while (at("or") || at("||")) {
            int line = next().line();
            e = new Ast.Logic(line, false, e, and());
        }
        return e;
    }

    private Expr and() {
        Expr e = not();
        while (at("and") || at("&&")) {
            int line = next().line();
            e = new Ast.Logic(line, true, e, not());
        }
        return e;
    }

    private Expr not() {
        if (at("not") || at("!")) {
            int line = next().line();
            return new Ast.Unary(line, "not", not());
        }
        return cmp();
    }

    private Expr cmp() {
        Expr e = add();
        while (true) {
            Tok t = peek();
            String op = null;
            if (t.type() == T.OP && Set.of("==", "!=", "<", "<=", ">", ">=").contains(t.text())) op = t.text();
            else if (t.is("in")) op = "in";
            else if (t.is("not") && peek(1).is("in")) { next(); op = "not in"; }
            else if (t.type() == T.OP && t.text().equals("=") ) {
                // "if x = 3" is almost always meant as ==; say so instead of a confusing error later
                if (inCondition) throw new ParseError("Use == to compare (a single = gives a variable a value).", t.line());
            }
            if (op == null) return e;
            next();
            e = new Ast.Binary(t.line(), op, e, add());
        }
    }

    private boolean inCondition = false;

    /** A condition (if / elif / while / until / on): a single = there gets a clear message. */
    private Expr cond() {
        boolean was = inCondition;
        inCondition = true;
        try {
            return expr();
        } finally {
            inCondition = was;
        }
    }

    private Expr add() {
        Expr e = mul();
        while (at("+") || at("-")) {
            Tok t = next();
            e = new Ast.Binary(t.line(), t.text(), e, mul());
        }
        return e;
    }

    private Expr mul() {
        Expr e = unary();
        while (at("*") || at("/") || at("%")) {
            Tok t = next();
            e = new Ast.Binary(t.line(), t.text(), e, unary());
        }
        return e;
    }

    private Expr unary() {
        if (at("-")) {
            int line = next().line();
            return new Ast.Unary(line, "-", unary());
        }
        return postfix();
    }

    private Expr postfix() {
        Expr e = primary();
        while (true) {
            Tok t = peek();
            if (t.is("(")) {
                next();
                List<Expr> args = new ArrayList<>();
                if (!at(")")) {
                    do {
                        args.add(expr());
                    } while (accept(","));
                }
                expect(")", "to close the call");
                e = new Ast.Call(t.line(), e, args);
            } else if (t.is("[")) {
                next();
                Expr ix = expr();
                expect("]", "to close [");
                e = new Ast.Index(t.line(), e, ix);
            } else if (t.is(".")) {
                next();
                Tok n = next();
                if (n.type() != T.IDENT && n.type() != T.KEYWORD) throw new ParseError("Expected a name after '.', found " + n + ".", n.line());
                e = new Ast.Member(t.line(), e, n.text());
            } else {
                return e;
            }
        }
    }

    private Expr primary() {
        Tok t = next();
        int line = t.line();
        switch (t.type()) {
            case NUM -> {
                try {
                    return new Ast.Lit(line, Double.parseDouble(t.text()));
                } catch (NumberFormatException ex) {
                    throw new ParseError("'" + t.text() + "' is not a number.", line);
                }
            }
            case STR -> { return template(t.text(), line, false); }
            case IDENT -> { return new Ast.Var(line, t.text()); }
            case KEYWORD -> {
                switch (t.text()) {
                    case "true" -> { return new Ast.Lit(line, Boolean.TRUE); }
                    case "false" -> { return new Ast.Lit(line, Boolean.FALSE); }
                    case "null" -> { return new Ast.Lit(line, null); }
                    case "wait" -> { return new Ast.Var(line, "wait"); }
                    case "func", "function" -> {
                        List<String> params = params();
                        return new Ast.Lambda(line, params, blockUntilEnd("func", line));
                    }
                    default -> throw new ParseError("'" + t.text() + "' cannot stand here.", line);
                }
            }
            case OP -> {
                switch (t.text()) {
                    case "(" -> {
                        Expr e = expr();
                        expect(")", "to close (");
                        return e;
                    }
                    case "[" -> {
                        List<Expr> items = new ArrayList<>();
                        skipNewlines();
                        if (!at("]")) {
                            do {
                                skipNewlines();
                                if (at("]")) break;
                                items.add(expr());
                                skipNewlines();
                            } while (accept(","));
                        }
                        expect("]", "to close the list");
                        return new Ast.ListLit(line, items);
                    }
                    case "{" -> {
                        List<Expr> keys = new ArrayList<>(), values = new ArrayList<>();
                        skipNewlines();
                        if (!at("}")) {
                            do {
                                skipNewlines();
                                if (at("}")) break;
                                Tok k = peek();
                                Expr key;
                                if (k.type() == T.IDENT || k.type() == T.KEYWORD) { next(); key = new Ast.Lit(k.line(), k.text()); }
                                else key = expr();
                                expect(":", "after a key in a map");
                                values.add(expr());
                                keys.add(key);
                                skipNewlines();
                            } while (accept(","));
                        }
                        expect("}", "to close the map");
                        return new Ast.MapLit(line, keys, values);
                    }
                    default -> { }
                }
            }
            case ERROR -> throw new ParseError(t.text().equals("unclosed text") ? "A text is not closed (a \" is missing)." : "'" + t.text() + "' is not allowed here.", line);
            case NEWLINE -> throw new ParseError("The line ends too early: something is missing.", line);
            case EOF -> throw new ParseError("The script ends too early: something is missing.", line);
            default -> { }
        }
        throw new ParseError("Unexpected " + t + ".", line);
    }

    /** A text with {expr} parts ({{ and }} are plain braces). In # and / lines a {...} that is no expression stays as it is. */
    static Expr template(String text, int line, boolean lenient) {
        List<Expr> parts = new ArrayList<>();
        StringBuilder lit = new StringBuilder();
        int n = text.length();
        for (int k = 0; k < n; k++) {
            char c = text.charAt(k);
            if (c == '{' && k + 1 < n && text.charAt(k + 1) == '{') { lit.append('{'); k++; continue; }
            if (c == '}' && k + 1 < n && text.charAt(k + 1) == '}') { lit.append('}'); k++; continue; }
            if (c == '{') {
                int close = text.indexOf('}', k + 1);
                if (close < 0) {
                    if (lenient) { lit.append(text.substring(k)); break; }
                    throw new ParseError("A '{' in the text is never closed with '}' (write {{ for a plain brace).", line);
                }
                String inner = text.substring(k + 1, close);
                Expr e;
                try {
                    e = expression(inner, line);
                } catch (ParseError err) {
                    if (lenient) { lit.append(text, k, close + 1); k = close; continue; }
                    throw err;
                }
                if (lit.length() > 0) { parts.add(new Ast.Lit(line, lit.toString())); lit.setLength(0); }
                parts.add(e);
                k = close;
                continue;
            }
            lit.append(c);
        }
        if (parts.isEmpty()) return new Ast.Lit(line, lit.toString());
        if (lit.length() > 0) parts.add(new Ast.Lit(line, lit.toString()));
        return new Ast.Concat(line, parts);
    }
}
