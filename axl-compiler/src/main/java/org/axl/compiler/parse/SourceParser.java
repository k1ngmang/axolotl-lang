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
            String message = null;
            if (t.getType() == AxolotlLexer.UNKNOWN_CHAR) {
                message = switch (t.getText()) {
                    case "\"" -> "unclosed string: add `\"` before the end of the line";
                    case "'" -> adjacent(all, i) && all.get(i + 1).getText().equals("'")
                            ? "a character literal holds one character; put one between the quotes"
                            : "unclosed character literal: add `'`";
                    default -> unexpected(t.getText().codePointAt(0));
                };
            } else if ((t.getType() == AxolotlLexer.IMPORT || t.getType() == AxolotlLexer.PACKAGE)
                    && (previousCode(all, i) < 0 || all.get(previousCode(all, i)).getLine() < t.getLine())
                    && (nextCode(all, i) >= all.size() || all.get(nextCode(all, i)).getLine() > t.getLine())) {
                message = "`" + t.getText() + "` needs a name after it on the same line";
            } else if (t.getType() == AxolotlLexer.SLASH && i + 1 < all.size() && all.get(i + 1).getType() == AxolotlLexer.STAR
                    && all.get(i + 1).getStartIndex() == t.getStopIndex() + 1) {
                message = "unclosed comment: add `*/`";
            } else if (t.getType() == AxolotlLexer.INT_LITERAL && t.getText().matches("0[0-9_]+[lL]?")) {
                message = "a number cannot start with 0: Java would read `" + t.getText() + "` as octal";
            } else if ((t.getType() == AxolotlLexer.PLUS || t.getType() == AxolotlLexer.MINUS) && adjacent(all, i)
                    && all.get(i + 1).getType() == t.getType()) {
                message = "Axolotl has no `" + t.getText() + t.getText() + "`; write `" + t.getText() + "= 1`";
            } else if (t.getType() == AxolotlLexer.INT_LITERAL && adjacent(all, i) && all.get(i + 1).getType() == AxolotlLexer.DOT) {
                Token after = adjacent(all, i + 1) ? all.get(i + 2) : null;
                if (after == null || after.getType() != AxolotlLexer.IDENTIFIER) {
                    message = "`" + t.getText() + ".` is not a valid number; write `" + t.getText() + ".0`";
                } else if (after.getText().matches("[fFdD]|[eE]\\d*")) {
                    // Java reads `3.f` as a float and `1.e5` as a double.
                    String fixed = t.getText() + ".0" + (after.getText().matches("[eE].*") ? after.getText() : "");
                    message = "`" + t.getText() + "." + after.getText() + "` is not a valid number; write `" + fixed + "`";
                } else {
                    message = "a number has no members; for `" + t.getText() + "." + after.getText()
                            + "`, call a method of its class, such as `Integer.toString(" + t.getText() + ")`";
                }
            } else if (t.getType() == AxolotlLexer.INT_LITERAL || t.getType() == AxolotlLexer.FLOAT_LITERAL) {
                message = numberProblem(t, afterUnaryMinus(all, i), i + 1 < all.size() ? all.get(i + 1) : null);
            } else if (t.getType() == AxolotlLexer.STRING_LITERAL || t.getType() == AxolotlLexer.CHAR_LITERAL) {
                message = textProblem(t);
            } else if (t.getType() == AxolotlLexer.IDENTIFIER && !isJavaName(t.getText())) {
                message = "`" + t.getText() + "` is not a valid name: use the letters A to Z and a to z, digits and `_`";
            }
            int line = t.getLine();
            if (message != null && errors.stream().noneMatch(d -> d.line() == line)) {
                errors.add(AxlDiagnostic.error(path, line, t.getCharPositionInLine() + 1, ErrorCode.SYNTAX, message));
            }
        }
        return errors;
    }

    /** Statements end at the end of a line or at `;`; the grammar alone would take `f() g()` as two. */
    private static void sharedLines(String path, ParseTree node, List<AxlDiagnostic> diagnostics) {
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
                AxlDiagnostic problem = sharedLine(path, statements.get(i - 1).getStart(), end, start);
                diagnostics.add(problem);
                // A misspelled or Java-style word splits one line into several statements; report it once.
                if (!problem.message().startsWith(OWN_LINE)) {
                    reportedLine = start.getLine();
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            sharedLines(path, node.getChild(i), diagnostics);
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

    private static AxlDiagnostic sharedLine(String path, Token first, Token last, Token next) {
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

    private static AxlDiagnostic error(String path, Token at, String message) {
        return AxlDiagnostic.error(path, at.getLine(), at.getCharPositionInLine() + 1, ErrorCode.SYNTAX, message);
    }

    /** The keyword a word is a slip of: one or two letters off, or swapped. */
    private static String keywordLike(Token token) {
        if (token.getType() != AxolotlLexer.IDENTIFIER || token.getText().length() < 3) {
            return null;
        }
        String text = token.getText();
        if (text.equals("fun") || text.equals("def") || text.equals("function")) {
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

    private static int nextCode(List<Token> tokens, int i) {
        int j = i + 1;
        while (j < tokens.size() && Set.of(AxolotlLexer.WS, AxolotlLexer.LINE_COMMENT, AxolotlLexer.BLOCK_COMMENT).contains(tokens.get(j).getType())) {
            j++;
        }
        return j;
    }

    private static int previousCode(List<Token> tokens, int i) {
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
        for (int i = 0; i < inner.length(); count++) {
            if (inner.charAt(i) != '\\') {
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
            i = escape.end();
        }
        if (text.startsWith("'") && count != 1) {
            return "a character literal holds one character; write text in double quotes";
        }
        if (text.startsWith("'") && Character.isSupplementaryCodePoint(inner.codePointAt(0))) {
            return "`" + inner + "` takes two Java chars, so it does not fit in a `Character`; write it in double quotes";
        }
        return null;
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
        if (!token.equals("->") && (msg.contains("'->'") && !msg.startsWith("missing ") || brokenArm(msg, previous, at))) {
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

    /** Whether the `)` closes the condition of an `if`. */
    private static boolean closesIf(CommonTokenStream tokens, Token close) {
        int depth = 0;
        for (int i = close.getTokenIndex(); i >= 0; i--) {
            int type = tokens.get(i).getType();
            if (type == AxolotlLexer.RPAREN) {
                depth++;
            } else if (type == AxolotlLexer.LPAREN && --depth == 0) {
                Token before = previousToken(tokens, tokens.get(i));
                return before != null && before.getType() == AxolotlLexer.IF;
            }
        }
        return false;
    }

    /** ANTLR gives up on the whole `when` when an arm's condition runs into its value. */
    private static boolean brokenArm(String msg, Token previous, Token at) {
        return msg.startsWith("no viable alternative at input 'when") && previous != null
                && previous.getLine() == at.getLine();
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

    private static Token previousToken(CommonTokenStream tokens, Token at) {
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

    private static Token unclosedBrace(CommonTokenStream tokens) {
        java.util.ArrayDeque<Token> open = new java.util.ArrayDeque<>();
        for (Token t : tokens.getTokens()) {
            if (t.getType() == AxolotlLexer.LBRACE) {
                open.push(t);
            } else if (t.getType() == AxolotlLexer.RBRACE && !open.isEmpty()) {
                open.pop();
            }
        }
        return open.peek();
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
        var tokens = new CommonTokenStream(lexer);
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
        diagnostics.addAll(lexicalErrors(path, tokens));
        if (!diagnostics.isEmpty()) {
            // The parser's errors after a broken token only repeat the problem.
            return new Result(null, diagnostics);
        }
        var parserErrors = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                    int charPositionInLine, String msg, RecognitionException e) {
                Token at = offendingSymbol instanceof Token t ? t : null;
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
                String message = reportLine < line && openParens(tokens, reportLine) > 0
                        ? "missing `)` at the end of the line"
                        : reportLine < line && nextDeclaration && !unfinished(previous)
                        ? "the declaration is not finished at the end of this line"
                        : previous != null && unfinished(previous) ? "`" + previous.getText() + "` needs "
                                + (previous.getType() == AxolotlLexer.DOT ? "a name"
                                : previous.getType() == AxolotlLexer.ARROW ? "a value or a block" : "a value") + " after it"
                        : parserMessage(msg, at, tokens);
                // ANTLR recovery tends to report the same broken line several times, and the branches,
                // braces and end of file after a broken `when` branch.
                boolean cascade = !diagnostics.isEmpty() && at != null && (at.getType() == AxolotlLexer.ELSE
                        || at.getType() == AxolotlLexer.ARROW || at.getType() == Token.EOF || at.getType() == AxolotlLexer.RBRACE);
                if (!cascade && diagnostics.stream().noneMatch(d -> d.line() == finalLine)) {
                    diagnostics.add(AxlDiagnostic.error(path, reportLine, column, ErrorCode.SYNTAX, message));
                }
            }
        };
        var tree = parseTree(tokens, parserErrors);
        if (diagnostics.isEmpty()) {
            sharedLines(path, tree, diagnostics);
        }
        if (!diagnostics.isEmpty()) {
            return new Result(null, diagnostics);
        }
        for (Token token : tokens.getTokens()) {
            if (token.getType() == AxolotlLexer.IDENTIFIER && token.getText().indexOf('$') >= 0) {
                diagnostics.add(AxlDiagnostic.error(path, token.getLine(), token.getCharPositionInLine() + 1,
                        ErrorCode.DOLLAR_IN_NAME, "`$` is not allowed in names: `" + token.getText() + "`"));
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
