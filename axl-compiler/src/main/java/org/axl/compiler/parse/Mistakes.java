package org.axl.compiler.parse;

import java.util.Map;
import java.util.Set;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;

/**
 * Syntax errors with a known cause, often a habit from Java or Kotlin. Each is reported at the token
 * that shows the cause, which may come before the token where the parser gave up.
 */
final class Mistakes {

    /** last: the parser's errors after this one only follow from it. */
    record Placed(Token at, String message, boolean last) {
        Placed(Token at, String message) {
            this(at, message, false);
        }
    }

    private static final Set<Integer> BINARY_OPERATORS = Set.of(AxolotlLexer.PLUS, AxolotlLexer.MINUS, AxolotlLexer.STAR,
            AxolotlLexer.SLASH, AxolotlLexer.PERCENT, AxolotlLexer.EQ, AxolotlLexer.NEQ, AxolotlLexer.LE, AxolotlLexer.GE,
            AxolotlLexer.LT, AxolotlLexer.GT, AxolotlLexer.ANDAND, AxolotlLexer.OROR);

    private static final Set<String> PRIMITIVES = Set.of("int", "long", "double", "boolean", "char");

    // Kotlin's functions that create mutable collections, and the class Axolotl creates instead.
    private static final Map<String, String> COLLECTION_FUNCTIONS = Map.of("mutableListOf", "ArrayList",
            "arrayListOf", "ArrayList", "mutableMapOf", "HashMap", "hashMapOf", "HashMap", "mutableSetOf", "HashSet",
            "hashSetOf", "HashSet");

    private Mistakes() {}

    /** The cause of an error the parser found at the token at, or null. */
    static Placed find(CommonTokenStream tokens, Token at) {
        if (at.getType() == AxolotlLexer.VAR && braceDepth(tokens, at) == 0) {
            boolean field = enclosingParen(tokens, at) != null;
            return new Placed(at, field ? "a data class cannot change; declare its fields with `val`"
                    : "top-level values cannot change; use `val`, or keep the state in a class");
        }
        Token ifWithoutBraces = ifWithoutBraces(tokens, at);
        if (ifWithoutBraces != null) {
            return new Placed(ifWithoutBraces, "an `if` statement needs braces: `if (c) { ... }`");
        }
        Token previous = SourceParser.previousToken(tokens, at);
        if (previous == null) {
            return null;
        }
        Token typeArguments = previous.getType() == AxolotlLexer.GT ? typeArgumentsOfIs(tokens, previous) : null;
        if (typeArguments != null) {
            return new Placed(typeArguments, "a type test cannot check type arguments, which Java does not keep at run "
                    + "time; write `is " + before(tokens, typeArguments).getText() + "`");
        }
        Placed unfinished = previous.getLine() < at.getLine() ? unfinishedLine(tokens, previous) : null;
        if (unfinished != null) {
            return unfinished;
        }
        Placed placed = at.getType() == Token.EOF ? null : Misplaced.find(tokens, at, previous);
        if (placed == null) {
            placed = missingPart(tokens, at, previous);
        }
        if (placed != null) {
            return placed;
        }
        String message = switch (at.getType()) {
            case AxolotlLexer.LT -> typeParameters(tokens, previous);
            case AxolotlLexer.LPAREN -> afterName(tokens, at, previous);
            case AxolotlLexer.DOT -> previous.getType() == AxolotlLexer.IDENTIFIER && type(before(tokens, previous)) == AxolotlLexer.FN
                    ? "Axolotl has no extension functions; take the value as the first parameter: `fn "
                    + text(SourceParser.nextToken(tokens, at)) + "(s: " + previous.getText() + ")`" : null;
            case AxolotlLexer.FN -> inInterface(tokens, at) ? INTERFACE_FUNCTION : null;
            case AxolotlLexer.LBRACE -> text(SourceParser.lineStart(tokens, at)).equals("data")
                    ? "a data class has no body; write its functions at the top level, taking it as a parameter" : null;
            case AxolotlLexer.COLON -> previous.getType() == AxolotlLexer.RPAREN && startsFunction(tokens, at)
                    ? "the result type comes after `->`: `fn f(...) -> Int`"
                    : endsLine(tokens, at) ? "a block goes in braces: `{ ... }`, not after `:`" : null;
            case AxolotlLexer.ASSIGN -> assignment(tokens, at, previous);
            case AxolotlLexer.RETURN -> previous.getType() == AxolotlLexer.ASSIGN
                    ? "a body after `=` is the value itself; remove `return`" : null;
            case AxolotlLexer.ARROW -> inParameters(tokens, at) ? "missing `)` before `->`" : javaLambda(tokens, previous);
            case AxolotlLexer.IDENTIFIER -> identifier(tokens, at, previous);
            default -> missingComma(tokens, at, previous);
        };
        return message == null ? null : new Placed(at, message);
    }

    private static final String INTERFACE_FUNCTION = "an interface lists values (`val name: Type`), not functions; "
            + "write the function at the top level";

    private static String identifier(CommonTokenStream tokens, Token at, Token previous) {
        Token next = SourceParser.nextToken(tokens, at);
        if (next.getType() == AxolotlLexer.LPAREN && inInterface(tokens, at)) {
            return INTERFACE_FUNCTION;
        }
        String typeArguments = next.getType() == AxolotlLexer.LT ? typeArgumentCall(tokens, at, next) : null;
        return typeArguments != null ? typeArguments : missingComma(tokens, at, previous);
    }

    /**
     * `mutableListOf<String>()` and `ArrayList<int>()`, which the parser reads as comparisons. A call
     * takes type arguments only when it creates an object of a generic class.
     */
    private static String typeArgumentCall(CommonTokenStream tokens, Token name, Token open) {
        StringBuilder arguments = new StringBuilder();
        String primitive = null;
        Token t = SourceParser.nextToken(tokens, open);
        for (; t.getType() != AxolotlLexer.GT && t.getType() != Token.EOF && t.getLine() == open.getLine(); t = SourceParser.nextToken(tokens, t)) {
            arguments.append(t.getType() == AxolotlLexer.COMMA ? ", " : t.getText());
            primitive = primitive == null && PRIMITIVES.contains(t.getText()) ? t.getText() : primitive;
        }
        if (t.getType() != AxolotlLexer.GT || SourceParser.nextToken(tokens, t).getType() != AxolotlLexer.LPAREN) {
            return null;
        }
        if (primitive != null) {
            String boxed = primitive.equals("int") ? "Int" : primitive.equals("char") ? "Character"
                    : Character.toUpperCase(primitive.charAt(0)) + primitive.substring(1);
            return "a type argument cannot be `" + primitive + "`; write `" + boxed + "`, as in `" + name.getText() + "<"
                    + boxed + ">()`";
        }
        String collection = COLLECTION_FUNCTIONS.get(name.getText());
        if (collection != null) {
            return "unknown function `" + name.getText() + "`; write `" + collection + "<" + arguments + ">()`";
        }
        return Character.isLowerCase(name.getText().charAt(0))
                ? "Axolotl functions take no type arguments; remove `<" + arguments + ">`" : null;
    }

    /** An unclosed `if` or `attempt` on the line before, or a declaration there without a value. */
    private static Placed unfinishedLine(CommonTokenStream tokens, Token previous) {
        Placed unfinished = unfinished(tokens, previous);
        if (unfinished != null) {
            return unfinished;
        }
        Token first = SourceParser.lineStart(tokens, previous);
        Token declared = first.getType() == AxolotlLexer.PRIVATE ? SourceParser.nextToken(tokens, first) : first;
        boolean declaration = declared.getType() == AxolotlLexer.VAR || declared.getType() == AxolotlLexer.VAL;
        if (!declaration || lineHas(tokens, first.getLine(), AxolotlLexer.ASSIGN) || !lineHas(tokens, first.getLine(), AxolotlLexer.COLON)) {
            return null;
        }
        Token name = SourceParser.nextToken(tokens, declared);
        String written = lineText(tokens, declared);
        Token brace = enclosingBrace(tokens, first);
        if (brace != null && lineHas(tokens, brace.getLine(), AxolotlLexer.CLASS)) {
            return new Placed(name, "a property needs a value: `" + written + " = ...`", true);
        }
        return new Placed(name, "`" + declared.getText() + " " + name.getText() + "` needs a first value: `" + written
                + " = ...`; to choose it, compute it with `if`: `val " + name.getText() + " = if (c) a else b`", true);
    }

    /** Code with a part left out: a type, a condition, a value after an operator or `,`, a name after `.`. */
    private static Placed missingPart(CommonTokenStream tokens, Token at, Token previous) {
        if (previous.getType() == AxolotlLexer.COLON && at.getType() == AxolotlLexer.LPAREN) {
            return new Placed(at, "Axolotl has no function types; use an interface such as "
                    + "`java.util.function.Function<Int, Int>`", true);
        }
        if (previous.getType() == AxolotlLexer.COLON && Set.of(AxolotlLexer.ASSIGN, AxolotlLexer.RPAREN, AxolotlLexer.COMMA,
                AxolotlLexer.LBRACE, AxolotlLexer.ARROW).contains(at.getType())) {
            return new Placed(at, "missing a type after `:`", true);
        }
        if (previous.getType() == AxolotlLexer.ARROW && at.getType() == AxolotlLexer.LBRACE && startsFunction(tokens, at)) {
            return new Placed(previous, "missing the result type after `->`; leave out `->` for a function that returns "
                    + "nothing", true);
        }
        if (at.getType() == AxolotlLexer.RPAREN && previous.getType() == AxolotlLexer.LPAREN
                && Set.of(AxolotlLexer.IF, AxolotlLexer.WHILE).contains(type(before(tokens, previous)))) {
            String keyword = before(tokens, previous).getText();
            return new Placed(at, "`" + keyword + "` needs a condition: `" + keyword + " (c) { ... }`", true);
        }
        if (at.getType() == AxolotlLexer.RPAREN && previous.getType() == AxolotlLexer.COMMA) {
            return new Placed(previous, "`,` needs a value after it; remove it or add the value", true);
        }
        Token next = SourceParser.nextToken(tokens, at);
        if (BINARY_OPERATORS.contains(at.getType()) && BINARY_OPERATORS.contains(next.getType()) && next.getLine() == at.getLine()) {
            return new Placed(at, "`" + at.getText() + "` needs a value after it", true);
        }
        if (BINARY_OPERATORS.contains(at.getType()) && BINARY_OPERATORS.contains(previous.getType())
                && previous.getLine() == at.getLine()) {
            return new Placed(previous, "`" + previous.getText() + "` needs a value after it", true);
        }
        if (at.getType() == AxolotlLexer.DOT && endsLine(tokens, at)) {
            return new Placed(at, "`.` needs a name after it", true);
        }
        return null;
    }

    private static boolean inInterface(CommonTokenStream tokens, Token at) {
        Token brace = enclosingBrace(tokens, at);
        return brace != null && lineHas(tokens, brace.getLine(), AxolotlLexer.INTERFACE);
    }

    /** Whether the token is in the parameter list of a function declaration. */
    private static boolean inParameters(CommonTokenStream tokens, Token at) {
        Token paren = enclosingParen(tokens, at);
        return paren != null && type(before(tokens, before(tokens, paren))) == AxolotlLexer.FN;
    }

    private static boolean endsLine(CommonTokenStream tokens, Token at) {
        Token next = SourceParser.nextToken(tokens, at);
        return next.getType() == Token.EOF || next.getLine() > at.getLine();
    }

    /** The rest of the line from the token, spaced as in `var s: List<String>?`. */
    private static String lineText(CommonTokenStream tokens, Token first) {
        StringBuilder text = new StringBuilder(first.getText());
        Token previous = first;
        for (Token t = SourceParser.nextToken(tokens, first); t.getLine() == first.getLine() && t.getType() != Token.EOF;
                t = SourceParser.nextToken(tokens, t)) {
            boolean joined = Set.of(AxolotlLexer.COLON, AxolotlLexer.QUESTION, AxolotlLexer.COMMA, AxolotlLexer.LT,
                    AxolotlLexer.GT).contains(t.getType()) || previous.getType() == AxolotlLexer.LT;
            text.append(joined ? "" : " ").append(t.getText());
            previous = t;
        }
        return text.toString();
    }

    /** `attempt` or an `if` that gives a value, left without `else` at the end of a declaration. */
    static Placed unfinished(CommonTokenStream tokens, Token last) {
        for (Token t = last; t != null && t.getCharPositionInLine() > 0; t = SourceParser.previousToken(tokens, t)) {
            if (t.getType() == AxolotlLexer.ELSE) {
                return null;
            }
            if (t.getType() == AxolotlLexer.ATTEMPT) {
                return new Placed(t, "`attempt` needs `else`: `attempt f() else e -> ...`");
            }
            if (t.getType() == AxolotlLexer.IF && givesValue(tokens, t)) {
                return new Placed(t, "an `if` that gives a value needs an `else`: `if (c) a else b`");
            }
        }
        return null;
    }

    private static String typeParameters(CommonTokenStream tokens, Token previous) {
        if (previous.getType() == AxolotlLexer.FN) {
            return "Axolotl functions have no type parameters; write the types the function works with";
        }
        boolean className = previous.getType() == AxolotlLexer.IDENTIFIER && type(before(tokens, previous)) == AxolotlLexer.CLASS;
        return className ? "Axolotl classes have no type parameters; write the types the class holds" : null;
    }

    /** `val (a, b) = p`, `enum Color(...)` and `class Dog(...) : Animal(name)`. */
    private static String afterName(CommonTokenStream tokens, Token at, Token previous) {
        if (previous.getType() == AxolotlLexer.VAL || previous.getType() == AxolotlLexer.VAR) {
            return "Axolotl has no destructuring; declare each value: `val a = p.a()`";
        }
        if (previous.getType() != AxolotlLexer.IDENTIFIER) {
            return null;
        }
        Token before = before(tokens, previous);
        if (type(before) == AxolotlLexer.ENUM) {
            return "an enum lists its constants only: `enum " + previous.getText() + " { RED, GREEN }`";
        }
        if (type(before) == AxolotlLexer.COLON && lineHas(tokens, at.getLine(), AxolotlLexer.CLASS)) {
            return "a class cannot extend another class; it can only implement interfaces";
        }
        return null;
    }

    /** `if (x = 1)`, `fn f(x: Int = 1)` and `f(x = 1)`. */
    private static String assignment(CommonTokenStream tokens, Token at, Token previous) {
        Token paren = enclosingParen(tokens, at);
        if (paren == null) {
            return null;
        }
        Token beforeParen = before(tokens, paren);
        if (type(beforeParen) == AxolotlLexer.IF || type(beforeParen) == AxolotlLexer.WHILE) {
            return "compare with `==`; `=` assigns";
        }
        if (type(before(tokens, beforeParen)) == AxolotlLexer.FN) {
            return "parameters have no default values in Axolotl; declare a second function with fewer parameters";
        }
        int beforeName = type(before(tokens, previous));
        if (previous.getType() == AxolotlLexer.IDENTIFIER && (beforeName == AxolotlLexer.LPAREN || beforeName == AxolotlLexer.COMMA)) {
            return "Axolotl has no named arguments; pass the values in the order of the parameters";
        }
        return null;
    }

    /** Java's `x -> x + 1` and `(a, b) -> a + b` as an argument or a value. */
    private static String javaLambda(CommonTokenStream tokens, Token previous) {
        Token start = previous.getType() == AxolotlLexer.RPAREN ? matchingOpen(tokens, previous)
                : previous.getType() == AxolotlLexer.IDENTIFIER ? previous : null;
        if (start == null) {
            return null;
        }
        int before = type(before(tokens, start));
        if (before != AxolotlLexer.LPAREN && before != AxolotlLexer.COMMA && before != AxolotlLexer.ASSIGN) {
            return null;
        }
        String params = start == previous ? previous.getText() : "a, b";
        return "lambdas are written in braces: `{ " + params + " -> ... }`";
    }

    /** `fn add(a: Int b: Int)` and `add(1 2)`, and Kotlin's `"a" to 1`. */
    private static String missingComma(CommonTokenStream tokens, Token at, Token previous) {
        if (at.getText().equals("to") && enclosingParen(tokens, at) != null) {
            return "Axolotl has no `to`; a map takes keys and values in turn: `Map.of(\"a\", 1)`";
        }
        boolean value = at.getType() == AxolotlLexer.IDENTIFIER || literal(at);
        boolean afterValue = previous.getType() == AxolotlLexer.IDENTIFIER || literal(previous);
        if (!value || !afterValue || previous.getLine() != at.getLine() || enclosingParen(tokens, at) == null) {
            return null;
        }
        boolean text = at.getType() == AxolotlLexer.STRING_LITERAL || previous.getType() == AxolotlLexer.STRING_LITERAL;
        return "missing " + (text ? "`+` or `,`" : "`,`") + " before `" + at.getText() + "`";
    }

    /** The `<` of `x is List<String>`, given its `>`, or null. */
    private static Token typeArgumentsOfIs(CommonTokenStream tokens, Token close) {
        int depth = 0;
        for (Token t = close; t != null && t.getLine() == close.getLine(); t = before(tokens, t)) {
            if (t.getType() == AxolotlLexer.GT) {
                depth++;
            } else if (t.getType() == AxolotlLexer.LT && --depth == 0) {
                Token name = before(tokens, t);
                while (type(before(tokens, name)) == AxolotlLexer.DOT) {
                    name = before(tokens, before(tokens, name));
                }
                return type(before(tokens, name)) == AxolotlLexer.IS ? t : null;
            }
        }
        return null;
    }

    /** An `if (c)` before the token, whose statement does not start with `{`. */
    private static Token ifWithoutBraces(CommonTokenStream tokens, Token at) {
        for (Token t = SourceParser.previousToken(tokens, at); t != null; t = SourceParser.previousToken(tokens, t)) {
            switch (t.getType()) {
                case AxolotlLexer.LBRACE, AxolotlLexer.RBRACE, AxolotlLexer.SEMI, AxolotlLexer.ELSE -> {
                    return null;
                }
                case AxolotlLexer.RPAREN -> {
                    Token next = SourceParser.nextToken(tokens, t);
                    if (SourceParser.closesIf(tokens, t) && next.getType() != AxolotlLexer.LBRACE) {
                        Token ifToken = before(tokens, matchingOpen(tokens, t));
                        return givesValue(tokens, ifToken) ? null : ifToken;
                    }
                }
                default -> { }
            }
        }
        return null;
    }

    /** An `if` after `=`, `return` or an opening parenthesis is an expression. */
    private static boolean givesValue(CommonTokenStream tokens, Token ifToken) {
        int before = type(before(tokens, ifToken));
        return before == AxolotlLexer.ASSIGN || before == AxolotlLexer.RETURN || before == AxolotlLexer.LPAREN
                || before == AxolotlLexer.COMMA || before == AxolotlLexer.ARROW || before == AxolotlLexer.ELSE;
    }

    private static boolean startsFunction(CommonTokenStream tokens, Token at) {
        Token first = SourceParser.lineStart(tokens, at);
        return first.getType() == AxolotlLexer.FN
                || first.getType() == AxolotlLexer.PRIVATE && type(SourceParser.nextToken(tokens, first)) == AxolotlLexer.FN;
    }

    static boolean lineHas(CommonTokenStream tokens, int line, int keyword) {
        return tokens.getTokens().stream().anyMatch(t -> t.getLine() == line && t.getType() == keyword);
    }

    static Token enclosingBrace(CommonTokenStream tokens, Token at) {
        return enclosing(tokens, at, AxolotlLexer.LBRACE, AxolotlLexer.RBRACE);
    }

    static Token enclosingParen(CommonTokenStream tokens, Token at) {
        return enclosing(tokens, at, AxolotlLexer.LPAREN, AxolotlLexer.RPAREN);
    }

    private static Token enclosing(CommonTokenStream tokens, Token at, int open, int close) {
        int depth = 0;
        for (int i = at.getTokenIndex() - 1; i >= 0; i--) {
            int type = tokens.get(i).getType();
            if (type == close) {
                depth++;
            } else if (type == open && depth-- == 0) {
                return tokens.get(i);
            }
        }
        return null;
    }

    private static Token matchingOpen(CommonTokenStream tokens, Token close) {
        int depth = 0;
        for (int i = close.getTokenIndex(); i >= 0; i--) {
            int type = tokens.get(i).getType();
            if (type == AxolotlLexer.RPAREN) {
                depth++;
            } else if (type == AxolotlLexer.LPAREN && --depth == 0) {
                return tokens.get(i);
            }
        }
        return null;
    }

    private static int braceDepth(CommonTokenStream tokens, Token at) {
        int depth = 0;
        for (int i = 0; i < at.getTokenIndex(); i++) {
            int type = tokens.get(i).getType();
            depth += type == AxolotlLexer.LBRACE ? 1 : type == AxolotlLexer.RBRACE ? -1 : 0;
        }
        return depth;
    }

    private static boolean literal(Token t) {
        return switch (t.getType()) {
            case AxolotlLexer.INT_LITERAL, AxolotlLexer.FLOAT_LITERAL, AxolotlLexer.STRING_LITERAL,
                 AxolotlLexer.CHAR_LITERAL -> true;
            default -> false;
        };
    }

    static Token before(CommonTokenStream tokens, Token t) {
        return t == null ? null : SourceParser.previousToken(tokens, t);
    }

    static int type(Token t) {
        return t == null ? Token.INVALID_TYPE : t.getType();
    }

    static String text(Token t) {
        return t == null ? "" : t.getText();
    }
}
