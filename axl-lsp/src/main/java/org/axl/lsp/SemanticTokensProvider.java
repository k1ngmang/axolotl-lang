package org.axl.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.parse.AxolotlLexer;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;

/** Token kinds from the lexer, refined for names by what follows or precedes them. */
final class SemanticTokensProvider {

    static final List<String> TYPES = List.of("keyword", "function", "variable", "parameter", "type", "string",
            "number", "comment", "operator", "property", "namespace");
    static final SemanticTokensLegend LEGEND = new SemanticTokensLegend(TYPES, List.of());

    private static final Set<Integer> KEYWORDS = Set.of(AxolotlLexer.PACKAGE, AxolotlLexer.IMPORT, AxolotlLexer.FN,
            AxolotlLexer.VAL, AxolotlLexer.VAR, AxolotlLexer.PRIVATE, AxolotlLexer.RETURN, AxolotlLexer.IF,
            AxolotlLexer.ELSE, AxolotlLexer.WHILE, AxolotlLexer.FOR, AxolotlLexer.WHEN, AxolotlLexer.IS,
            AxolotlLexer.CLASS, AxolotlLexer.ENUM, AxolotlLexer.INTERFACE, AxolotlLexer.SEALED, AxolotlLexer.THROW,
            AxolotlLexer.TRY, AxolotlLexer.ATTEMPT, AxolotlLexer.TRUE, AxolotlLexer.FALSE, AxolotlLexer.NULL);
    private static final Set<Integer> OPERATORS = Set.of(AxolotlLexer.SAFE_DOT, AxolotlLexer.ELVIS, AxolotlLexer.ARROW, AxolotlLexer.PIPE,
            AxolotlLexer.BANGBANG);

    private SemanticTokensProvider() {}

    static SemanticTokens tokens(Document document) {
        List<Integer> data = new ArrayList<>();
        List<? extends Token> tokens = document.tokens;
        int lastLine = 0;
        int lastStart = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            int type = classify(tokens, i);
            if (type < 0) {
                continue;
            }
            // Multi-line block comments are sent one line at a time.
            String[] parts = token.getText().split("\r?\n", -1);
            for (int p = 0; p < parts.length; p++) {
                int line = token.getLine() - 1 + p;
                int start = p == 0 ? document.utf16Column(line, token.getCharPositionInLine()) : 0;
                if (parts[p].isEmpty()) {
                    continue;
                }
                data.add(line - lastLine);
                data.add(line == lastLine ? start - lastStart : start);
                data.add(parts[p].length());
                data.add(type);
                data.add(0);
                lastLine = line;
                lastStart = start;
            }
        }
        return new SemanticTokens(data);
    }

    private static int classify(List<? extends Token> tokens, int index) {
        Token token = tokens.get(index);
        int kind = token.getType();
        if (KEYWORDS.contains(kind)) {
            return TYPES.indexOf("keyword");
        }
        if (OPERATORS.contains(kind)) {
            return TYPES.indexOf("operator");
        }
        return switch (kind) {
            case AxolotlLexer.STRING_LITERAL, AxolotlLexer.CHAR_LITERAL -> TYPES.indexOf("string");
            case AxolotlLexer.INT_LITERAL, AxolotlLexer.FLOAT_LITERAL -> TYPES.indexOf("number");
            case AxolotlLexer.LINE_COMMENT, AxolotlLexer.BLOCK_COMMENT -> TYPES.indexOf("comment");
            case AxolotlLexer.IDENTIFIER -> TYPES.indexOf(name(tokens, index));
            default -> -1;
        };
    }

    private static String name(List<? extends Token> tokens, int index) {
        Token next = neighbour(tokens, index, 1);
        Token previous = neighbour(tokens, index, -1);
        if (isContextualKeyword(tokens, index, next, previous)) {
            return "keyword";
        }
        boolean upperCase = Character.isUpperCase(tokens.get(index).getText().charAt(0));
        String qualified = inDeclaredName(tokens, index, next);
        if (qualified != null) {
            return qualified;
        }
        if (next != null && next.getType() == AxolotlLexer.LPAREN) {
            return upperCase ? "type" : "function";
        }
        if (previous != null && (previous.getType() == AxolotlLexer.FN || previous.getType() == AxolotlLexer.COLONCOLON)) {
            return "function";
        }
        if (previous != null && previous.getType() == AxolotlLexer.ELSE && next != null && next.getType() == AxolotlLexer.ARROW) {
            return "parameter";
        }
        if (previous != null && (previous.getType() == AxolotlLexer.DOT || previous.getType() == AxolotlLexer.SAFE_DOT)) {
            return upperCase && next != null && next.getType() == AxolotlLexer.DOT ? "type" : "property";
        }
        return upperCase ? "type" : "variable";
    }

    /** `data` before `class`, and `in` in `for (x in xs)`; both are ordinary names elsewhere. */
    private static boolean isContextualKeyword(List<? extends Token> tokens, int index, Token next, Token previous) {
        String text = tokens.get(index).getText();
        if (text.equals("data")) {
            return next != null && next.getType() == AxolotlLexer.CLASS;
        }
        if (text.equals("in")) {
            int loopVariable = previousIndex(tokens, index);
            return previous != null && previous.getType() == AxolotlLexer.IDENTIFIER
                    && type(tokens, previousIndex(tokens, loopVariable)) == AxolotlLexer.LPAREN
                    && type(tokens, previousIndex(tokens, previousIndex(tokens, loopVariable))) == AxolotlLexer.FOR;
        }
        return false;
    }

    /**
     * The kind of a segment of the qualified name in a package or import declaration or a written
     * type, where the segments before the last are packages; null for other names.
     */
    private static String inDeclaredName(List<? extends Token> tokens, int index, Token next) {
        int before = previousIndex(tokens, index);
        while (type(tokens, before) == AxolotlLexer.DOT && type(tokens, previousIndex(tokens, before)) == AxolotlLexer.IDENTIFIER) {
            before = previousIndex(tokens, previousIndex(tokens, before));
        }
        int keyword = type(tokens, before);
        int beforeKeyword = type(tokens, previousIndex(tokens, before));
        boolean importMember = (keyword == AxolotlLexer.FN || keyword == AxolotlLexer.VAL) && beforeKeyword == AxolotlLexer.IMPORT;
        boolean written = keyword == AxolotlLexer.COLON || keyword == AxolotlLexer.ARROW && beforeKeyword == AxolotlLexer.RPAREN;
        if (!importMember && !written && keyword != AxolotlLexer.IMPORT && keyword != AxolotlLexer.PACKAGE) {
            return null;
        }
        boolean last = next == null || next.getType() != AxolotlLexer.DOT;
        if (!last || keyword == AxolotlLexer.PACKAGE) {
            return "namespace";
        }
        if (importMember) {
            return keyword == AxolotlLexer.FN ? "function" : "variable";
        }
        return "type";
    }

    private static Token neighbour(List<? extends Token> tokens, int index, int step) {
        for (int i = index + step; i >= 0 && i < tokens.size(); i += step) {
            if (tokens.get(i).getChannel() == Token.DEFAULT_CHANNEL) {
                return tokens.get(i);
            }
        }
        return null;
    }

    /** The index of the default-channel token before an index, or -1. */
    private static int previousIndex(List<? extends Token> tokens, int index) {
        for (int i = index - 1; i >= 0; i--) {
            if (tokens.get(i).getChannel() == Token.DEFAULT_CHANNEL) {
                return i;
            }
        }
        return -1;
    }

    private static int type(List<? extends Token> tokens, int index) {
        return index < 0 ? Token.INVALID_TYPE : tokens.get(index).getType();
    }
}
