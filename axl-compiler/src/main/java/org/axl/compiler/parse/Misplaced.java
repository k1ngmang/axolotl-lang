package org.axl.compiler.parse;

import static org.axl.compiler.parse.Mistakes.before;
import static org.axl.compiler.parse.Mistakes.enclosingBrace;
import static org.axl.compiler.parse.Mistakes.enclosingParen;
import static org.axl.compiler.parse.Mistakes.lineHas;
import static org.axl.compiler.parse.Mistakes.type;

import java.util.Set;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.parse.Mistakes.Placed;

/**
 * Code in a place that does not take it: a statement outside a function, a function or type inside
 * one, an assignment as a `when` branch, a class parameter written like a function parameter.
 */
final class Misplaced {

    private static final Set<Integer> TYPE_WORDS = Set.of(AxolotlLexer.CLASS, AxolotlLexer.ENUM, AxolotlLexer.INTERFACE,
            AxolotlLexer.SEALED);

    private static final Set<Integer> STATEMENT_WORDS = Set.of(AxolotlLexer.RETURN, AxolotlLexer.IF, AxolotlLexer.WHILE,
            AxolotlLexer.FOR, AxolotlLexer.WHEN, AxolotlLexer.THROW);

    private static final Set<Integer> CONDITION_OPERATORS = Set.of(AxolotlLexer.LT, AxolotlLexer.GT, AxolotlLexer.LE,
            AxolotlLexer.GE, AxolotlLexer.EQ, AxolotlLexer.NEQ, AxolotlLexer.ANDAND, AxolotlLexer.OROR);

    private Misplaced() {}

    /** The cause of an error the parser found at the token at, after previous, or null. */
    static Placed find(CommonTokenStream tokens, Token at, Token previous) {
        if (at.getType() == AxolotlLexer.CLASS && previous.getType() == AxolotlLexer.COLONCOLON) {
            String type = Mistakes.text(SourceParser.previousToken(tokens, previous));
            return new Placed(previous, "Axolotl has no `::class`; the class of a type is `" + type + ".class`", true);
        }
        Placed declaration = declaration(tokens, at, previous);
        if (declaration != null) {
            return declaration;
        }
        Placed branch = whenBranch(tokens, at, previous);
        return branch != null ? branch : classParameter(tokens, at, previous);
    }

    /** Functions and types inside a block, statements outside one, and a `{` left open before the next function. */
    private static Placed declaration(CommonTokenStream tokens, Token at, Token previous) {
        boolean named = Set.of(AxolotlLexer.VAL, AxolotlLexer.VAR, AxolotlLexer.FN).contains(previous.getType());
        if (named && previous.getLine() == at.getLine() && at.getType() != AxolotlLexer.IDENTIFIER
                && at.getText().matches("[a-z]+")) {
            return new Placed(at, "`" + at.getText() + "` is a keyword and cannot be a name; choose another name", true);
        }
        if (at.getType() == AxolotlLexer.IMPORT && at.getCharPositionInLine() == 0) {
            return new Placed(at, "imports go at the top of the file, after `package`", true);
        }
        Token brace = enclosingBrace(tokens, at);
        boolean declares = at.getType() == AxolotlLexer.FN || TYPE_WORDS.contains(at.getType());
        if (declares && brace != null && at.getCharPositionInLine() == 0) {
            return new Placed(at, "the `{` at line " + brace.getLine() + " is not closed; add `}` before this `"
                    + at.getText() + "`", true);
        }
        boolean inInterface = brace != null && lineHas(tokens, brace.getLine(), AxolotlLexer.INTERFACE);
        if (at.getType() == AxolotlLexer.FN && brace != null && !inInterface) {
            return new Placed(at, "functions cannot be nested; declare `" + Mistakes.text(SourceParser.nextToken(tokens, at))
                    + "` at the top level, or keep a lambda in a `val`", true);
        }
        if (TYPE_WORDS.contains(at.getType()) && brace != null) {
            Token start = previous.getText().equals("data") ? previous : at;
            return new Placed(start, "types are declared at the top level of the file", true);
        }
        boolean call = at.getType() == AxolotlLexer.IDENTIFIER
                && Set.of(AxolotlLexer.LPAREN, AxolotlLexer.DOT).contains(type(SourceParser.nextToken(tokens, at)));
        if ((call || STATEMENT_WORDS.contains(at.getType())) && brace == null && at.getCharPositionInLine() == 0) {
            return new Placed(at, "statements go inside a function, such as `fn main() { ... }`", true);
        }
        return null;
    }

    /** Kotlin's `1 -> a(); b()` and `n > 0 -> "pos"`. */
    private static Placed whenBranch(CommonTokenStream tokens, Token at, Token previous) {
        Token first = SourceParser.lineStart(tokens, at);
        if (!SourceParser.inWhenBraces(tokens, first)) {
            return null;
        }
        Token arrow = arrowBefore(tokens, first, at);
        Token semicolon = arrow == null ? null : semicolonBefore(tokens, arrow, at);
        if (semicolon != null) {
            return new Placed(semicolon, "several statements in a `when` branch go in braces: `" + text(tokens, first, arrow)
                    + " -> { " + tokens.getText(SourceParser.nextToken(tokens, arrow), lastOfLine(tokens, at)) + " }`", true);
        }
        if (arrow == null && CONDITION_OPERATORS.contains(at.getType()) && lineHas(tokens, at.getLine(), AxolotlLexer.ARROW)) {
            return new Placed(at, "a `when` branch compares with a constant or tests a type with `is`; for other "
                    + "conditions, use `if` and `else if`", true);
        }
        return null;
    }

    /** `class Box(v: Int)` and `data class P(private val n: Int)`. */
    private static Placed classParameter(CommonTokenStream tokens, Token at, Token previous) {
        Token paren = enclosingParen(tokens, at);
        Token name = before(tokens, paren);
        if (paren == null || type(before(tokens, name)) != AxolotlLexer.CLASS) {
            return null;
        }
        boolean data = Mistakes.text(before(tokens, before(tokens, name))).equals("data");
        if (at.getType() == AxolotlLexer.PRIVATE && data) {
            return new Placed(at, "the fields of a data class are public, as those of a Java record; remove `private`, "
                    + "or make `" + name.getText() + "` a `class` to keep the value inside it", true);
        }
        boolean field = at.getType() == AxolotlLexer.IDENTIFIER && type(SourceParser.nextToken(tokens, at)) == AxolotlLexer.COLON
                && (previous == paren || previous.getType() == AxolotlLexer.COMMA);
        if (!field) {
            return null;
        }
        String declared = at.getText() + ": " + typeText(tokens, SourceParser.nextToken(tokens, SourceParser.nextToken(tokens, at)));
        return new Placed(at, "a parameter of a class is a property; write `val " + declared + "`"
                + (data ? "" : ", or `var " + declared + "` if it changes"), true);
    }

    /** The first `->` of the line before the token, or null. */
    private static Token arrowBefore(CommonTokenStream tokens, Token first, Token at) {
        for (Token t = first; t != null && t.getTokenIndex() < at.getTokenIndex(); t = SourceParser.nextToken(tokens, t)) {
            if (t.getType() == AxolotlLexer.ARROW) {
                return t;
            }
            if (t.getType() == Token.EOF) {
                break;
            }
        }
        return null;
    }

    /** A `;` between the arrow of a `when` branch and the token, or null. */
    private static Token semicolonBefore(CommonTokenStream tokens, Token arrow, Token at) {
        for (Token t = arrow; t.getTokenIndex() < at.getTokenIndex(); t = SourceParser.nextToken(tokens, t)) {
            if (t.getType() == AxolotlLexer.SEMI) {
                return t;
            }
        }
        return null;
    }

    private static Token lastOfLine(CommonTokenStream tokens, Token at) {
        Token last = at;
        for (Token t = SourceParser.nextToken(tokens, at); t.getLine() == at.getLine() && t.getType() != Token.EOF;
                t = SourceParser.nextToken(tokens, t)) {
            last = t;
        }
        return last;
    }

    /** The tokens from first up to end, without end, as written with single spaces. */
    private static String text(CommonTokenStream tokens, Token first, Token end) {
        StringBuilder text = new StringBuilder();
        for (Token t = first; t.getTokenIndex() < end.getTokenIndex(); t = SourceParser.nextToken(tokens, t)) {
            text.append(text.isEmpty() ? "" : " ").append(t.getText());
        }
        return text.toString();
    }

    /** A type as written, up to the `,` or `)` after it. */
    private static String typeText(CommonTokenStream tokens, Token first) {
        StringBuilder text = new StringBuilder();
        int depth = 0;
        for (Token t = first; t.getType() != Token.EOF; t = SourceParser.nextToken(tokens, t)) {
            depth += t.getType() == AxolotlLexer.LT ? 1 : t.getType() == AxolotlLexer.GT ? -1 : 0;
            if (depth == 0 && (t.getType() == AxolotlLexer.COMMA || t.getType() == AxolotlLexer.RPAREN)) {
                break;
            }
            text.append(t.getType() == AxolotlLexer.COMMA ? ", " : t.getText());
        }
        return text.toString();
    }
}
