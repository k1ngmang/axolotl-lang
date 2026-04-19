package org.axl.compiler.parse;

import java.util.List;
import java.util.Set;
import org.antlr.v4.runtime.Token;

/**
 * Words and symbols that Java and Kotlin have and Axolotl does not. They are found in the tokens,
 * before parsing, since the parser could only call them unexpected.
 */
final class Habits {

    private static final Set<String> MODIFIERS = Set.of("public", "protected", "static", "final", "abstract",
            "override", "open", "const", "lateinit");

    private static final Set<Integer> DECLARATION_WORDS = Set.of(AxolotlLexer.FN, AxolotlLexer.VAL, AxolotlLexer.VAR,
            AxolotlLexer.CLASS, AxolotlLexer.INTERFACE, AxolotlLexer.ENUM, AxolotlLexer.SEALED, AxolotlLexer.PRIVATE,
            AxolotlLexer.IDENTIFIER);

    private static final String NO_TRY_CATCH = "Axolotl has no `try`-`catch`; turn the exception into a `Result` "
            + "with `attempt f() else e -> ...`";

    private Habits() {}

    /** The message for the token at index i, or null. */
    static String of(List<Token> tokens, int i) {
        Token t = tokens.get(i);
        return switch (t.getType()) {
            case AxolotlLexer.IDENTIFIER -> word(tokens, i);
            case AxolotlLexer.FOR -> javaFor(tokens, i);
            case AxolotlLexer.IMPORT -> wildcardImport(tokens, i);
            case AxolotlLexer.QUESTION -> ternary(tokens, i);
            case AxolotlLexer.ASSIGN -> type(tokens, i + 1) == AxolotlLexer.GT && adjacent(tokens, i)
                    ? "write `->` after the condition of a `when` branch, and in a lambda" : null;
            case AxolotlLexer.UNKNOWN_CHAR -> t.getText().equals("[") ? bracket(tokens, i) : null;
            default -> null;
        };
    }

    private static String word(List<Token> tokens, int i) {
        String text = tokens.get(i).getText();
        int nextIndex = SourceParser.nextCode(tokens, i);
        int next = type(tokens, nextIndex);
        // `const x = 1` from JavaScript has a message of its own.
        boolean declares = !text.equals("const") || next == AxolotlLexer.VAL;
        if (MODIFIERS.contains(text)) {
            return declares && modifierPosition(tokens, i) ? modifier(text) : null;
        }
        return switch (text) {
            case "void" -> next == AxolotlLexer.IDENTIFIER && startsDeclaration(tokens, i)
                    && type(tokens, SourceParser.nextCode(tokens, nextIndex)) == AxolotlLexer.LPAREN
                    ? "functions are declared with `fn`: `fn " + text(tokens, nextIndex) + "(...)`" : null;
            case "switch" -> next == AxolotlLexer.LPAREN
                    ? "Axolotl has `when` instead of `switch`: `when (n) { 1 -> ... else -> ... }`" : null;
            case "case" -> startsLine(tokens, i)
                    ? "Axolotl has `when` instead of `switch`: `when (n) { 1 -> ... else -> ... }`" : null;
            case "do" -> next == AxolotlLexer.LBRACE ? "Axolotl has no `do`-`while`; write a `while` loop" : null;
            case "break" -> alone(tokens, i) ? "Axolotl has no `break`; end the loop with its condition, "
                    + "or move the loop into a function and `return`" : null;
            case "continue" -> alone(tokens, i) ? "Axolotl has no `continue`; put the rest of the loop body in an `if`" : null;
            case "catch", "finally" -> type(tokens, SourceParser.previousCode(tokens, i)) == AxolotlLexer.RBRACE
                    ? NO_TRY_CATCH : null;
            case "instanceof" -> "Axolotl tests types with `is`: `if (x is String) { ... }`";
            case "vararg" -> next == AxolotlLexer.IDENTIFIER ? "Axolotl has no `vararg`; take a `List` parameter" : null;
            case "object", "companion" -> startsLine(tokens, i) && (next == AxolotlLexer.IDENTIFIER || next == AxolotlLexer.LBRACE)
                    ? "Axolotl has no `object`; functions and values at the top of a file are already shared by the "
                    + "whole program" : null;
            default -> null;
        };
    }

    private static String modifier(String word) {
        return switch (word) {
            case "public", "protected" -> "declarations are public by default; remove `" + word + "`";
            case "static" -> "Axolotl has no `static`; remove it: functions and values at the top of a file "
                    + "need no object";
            case "final" -> "a `val` cannot change already; remove `final`";
            case "const" -> "Axolotl has no `const`; a top-level `val` is already constant, so remove `const`";
            case "lateinit" -> "Axolotl has no `lateinit`; give the `var` a value, or make it nullable: "
                    + "`var x: T? = null`";
            case "override" -> "Axolotl has no `override`; remove it";
            default -> "a class cannot be extended in Axolotl; remove `" + word + "`";
        };
    }

    /** Before a declaration: `public fn`, `static void main`, `override fun`. */
    private static boolean modifierPosition(List<Token> tokens, int i) {
        int next = SourceParser.nextCode(tokens, i);
        return startsDeclaration(tokens, i) && DECLARATION_WORDS.contains(type(tokens, next));
    }

    /** First on its line, or after another modifier. */
    private static boolean startsDeclaration(List<Token> tokens, int i) {
        int previous = SourceParser.previousCode(tokens, i);
        return startsLine(tokens, i) || MODIFIERS.contains(text(tokens, previous));
    }

    /** Java's `for (int i = 0; i < n; i++)` and `for (String s : xs)`. */
    private static String javaFor(List<Token> tokens, int i) {
        int open = SourceParser.nextCode(tokens, i);
        if (type(tokens, open) != AxolotlLexer.LPAREN) {
            return null;
        }
        int depth = 0;
        boolean colon = false;
        for (int j = open; j < tokens.size(); j++) {
            switch (tokens.get(j).getType()) {
                case AxolotlLexer.LPAREN -> depth++;
                case AxolotlLexer.RPAREN -> {
                    if (--depth == 0) {
                        return colon ? "Axolotl's `for` uses `in`: `for (x in xs) { ... }`" : null;
                    }
                }
                case AxolotlLexer.SEMI -> {
                    return "Axolotl's `for` goes over a collection: `for (x in xs) { ... }`; count with a `while` loop";
                }
                case AxolotlLexer.COLON -> colon |= depth == 1;
                case AxolotlLexer.LBRACE, Token.EOF -> {
                    return null;
                }
                default -> { }
            }
        }
        return null;
    }

    private static String wildcardImport(List<Token> tokens, int i) {
        int line = tokens.get(i).getLine();
        for (int j = i + 1; j < tokens.size() && tokens.get(j).getLine() == line; j++) {
            if (type(tokens, j) == AxolotlLexer.STAR && type(tokens, j - 1) == AxolotlLexer.DOT) {
                return "Axolotl imports one type at a time: `import java.util.ArrayList`";
            }
        }
        return null;
    }

    /** `c ? a : b`; a `?` that makes a type nullable is written right after the type. */
    private static String ternary(List<Token> tokens, int i) {
        if (i == 0 || type(tokens, i - 1) != AxolotlLexer.WS) {
            return null;
        }
        int line = tokens.get(i).getLine();
        for (int j = i + 1; j < tokens.size() && tokens.get(j).getLine() == line; j++) {
            if (type(tokens, j) == AxolotlLexer.COLON) {
                return "Axolotl has no `? :`; write `if (c) a else b`";
            }
        }
        return null;
    }

    /** Indexing, array types and list literals. */
    private static String bracket(List<Token> tokens, int i) {
        int previous = SourceParser.previousCode(tokens, i);
        String before = text(tokens, previous);
        boolean afterValue = previous == i - 1
                && (type(tokens, previous) == AxolotlLexer.IDENTIFIER || type(tokens, previous) == AxolotlLexer.RPAREN);
        if (afterValue && (text(tokens, i + 1).equals("]") || text(tokens, SourceParser.previousCode(tokens, previous)).equals("new"))) {
            return "Axolotl has no arrays; use a list, such as `List<" + typeName(before) + ">` or `ArrayList<"
                    + typeName(before) + ">()`";
        }
        if (afterValue) {
            String receiver = type(tokens, previous) == AxolotlLexer.IDENTIFIER ? before : "list";
            return "Axolotl has no `[]` indexing; call `get`: `" + receiver + ".get(0)`";
        }
        return "Axolotl has no list literals; write `List.of(1, 2, 3)`";
    }

    private static String typeName(String javaName) {
        return switch (javaName) {
            case "int" -> "Int";
            case "char" -> "Character";
            case "long", "double", "boolean" -> Character.toUpperCase(javaName.charAt(0)) + javaName.substring(1);
            default -> javaName;
        };
    }

    private static boolean alone(List<Token> tokens, int i) {
        int next = SourceParser.nextCode(tokens, i);
        return startsLine(tokens, i) && (next >= tokens.size() || tokens.get(next).getLine() > tokens.get(i).getLine()
                || type(tokens, next) == AxolotlLexer.SEMI || type(tokens, next) == AxolotlLexer.RBRACE);
    }

    private static boolean startsLine(List<Token> tokens, int i) {
        int previous = SourceParser.previousCode(tokens, i);
        return previous < 0 || tokens.get(previous).getLine() < tokens.get(i).getLine();
    }

    private static boolean adjacent(List<Token> tokens, int i) {
        return i + 1 < tokens.size() && tokens.get(i + 1).getStartIndex() == tokens.get(i).getStopIndex() + 1;
    }

    private static int type(List<Token> tokens, int i) {
        return i >= 0 && i < tokens.size() ? tokens.get(i).getType() : Token.EOF;
    }

    private static String text(List<Token> tokens, int i) {
        return i >= 0 && i < tokens.size() && tokens.get(i).getType() != Token.EOF ? tokens.get(i).getText() : "";
    }
}
