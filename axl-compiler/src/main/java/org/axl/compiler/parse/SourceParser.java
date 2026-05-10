package org.axl.compiler.parse;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.ParseTree;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.ErrorCode;
import org.axl.compiler.LargeStack;
import org.axl.compiler.ast.SourceFile;

public final class SourceParser {

    // The escapes Java knows in strings and characters.
    private static final Pattern ESCAPE = Pattern.compile(
            "\\\\(?:[btnfr\"'\\\\]|[0-3]?[0-7]{1,2}|u+[0-9a-fA-F]{4})");

    // The class file format stores a string constant's length in two bytes.
    private static final int MAX_CONSTANT_BYTES = 65535;

    private static final Pattern TEMPLATE = Pattern.compile("(?<!\\\\)\\$(\\{|[A-Za-z_]\\w*)");

    /** file is null when the source has syntax errors. */
    public record Result(SourceFile file, List<AxlDiagnostic> diagnostics) {}

    private SourceParser() {}

    /**
     * The faster SLL prediction first; the line predicates make full LL prediction slow on long
     * expressions. Only input that SLL cannot handle, which includes all broken input, is parsed again.
     */
    private static AxolotlParser.CompilationUnitContext parseTree(CommonTokenStream tokens, BaseErrorListener errors) {
        var fast = new AxolotlParser(tokens);
        fast.removeErrorListeners();
        fast.getInterpreter().setPredictionMode(PredictionMode.SLL);
        fast.setErrorHandler(new BailErrorStrategy());
        try {
            return fast.compilationUnit();
        } catch (ParseCancellationException e) {
            tokens.seek(0);
            var full = new AxolotlParser(tokens);
            full.removeErrorListeners();
            full.addErrorListener(errors);
            return full.compilationUnit();
        }
    }

    private static String lexerMessage(String msg) {
        return "unexpected character `" + msg.replaceFirst("^token recognition error at: '", "").replaceFirst("'$", "") + "`";
    }

    private static final Set<Integer> DECLARATION_STARTS = Set.of(AxolotlLexer.FN, AxolotlLexer.VAL, AxolotlLexer.VAR,
            AxolotlLexer.PRIVATE, AxolotlLexer.IMPORT);

    /** Characters that cannot be seen are named by their number. */
    private static String unexpected(int c) {
        if (Character.isISOControl(c)) {
            return String.format("unexpected control character U+%04X", c);
        }
        int type = Character.getType(c);
        boolean invisible = Character.isWhitespace(c) || Character.isSpaceChar(c) || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
        return invisible ? String.format("unexpected invisible character U+%04X; remove it or type a plain space", c)
                : "unexpected character `" + Character.toString(c) + "`";
    }

    /** Characters that start no token, names Java would reject, and unclosed strings and comments. */
    private static List<AxlDiagnostic> lexicalErrors(String path, CommonTokenStream tokens) {
        List<AxlDiagnostic> errors = new ArrayList<>();
        List<Token> all = tokens.getTokens();
        for (int i = 0; i < all.size(); i++) {
            Token t = all.get(i);
            // A word or symbol from Java or Kotlin gets more than "unexpected" from the parser; once is enough.
            String habit = Habits.of(all, i);
            String message = habit != null ? habit : tokenProblem(all, i);
            int line = t.getLine();
            boolean repeated = habit != null && errors.stream().anyMatch(d -> d.message().equals(habit));
            if (message != null && !repeated && errors.stream().noneMatch(d -> d.line() == line)) {
                errors.add(AxlDiagnostic.error(path, line, t.getCharPositionInLine() + 1, ErrorCode.SYNTAX, message));
            }
        }
        return errors;
    }

    private static String tokenProblem(List<Token> all, int i) {
        Token t = all.get(i);
        if (t.getType() == AxolotlLexer.UNKNOWN_CHAR) {
            return switch (t.getText()) {
                case "\"" -> "unclosed string: add `\"` before the end of the line";
                case "'" -> adjacent(all, i) && all.get(i + 1).getText().equals("'")
                        ? "a character literal holds one character; put one between the quotes"
                        : "unclosed character literal: add `'`";
                default -> unexpected(t.getText().codePointAt(0));
            };
        }
        if ((t.getType() == AxolotlLexer.IMPORT || t.getType() == AxolotlLexer.PACKAGE)
                && (previousCode(all, i) < 0 || all.get(previousCode(all, i)).getLine() < t.getLine())
                && (nextCode(all, i) >= all.size() || all.get(nextCode(all, i)).getLine() > t.getLine())) {
            return "`" + t.getText() + "` needs a name after it on the same line";
        }
        if (t.getType() == AxolotlLexer.SLASH && i + 1 < all.size() && all.get(i + 1).getType() == AxolotlLexer.STAR
                && all.get(i + 1).getStartIndex() == t.getStopIndex() + 1) {
            return "unclosed comment: add `*/`";
        }
        if (t.getType() == AxolotlLexer.INT_LITERAL && t.getText().matches("0[0-9_]+[lL]?")) {
            return "a number cannot start with 0: Java would read `" + t.getText() + "` as octal";
        }
        if ((t.getType() == AxolotlLexer.PLUS || t.getType() == AxolotlLexer.MINUS) && adjacent(all, i)
                && all.get(i + 1).getType() == t.getType()) {
            return "Axolotl has no `" + t.getText() + t.getText() + "`; write `" + t.getText() + "= 1`";
        }
        if (t.getType() == AxolotlLexer.INT_LITERAL && adjacent(all, i) && all.get(i + 1).getType() == AxolotlLexer.DOT) {
            return numberWithDot(t, adjacent(all, i + 1) ? all.get(i + 2) : null);
        }
        if (t.getType() == AxolotlLexer.INT_LITERAL || t.getType() == AxolotlLexer.FLOAT_LITERAL) {
            return numberProblem(t, afterUnaryMinus(all, i), i + 1 < all.size() ? all.get(i + 1) : null);
        }
        if (t.getType() == AxolotlLexer.STRING_LITERAL || t.getType() == AxolotlLexer.CHAR_LITERAL) {
            return textProblem(t);
        }
        if (t.getType() == AxolotlLexer.IDENTIFIER && !isJavaName(t.getText())) {
            return "`" + t.getText() + "` is not a valid name: use the letters A to Z and a to z, digits and `_`";
        }
        return null;
    }

    /** `3.`, `3.f`, `3.toString()` and Kotlin's range `0..10`; after is the token after the dot, or null. */
    private static String numberWithDot(Token number, Token after) {
        String text = number.getText();
        if (after != null && after.getType() == AxolotlLexer.DOT) {
            return "Axolotl has no ranges (`..`); count with a `while` loop, or use "
                    + "`java.util.stream.IntStream.range(from, to)`";
        }
        if (after == null || after.getType() != AxolotlLexer.IDENTIFIER) {
            return "`" + text + ".` is not a valid number; write `" + text + ".0`";
        }
        if (after.getText().matches("[fFdD]|[eE]\\d*")) {
            // Java reads `3.f` as a float and `1.e5` as a double.
            String fixed = text + ".0" + (after.getText().matches("[eE].*") ? after.getText() : "");
            return "`" + text + "." + after.getText() + "` is not a valid number; write `" + fixed + "`";
        }
        return "a number has no members; for `" + text + "." + after.getText()
                + "`, call a method of its class, such as `Integer.toString(" + text + ")`";
    }

    /** Statements end at the end of a line or at `;`; the grammar alone would take `f() g()` as two. */
    private static void sharedLines(String path, CommonTokenStream tokens, ParseTree node, List<AxlDiagnostic> diagnostics) {
        List<? extends ParserRuleContext> statements = switch (node) {
            case AxolotlParser.CompilationUnitContext unit -> {
                List<ParserRuleContext> all = new ArrayList<>();
                if (unit.packageDecl() != null) {
                    all.add(unit.packageDecl());
                }
                all.addAll(unit.importDecl());
                all.addAll(unit.topLevel());
                yield all;
            }
            case AxolotlParser.BlockContext block -> block.statement();
            case AxolotlParser.LambdaContext lambda -> lambda.statement();
            default -> List.of();
        };
        int reportedLine = -1;
        for (int i = 1; i < statements.size(); i++) {
            Token end = statements.get(i - 1).getStop();
            Token start = statements.get(i).getStart();
            if (end.getLine() == start.getLine() && end.getType() != AxolotlLexer.SEMI
                    && start.getType() != AxolotlLexer.SEMI && start.getLine() != reportedLine) {
                AxlDiagnostic problem = sharedLine(path, tokens, statements.get(i - 1).getStart(), end, start);
                diagnostics.add(problem);
                // A misspelled or Java-style word splits one line into several statements; report it once.
                if (!problem.message().startsWith(OWN_LINE)) {
                    reportedLine = start.getLine();
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            sharedLines(path, tokens, node.getChild(i), diagnostics);
        }
    }

    private static final List<String> KEYWORDS = List.of("return", "while", "else", "when", "val", "var", "for", "if",
            "try", "throw", "fn", "class", "enum", "data", "sealed", "interface", "import", "package", "private",
            "attempt", "null", "true", "false");

    private static final Map<String, String> JAVA_TYPES = Map.of("int", "Int", "long", "Long", "double", "Double",
            "boolean", "Boolean", "float", "Float", "char", "Character", "String", "String");

    /**
     * Two statements on one line often come from a word Axolotl does not have: a misspelled keyword, a
     * Java declaration, a cast or `new`. first and last are the tokens of the earlier statement.
     */
    private static final String OWN_LINE = "put this statement on its own line, or end the one before it with `;`";

    private static AxlDiagnostic sharedLine(String path, CommonTokenStream tokens, Token first, Token last, Token next) {
        String nextGuess = keywordLike(next);
        if (next.getType() == AxolotlLexer.IDENTIFIER && next.getText().equals("as")) {
            return error(path, next, "Axolotl has no casts; test the type with `is`: `if (x is String) { ... }`");
        }
        if (nextGuess != null) {
            return error(path, next, "unknown word `" + next.getText() + "`; did you mean `" + nextGuess + "`?");
        }
        if (last.getType() == AxolotlLexer.IDENTIFIER && last.getText().equals("new")) {
            return error(path, last, "Axolotl creates objects without `new`: `" + next.getText() + "(...)`");
        }
        Token beforeClose = last.getType() == AxolotlLexer.RPAREN ? previousToken(tokens, last) : null;
        if (beforeClose != null && beforeClose.getType() == AxolotlLexer.IDENTIFIER
                && previousToken(tokens, beforeClose).getType() == AxolotlLexer.LPAREN
                && Character.isUpperCase(beforeClose.getText().charAt(0))) {
            return error(path, previousToken(tokens, beforeClose), "Axolotl has no casts; test the type with `is`: "
                    + "`if (x is " + beforeClose.getText() + ") { ... }`");
        }
        String control = controlLike(tokens, first, last, next);
        if (control != null) {
            return error(path, first, "unknown word `" + first.getText() + "`; did you mean `" + control + "`?");
        }
        if (next.getType() == AxolotlLexer.LBRACE && callee(tokens, last) != null) {
            return error(path, next, trailingLambda(tokens, callee(tokens, last)));
        }
        String text = first.getText();
        if (first == last && (text.equals("let") || text.equals("const"))) {
            return error(path, first, "declare a value with `val`, or a variable with `var`");
        }
        if (first == last && JAVA_TYPES.containsKey(text)) {
            return error(path, first, "declare a local with its type after the name: `val " + next.getText() + ": "
                    + JAVA_TYPES.get(text) + " = ...`");
        }
        String guess = keywordLike(first);
        if (guess != null) {
            return error(path, first, "unknown word `" + text + "`; did you mean `" + guess + "`?");
        }
        return error(path, next, OWN_LINE);
    }

    private static final Set<String> PIPE_STAGES = Set.of("filter", "map", "forEach", "sorted", "peek");

    private static final List<String> CONTROL_KEYWORDS = List.of("if", "while", "for", "when");

    /** `iff (c) {` looks like a call with a lambda after it; only `if`, `while`, `for` and `when` are written so. */
    private static String controlLike(CommonTokenStream tokens, Token first, Token last, Token next) {
        int open = nextCode(tokens.getTokens(), first.getTokenIndex());
        if (first.getType() != AxolotlLexer.IDENTIFIER || tokens.get(open).getType() != AxolotlLexer.LPAREN
                || last.getType() != AxolotlLexer.RPAREN || next.getType() != AxolotlLexer.LBRACE) {
            return null;
        }
        return CONTROL_KEYWORDS.stream().filter(k -> distance(first.getText(), k) <= (k.length() > 4 ? 2 : 1))
                .findFirst().orElse(null);
    }

    /** The name of the call or member that ends a statement, or null: `xs.map` or `xs.forEach()`. */
    private static Token callee(CommonTokenStream tokens, Token last) {
        if (last.getType() == AxolotlLexer.IDENTIFIER) {
            return last;
        }
        if (last.getType() != AxolotlLexer.RPAREN) {
            return null;
        }
        int depth = 0;
        for (int i = last.getTokenIndex(); i >= 0; i--) {
            int type = tokens.get(i).getType();
            if (type == AxolotlLexer.RPAREN) {
                depth++;
            } else if (type == AxolotlLexer.LPAREN && --depth == 0) {
                Token name = previousToken(tokens, tokens.get(i));
                return name != null && name.getType() == AxolotlLexer.IDENTIFIER ? name : null;
            }
        }
        return null;
    }

    /** Kotlin's `xs.forEach { ... }` and `s?.let { ... }`. */
    private static String trailingLambda(CommonTokenStream tokens, Token name) {
        Token dot = previousToken(tokens, name);
        if (dot != null && dot.getType() == AxolotlLexer.SAFE_DOT && name.getText().equals("let")) {
            return "Axolotl has no `let`; check for null instead: `if (s != null) { ... }`";
        }
        boolean member = dot != null && (dot.getType() == AxolotlLexer.DOT || dot.getType() == AxolotlLexer.SAFE_DOT);
        Token receiver = member ? previousToken(tokens, dot) : null;
        String owner = receiver != null && receiver.getType() == AxolotlLexer.IDENTIFIER ? receiver.getText() + dot.getText() : "";
        String hint = "a lambda argument goes inside the parentheses: `" + owner + name.getText() + "({ ... })`";
        return member && PIPE_STAGES.contains(name.getText())
                ? hint + "; for a collection, use a pipe: `xs |> " + name.getText() + " { ... }`" : hint;
    }

    private static AxlDiagnostic error(String path, Token at, String message) {
        return AxlDiagnostic.error(path, at.getLine(), at.getCharPositionInLine() + 1, ErrorCode.SYNTAX, message);
    }

    /** The keyword a word is a slip of: one or two letters off, or swapped. */
    private static String keywordLike(Token token) {
        if (token.getType() != AxolotlLexer.IDENTIFIER || token.getText().length() < 3) {
            return null;
        }
        String text = token.getText();
        if (Set.of("fun", "fnn", "func", "def", "function").contains(text)) {
            return "fn";
        }
        for (String keyword : KEYWORDS) {
            if (keyword.length() >= 3 && distance(text, keyword) <= (keyword.length() > 4 ? 2 : 1)) {
                return keyword;
            }
        }
        return null;
    }

    /** Edits that turn one word into the other, a swap of neighbours counting as one. */
    private static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            for (int j = 0; j <= b.length(); j++) {
                if (i == 0 || j == 0) {
                    d[i][j] = i + j;
                    continue;
                }
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }

    private static String doubleProblem(String text) {
        double value = Double.parseDouble(text.replace("_", "").replaceAll("[dD]$", ""));
        if (Double.isInfinite(value)) {
            return "`" + text + "` is too large for a `Double`";
        }
        boolean nonZeroDigits = !text.replace("_", "").matches("[0.]*([eE].*)?[dD]?");
        return value == 0 && nonZeroDigits ? "`" + text + "` is too small for a `Double` and would be 0" : null;
    }

    /** `-2147483648` and `- 2147483648`, but not `x - 2147483648`, which Java rejects too. */
    private static boolean afterUnaryMinus(List<Token> tokens, int i) {
        int minus = previousCode(tokens, i);
        if (minus < 0 || tokens.get(minus).getType() != AxolotlLexer.MINUS) {
            return false;
        }
        int before = previousCode(tokens, minus);
        return before < 0 || !Set.of(AxolotlLexer.IDENTIFIER, AxolotlLexer.INT_LITERAL, AxolotlLexer.FLOAT_LITERAL,
                AxolotlLexer.STRING_LITERAL, AxolotlLexer.CHAR_LITERAL, AxolotlLexer.RPAREN, AxolotlLexer.TRUE,
                AxolotlLexer.FALSE, AxolotlLexer.NULL).contains(tokens.get(before).getType());
    }

    static int nextCode(List<Token> tokens, int i) {
        int j = i + 1;
        while (j < tokens.size() && Set.of(AxolotlLexer.WS, AxolotlLexer.LINE_COMMENT, AxolotlLexer.BLOCK_COMMENT).contains(tokens.get(j).getType())) {
            j++;
        }
        return j;
    }

    static int previousCode(List<Token> tokens, int i) {
        int j = i - 1;
        while (j >= 0 && Set.of(AxolotlLexer.WS, AxolotlLexer.LINE_COMMENT, AxolotlLexer.BLOCK_COMMENT).contains(tokens.get(j).getType())) {
            j--;
        }
        return j;
    }

    private static String numberProblem(Token t, boolean afterUnaryMinus, Token next) {
        String text = t.getText();
        if (next != null && next.getStartIndex() == t.getStopIndex() + 1
                && (next.getType() == AxolotlLexer.IDENTIFIER || next.getType() == AxolotlLexer.INT_LITERAL)) {
            return "`" + text + next.getText() + "` is not a valid number";
        }
        boolean hex = text.startsWith("0x") || text.startsWith("0X");
        boolean misplacedUnderscore = hex ? text.matches("0[xX]_.*|.*_[lL]?")
                : text.matches(".*_([lLfFdD.eE]|$).*") || text.matches(".*[.eE+-]_.*");
        if (misplacedUnderscore) {
            return "`" + text + "`: an underscore in a number must stand between two digits";
        }
        if (!hex && text.matches(".*[0-9][fF]")) {
            return "Axolotl has no `Float` type; write `" + text.substring(0, text.length() - 1) + "` for a `Double`";
        }
        if (t.getType() != AxolotlLexer.INT_LITERAL) {
            return doubleProblem(text);
        }
        boolean isLong = text.endsWith("l") || text.endsWith("L");
        String digits = text.replace("_", "").replaceAll("[lL]$", "");
        BigInteger value = new BigInteger(hex ? digits.substring(2) : digits, hex ? 16 : 10);
        BigInteger max = BigInteger.valueOf(isLong ? Long.MAX_VALUE : Integer.MAX_VALUE);
        // Hexadecimal numbers may use the sign bit; a decimal one may be the magnitude of the minimum after `-`.
        BigInteger limit = hex ? max.shiftLeft(1).add(BigInteger.ONE)
                : afterUnaryMinus ? max.add(BigInteger.ONE) : max;
        if (value.compareTo(limit) > 0) {
            return "`" + text + "` is too large for " + (isLong ? "a `Long`" : "an `Int`; add `L` for a `Long`");
        }
        return null;
    }

    /** Escapes Java knows, and one character in a character literal. */
    private static String textProblem(Token t) {
        String text = t.getText();
        String inner = text.substring(1, text.length() - 1);
        int count = 0;
        int bytes = 0;
        for (int i = 0; i < inner.length(); count++) {
            if (inner.charAt(i) != '\\') {
                bytes += constantBytes(inner.codePointAt(i));
                i += Character.charCount(inner.codePointAt(i));
                continue;
            }
            Matcher escape = ESCAPE.matcher(inner).region(i, inner.length());
            if (!escape.lookingAt()) {
                String shown = inner.substring(i, Math.min(inner.length(), inner.charAt(i + 1) == 'u' ? i + 6 : i + 2));
                if (shown.equals("\\s")) {
                    return "write a space instead of `\\s`, which is easily misread";
                }
                return "unknown escape `" + shown + "`; write `\\\\` for a backslash";
            }
            bytes += constantBytes(escaped(escape.group()));
            i = escape.end();
        }
        if (text.startsWith("'") && count != 1) {
            return "a character literal holds one character; write text in double quotes";
        }
        if (text.startsWith("'") && Character.isSupplementaryCodePoint(inner.codePointAt(0))) {
            return "`" + inner + "` takes two Java chars, so it does not fit in a `Character`; write it in double quotes";
        }
        if (bytes > MAX_CONSTANT_BYTES) {
            return "this string is too long for Java, which allows " + MAX_CONSTANT_BYTES + " bytes of UTF-8 in a "
                    + "string constant; read long text from a file instead";
        }
        return null;
    }

    /** The character an escape stands for, or one of the same size in a class file. */
    private static int escaped(String escape) {
        if (escape.charAt(1) == 'u') {
            return Integer.parseInt(escape.substring(escape.length() - 4), 16);
        }
        return Character.isDigit(escape.charAt(1)) ? Integer.parseInt(escape.substring(1), 8) : 'n';
    }

    /** The size in a class file, whose UTF-8 writes U+0000 in two bytes and a surrogate pair in six. */
    private static int constantBytes(int c) {
        if (Character.isSupplementaryCodePoint(c)) {
            return 6;
        }
        return c == 0 || c >= 0x80 ? (c >= 0x800 ? 3 : 2) : 1;
    }

    private static boolean adjacent(List<Token> tokens, int i) {
        return i + 1 < tokens.size() && tokens.get(i + 1).getStartIndex() == tokens.get(i).getStopIndex() + 1;
    }

    /** Error Prone rejects other letters in code. */
    private static boolean isJavaName(String name) {
        return name.matches("[A-Za-z_$][A-Za-z0-9_$]*");
    }

    /** A token after which something must follow: a binary operator or a `.`. */
    private static boolean unfinished(Token token) {
        return switch (token.getType()) {
            case AxolotlLexer.PLUS, AxolotlLexer.MINUS, AxolotlLexer.STAR, AxolotlLexer.SLASH, AxolotlLexer.PERCENT,
                 AxolotlLexer.ANDAND, AxolotlLexer.OROR, AxolotlLexer.ELVIS, AxolotlLexer.EQ, AxolotlLexer.NEQ,
                 AxolotlLexer.LE, AxolotlLexer.GE, AxolotlLexer.ASSIGN, AxolotlLexer.DOT, AxolotlLexer.SAFE_DOT,
                 AxolotlLexer.PIPE, AxolotlLexer.ARROW -> true;
            default -> false;
        };
    }

    private static String parserMessage(String msg, Token at, CommonTokenStream tokens) {
        if (at != null && at.getType() == Token.EOF) {
            Token open = unclosedBrace(tokens);
            return open != null ? "unexpected end of file: the `{` at line " + open.getLine() + " is not closed"
                    : "unexpected end of file";
        }
        if (at != null && at.getType() == AxolotlParser.BLOCK_START) {
            return "an `if` that gives a value takes expressions after `if (...)` and `else`, not braces: "
                    + "`if (c) a else b`; braces make an `if` a statement";
        }
        String token = at == null ? "" : at.getText();
        String guess = at == null ? null : keywordLike(at);
        if (guess != null) {
            return "unknown word `" + token + "`; did you mean `" + guess + "`?";
        }
        Token previous = at == null ? null : previousToken(tokens, at);
        if (previous != null && (previous.getType() == AxolotlLexer.IF || previous.getType() == AxolotlLexer.WHILE)
                && at.getType() != AxolotlLexer.LPAREN) {
            return "missing `(` after `" + previous.getText() + "`: `" + previous.getText() + " (c) { ... }`";
        }
        if (previous != null && previous.getType() == AxolotlLexer.RPAREN && at.getType() != AxolotlLexer.LBRACE
                && closesIf(tokens, previous)) {
            return "an `if` statement needs braces: `if (c) { ... }`";
        }
        if (previous != null && previous.getType() == AxolotlLexer.WHEN && at.getType() == AxolotlLexer.LBRACE) {
            return "`when` needs a subject in parentheses: `when (x) { ... }`; test conditions with `if` and `else if`";
        }
        if (!token.equals("->") && (msg.contains("'->'") && !msg.startsWith("missing ") || brokenArm(msg, tokens, at))) {
            return "missing `->` before `" + token + "`";
        }
        if (msg.startsWith("missing ")) {
            String missing = msg.substring("missing ".length(), msg.indexOf(" at "));
            return "missing " + describe(missing) + " before `" + token + "`";
        }
        if (msg.startsWith("mismatched input ")) {
            int expecting = msg.indexOf(" expecting ");
            String expected = expecting < 0 ? "" : msg.substring(expecting + " expecting ".length());
            return expected.startsWith("{") ? "unexpected `" + token + "`" : "unexpected `" + token + "`, expected " + describe(expected);
        }
        return "unexpected `" + token + "`";
    }

    /** Kotlin's `"${a + b}"` and `"Hello, $name"`; a string that is only `"$t"` is more likely meant as it is. */
    private static boolean looksLikeTemplate(String literal) {
        String inner = literal.substring(1, literal.length() - 1);
        Matcher template = TEMPLATE.matcher(inner);
        return template.find() && (template.group(1).equals("{") || template.group().length() < inner.length());
    }

    /** A line that starts with `+` or `-` starts a new statement, which leaves the call on the line before open. */
    private static String missingParen(Token next) {
        boolean sign = next != null && (next.getType() == AxolotlLexer.PLUS || next.getType() == AxolotlLexer.MINUS);
        return sign ? "missing `)` at the end of the line: a line that starts with `" + next.getText()
                + "` starts a new statement, so end this line with the `" + next.getText() + "` instead"
                : "missing `)` at the end of the line";
    }

    /** Whether the `)` closes the condition of an `if`. */
    static boolean closesIf(CommonTokenStream tokens, Token close) {
        Token before = beforeParens(tokens, close);
        return before != null && before.getType() == AxolotlLexer.IF;
    }

    /** The token before the `(` that the `)` closes, or null. */
    private static Token beforeParens(CommonTokenStream tokens, Token close) {
        int depth = 0;
        for (int i = close.getTokenIndex(); i >= 0; i--) {
            int type = tokens.get(i).getType();
            if (type == AxolotlLexer.RPAREN) {
                depth++;
            } else if (type == AxolotlLexer.LPAREN && --depth == 0) {
                return previousToken(tokens, tokens.get(i));
            }
        }
        return null;
    }

    /** Whether the innermost `{` open at the token is the one of a `when`, not of an arm's block. */
    private static boolean inWhenBraces(CommonTokenStream tokens, Token at) {
        int depth = 0;
        for (int i = at.getTokenIndex() - 1; i >= 0; i--) {
            int type = tokens.get(i).getType();
            if (type == AxolotlLexer.RBRACE) {
                depth++;
            } else if ((type == AxolotlLexer.LBRACE || type == AxolotlParser.BLOCK_START) && depth-- == 0) {
                Token close = previousToken(tokens, tokens.get(i));
                Token before = close == null || close.getType() != AxolotlLexer.RPAREN ? null : beforeParens(tokens, close);
                return before != null && before.getType() == AxolotlLexer.WHEN;
            }
        }
        return false;
    }

    /** ANTLR gives up on the whole `when` when an arm's condition runs into its value. */
    private static boolean brokenArm(String msg, CommonTokenStream tokens, Token at) {
        if (at == null || !msg.startsWith("no viable alternative at input 'when")) {
            return false;
        }
        Token first = lineStart(tokens, at);
        boolean arrowBefore = tokens.getTokens().subList(first.getTokenIndex(), at.getTokenIndex()).stream()
                .anyMatch(t -> t.getType() == AxolotlLexer.ARROW);
        return first != at && !arrowBefore && inWhenBraces(tokens, at);
    }

    /** A token name or literal from an ANTLR message, as a person would say it. */
    private static String describe(String expected) {
        return switch (expected) {
            case "IDENTIFIER" -> "a name";
            case "INT_LITERAL", "FLOAT_LITERAL" -> "a number";
            case "STRING_LITERAL" -> "a string";
            default -> expected.startsWith("'") ? "`" + expected.substring(1, expected.length() - 1) + "`" : expected;
        };
    }

    static Token nextToken(CommonTokenStream tokens, Token at) {
        return tokens.get(Math.min(nextCode(tokens.getTokens(), at.getTokenIndex()), tokens.size() - 1));
    }

    static Token previousToken(CommonTokenStream tokens, Token at) {
        for (int i = at.getTokenIndex() - 1; i >= 0; i--) {
            Token t = tokens.get(i);
            if (t.getChannel() == Token.DEFAULT_CHANNEL) {
                return t;
            }
        }
        return null;
    }

    private static int openParens(CommonTokenStream tokens, int line) {
        int open = 0;
        for (Token t : tokens.getTokens()) {
            if (t.getLine() == line && t.getType() == AxolotlLexer.LPAREN) {
                open++;
            } else if (t.getLine() == line && t.getType() == AxolotlLexer.RPAREN) {
                open--;
            }
        }
        return open;
    }

    /**
     * The `{` left open. A `}` indented less than the line of the `{` it would close most likely
     * belongs to an outer block, so that `{` is the one without its `}`.
     */
    private static Token unclosedBrace(CommonTokenStream tokens) {
        java.util.ArrayDeque<Token> open = new java.util.ArrayDeque<>();
        for (Token t : tokens.getTokens()) {
            if (t.getType() == AxolotlLexer.LBRACE) {
                open.push(t);
            } else if (t.getType() == AxolotlLexer.RBRACE && !open.isEmpty()) {
                Token start = lineStart(tokens, open.peek());
                Token before = previousToken(tokens, t);
                boolean startsLine = before == null || before.getLine() < t.getLine();
                if (open.size() > 1 && startsLine && t.getCharPositionInLine() < start.getCharPositionInLine()) {
                    return open.peek();
                }
                open.pop();
            }
        }
        return open.peek();
    }

    static Token lineStart(CommonTokenStream tokens, Token at) {
        Token first = at;
        for (Token t = previousToken(tokens, at); t != null && t.getLine() == at.getLine(); t = previousToken(tokens, t)) {
            first = t;
        }
        return first;
    }

    public static Result parse(String path, String className, String source) {
        return LargeStack.call(() -> parseHere(path, className, source), () -> new Result(null, List.of(
                AxlDiagnostic.error(path, 1, 1, ErrorCode.SYNTAX, "the code is nested too deeply to translate; "
                        + "split it into smaller expressions"))));
    }

    private static Result parseHere(String path, String className, String source) {
        List<AxlDiagnostic> diagnostics = new ArrayList<>();
        // A byte order mark is not code, and a lone \r ends a line in every editor.
        String text = source.replaceFirst("^\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');
        var lexer = new AxolotlLexer(CharStreams.fromString(text, path));
        var tokens = new BlockTokens(lexer);
        var lexerErrors = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                    int charPositionInLine, String msg, RecognitionException e) {
                if (diagnostics.stream().noneMatch(d -> d.line() == line)) {
                    diagnostics.add(AxlDiagnostic.error(path, line, charPositionInLine + 1, ErrorCode.SYNTAX, lexerMessage(msg)));
                }
            }
        };
        lexer.removeErrorListeners();
        lexer.addErrorListener(lexerErrors);
        tokens.fill();
        tokens.markIfBlocks();
        diagnostics.addAll(lexicalErrors(path, tokens));
        if (!diagnostics.isEmpty()) {
            // The parser's errors after a broken token only repeat the problem.
            return new Result(null, diagnostics);
        }
        var parserErrors = new BaseErrorListener() {
            // After an unclosed `(`, the parser's recovery reports the lines after it too.
            boolean unclosedParen;
            // Prediction may look ahead over a whole `when` and fail at a later line first; the
            // recovery then parses the tokens before that point again and reports them too.
            int furthest = -1;

            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                    int charPositionInLine, String msg, RecognitionException e) {
                Token at = offendingSymbol instanceof Token t ? t : null;
                if (unclosedParen || at != null && at.getTokenIndex() < furthest) {
                    return;
                }
                furthest = at == null ? furthest : at.getTokenIndex();
                Mistakes.Placed known = at == null || at.getType() == Token.EOF ? null : Mistakes.find(tokens, at);
                if (known != null) {
                    report(known);
                    return;
                }
                Token previous = at == null ? null : previousToken(tokens, at);
                int reportLine = line;
                int column = charPositionInLine + 1;
                boolean closes = at == null || at.getType() == Token.EOF || at.getType() == AxolotlLexer.RBRACE
                        || at.getType() == AxolotlLexer.RPAREN;
                // The next declaration, where the parser notices that the one before is not finished.
                boolean nextDeclaration = at != null && at.getCharPositionInLine() == 0 && DECLARATION_STARTS.contains(at.getType());
                // Something missing at the end of a line is reported there, not where the next line starts.
                if (previous != null && previous.getLine() < line && previous.getType() != AxolotlLexer.COMMA
                        && (unfinished(previous) || nextDeclaration || !msg.startsWith("extraneous input")
                        && (closes || msg.startsWith("missing ") || openParens(tokens, previous.getLine()) > 0))) {
                    reportLine = previous.getLine();
                    column = previous.getCharPositionInLine() + previous.getText().codePointCount(0, previous.getText().length()) + 1;
                }
                int finalLine = reportLine;
                boolean notFinished = reportLine < line && nextDeclaration && !unfinished(previous);
                if (notFinished && Mistakes.unfinished(tokens, previous) != null) {
                    report(Mistakes.unfinished(tokens, previous));
                    return;
                }
                unclosedParen = reportLine < line && openParens(tokens, reportLine) > 0;
                String message = unclosedParen ? missingParen(at)
                        : notFinished ? "the declaration is not finished at the end of this line"
                        : previous != null && unfinished(previous) ? "`" + previous.getText() + "` needs "
                                + (previous.getType() == AxolotlLexer.DOT ? "a name"
                                : previous.getType() == AxolotlLexer.ARROW ? "a value or a block" : "a value") + " after it"
                        : parserMessage(msg, at, tokens);
                // ANTLR recovery tends to report the same broken line several times, the branches,
                // braces and end of file after a broken `when` branch, and statements left outside their
                // function when it took a `}` too early.
                boolean cascade = !diagnostics.isEmpty() && (notFinished || msg.startsWith("extraneous input")
                        || at != null && (at.getType() == AxolotlLexer.ELSE
                        || at.getType() == AxolotlLexer.ARROW || at.getType() == Token.EOF || at.getType() == AxolotlLexer.RBRACE));
                if (!cascade && diagnostics.stream().noneMatch(d -> d.line() == finalLine)) {
                    diagnostics.add(AxlDiagnostic.error(path, reportLine, column, ErrorCode.SYNTAX, message));
                }
            }

            private void report(Mistakes.Placed known) {
                Token at = known.at();
                if (diagnostics.stream().noneMatch(d -> d.line() == at.getLine())) {
                    diagnostics.add(AxlDiagnostic.error(path, at.getLine(), at.getCharPositionInLine() + 1,
                            ErrorCode.SYNTAX, known.message()));
                }
            }
        };
        var tree = parseTree(tokens, parserErrors);
        if (diagnostics.isEmpty()) {
            sharedLines(path, tokens, tree, diagnostics);
        }
        if (!diagnostics.isEmpty()) {
            return new Result(null, diagnostics);
        }
        for (Token token : tokens.getTokens()) {
            if (token.getType() == AxolotlLexer.IDENTIFIER && token.getText().indexOf('$') >= 0) {
                diagnostics.add(AxlDiagnostic.error(path, token.getLine(), token.getCharPositionInLine() + 1,
                        ErrorCode.DOLLAR_IN_NAME, "`$` is not allowed in names: `" + token.getText() + "`"));
            }
            if (token.getType() == AxolotlLexer.STRING_LITERAL && looksLikeTemplate(token.getText())) {
                diagnostics.add(AxlDiagnostic.warning(path, token.getLine(), token.getCharPositionInLine() + 1,
                        ErrorCode.SYNTAX, "Axolotl has no string templates, so `$` stays in the text; "
                                + "join the parts with `+`: `\"value is \" + x`"));
            }
        }
        try {
            return new Result(AstBuilder.build(path, className, tree), diagnostics);
        } catch (Rejected e) {
            diagnostics.add(AxlDiagnostic.error(path, e.pos.line(), e.pos.column(), ErrorCode.SYNTAX, e.getMessage()));
            return new Result(null, diagnostics);
        }
    }
}
