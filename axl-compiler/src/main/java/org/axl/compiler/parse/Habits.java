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

    private static final Set<Integer> VALUE_ENDS = Set.of(AxolotlLexer.IDENTIFIER, AxolotlLexer.RPAREN,
            AxolotlLexer.INT_LITERAL, AxolotlLexer.FLOAT_LITERAL, AxolotlLexer.STRING_LITERAL, AxolotlLexer.CHAR_LITERAL,
            AxolotlLexer.TRUE, AxolotlLexer.FALSE, AxolotlLexer.NULL);

    private static final Set<Integer> VALUE_STARTS = Set.of(AxolotlLexer.IDENTIFIER, AxolotlLexer.LPAREN,
            AxolotlLexer.INT_LITERAL, AxolotlLexer.FLOAT_LITERAL, AxolotlLexer.STRING_LITERAL, AxolotlLexer.CHAR_LITERAL,
            AxolotlLexer.TRUE, AxolotlLexer.FALSE, AxolotlLexer.NULL, AxolotlLexer.BANG, AxolotlLexer.MINUS);

    private static final String BIT_OPERATORS = "Axolotl has no bit operators such as `&`, `|` and `<<`; for flags, "
            + "use a `Set` or `java.util.BitSet`";

    private static final String RANGES = "Axolotl has no ranges (`until`, `..`); count with a `while` loop, or use "
            + "`java.util.stream.IntStream.range(from, to)`";

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
            case AxolotlLexer.QUESTION -> followedBy(tokens, i, AxolotlLexer.QUESTION)
                    ? "write `?:` for a default: `s ?: \"x\"`" : ternary(tokens, i);
            case AxolotlLexer.ASSIGN -> followedBy(tokens, i, AxolotlLexer.GT) ? fatArrow(tokens, i) : null;
            case AxolotlLexer.UNKNOWN_CHAR -> unknownChar(tokens, i);
            default -> symbol(tokens, i);
        };
    }

    /** Operators of Java, TypeScript and Python that Axolotl writes otherwise or not at all. */
    private static String symbol(List<Token> tokens, int i) {
        int next = type(tokens, SourceParser.nextCode(tokens, i));
        return switch (tokens.get(i).getType()) {
            case AxolotlLexer.EQ, AxolotlLexer.NEQ -> followedBy(tokens, i, AxolotlLexer.ASSIGN)
                    ? "Axolotl has no `===` or `!==`; `==` compares numbers by value, and other objects by identity; "
                    + "compare objects with `a.equals(b)`" : null;
            case AxolotlLexer.STAR -> followedBy(tokens, i, AxolotlLexer.STAR) && type(tokens, i - 1) != AxolotlLexer.DOT
                    ? "Axolotl has no `**`; use `Math.pow(a, b)`" : null;
            case AxolotlLexer.LT -> followedBy(tokens, i, AxolotlLexer.LT) ? BIT_OPERATORS : null;
            case AxolotlLexer.BANG -> followedBy(tokens, i, AxolotlLexer.DOT)
                    ? "write `!!.` to state that the value is not null: `s!!.length()`" : null;
            case AxolotlLexer.IS -> next == AxolotlLexer.NULL ? "compare with `==` to test for null: `s == null`" : null;
            case AxolotlLexer.ELVIS -> next == AxolotlLexer.RETURN ? elvisReturn(tokens, i) : null;
            case AxolotlLexer.STRING_LITERAL -> tokens.get(i).getText().equals("\"\"")
                    && followedBy(tokens, i, AxolotlLexer.STRING_LITERAL)
                    ? "Axolotl has no text blocks (`\"\"\"`); join the lines with `+` and `\\n`" : null;
            default -> null;
        };
    }

    /** `s ?: return 0`, which Kotlin allows. */
    private static String elvisReturn(List<Token> tokens, int i) {
        int previous = SourceParser.previousCode(tokens, i);
        String value = type(tokens, previous) == AxolotlLexer.IDENTIFIER ? text(tokens, previous) : "s";
        StringBuilder fallback = new StringBuilder();
        int line = tokens.get(i).getLine();
        for (int j = SourceParser.nextCode(tokens, i); j < tokens.size() && tokens.get(j).getLine() == line
                && type(tokens, j) != Token.EOF; j = SourceParser.nextCode(tokens, j)) {
            fallback.append(fallback.isEmpty() ? "" : " ").append(text(tokens, j));
        }
        return "`return` cannot be the fallback of `?:`; check first: `if (" + value + " == null) { " + fallback
                + " }`, and `" + value + "` is not null after it";
    }

    /** `=>` of TypeScript lambdas, or a `when` branch written with the wrong arrow. */
    private static String fatArrow(List<Token> tokens, int i) {
        int previous = SourceParser.previousCode(tokens, i);
        if (type(tokens, previous) != AxolotlLexer.RPAREN) {
            return "write `->` after the condition of a `when` branch, and in a lambda";
        }
        List<String> names = new java.util.ArrayList<>();
        int depth = 0;
        for (int j = previous; j >= 0; j--) {
            depth += type(tokens, j) == AxolotlLexer.RPAREN ? 1 : type(tokens, j) == AxolotlLexer.LPAREN ? -1 : 0;
            int before = type(tokens, SourceParser.previousCode(tokens, j));
            int after = type(tokens, SourceParser.nextCode(tokens, j));
            boolean untyped = (before == AxolotlLexer.LPAREN || before == AxolotlLexer.COMMA)
                    && (after == AxolotlLexer.COMMA || after == AxolotlLexer.RPAREN);
            if (depth == 1 && type(tokens, j) == AxolotlLexer.IDENTIFIER && (after == AxolotlLexer.COLON || untyped)) {
                names.addFirst(text(tokens, j));
            }
            if (depth == 0) {
                break;
            }
        }
        return "lambdas are written in braces: `{ " + String.join(", ", names) + (names.isEmpty() ? "" : " ")
                + "-> ... }`";
    }

    /** Characters that start no Axolotl token. */
    private static String unknownChar(List<Token> tokens, int i) {
        return switch (tokens.get(i).getText()) {
            case "[" -> bracket(tokens, i);
            case "@" -> followedBy(tokens, i, AxolotlLexer.IDENTIFIER)
                    ? "Axolotl has no annotations; remove `@" + text(tokens, i + 1) + "`" : null;
            case "`" -> "Axolotl has no template strings; join the parts with `+`: `\"n is \" + n`";
            case "#" -> !alone(tokens, i) && startsLine(tokens, i) ? "comments start with `//`" : null;
            case "|" -> unionType(tokens, i) ? "a parameter has one type; Axolotl has no union types, so take a common "
                    + "interface or `Object`" : BIT_OPERATORS;
            case "&" -> BIT_OPERATORS;
            default -> null;
        };
    }

    /** `a: String | Int` in a parameter list. */
    private static boolean unionType(List<Token> tokens, int i) {
        for (int j = i - 1; j >= 0 && type(tokens, j) != AxolotlLexer.LPAREN && type(tokens, j) != AxolotlLexer.COMMA; j--) {
            if (type(tokens, j) == AxolotlLexer.COLON) {
                return true;
            }
        }
        return false;
    }

    private static boolean followedBy(List<Token> tokens, int i, int type) {
        return adjacent(tokens, i) && type(tokens, i + 1) == type;
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
            case "and", "or" -> VALUE_ENDS.contains(type(tokens, SourceParser.previousCode(tokens, i)))
                    && VALUE_STARTS.contains(next) ? AND_OR_NOT : null;
            case "not" -> !VALUE_ENDS.contains(type(tokens, SourceParser.previousCode(tokens, i)))
                    && (next == AxolotlLexer.IDENTIFIER || next == AxolotlLexer.TRUE || next == AxolotlLexer.FALSE)
                    ? AND_OR_NOT : null;
            case "in" -> inOperator(tokens, i, nextIndex);
            case "until", "downTo", "step" -> VALUE_ENDS.contains(type(tokens, SourceParser.previousCode(tokens, i)))
                    && VALUE_STARTS.contains(next) ? RANGES : null;
            case "elif" -> next == AxolotlLexer.LPAREN && (startsLine(tokens, i)
                    || type(tokens, SourceParser.previousCode(tokens, i)) == AxolotlLexer.RBRACE) ? "write `else if`" : null;
            case "f" -> followedBy(tokens, i, AxolotlLexer.STRING_LITERAL)
                    ? "Axolotl has no f-strings; join the parts with `+`: `\"n=\" + n`" : null;
            case "object", "companion" -> startsLine(tokens, i) && (next == AxolotlLexer.IDENTIFIER || next == AxolotlLexer.LBRACE)
                    ? "Axolotl has no `object`; functions and values at the top of a file are already shared by the "
                    + "whole program" : null;
            default -> foreignDeclaration(tokens, i, text, next);
        };
    }

    private static final String AND_OR_NOT = "write `&&`, `||` and `!` instead of `and`, `or` and `not`";

    /** Python's `3 in xs`; `for (x in xs)` is Axolotl's own. */
    private static String inOperator(List<Token> tokens, int i, int nextIndex) {
        int previous = SourceParser.previousCode(tokens, i);
        int beforeName = SourceParser.previousCode(tokens, previous);
        boolean loop = type(tokens, beforeName) == AxolotlLexer.LPAREN
                && type(tokens, SourceParser.previousCode(tokens, beforeName)) == AxolotlLexer.FOR;
        if (loop || !VALUE_ENDS.contains(type(tokens, previous)) || type(tokens, nextIndex) != AxolotlLexer.IDENTIFIER) {
            return null;
        }
        return "Axolotl has no `in` operator; write `" + text(tokens, nextIndex) + ".contains(" + text(tokens, previous) + ")`";
    }

    /** Declarations and parts of them from Kotlin, TypeScript and Python. */
    private static String foreignDeclaration(List<Token> tokens, int i, String text, int next) {
        int previousIndex = SourceParser.previousCode(tokens, i);
        int previous = type(tokens, previousIndex);
        return switch (text) {
            case "export" -> startsLine(tokens, i) && DECLARATION_WORDS.contains(next)
                    ? "declarations are public by default; remove `export`" : null;
            case "async" -> startsLine(tokens, i) && next == AxolotlLexer.FN ? "Axolotl has no `async`; remove it" : null;
            case "throws" -> previous == AxolotlLexer.RPAREN && next == AxolotlLexer.IDENTIFIER
                    ? "Axolotl functions have no `throws`; remove it, and turn exceptions into a `Result` with `attempt`"
                    : null;
            case "typealias" -> startsLine(tokens, i) && next == AxolotlLexer.IDENTIFIER
                    ? "Axolotl has no `typealias`; write the type itself" : null;
            case "init" -> startsLine(tokens, i) && next == AxolotlLexer.LBRACE
                    ? "a class has no `init` block; give each property its value where it is declared" : null;
            case "constructor" -> previous == AxolotlLexer.IDENTIFIER && next == AxolotlLexer.LPAREN
                    ? "write the parameters right after the class name: `class Box(val v: Int)`" : null;
            case "self" -> previous == AxolotlLexer.LPAREN && (next == AxolotlLexer.RPAREN || next == AxolotlLexer.COMMA)
                    && declaresFunction(tokens, previousIndex)
                    ? "a function of a class takes no `self`; use `this` inside the class" : null;
            case "lambda" -> (previous == AxolotlLexer.ASSIGN || previous == AxolotlLexer.LPAREN
                    || previous == AxolotlLexer.COMMA) && next == AxolotlLexer.IDENTIFIER
                    ? "lambdas are written in braces: `{ x -> x + 1 }`" : null;
            default -> null;
        };
    }

    /** Whether the `(` at open starts the parameters of `fn name(`. */
    private static boolean declaresFunction(List<Token> tokens, int open) {
        int name = SourceParser.previousCode(tokens, open);
        return type(tokens, SourceParser.previousCode(tokens, name)) == AxolotlLexer.FN;
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
        if (text(tokens, SourceParser.nextCode(tokens, SourceParser.nextCode(tokens, open))).equals("of")) {
            return "Axolotl's `for` uses `in`: `for (x in xs) { ... }`";
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
