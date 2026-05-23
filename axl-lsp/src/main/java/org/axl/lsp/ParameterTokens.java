package org.axl.lsp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.parse.AxolotlLexer;

/**
 * The tokens of a file that are parameters of functions and lambdas, where they are declared and
 * where their names are used in the function or lambda. Found from the tokens, so that a file
 * that does not parse is colored too.
 */
final class ParameterTokens {

    /** Keywords that start the next declaration after a function with an expression body. */
    private static final Set<Integer> DECLARATIONS = Set.of(AxolotlLexer.FN, AxolotlLexer.VAL, AxolotlLexer.VAR,
            AxolotlLexer.PRIVATE, AxolotlLexer.CLASS, AxolotlLexer.ENUM, AxolotlLexer.INTERFACE, AxolotlLexer.SEALED);

    private final List<? extends Token> tokens;
    // The indexes of the default-channel tokens.
    private final List<Integer> code = new ArrayList<>();
    private final Set<Integer> result = new HashSet<>();

    private ParameterTokens(List<? extends Token> tokens) {
        this.tokens = tokens;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).getChannel() == Token.DEFAULT_CHANNEL) {
                code.add(i);
            }
        }
    }

    /** The indexes of the parameter tokens in a token list. */
    static Set<Integer> of(List<? extends Token> tokens) {
        ParameterTokens parameters = new ParameterTokens(tokens);
        for (int k = 0; k < parameters.code.size(); k++) {
            if (parameters.type(k) == AxolotlLexer.FN && parameters.type(k + 1) == AxolotlLexer.IDENTIFIER
                    && parameters.type(k + 2) == AxolotlLexer.LPAREN) {
                parameters.function(k + 2);
            } else if (parameters.type(k) == AxolotlLexer.LBRACE) {
                parameters.lambda(k);
            }
        }
        return parameters.result;
    }

    /** The parameters of a function whose `(` is at a position, used up to the end of its body. */
    private void function(int open) {
        List<String> names = new ArrayList<>();
        int depth = 0;
        int k = open;
        for (; k < code.size(); k++) {
            int type = type(k);
            depth += type == AxolotlLexer.LPAREN ? 1 : type == AxolotlLexer.RPAREN ? -1 : 0;
            if (depth == 0) {
                break;
            }
            if (depth == 1 && type == AxolotlLexer.IDENTIFIER && type(k + 1) == AxolotlLexer.COLON) {
                names.add(text(k));
            }
        }
        mark(names, open, bodyEnd(k + 1));
    }

    /** The parameters before the `->` of a lambda whose `{` is at a position, used up to its `}`. */
    private void lambda(int open) {
        List<String> names = new ArrayList<>();
        int k = open + 1;
        while (type(k) == AxolotlLexer.IDENTIFIER) {
            names.add(text(k));
            k++;
            if (type(k) == AxolotlLexer.COLON) {
                k = afterType(k + 1);
            }
            if (type(k) != AxolotlLexer.COMMA) {
                break;
            }
            k++;
        }
        if (type(k) == AxolotlLexer.ARROW && !names.isEmpty()) {
            mark(names, open, closing(open));
        }
    }

    private int afterType(int k) {
        int depth = 0;
        for (; k < code.size(); k++) {
            int type = type(k);
            if (type == AxolotlLexer.LT) {
                depth++;
            } else if (type == AxolotlLexer.GT && depth > 0) {
                depth--;
            } else if (type != AxolotlLexer.IDENTIFIER && type != AxolotlLexer.DOT && type != AxolotlLexer.QUESTION
                    && !(type == AxolotlLexer.COMMA && depth > 0)) {
                return k;
            }
        }
        return k;
    }

    /**
     * Where the body of a function ends that follows a position: its `}`, or for `= expression`
     * the token before the next declaration or before the `}` of the class around it.
     */
    private int bodyEnd(int k) {
        while (k < code.size() && type(k) != AxolotlLexer.LBRACE && type(k) != AxolotlLexer.ASSIGN) {
            k++;
        }
        if (type(k) == AxolotlLexer.LBRACE) {
            return closing(k);
        }
        int depth = 0;
        for (k++; k < code.size(); k++) {
            int type = type(k);
            if (depth == 0 && (DECLARATIONS.contains(type) || type == AxolotlLexer.RBRACE)) {
                return k - 1;
            }
            depth += type == AxolotlLexer.LBRACE || type == AxolotlLexer.LPAREN ? 1
                    : type == AxolotlLexer.RBRACE || type == AxolotlLexer.RPAREN ? -1 : 0;
        }
        return code.size() - 1;
    }

    /** The position of the `}` that closes the `{` at a position, or the last one. */
    private int closing(int open) {
        int depth = 0;
        for (int k = open; k < code.size(); k++) {
            depth += type(k) == AxolotlLexer.LBRACE ? 1 : type(k) == AxolotlLexer.RBRACE ? -1 : 0;
            if (depth == 0) {
                return k;
            }
        }
        return code.size() - 1;
    }

    /** The names between two positions that are not members, as in `p.name`. */
    private void mark(List<String> names, int from, int to) {
        for (int k = from; k <= to && k < code.size(); k++) {
            boolean member = type(k - 1) == AxolotlLexer.DOT || type(k - 1) == AxolotlLexer.SAFE_DOT;
            if (type(k) == AxolotlLexer.IDENTIFIER && !member && names.contains(text(k))) {
                result.add(code.get(k));
            }
        }
    }

    private int type(int k) {
        return k >= 0 && k < code.size() ? tokens.get(code.get(k)).getType() : Token.INVALID_TYPE;
    }

    private String text(int k) {
        return tokens.get(code.get(k)).getText();
    }
}
