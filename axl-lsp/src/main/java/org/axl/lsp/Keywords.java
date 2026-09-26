package org.axl.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.parse.AxolotlLexer;

/** The keywords that can stand where the cursor is: at the top level, in a class, a block, a `when` or an expression. */
final class Keywords {

    private static final List<String> TOP_LEVEL = List.of("package", "import", "fn", "val", "private", "data", "class",
            "enum", "interface", "sealed");
    private static final List<String> IN_CLASS = List.of("val", "var", "fn", "private", "constructor");
    private static final List<String> IN_DATA_CLASS = List.of("fn", "private");
    private static final List<String> IN_INTERFACE = List.of("val");
    private static final List<String> STATEMENT = List.of("val", "var", "return", "if", "while", "for", "use", "break",
            "continue", "when", "throw", "try", "attempt", "true", "false", "null");
    private static final List<String> ARM = List.of("is", "else", "true", "false", "null");
    private static final List<String> EXPRESSION = List.of("if", "when", "throw", "try", "attempt", "true", "false", "null");

    /** Tokens after which an expression starts. */
    private static final Set<Integer> BEFORE_EXPRESSION = Set.of(AxolotlLexer.ASSIGN, AxolotlLexer.LPAREN, AxolotlLexer.COMMA,
            AxolotlLexer.ARROW, AxolotlLexer.RETURN, AxolotlLexer.THROW, AxolotlLexer.PLUS, AxolotlLexer.MINUS,
            AxolotlLexer.STAR, AxolotlLexer.SLASH, AxolotlLexer.PERCENT, AxolotlLexer.ANDAND, AxolotlLexer.OROR,
            AxolotlLexer.EQ, AxolotlLexer.NEQ, AxolotlLexer.LT, AxolotlLexer.LE, AxolotlLexer.GT, AxolotlLexer.GE,
            AxolotlLexer.ELVIS, AxolotlLexer.BANG, AxolotlLexer.BANGBANG, AxolotlLexer.PLUS_ASSIGN, AxolotlLexer.MINUS_ASSIGN,
            AxolotlLexer.STAR_ASSIGN, AxolotlLexer.SLASH_ASSIGN, AxolotlLexer.PERCENT_ASSIGN, AxolotlLexer.ELSE, AxolotlLexer.TRY);

    /** Tokens of the type arguments written before the cursor: names, dots, commas, `?` and `*`. */
    private static final Set<Integer> TYPE_ARGUMENT_TOKENS = Set.of(AxolotlLexer.IDENTIFIER, AxolotlLexer.DOT, AxolotlLexer.COMMA,
            AxolotlLexer.QUESTION, AxolotlLexer.STAR);

    /** Keywords in a header that tell what kind of braces follow. */
    private static final Set<Integer> HEADS = Set.of(AxolotlLexer.CLASS, AxolotlLexer.INTERFACE, AxolotlLexer.ENUM,
            AxolotlLexer.WHEN, AxolotlLexer.FN, AxolotlLexer.IF, AxolotlLexer.ELSE, AxolotlLexer.WHILE, AxolotlLexer.FOR,
            AxolotlLexer.ATTEMPT, AxolotlLexer.TRY);

    private Keywords() {}

    /** The keywords for a 0-based position; a name being typed there does not count. */
    static List<String> at(Document document, int line, int character) {
        List<Token> before = tokensBefore(document, line, document.codePointColumn(line, character));
        Token previous = before.isEmpty() ? null : before.getLast();
        if (previous != null && BEFORE_EXPRESSION.contains(previous.getType()) || afterLoopIn(before)) {
            return EXPRESSION;
        }
        int brace = innermostOpenBrace(before);
        if (brace < 0) {
            return TOP_LEVEL;
        }
        return switch (head(before, brace)) {
            case AxolotlLexer.CLASS -> dataClass(before, brace) ? IN_DATA_CLASS : IN_CLASS;
            case AxolotlLexer.INTERFACE -> IN_INTERFACE;
            case AxolotlLexer.ENUM -> List.of();
            case AxolotlLexer.WHEN -> ARM;
            default -> previous.getType() == AxolotlLexer.RBRACE ? withElse() : STATEMENT;
        };
    }

    /**
     * Whether a type is wanted at a 0-based position: after `is`, after `:`, after the `->` of a
     * function header and among type arguments.
     */
    static boolean typeExpected(Document document, int line, int character) {
        List<Token> before = tokensBefore(document, line, document.codePointColumn(line, character));
        int previous = before.isEmpty() ? Token.INVALID_TYPE : before.getLast().getType();
        return previous == AxolotlLexer.IS || previous == AxolotlLexer.COLON || previous == AxolotlLexer.ARROW && afterParameters(before)
                || (previous == AxolotlLexer.LT || previous == AxolotlLexer.COMMA) && inTypeArguments(before);
    }

    /** Whether the tokens end inside the `<` of a type, such as `HashMap<String, `, and not after a less-than. */
    private static boolean inTypeArguments(List<Token> before) {
        int depth = 0;
        for (int i = before.size() - 1; i > 0; i--) {
            Token token = before.get(i);
            if (token.getType() == AxolotlLexer.GT) {
                depth++;
            } else if (token.getType() == AxolotlLexer.LT && depth > 0) {
                depth--;
            } else if (token.getType() == AxolotlLexer.LT) {
                return isTypeName(before.get(i - 1));
            } else if (!TYPE_ARGUMENT_TOKENS.contains(token.getType())) {
                return false;
            }
        }
        return false;
    }

    /** A capitalized name that is not a constant: `HashMap`, not `MAX`. */
    private static boolean isTypeName(Token token) {
        String name = token.getText();
        return token.getType() == AxolotlLexer.IDENTIFIER && Character.isUpperCase(name.charAt(0))
                && !name.equals(name.toUpperCase(Locale.ROOT));
    }

    /** Whether the token before the last one closes the parameters of a function: `fn f(a: Int) ->`. */
    private static boolean afterParameters(List<Token> before) {
        int depth = 0;
        for (int i = before.size() - 2; i >= 0; i--) {
            int type = before.get(i).getType();
            if (type == AxolotlLexer.RPAREN) {
                depth++;
            } else if (type == AxolotlLexer.LPAREN) {
                depth--;
            }
            if (depth <= 0) {
                return depth == 0 && i >= 2 && type == AxolotlLexer.LPAREN && before.get(i - 2).getType() == AxolotlLexer.FN;
            }
        }
        return false;
    }

    /** After `in` in `for (x in `, where the iterable follows. */
    private static boolean afterLoopIn(List<Token> before) {
        int n = before.size();
        return n >= 4 && before.get(n - 1).getText().equals("in") && before.get(n - 1).getType() == AxolotlLexer.IDENTIFIER
                && before.get(n - 2).getType() == AxolotlLexer.IDENTIFIER && before.get(n - 3).getType() == AxolotlLexer.LPAREN
                && before.get(n - 4).getType() == AxolotlLexer.FOR;
    }

    private static List<String> withElse() {
        List<String> result = new ArrayList<>(STATEMENT);
        result.add("else");
        return result;
    }

    /** The default-channel tokens before a column, without a name that ends there. */
    private static List<Token> tokensBefore(Document document, int line, int column) {
        List<Token> result = new ArrayList<>();
        for (Token token : document.tokens) {
            int tokenLine = token.getLine() - 1;
            if (tokenLine > line || tokenLine == line && token.getCharPositionInLine() >= column) {
                break;
            }
            if (token.getChannel() == Token.DEFAULT_CHANNEL) {
                result.add(token);
            }
        }
        if (!result.isEmpty() && result.getLast().getType() == AxolotlLexer.IDENTIFIER
                && result.getLast().getLine() - 1 == line
                && result.getLast().getCharPositionInLine() + result.getLast().getText().length() == column) {
            result.removeLast();
        }
        return result;
    }

    /** The index of the last `{` that is not closed, or -1. */
    private static int innermostOpenBrace(List<Token> tokens) {
        List<Integer> open = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).getType() == AxolotlLexer.LBRACE) {
                open.add(i);
            } else if (tokens.get(i).getType() == AxolotlLexer.RBRACE && !open.isEmpty()) {
                open.removeLast();
            }
        }
        return open.isEmpty() ? -1 : open.getLast();
    }

    /** Whether the brace at an index opens the body of a data class: `data` comes before its `class`. */
    private static boolean dataClass(List<Token> tokens, int brace) {
        for (int i = brace - 1; i > 0; i--) {
            if (tokens.get(i).getType() == AxolotlLexer.CLASS && tokens.get(i - 1).getType() != AxolotlLexer.DOT) {
                return tokens.get(i - 1).getText().equals("data");
            }
        }
        return false;
    }

    /** The last keyword outside parentheses between the previous brace and this one, such as `when` in `val k = when (x) {`. */
    private static int head(List<Token> tokens, int brace) {
        int depth = 0;
        for (int i = brace - 1; i >= 0; i--) {
            int type = tokens.get(i).getType();
            if (type == AxolotlLexer.RPAREN) {
                depth++;
            } else if (type == AxolotlLexer.LPAREN) {
                depth--;
            } else if (type == AxolotlLexer.LBRACE || type == AxolotlLexer.RBRACE) {
                return Token.INVALID_TYPE;
            } else if (depth == 0 && HEADS.contains(type)) {
                return type;
            }
        }
        return Token.INVALID_TYPE;
    }
}
