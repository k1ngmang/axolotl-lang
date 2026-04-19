package org.axl.compiler.parse;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;

/**
 * Syntax errors with a known cause, often a habit from Java or Kotlin. Each is reported at the token
 * that shows the cause, which may come before the token where the parser gave up.
 */
final class Mistakes {

    record Placed(Token at, String message) {}

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
        String message = switch (at.getType()) {
            case AxolotlLexer.LT -> typeParameters(tokens, previous);
            case AxolotlLexer.LPAREN -> afterName(tokens, at, previous);
            case AxolotlLexer.DOT -> previous.getType() == AxolotlLexer.IDENTIFIER && type(before(tokens, previous)) == AxolotlLexer.FN
                    ? "Axolotl has no extension functions; take the value as the first parameter: `fn "
                    + text(SourceParser.nextToken(tokens, at)) + "(s: " + previous.getText() + ")`" : null;
            case AxolotlLexer.FN -> enclosingBrace(tokens, at) != null
                    && lineHas(tokens, enclosingBrace(tokens, at).getLine(), AxolotlLexer.INTERFACE)
                    ? "an interface lists values (`val name: Type`), not functions; write the function at the top level" : null;
            case AxolotlLexer.LBRACE -> text(SourceParser.lineStart(tokens, at)).equals("data")
                    ? "a data class has no body; write its functions at the top level, taking it as a parameter" : null;
            case AxolotlLexer.COLON -> previous.getType() == AxolotlLexer.RPAREN && startsFunction(tokens, at)
                    ? "the result type comes after `->`: `fn f(...) -> Int`" : null;
            case AxolotlLexer.ASSIGN -> assignment(tokens, at, previous);
            case AxolotlLexer.RETURN -> previous.getType() == AxolotlLexer.ASSIGN
                    ? "a body after `=` is the value itself; remove `return`" : null;
            case AxolotlLexer.ARROW -> javaLambda(tokens, previous);
            default -> missingComma(tokens, at, previous);
        };
        return message == null ? null : new Placed(at, message);
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
        return "missing `,` before `" + at.getText() + "`";
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

    private static boolean lineHas(CommonTokenStream tokens, int line, int keyword) {
        return tokens.getTokens().stream().anyMatch(t -> t.getLine() == line && t.getType() == keyword);
    }

    private static Token enclosingBrace(CommonTokenStream tokens, Token at) {
        return enclosing(tokens, at, AxolotlLexer.LBRACE, AxolotlLexer.RBRACE);
    }

    private static Token enclosingParen(CommonTokenStream tokens, Token at) {
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

    private static Token before(CommonTokenStream tokens, Token t) {
        return t == null ? null : SourceParser.previousToken(tokens, t);
    }

    private static int type(Token t) {
        return t == null ? Token.INVALID_TYPE : t.getType();
    }

    private static String text(Token t) {
        return t == null ? "" : t.getText();
    }
}
