package org.axl.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.parse.AxolotlLexer;

/** The keywords that can stand where the cursor is: at the top level, in a class, a block, a `when` or an expression. */
final class Keywords {

    private static final List<String> TOP_LEVEL = List.of("package", "import", "fn", "val", "private", "data", "class",
            "enum", "interface", "sealed");
    private static final List<String> IN_CLASS = List.of("val", "var", "fn", "private");
    private static final List<String> IN_INTERFACE = List.of("val");
    private static final List<String> STATEMENT = List.of("val", "var", "return", "if", "while", "for", "when", "throw",
            "try", "attempt", "true", "false", "null");
    private static final List<String> ARM = List.of("is", "else", "true", "false", "null");
    private static final List<String> EXPRESSION = List.of("if", "when", "throw", "try", "attempt", "true", "false", "null");

    /** Tokens after which an expression starts. */
    private static final Set<Integer> BEFORE_EXPRESSION = Set.of(AxolotlLexer.ASSIGN, AxolotlLexer.LPAREN, AxolotlLexer.COMMA,
            AxolotlLexer.ARROW, AxolotlLexer.RETURN, AxolotlLexer.THROW, AxolotlLexer.PLUS, AxolotlLexer.MINUS,
            AxolotlLexer.STAR, AxolotlLexer.SLASH, AxolotlLexer.PERCENT, AxolotlLexer.ANDAND, AxolotlLexer.OROR,
            AxolotlLexer.EQ, AxolotlLexer.NEQ, AxolotlLexer.LT, AxolotlLexer.LE, AxolotlLexer.GT, AxolotlLexer.GE,
            AxolotlLexer.ELVIS, AxolotlLexer.BANG, AxolotlLexer.PLUS_ASSIGN, AxolotlLexer.MINUS_ASSIGN);

    /** Keywords in a header that tell what kind of braces follow. */
    private static final Set<Integer> HEADS = Set.of(AxolotlLexer.CLASS, AxolotlLexer.INTERFACE, AxolotlLexer.ENUM,
            AxolotlLexer.WHEN, AxolotlLexer.FN, AxolotlLexer.IF, AxolotlLexer.ELSE, AxolotlLexer.WHILE, AxolotlLexer.FOR,
            AxolotlLexer.ATTEMPT, AxolotlLexer.TRY);

    private Keywords() {}

    /** The keywords for a 0-based position; a name being typed there does not count. */
    static List<String> at(Document document, int line, int character) {
        List<Token> before = tokensBefore(document, line, document.codePointColumn(line, character));
        Token previous = before.isEmpty() ? null : before.getLast();
        if (previous != null && BEFORE_EXPRESSION.contains(previous.getType())) {
            return EXPRESSION;
        }
        int brace = innermostOpenBrace(before);
        if (brace < 0) {
            return TOP_LEVEL;
        }
        return switch (head(before, brace)) {
            case AxolotlLexer.CLASS -> IN_CLASS;
            case AxolotlLexer.INTERFACE -> IN_INTERFACE;
            case AxolotlLexer.ENUM -> List.of();
            case AxolotlLexer.WHEN -> ARM;
            default -> previous.getType() == AxolotlLexer.RBRACE ? withElse() : STATEMENT;
        };
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
