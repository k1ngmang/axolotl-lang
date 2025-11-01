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
            "number", "comment", "operator", "property");
    static final SemanticTokensLegend LEGEND = new SemanticTokensLegend(TYPES, List.of());

    private static final Set<Integer> KEYWORDS = Set.of(AxolotlLexer.PACKAGE, AxolotlLexer.IMPORT, AxolotlLexer.FN,
            AxolotlLexer.VAL, AxolotlLexer.VAR, AxolotlLexer.PRIVATE, AxolotlLexer.RETURN, AxolotlLexer.IF,
            AxolotlLexer.ELSE, AxolotlLexer.WHILE, AxolotlLexer.TRY, AxolotlLexer.ATTEMPT, AxolotlLexer.TRUE,
            AxolotlLexer.FALSE, AxolotlLexer.NULL);
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
            String[] parts = token.getText().split("\n", -1);
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
        if (next != null && next.getType() == AxolotlLexer.LPAREN) {
            return Character.isUpperCase(tokens.get(index).getText().charAt(0)) ? "type" : "function";
        }
        if (previous != null && (previous.getType() == AxolotlLexer.FN)) {
            return "function";
        }
        if (previous != null && (previous.getType() == AxolotlLexer.DOT || previous.getType() == AxolotlLexer.SAFE_DOT)) {
            return "property";
        }
        if (previous != null && (previous.getType() == AxolotlLexer.COLON || previous.getType() == AxolotlLexer.ARROW
                && next != null && next.getType() != AxolotlLexer.ASSIGN)
                || Character.isUpperCase(tokens.get(index).getText().charAt(0))) {
            return "type";
        }
        return "variable";
    }

    private static Token neighbour(List<? extends Token> tokens, int index, int step) {
        for (int i = index + step; i >= 0 && i < tokens.size(); i += step) {
            if (tokens.get(i).getChannel() == Token.DEFAULT_CHANNEL) {
                return tokens.get(i);
            }
        }
        return null;
    }
}
