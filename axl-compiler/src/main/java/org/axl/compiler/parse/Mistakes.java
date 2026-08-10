package org.axl.compiler.parse;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    // `message: String = reason`, as lineText spaces it: the name, then the type and the value when written.
    private static final Pattern PROPERTY = Pattern.compile("^(\\w+)(?:: (.+?))?(?: = (.+))?$");

    private Mistakes() {}

    /** The cause of an error the parser found at the token at, or null. */
    static Placed find(CommonTokenStream tokens, Token at) {
        if (at.getType() == AxolotlLexer.VAR && braceDepth(tokens, at) == 0) {
            boolean field = enclosingParen(tokens, at) != null;
            return new Placed(at, field ? "a data class cannot change; declare its fields with `val`"
                    : "top-level values cannot change; use `val`, or keep the state in a class");
        }
        if ((at.getType() == AxolotlLexer.VAL || at.getType() == AxolotlLexer.VAR) && inDataClass(tokens, at)) {
            return new Placed(at, dataClassProperty(tokens, at));
        }
        Token withoutBraces = withoutBraces(tokens, at);
        if (withoutBraces != null) {
            return new Placed(withoutBraces, SourceParser.needsBraces(withoutBraces));
        }
        Token previous = SourceParser.previousToken(tokens, at);
        if (previous == null) {
            return null;
        }
        // The parser gives up after the `>` of `is List<String>`, at the `>` of `is List<*>`, or at the `*`;
        // in a `when` branch it gives up at the `<`.
        Token close = previous.getType() == AxolotlLexer.GT ? previous : at.getType() == AxolotlLexer.GT ? at
                : at.getType() == AxolotlLexer.STAR && type(SourceParser.nextToken(tokens, at)) == AxolotlLexer.GT
                ? SourceParser.nextToken(tokens, at) : null;
        Token typeArguments = at.getType() == AxolotlLexer.LT && testsType(tokens, previous) ? at
                : close == null ? null : typeArgumentsOfIs(tokens, close);
        if (typeArguments != null) {
            String name = before(tokens, typeArguments).getText();
            boolean star = SourceParser.nextToken(tokens, typeArguments).getType() == AxolotlLexer.STAR;
            return new Placed(typeArguments, star ? "a type test names the class only, and `is " + name + "` already matches a `"
                    + name + "` of anything; write `is " + name + "`"
                    : "a type test cannot check type arguments, which Java does not keep at run time; write `is " + name + "`");
        }
        Placed unfinished = previous.getLine() < at.getLine() ? unfinishedLine(tokens, previous)
                : at.getType() == AxolotlLexer.RBRACE ? unfinished(tokens, previous) : null;
        if (unfinished != null) {
            return unfinished;
        }
        Placed placed = at.getType() == Token.EOF ? null : Misplaced.find(tokens, at, previous);
        if (placed == null) {
            placed = missingPart(tokens, at, previous);
        }
        if (placed == null) {
            placed = slip(tokens, at, previous);
        }
        if (placed != null) {
            return placed;
        }
        String message = switch (at.getType()) {
            case AxolotlLexer.LT -> typeParameters(tokens, previous);
            case AxolotlLexer.AT -> previous.getType() == AxolotlLexer.RETURN
                    ? "Axolotl has no labels; a `return` in a lambda ends only the lambda, so remove `@"
                    + text(SourceParser.nextToken(tokens, at)) + "`"
                    : "an annotation goes before a declaration, as in `@Override fn toString()`";
            case AxolotlLexer.LPAREN -> afterName(tokens, at, previous);
            case AxolotlLexer.DOT -> previous.getType() == AxolotlLexer.IDENTIFIER && type(before(tokens, previous)) == AxolotlLexer.FN
                    ? "Axolotl has no extension functions; take the value as the first parameter: `fn "
                    + text(SourceParser.nextToken(tokens, at)) + "(s: " + previous.getText() + ")`" : null;
            case AxolotlLexer.LBRACE -> inInterface(tokens, at) && startsFunction(tokens, at) ? INTERFACE_METHOD_BODY : null;
            case AxolotlLexer.ELSE -> previous.getType() != AxolotlLexer.RBRACE && !lineHas(tokens, previous.getLine(), AxolotlLexer.IF)
                    && type(SourceParser.nextToken(tokens, at)) != AxolotlLexer.ARROW
                    ? "`else` needs an `if` before it: `if (c) { ... } else { ... }`" : null;
            case AxolotlLexer.ELVIS -> previous.getType() == AxolotlLexer.IDENTIFIER && inParameters(tokens, at)
                    ? "Axolotl has no optional parameters; for a value that may be null, write `" + previous.getText() + ": "
                    + text(SourceParser.nextToken(tokens, at)) + "?`" : null;
            case AxolotlLexer.COMMA -> Set.of(AxolotlLexer.VAL, AxolotlLexer.VAR).contains(type(SourceParser.nextToken(tokens, at)))
                    ? "declare each value on a line of its own, or separate them with `;`" : missingComma(tokens, at, previous);
            case AxolotlLexer.RPAREN -> enclosingParen(tokens, at) == null
                    ? "no `(` is open here" + (enclosingBrace(tokens, at) != null ? "; did you mean `}`?" : "") : null;
            case AxolotlLexer.SEMI -> startsFunction(tokens, at) && !lineHas(tokens, at.getLine(), AxolotlLexer.ASSIGN)
                    ? "a function needs a body after its header: `= value` or `{ ... }`" : null;
            case AxolotlLexer.COLON -> previous.getType() == AxolotlLexer.RPAREN && startsFunction(tokens, at)
                    ? "the result type comes after `->`: `fn f(...) -> Int`"
                    : endsLine(tokens, at) ? "a block goes in braces: `{ ... }`, not after `:`" : null;
            case AxolotlLexer.ASSIGN -> assignment(tokens, at, previous);
            case AxolotlLexer.RETURN -> previous.getType() == AxolotlLexer.ASSIGN
                    ? "a body after `=` is the value itself; remove `return`" : attemptExit(tokens, at, previous);
            case AxolotlLexer.BREAK, AxolotlLexer.CONTINUE -> attemptExit(tokens, at, previous);
            case AxolotlLexer.ARROW -> inParameters(tokens, at) ? "missing `)` before `->`" : javaLambda(tokens, previous);
            case AxolotlLexer.IDENTIFIER -> identifier(tokens, at, previous);
            default -> missingComma(tokens, at, previous);
        };
        return message == null ? null : new Placed(at, message);
    }

    /** `val r = attempt f() else e -> break`: only a statement of its own can leave the loop or the function. */
    private static String attemptExit(CommonTokenStream tokens, Token at, Token previous) {
        boolean handler = previous.getType() == AxolotlLexer.ARROW || previous.getType() == AxolotlLexer.ELSE;
        return handler && lineHas(tokens, at.getLine(), AxolotlLexer.ATTEMPT)
                ? "`" + at.getText() + "` can only be the handler of an `attempt` on a line of its own" : null;
    }

    private static final String INTERFACE_METHOD_BODY = "a method of an interface has no body; it ends after `)` or its "
            + "result type, and each class that implements the interface writes the body";

    private static String identifier(CommonTokenStream tokens, Token at, Token previous) {
        Token next = SourceParser.nextToken(tokens, at);
        if (next.getType() == AxolotlLexer.LPAREN && inInterface(tokens, at)) {
            return "a method of an interface is written `fn " + at.getText() + "(name: Type) -> Type`, without a body";
        }
        if (next.getType() == AxolotlLexer.COLON && inInterface(tokens, at)) {
            return "an interface lists values: `val " + at.getText() + ": " + text(SourceParser.nextToken(tokens, next)) + "`";
        }
        // `Function.identity<String>()`: the type arguments follow the last name of the chain.
        Token name = at;
        Token after = next;
        while (after.getType() == AxolotlLexer.DOT && SourceParser.nextToken(tokens, after).getType() == AxolotlLexer.IDENTIFIER) {
            name = SourceParser.nextToken(tokens, after);
            after = SourceParser.nextToken(tokens, name);
        }
        String typeArguments = after.getType() == AxolotlLexer.LT ? typeArgumentCall(tokens, at, name, after) : null;
        return typeArguments != null ? typeArguments : missingComma(tokens, at, previous);
    }

    /** A misspelled keyword before the token where the parser gave up: `atempt f() else e`, `whne (n) {`. */
    private static Placed slip(CommonTokenStream tokens, Token at, Token previous) {
        Token word = previous;
        String keyword = previous.getLine() == at.getLine() ? SourceParser.keywordLike(previous) : null;
        if (keyword == null && at.getType() == AxolotlLexer.LBRACE && previous.getType() == AxolotlLexer.RPAREN) {
            word = before(tokens, matchingOpen(tokens, previous));
            keyword = type(word) == AxolotlLexer.IDENTIFIER ? SourceParser.controlKeyword(word.getText()) : null;
        }
        if (keyword == null || type(before(tokens, word)) == AxolotlLexer.DOT) {
            return null;
        }
        return new Placed(word, "unknown word `" + word.getText() + "`; did you mean `" + keyword + "`?", true);
    }

    /**
     * `mutableListOf<String>()`, `ArrayList<int>()` and `Function.identity<String>()`, which the parser
     * reads as comparisons; first is the start of the chain that ends in the name. A call takes type
     * arguments only when it creates an object of a generic class.
     */
    private static String typeArgumentCall(CommonTokenStream tokens, Token first, Token name, Token open) {
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
        if (!Character.isLowerCase(name.getText().charAt(0))) {
            return null;
        }
        // The type comes from the value the call is stored in, so the example gives it one.
        Token receiver = first == name ? null : before(tokens, before(tokens, name));
        String call = tokens.getText(first, name) + "()";
        return "Axolotl has no type arguments on calls; remove `<" + arguments + ">` and give the type to the value instead: `val "
                + declaredName(tokens, first) + ": " + typeExample(receiver == null ? "" : receiver.getText(), name.getText(), arguments.toString())
                + " = " + call + "`";
    }

    /** The type a call with these type arguments gives, for the JDK's generic factories. */
    private static String typeExample(String receiver, String method, String arguments) {
        return switch (method) {
            case "identity" -> "Function<" + arguments + ", " + arguments + ">";
            case "emptyList" -> "List<" + arguments + ">";
            case "emptySet" -> "Set<" + arguments + ">";
            case "emptyMap" -> "Map<" + arguments + ">";
            default -> receiver.isEmpty() || !Character.isUpperCase(receiver.charAt(0)) ? "Type" : receiver + "<" + arguments + ">";
        };
    }

    /** `f` of `val f = ` before the token, or `v`. */
    private static String declaredName(CommonTokenStream tokens, Token value) {
        Token assign = before(tokens, value);
        Token name = before(tokens, assign);
        int keyword = type(before(tokens, name));
        boolean declared = type(assign) == AxolotlLexer.ASSIGN && type(name) == AxolotlLexer.IDENTIFIER
                && (keyword == AxolotlLexer.VAL || keyword == AxolotlLexer.VAR);
        return declared ? name.getText() : "v";
    }

    /** An unclosed `if` or `attempt` on the line before, or a declaration there without a value. */
    private static Placed unfinishedLine(CommonTokenStream tokens, Token previous) {
        Placed unfinished = unfinished(tokens, previous);
        if (unfinished != null) {
            return unfinished;
        }
        Token first = SourceParser.lineStart(tokens, previous);
        if (startsFunction(tokens, previous) && !lineHas(tokens, first.getLine(), AxolotlLexer.ASSIGN)
                && !lineHas(tokens, first.getLine(), AxolotlLexer.LBRACE) && !unclosedParen(tokens, previous)
                && !inInterface(tokens, previous)) {
            // An indented line after the header is its body; a declaration at the margin is the next one.
            Token next = SourceParser.nextToken(tokens, previous);
            return next.getCharPositionInLine() > 0
                    ? new Placed(next, "missing `{` at the end of line " + previous.getLine(), true) : null;
        }
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
        Token declaration = before(tokens, previous);
        if (previous.getType() == AxolotlLexer.IDENTIFIER && declaration != null && declaration.getLine() < previous.getLine()
                && Set.of(AxolotlLexer.VAL, AxolotlLexer.VAR).contains(declaration.getType())) {
            return new Placed(declaration, "`" + declaration.getText() + "` needs a name: `" + declaration.getText()
                    + " x = ...`", true);
        }
        Token paren = enclosingParen(tokens, at);
        if (at.getType() == AxolotlLexer.LBRACE && paren != null && paren.getLine() == at.getLine()
                && Set.of(AxolotlLexer.IF, AxolotlLexer.WHILE, AxolotlLexer.FOR, AxolotlLexer.WHEN).contains(type(before(tokens, paren)))) {
            return new Placed(at, "missing `)` before `{`", true);
        }
        boolean untypedParameter = (at.getType() == AxolotlLexer.RPAREN || at.getType() == AxolotlLexer.COMMA)
                && previous.getType() == AxolotlLexer.IDENTIFIER && inParameters(tokens, at)
                && Set.of(AxolotlLexer.LPAREN, AxolotlLexer.COMMA).contains(type(before(tokens, previous)));
        if (untypedParameter) {
            return new Placed(previous, "`" + previous.getText() + "` needs a type: `" + previous.getText() + ": Int`", true);
        }
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
        if (BINARY_OPERATORS.contains(at.getType()) && (next.getType() == Token.EOF
                || BINARY_OPERATORS.contains(next.getType()) && next.getLine() == at.getLine())) {
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

    private static boolean declaresInterface(CommonTokenStream tokens, String name) {
        return tokens.getTokens().stream().anyMatch(t -> t.getType() == AxolotlLexer.INTERFACE
                && SourceParser.nextToken(tokens, t).getText().equals(name));
    }

    /** Whether a `(` of the line is still open at last, as in a header written over several lines. */
    private static boolean unclosedParen(CommonTokenStream tokens, Token last) {
        int depth = 0;
        for (Token t = SourceParser.lineStart(tokens, last); t.getTokenIndex() <= last.getTokenIndex(); t = SourceParser.nextToken(tokens, t)) {
            depth += t.getType() == AxolotlLexer.LPAREN ? 1 : t.getType() == AxolotlLexer.RPAREN ? -1 : 0;
            if (t.getType() == Token.EOF) {
                break;
            }
        }
        return depth > 0;
    }

    private static boolean inInterface(CommonTokenStream tokens, Token at) {
        Token brace = enclosingBrace(tokens, at);
        return brace != null && lineHas(tokens, brace.getLine(), AxolotlLexer.INTERFACE);
    }

    /** Whether the innermost `{` open at the token is the body of a data class, which holds methods only. */
    private static boolean inDataClass(CommonTokenStream tokens, Token at) {
        Token brace = enclosingBrace(tokens, at);
        return brace != null && lineHas(tokens, brace.getLine(), AxolotlLexer.CLASS)
                && tokens.getTokens().stream().anyMatch(t -> t.getLine() == brace.getLine() && t.getText().equals("data"));
    }

    /** `val message: String = reason` in the body of a data class, as the method it can be. */
    private static String dataClassProperty(CommonTokenStream tokens, Token at) {
        String message = "a data class body holds methods only; ";
        Token name = SourceParser.nextToken(tokens, at);
        if (name.getType() != AxolotlLexer.IDENTIFIER) {
            return message + "write `fn name() -> Type`";
        }
        Matcher written = PROPERTY.matcher(lineText(tokens, name));
        if (!written.matches()) {
            return message + "write `fn " + name.getText() + "() -> Type`";
        }
        String type = written.group(2) == null ? "" : " -> " + written.group(2);
        String value = written.group(3) == null ? "" : " = " + written.group(3);
        return message + "write `fn " + name.getText() + "()" + type + value + "`";
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
            if (t.getType() == AxolotlLexer.IF && type(before(tokens, t)) == AxolotlLexer.ARROW) {
                // After `->` of a lambda or a `when` branch, either may have been meant.
                return new Placed(t, "an `if` that gives a value needs an `else`: `if (c) a else b`; "
                        + "as a statement it needs braces: `if (c) { ... }`");
            }
            if (t.getType() == AxolotlLexer.IF && givesValue(tokens, t)) {
                return new Placed(t, "an `if` that gives a value needs an `else`: `if (c) a else b`");
            }
        }
        return null;
    }

    /** `fn <T> first(...)`, as Kotlin and Java write it. */
    private static String typeParameters(CommonTokenStream tokens, Token previous) {
        return previous.getType() == AxolotlLexer.FN
                ? "the type parameters go after the name of the function: `fn first<T>(xs: List<T>) -> T?`" : null;
    }

    /** `val (a, b) = p`, `enum Color(...)` and `class Dog(...) : Animal(name)`. */
    private static String afterName(CommonTokenStream tokens, Token at, Token previous) {
        if (previous.getType() == AxolotlLexer.VAL || previous.getType() == AxolotlLexer.VAR) {
            return "Axolotl has no destructuring; declare each value: `val a = p.a()`";
        }
        if (previous.getType() == AxolotlLexer.FN) {
            return "missing the name of the function: `fn name(...)`";
        }
        if (previous.getType() != AxolotlLexer.IDENTIFIER) {
            return null;
        }
        Token before = before(tokens, previous);
        if (type(before) == AxolotlLexer.COLON && lineHas(tokens, at.getLine(), AxolotlLexer.CLASS)
                && declaresInterface(tokens, previous.getText())) {
            return "an interface takes no `()`: `: " + previous.getText() + "`";
        }
        return null;
    }

    /** `if (x = 1)`, `fn f(x: Int = 1)`, `f(x = 1)` and `while ((line = readLine()) != null)`. */
    private static String assignment(CommonTokenStream tokens, Token at, Token previous) {
        Token paren = enclosingParen(tokens, at);
        if (paren == null) {
            Token brace = enclosingBrace(tokens, at);
            if (brace != null && lineHas(tokens, brace.getLine(), AxolotlLexer.ENUM)) {
                return "an enum constant takes its values in parentheses, for fields declared after the name: "
                        + "`enum Color(val code: Int) { RED(1), GREEN(2) }`";
            }
            return inInterface(tokens, at) && startsFunction(tokens, at) ? INTERFACE_METHOD_BODY : null;
        }
        Token beforeParen = before(tokens, paren);
        if (type(beforeParen) == AxolotlLexer.IF || type(beforeParen) == AxolotlLexer.WHILE) {
            return "compare with `==`; `=` assigns";
        }
        if (type(before(tokens, beforeParen)) == AxolotlLexer.FN) {
            return "parameters have no default values in Axolotl; declare a second function with fewer parameters";
        }
        int beforeName = type(before(tokens, previous));
        if (previous.getType() != AxolotlLexer.IDENTIFIER || beforeName != AxolotlLexer.LPAREN && beforeName != AxolotlLexer.COMMA) {
            return null;
        }
        // A `(` after a name or `>` opens a call, whose arguments may be named; any other `(` groups a value.
        boolean call = beforeName == AxolotlLexer.COMMA || type(beforeParen) == AxolotlLexer.IDENTIFIER
                || type(beforeParen) == AxolotlLexer.GT;
        return call ? "Axolotl has no named arguments; pass the values in the order of the parameters"
                : assignedValue(tokens, previous, paren);
    }

    /** `(line = readLine())` used as a value, as in Java's reading loop: the name stands in its place. */
    private static String assignedValue(CommonTokenStream tokens, Token name, Token paren) {
        Token close = matchingClose(tokens, paren);
        String statement = close == null ? name.getText() + " = ..." : tokens.getText(name, before(tokens, close));
        String message = "an assignment is a statement, not a value: write `" + statement + "` on its own line";
        Token outer = before(tokens, paren);
        Token keyword = before(tokens, outer);
        Token outerClose = type(outer) == AxolotlLexer.LPAREN ? matchingClose(tokens, outer) : null;
        if (close == null || outerClose == null || type(keyword) != AxolotlLexer.IF && type(keyword) != AxolotlLexer.WHILE) {
            return message + ", then use `" + name.getText() + "` in its place";
        }
        Token first = SourceParser.nextToken(tokens, outer);
        Token last = before(tokens, outerClose);
        String head = first == paren ? "" : tokens.getText(first, before(tokens, paren)) + " ";
        String tail = last == close ? "" : " " + tokens.getText(SourceParser.nextToken(tokens, close), last);
        return message + ", then `" + keyword.getText() + " (" + head + name.getText() + tail + ")`";
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
        boolean text = at.getType() == AxolotlLexer.STRING_LITERAL || previous.getType() == AxolotlLexer.STRING_LITERAL;
        if (!value || !afterValue || previous.getLine() != at.getLine() || enclosingParen(tokens, at) == null && !text) {
            return null;
        }
        String separator = enclosingParen(tokens, at) == null ? "`+`" : text ? "`+` or `,`" : "`,`";
        return "missing " + separator + " before `" + at.getText() + "`";
    }

    /** The `<` of `x is List<String>`, given its `>`, or null. */
    private static Token typeArgumentsOfIs(CommonTokenStream tokens, Token close) {
        int depth = 0;
        for (Token t = close; t != null && t.getLine() == close.getLine(); t = before(tokens, t)) {
            if (t.getType() == AxolotlLexer.GT) {
                depth++;
            } else if (t.getType() == AxolotlLexer.LT && --depth == 0) {
                return testsType(tokens, before(tokens, t)) ? t : null;
            }
        }
        return null;
    }

    /** Whether the name that ends at the token follows `is`. */
    private static boolean testsType(CommonTokenStream tokens, Token name) {
        while (type(before(tokens, name)) == AxolotlLexer.DOT) {
            name = before(tokens, before(tokens, name));
        }
        return type(before(tokens, name)) == AxolotlLexer.IS;
    }

    /** An `if (c)`, `while (c)` or `for (x in xs)` before the token, whose statement does not start with `{`. */
    private static Token withoutBraces(CommonTokenStream tokens, Token at) {
        for (Token t = SourceParser.previousToken(tokens, at); t != null; t = SourceParser.previousToken(tokens, t)) {
            switch (t.getType()) {
                case AxolotlLexer.LBRACE, AxolotlLexer.RBRACE, AxolotlLexer.SEMI, AxolotlLexer.ELSE -> {
                    return null;
                }
                case AxolotlLexer.RPAREN -> {
                    Token next = SourceParser.nextToken(tokens, t);
                    if (SourceParser.controlWord(tokens, t) != null && next.getType() != AxolotlLexer.LBRACE) {
                        Token word = before(tokens, matchingOpen(tokens, t));
                        return word.getType() == AxolotlLexer.IF && givesValue(tokens, word) ? null : word;
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

    private static Token matchingClose(CommonTokenStream tokens, Token open) {
        int depth = 0;
        for (int i = open.getTokenIndex(); i < tokens.size(); i++) {
            int type = tokens.get(i).getType();
            if (type == AxolotlLexer.LPAREN) {
                depth++;
            } else if (type == AxolotlLexer.RPAREN && --depth == 0) {
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
