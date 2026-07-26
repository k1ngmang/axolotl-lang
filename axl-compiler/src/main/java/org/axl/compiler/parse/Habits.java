package org.axl.compiler.parse;

import java.util.ArrayList;
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

    private static final String TEXT_BLOCKS = "Axolotl has no raw strings or text blocks (`\"\"\"`); write one string with "
            + "`\\n`, or join the lines with `+`";

    private Habits() {}

    /** The message for the token at index i, or null. */
    static String of(List<Token> tokens, int i) {
        Token t = tokens.get(i);
        return switch (t.getType()) {
            case AxolotlLexer.IDENTIFIER -> word(tokens, i);
            case AxolotlLexer.FOR -> javaFor(tokens, i);
            case AxolotlLexer.IMPORT -> text(tokens, SourceParser.nextCode(tokens, i)).equals("static")
                    ? staticImport(tokens, i) : wildcardImport(tokens, i);
            case AxolotlLexer.QUESTION -> followedBy(tokens, i, AxolotlLexer.QUESTION) ? nullish(tokens, i) : ternary(tokens, i);
            case AxolotlLexer.CLASS -> kotlinClass(tokens, i);
            case AxolotlLexer.COLON -> pythonBlock(tokens, i);
            case AxolotlLexer.ASSIGN -> followedBy(tokens, i, AxolotlLexer.GT) ? fatArrow(tokens, i) : null;
            case AxolotlLexer.UNKNOWN_CHAR -> unknownChar(tokens, i);
            case AxolotlLexer.AT -> i > 0 && type(tokens, i - 1) == AxolotlLexer.RETURN
                    ? "Axolotl has no labels; a `return` in a lambda ends only the lambda, so remove `@" + text(tokens, i + 1) + "`"
                    : null;
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
                    ? "write `!!.` to state that the value is not null: `s!!.length()`"
                    : followedBy(tokens, i, AxolotlLexer.IDENTIFIER) && text(tokens, i + 1).equals("in") ? notIn(tokens, i) : null;
            case AxolotlLexer.IS -> next == AxolotlLexer.NULL
                    || text(tokens, SourceParser.nextCode(tokens, i)).equals("None") && !declaresType(tokens, "None")
                    ? "compare with `==` to test for null: `s == null`" : null;
            case AxolotlLexer.ELVIS -> next == AxolotlLexer.RETURN ? elvisJump(tokens, i) : null;
            case AxolotlLexer.STRING_LITERAL -> tokens.get(i).getText().equals("\"\"")
                    && followedBy(tokens, i, AxolotlLexer.STRING_LITERAL) ? TEXT_BLOCKS : null;
            default -> null;
        };
    }

    /** Whether the file declares or imports a type of the name, such as `data class None<T>(...)` or `app.Options.None`. */
    private static boolean declaresType(List<Token> tokens, String name) {
        for (int i = 1; i < tokens.size(); i++) {
            if (!tokens.get(i).getText().equals(name)) {
                continue;
            }
            int previous = type(tokens, SourceParser.previousCode(tokens, i));
            if (previous == AxolotlLexer.CLASS || previous == AxolotlLexer.INTERFACE || previous == AxolotlLexer.ENUM
                    || previous == AxolotlLexer.DOT) {
                return true;
            }
        }
        return false;
    }

    /** `s ?: return 0`, which Kotlin allows. */
    private static String elvisJump(List<Token> tokens, int i) {
        int previous = SourceParser.previousCode(tokens, i);
        int start = operandStart(tokens, previous);
        int fallback = SourceParser.nextCode(tokens, i);
        String problem = "`" + text(tokens, fallback) + "` cannot be the fallback of `?:`; ";
        String jump = source(tokens, fallback, lineEnd(tokens, fallback));
        if (start == previous && type(tokens, previous) == AxolotlLexer.IDENTIFIER) {
            String value = text(tokens, previous);
            return problem + "check first: `if (" + value + " == null) { " + jump + " }`, and `" + value
                    + "` is not null after it";
        }
        // A null check of a call does not make the next call return non-null, so the value needs a name.
        String name = declaredName(tokens, start);
        return problem + "keep the value in a `val` and check that: `val " + name + " = " + source(tokens, start, previous)
                + "`, then `if (" + name + " == null) { " + jump + " }`";
    }

    /** `v` of `val v = ` before the token at start, or `v` when there is none. */
    private static String declaredName(List<Token> tokens, int start) {
        int assign = SourceParser.previousCode(tokens, start);
        int name = SourceParser.previousCode(tokens, assign);
        int keyword = type(tokens, SourceParser.previousCode(tokens, name));
        boolean declared = type(tokens, assign) == AxolotlLexer.ASSIGN && type(tokens, name) == AxolotlLexer.IDENTIFIER
                && (keyword == AxolotlLexer.VAL || keyword == AxolotlLexer.VAR);
        return declared ? text(tokens, name) : "v";
    }

    /** The last code token on the line of the token at i. */
    private static int lineEnd(List<Token> tokens, int i) {
        int end = i;
        for (int j = SourceParser.nextCode(tokens, i); type(tokens, j) != Token.EOF
                && tokens.get(j).getLine() == tokens.get(i).getLine(); j = SourceParser.nextCode(tokens, j)) {
            end = j;
        }
        return end;
    }

    /** TypeScript's `a ?? b`, or `String??` in a type. */
    private static String nullish(List<Token> tokens, int i) {
        int previous = SourceParser.previousCode(tokens, i);
        int next = SourceParser.nextCode(tokens, i + 1);
        if (!VALUE_STARTS.contains(type(tokens, next))) {
            return type(tokens, previous) == AxolotlLexer.IDENTIFIER
                    ? "a type takes one `?`: `" + text(tokens, previous) + "?`" : "a type takes one `?`";
        }
        String right = type(tokens, next) == AxolotlLexer.IDENTIFIER ? source(tokens, next, operandEnd(tokens, next))
                : text(tokens, next);
        return "write `?:` for a default: `" + source(tokens, operandStart(tokens, previous), previous) + " ?: " + right + "`";
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
            // `"""` reads as the empty string `""` and an unclosed `"`.
            case "\"" -> i > 0 && type(tokens, i - 1) == AxolotlLexer.STRING_LITERAL && text(tokens, i - 1).equals("\"\"")
                    && adjacent(tokens, i - 1) ? TEXT_BLOCKS : null;
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
            case "catch", "finally" -> type(tokens, SourceParser.previousCode(tokens, i)) == AxolotlLexer.RBRACE
                    ? NO_TRY_CATCH : null;
            case "instanceof" -> "Axolotl tests types with `is`: `if (x is String) { ... }`";
            case "typeof" -> next == AxolotlLexer.IDENTIFIER ? "Axolotl tests types with `is`: `if ("
                    + text(tokens, nextIndex) + " is String) { ... }`" : null;
            case "as" -> cast(tokens, i, nextIndex);
            case "to" -> pair(tokens, i, nextIndex);
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

    /** Kotlin's `o as String` and `o as? String`. */
    private static String cast(List<Token> tokens, int i, int nextIndex) {
        int previous = SourceParser.previousCode(tokens, i);
        boolean safe = type(tokens, nextIndex) == AxolotlLexer.QUESTION && adjacent(tokens, i);
        int typeName = safe ? SourceParser.nextCode(tokens, nextIndex) : nextIndex;
        if (!VALUE_ENDS.contains(type(tokens, previous)) || type(tokens, typeName) != AxolotlLexer.IDENTIFIER) {
            return null;
        }
        String value = type(tokens, previous) == AxolotlLexer.IDENTIFIER && operandStart(tokens, previous) == previous
                ? text(tokens, previous) : "x";
        String conversion = numberConversion(text(tokens, typeName));
        if (conversion != null) {
            return conversion;
        }
        return "Axolotl has no casts; test the type with `is`: `if (" + value + " is " + text(tokens, typeName) + ") { ... }`";
    }

    /** A cast to a number type, `(Int) x` or `x as Int`, which converts: how Axolotl converts numbers, or null. */
    static String numberConversion(String type) {
        return switch (type) {
            case "Int", "Integer", "int" -> "Axolotl has no casts; a `Double` becomes an `Int` with `Math.toIntExact(Math.round(x))`, "
                    + "a `Long` with `Math.toIntExact(x)`";
            case "Long", "long" -> "Axolotl has no casts; an `Int` fits a `Long` as it is, as in `val l: Long = n`, and a `Double` "
                    + "becomes one with `Math.round(x)`";
            case "Double", "double", "Float", "float" -> "Axolotl has no casts; an `Int` becomes a `Double` in arithmetic with a "
                    + "`Double`, as in `1.0 * n`, or with `Double.valueOf(n)`";
            default -> null;
        };
    }

    /** Kotlin's `1 to 2` outside a call; in a call, the parser explains it as the arguments of `Map.of`. */
    private static String pair(List<Token> tokens, int i, int nextIndex) {
        int previous = SourceParser.previousCode(tokens, i);
        boolean value = VALUE_ENDS.contains(type(tokens, previous)) && type(tokens, nextIndex) != AxolotlLexer.LPAREN
                && VALUE_STARTS.contains(type(tokens, nextIndex)) && tokens.get(nextIndex).getLine() == tokens.get(i).getLine();
        if (!value || inParentheses(tokens, i) || Set.of(AxolotlLexer.BANG, AxolotlLexer.MINUS).contains(type(tokens, nextIndex))) {
            return null;
        }
        String arguments = type(tokens, previous) == AxolotlLexer.RPAREN ? "a, b"
                : text(tokens, previous) + ", " + text(tokens, nextIndex);
        return "Axolotl has no `to` or `Pair`; use a data class, or `Map.entry(" + arguments + ")`";
    }

    private static boolean inParentheses(List<Token> tokens, int i) {
        int depth = 0;
        for (int j = 0; j < i; j++) {
            depth += type(tokens, j) == AxolotlLexer.LPAREN ? 1 : type(tokens, j) == AxolotlLexer.RPAREN ? -1 : 0;
        }
        return depth > 0;
    }

    /** Python's `3 in xs`; `for (x in xs)` is Axolotl's own. */
    private static String inOperator(List<Token> tokens, int i, int nextIndex) {
        int previous = SourceParser.previousCode(tokens, i);
        int beforeName = SourceParser.previousCode(tokens, previous);
        boolean loop = type(tokens, beforeName) == AxolotlLexer.LPAREN
                && type(tokens, SourceParser.previousCode(tokens, beforeName)) == AxolotlLexer.FOR;
        if (loop || !VALUE_ENDS.contains(type(tokens, previous)) || type(tokens, nextIndex) != AxolotlLexer.IDENTIFIER) {
            return null;
        }
        String destructured = destructuringLoop(tokens, previous, nextIndex);
        if (destructured != null) {
            return destructured;
        }
        // Python's `3 not in xs`.
        boolean not = text(tokens, previous).equals("not")
                && VALUE_ENDS.contains(type(tokens, SourceParser.previousCode(tokens, previous)));
        int end = not ? SourceParser.previousCode(tokens, previous) : previous;
        return "Axolotl has no `in` operator; write `" + contains(tokens, operandStart(tokens, end), end, nextIndex, not) + "`";
    }

    /** Kotlin's `for ((k, v) in m.entrySet())`: close is the `)` of the pair, collection the first token after `in`. */
    private static String destructuringLoop(List<Token> tokens, int close, int collection) {
        if (type(tokens, close) != AxolotlLexer.RPAREN) {
            return null;
        }
        int open = matching(tokens, close, -1);
        int beforeOpen = SourceParser.previousCode(tokens, open);
        if (type(tokens, beforeOpen) != AxolotlLexer.LPAREN
                || type(tokens, SourceParser.previousCode(tokens, beforeOpen)) != AxolotlLexer.FOR) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (int j = SourceParser.nextCode(tokens, open); j < close; j = SourceParser.nextCode(tokens, j)) {
            if (type(tokens, j) == AxolotlLexer.IDENTIFIER) {
                names.add(text(tokens, j));
            }
        }
        String key = names.isEmpty() ? "k" : names.getFirst();
        String value = names.size() < 2 ? "v" : names.get(1);
        return "Axolotl has no destructuring; loop over the entries and read each one: `for (entry in "
                + source(tokens, collection, operandEnd(tokens, collection)) + ") { val " + key + " = entry.getKey(); val "
                + value + " = entry.getValue() ... }`";
    }

    /** Kotlin's `x !in xs`. */
    private static String notIn(List<Token> tokens, int bang) {
        int previous = SourceParser.previousCode(tokens, bang);
        int collection = SourceParser.nextCode(tokens, bang + 1);
        if (!VALUE_ENDS.contains(type(tokens, previous)) || type(tokens, collection) != AxolotlLexer.IDENTIFIER) {
            return null;
        }
        return "Axolotl has no `!in`; write `" + contains(tokens, operandStart(tokens, previous), previous, collection, true) + "`";
    }

    /** `xs.contains(x)` for the element from first to last and the collection that starts at collection. */
    private static String contains(List<Token> tokens, int first, int last, int collection, boolean not) {
        return (not ? "!" : "") + source(tokens, collection, operandEnd(tokens, collection)) + ".contains("
                + source(tokens, first, last) + ")";
    }

    /** The first token of the operand that ends at end, such as `s` of `s.length()`. */
    private static int operandStart(List<Token> tokens, int end) {
        int start = end;
        while (true) {
            if (type(tokens, start) == AxolotlLexer.RPAREN) {
                start = matching(tokens, start, -1);
            }
            int before = SourceParser.previousCode(tokens, start);
            if (type(tokens, start) == AxolotlLexer.LPAREN && type(tokens, before) == AxolotlLexer.IDENTIFIER) {
                start = before;
                before = SourceParser.previousCode(tokens, start);
            }
            if (type(tokens, before) != AxolotlLexer.DOT) {
                return start;
            }
            start = SourceParser.previousCode(tokens, before);
        }
    }

    /** The last token of the operand that starts with the name at start, such as `)` of `b.items()`. */
    private static int operandEnd(List<Token> tokens, int start) {
        int end = start;
        while (true) {
            int next = SourceParser.nextCode(tokens, end);
            if (type(tokens, next) == AxolotlLexer.LPAREN) {
                end = matching(tokens, next, 1);
            } else if (type(tokens, next) == AxolotlLexer.DOT
                    && type(tokens, SourceParser.nextCode(tokens, next)) == AxolotlLexer.IDENTIFIER) {
                end = SourceParser.nextCode(tokens, next);
            } else {
                return end;
            }
        }
    }

    /** The parenthesis that matches the one at i, searching in direction step, or i when there is none. */
    private static int matching(List<Token> tokens, int i, int step) {
        int depth = 0;
        for (int j = i; j >= 0 && j < tokens.size(); j += step) {
            depth += type(tokens, j) == AxolotlLexer.LPAREN ? step : type(tokens, j) == AxolotlLexer.RPAREN ? -step : 0;
            if (depth == 0) {
                return j;
            }
        }
        return i;
    }

    /** The code from first to last as written. */
    private static String source(List<Token> tokens, int first, int last) {
        StringBuilder text = new StringBuilder();
        for (int j = first; j <= last; j++) {
            text.append(text(tokens, j));
        }
        return text.toString();
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
            case "type" -> startsLine(tokens, i) && next == AxolotlLexer.IDENTIFIER
                    && type(tokens, SourceParser.nextCode(tokens, SourceParser.nextCode(tokens, i))) == AxolotlLexer.ASSIGN
                    ? "Axolotl has no type aliases; write the type itself" : null;
            case "implements" -> (previous == AxolotlLexer.IDENTIFIER || previous == AxolotlLexer.RPAREN)
                    && next == AxolotlLexer.IDENTIFIER && lineStartsType(tokens, i)
                    ? "a class names its interfaces after `:`: `class " + className(tokens, i) + "(...) : "
                    + source(tokens, SourceParser.nextCode(tokens, i), lineEnd(tokens, i)) + "`" : null;
            case "readonly" -> next == AxolotlLexer.IDENTIFIER ? "write `val` instead of `readonly`" : null;
            case "init" -> startsLine(tokens, i) && next == AxolotlLexer.LBRACE
                    ? "a class has no `init` block; give each property its value where it is declared" : null;
            case "constructor" -> previous == AxolotlLexer.IDENTIFIER && next == AxolotlLexer.LPAREN
                    ? "write the parameters right after the class name: `class " + text(tokens, previousIndex)
                    + source(tokens, SourceParser.nextCode(tokens, i), matching(tokens, SourceParser.nextCode(tokens, i), 1)) + "`"
                    : null;
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
        int indexToken = SourceParser.nextCode(tokens, i);
        String index = text(tokens, SourceParser.nextCode(tokens, indexToken)).equals("]")
                && (type(tokens, indexToken) == AxolotlLexer.INT_LITERAL || type(tokens, indexToken) == AxolotlLexer.IDENTIFIER)
                ? text(tokens, indexToken) : "0";
        Boolean array = afterValue && type(tokens, previous) == AxolotlLexer.IDENTIFIER ? holdsArray(tokens, i, before) : null;
        if (afterValue && (before.equals("args") || Boolean.TRUE.equals(array))) {
            return "Axolotl has no `[]` indexing, and `" + before + "` is a Java array; read it as a list: `List.of(" + before
                    + ").get(" + index + ")`";
        }
        if (afterValue && type(tokens, previous) == AxolotlLexer.IDENTIFIER && array == null) {
            return "Axolotl has no `[]` indexing; call `get`: `" + before + ".get(" + index + ")` for a list, `List.of(" + before
                    + ").get(" + index + ")` for a Java array";
        }
        if (afterValue) {
            String receiver = type(tokens, previous) == AxolotlLexer.IDENTIFIER ? before : "list";
            return "Axolotl has no `[]` indexing; call `get`: `" + receiver + ".get(" + index + ")`";
        }
        String source = comprehensionSource(tokens, i);
        return source != null ? "Axolotl has no list comprehensions; use a pipe: `" + source + " |> map { ... }`"
                : "Axolotl has no list literals; write `List.of(1, 2, 3)`";
    }

    /**
     * Whether the local named name, declared before the token at i, holds a Java array: its value
     * comes from `split`, `toCharArray` or another array-giving method. False for another value,
     * null when no declaration is found, as for a parameter.
     */
    private static Boolean holdsArray(List<Token> tokens, int i, String name) {
        for (int j = i - 1; j >= 0; j--) {
            boolean declared = type(tokens, j) == AxolotlLexer.IDENTIFIER && text(tokens, j).equals(name)
                    && type(tokens, SourceParser.nextCode(tokens, j)) == AxolotlLexer.ASSIGN
                    && (type(tokens, SourceParser.previousCode(tokens, j)) == AxolotlLexer.VAL
                    || type(tokens, SourceParser.previousCode(tokens, j)) == AxolotlLexer.VAR);
            if (!declared) {
                continue;
            }
            int line = tokens.get(j).getLine();
            for (int k = SourceParser.nextCode(tokens, j); type(tokens, k) != Token.EOF && tokens.get(k).getLine() == line;
                    k = SourceParser.nextCode(tokens, k)) {
                if (type(tokens, k) == AxolotlLexer.IDENTIFIER && ARRAY_METHODS.contains(text(tokens, k))
                        && type(tokens, SourceParser.previousCode(tokens, k)) == AxolotlLexer.DOT
                        && type(tokens, SourceParser.nextCode(tokens, k)) == AxolotlLexer.LPAREN) {
                    return true;
                }
            }
            return false;
        }
        return null;
    }

    private static final Set<String> ARRAY_METHODS = Set.of("split", "toCharArray", "toArray", "getBytes",
            "getDeclaredConstructors", "getConstructors", "getDeclaredMethods", "getMethods", "getDeclaredFields", "getFields",
            "getRecordComponents", "getEnumConstants", "getStackTrace", "listFiles");

    /** `xs` of Python's `[x * 2 for x in xs]`, or null for a list literal. */
    private static String comprehensionSource(List<Token> tokens, int open) {
        boolean loop = false;
        for (int j = SourceParser.nextCode(tokens, open); type(tokens, j) != Token.EOF && !text(tokens, j).equals("]");
                j = SourceParser.nextCode(tokens, j)) {
            loop |= type(tokens, j) == AxolotlLexer.FOR;
            if (loop && text(tokens, j).equals("in") && type(tokens, SourceParser.nextCode(tokens, j)) == AxolotlLexer.IDENTIFIER) {
                return text(tokens, SourceParser.nextCode(tokens, j));
            }
        }
        return null;
    }

    private static String typeName(String javaName) {
        return switch (javaName) {
            case "int" -> "Int";
            case "char" -> "Character";
            case "long", "double", "boolean" -> Character.toUpperCase(javaName.charAt(0)) + javaName.substring(1);
            default -> javaName;
        };
    }

    /** Kotlin's `enum class Color` and `sealed class Shape`. */
    private static String kotlinClass(List<Token> tokens, int i) {
        int previous = type(tokens, SourceParser.previousCode(tokens, i));
        String name = text(tokens, SourceParser.nextCode(tokens, i));
        if (previous == AxolotlLexer.ENUM) {
            return "write `enum " + name + " { ... }` without `class`";
        }
        return previous == AxolotlLexer.SEALED ? "a sealed type is a `sealed interface " + name
                + "`; its data classes implement it: `: " + name + "`" : null;
    }

    /** Python's `class C:` with its body indented below; a type on the next line goes on with the header. */
    private static String pythonBlock(List<Token> tokens, int i) {
        int next = SourceParser.nextCode(tokens, i);
        boolean endsLine = type(tokens, next) == Token.EOF || tokens.get(next).getLine() > tokens.get(i).getLine();
        boolean typeBelow = type(tokens, next) == AxolotlLexer.IDENTIFIER && Character.isUpperCase(text(tokens, next).charAt(0));
        return endsLine && !typeBelow && lineStartsType(tokens, i) ? "a block goes in braces: `{ ... }`, not after `:`" : null;
    }

    /** Java's `import static java.lang.Math.max` and `import static java.lang.Math.*`. */
    private static String staticImport(List<Token> tokens, int i) {
        int first = SourceParser.nextCode(tokens, SourceParser.nextCode(tokens, i));
        String path = source(tokens, first, lineEnd(tokens, i));
        if (path.endsWith(".*")) {
            return "Axolotl has no `import static`; import each function with `import fn "
                    + path.substring(0, path.length() - 1) + "name`, and each constant with `import val`";
        }
        return "Axolotl has no `import static`; import a function with `import fn " + path
                + "`, and a constant with `import val`";
    }

    /** Whether the line of the token declares a type. */
    private static boolean lineStartsType(List<Token> tokens, int i) {
        int first = lineStart(tokens, i);
        if (type(tokens, first) == AxolotlLexer.PRIVATE) {
            first = SourceParser.nextCode(tokens, first);
        }
        return Set.of(AxolotlLexer.CLASS, AxolotlLexer.INTERFACE, AxolotlLexer.ENUM, AxolotlLexer.SEALED)
                .contains(type(tokens, first)) || text(tokens, first).equals("data");
    }

    /** The name after `class` on the line of the token, or `C`. */
    private static String className(List<Token> tokens, int i) {
        for (int j = lineStart(tokens, i); j < i; j = SourceParser.nextCode(tokens, j)) {
            if (type(tokens, j) == AxolotlLexer.CLASS) {
                return text(tokens, SourceParser.nextCode(tokens, j));
            }
        }
        return "C";
    }

    private static int lineStart(List<Token> tokens, int i) {
        int first = i;
        for (int j = SourceParser.previousCode(tokens, i); j >= 0 && tokens.get(j).getLine() == tokens.get(i).getLine();
                j = SourceParser.previousCode(tokens, j)) {
            first = j;
        }
        return first;
    }

    /** A statement of its own: `break` on its line, or in `if (c) { break }`. */
    private static boolean alone(List<Token> tokens, int i) {
        int next = SourceParser.nextCode(tokens, i);
        int previous = type(tokens, SourceParser.previousCode(tokens, i));
        // In `case 1: f(); break;` the `switch` is the habit to report.
        boolean afterStatement = previous == AxolotlLexer.SEMI && !Set.of("case", "default").contains(text(tokens, lineStart(tokens, i)));
        boolean starts = startsLine(tokens, i) || previous == AxolotlLexer.LBRACE || afterStatement;
        return starts && (next >= tokens.size() || tokens.get(next).getLine() > tokens.get(i).getLine()
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
