package org.axl.compiler.parse;

import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.ErrorCode;
import org.axl.compiler.ast.SourceFile;

public final class SourceParser {

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
                    case "'" -> "unclosed character literal: add `'`";
                    default -> "unexpected character `" + t.getText() + "`";
                };
            } else if (t.getType() == AxolotlLexer.SLASH && i + 1 < all.size() && all.get(i + 1).getType() == AxolotlLexer.STAR
                    && all.get(i + 1).getStartIndex() == t.getStopIndex() + 1) {
                message = "unclosed comment: add `*/`";
            } else if (t.getType() == AxolotlLexer.INT_LITERAL && t.getText().matches("0[0-9_]+[lL]?")) {
                message = "a number cannot start with 0: Java would read `" + t.getText() + "` as octal";
            } else if (t.getType() == AxolotlLexer.IDENTIFIER && !isJavaName(t.getText())) {
                message = "`" + t.getText() + "` is not a valid name: use letters, digits and `_`";
            }
            int line = t.getLine();
            if (message != null && errors.stream().noneMatch(d -> d.line() == line)) {
                errors.add(AxlDiagnostic.error(path, line, t.getCharPositionInLine() + 1, ErrorCode.SYNTAX, message));
            }
        }
        return errors;
    }

    private static boolean isJavaName(String name) {
        return Character.isJavaIdentifierStart(name.codePointAt(0))
                && name.codePoints().skip(1).allMatch(Character::isJavaIdentifierPart);
    }

    private static String parserMessage(String msg, Token at, CommonTokenStream tokens) {
        if (at != null && at.getType() == Token.EOF) {
            Token open = unclosedBrace(tokens);
            return open != null ? "unexpected end of file: the `{` at line " + open.getLine() + " is not closed"
                    : "unexpected end of file";
        }
        String token = at == null ? "" : at.getText();
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
                // Something missing at the end of a line is reported there, not where the next line starts.
                if (previous != null && previous.getLine() < line && !msg.startsWith("extraneous input")) {
                    reportLine = previous.getLine();
                    column = previous.getCharPositionInLine() + previous.getText().length() + 1;
                }
                int finalLine = reportLine;
                String message = reportLine < line && openParens(tokens, reportLine) > 0
                        ? "missing `)` at the end of the line" : parserMessage(msg, at, tokens);
                // ANTLR recovery tends to report the same broken line several times.
                if (diagnostics.stream().noneMatch(d -> d.line() == finalLine)) {
                    diagnostics.add(AxlDiagnostic.error(path, reportLine, column, ErrorCode.SYNTAX, message));
                }
            }
        };
        var tree = parseTree(tokens, parserErrors);
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
            return new Result(new AstBuilder().build(path, className, tree), diagnostics);
        } catch (AstBuilder.Rejected e) {
            diagnostics.add(AxlDiagnostic.error(path, e.pos.line(), e.pos.column(), ErrorCode.SYNTAX, e.getMessage()));
            return new Result(null, diagnostics);
        }
    }
}
