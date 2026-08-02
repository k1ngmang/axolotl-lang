package org.axl.lsp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.parse.AxolotlLexer;

/**
 * The tokens of a file that are parameters of functions, lambdas and the `else e ->` handlers of
 * `attempt` and `use`, or type parameters of functions and types, where they are declared and where
 * their names are used in the declaration. Found from the tokens, so that a file that does not
 * parse is colored too.
 */
final class ParameterTokens {

    /** Keywords that start the next declaration after a function with an expression body. */
    private static final Set<Integer> DECLARATIONS = Set.of(AxolotlLexer.FN, AxolotlLexer.VAL, AxolotlLexer.VAR,
            AxolotlLexer.PRIVATE, AxolotlLexer.CLASS, AxolotlLexer.ENUM, AxolotlLexer.INTERFACE, AxolotlLexer.SEALED);

    /** Tokens after which an expression goes on at the next line: an operator or a dot that wants a right side. */
    private static final Set<Integer> CONTINUING = Set.of(AxolotlLexer.ARROW, AxolotlLexer.PLUS, AxolotlLexer.MINUS,
            AxolotlLexer.STAR, AxolotlLexer.SLASH, AxolotlLexer.PERCENT, AxolotlLexer.ANDAND, AxolotlLexer.OROR,
            AxolotlLexer.EQ, AxolotlLexer.NEQ, AxolotlLexer.LT, AxolotlLexer.LE, AxolotlLexer.GT, AxolotlLexer.GE,
            AxolotlLexer.ELVIS, AxolotlLexer.PIPE, AxolotlLexer.DOT, AxolotlLexer.SAFE_DOT, AxolotlLexer.ASSIGN,
            AxolotlLexer.ELSE, AxolotlLexer.IS);

    /** Tokens that start a line of an expression that goes on: `.`, `?.`, `?:` and `|>`. */
    private static final Set<Integer> CONTINUATIONS = Set.of(AxolotlLexer.DOT, AxolotlLexer.SAFE_DOT, AxolotlLexer.ELVIS,
            AxolotlLexer.PIPE);

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
            if (parameters.type(k) == AxolotlLexer.FN && parameters.type(k + 1) == AxolotlLexer.IDENTIFIER) {
                int open = parameters.afterTypeParameters(k + 2);
                if (parameters.type(open) == AxolotlLexer.LPAREN) {
                    parameters.function(open);
                }
            } else if (parameters.type(k) == AxolotlLexer.LBRACE) {
                parameters.lambda(k);
            } else if (parameters.type(k) == AxolotlLexer.ELSE && parameters.type(k + 1) == AxolotlLexer.IDENTIFIER
                    && parameters.type(k + 2) == AxolotlLexer.ARROW) {
                parameters.handler(k + 1);
            }
        }
        return parameters.result;
    }

    /** The error name of `else e -> handler`, used up to the end of the handler, an expression. */
    private void handler(int name) {
        mark(List.of(text(name)), name, expressionEnd(name + 2));
    }

    /**
     * Where the expression that starts at a position ends: before a `,` or `;` or a closing bracket
     * outside its own brackets, or at the end of a line that no operator continues.
     */
    private int expressionEnd(int k) {
        int depth = 0;
        for (; k < code.size(); k++) {
            int type = type(k);
            boolean opens = type == AxolotlLexer.LPAREN || type == AxolotlLexer.LBRACE;
            boolean closes = type == AxolotlLexer.RPAREN || type == AxolotlLexer.RBRACE;
            if (depth == 0 && (closes || type == AxolotlLexer.COMMA || type == AxolotlLexer.SEMI)) {
                return k - 1;
            }
            if (depth == 0 && k > 0 && line(k) > line(k - 1) && !CONTINUING.contains(type(k - 1)) && !CONTINUATIONS.contains(type)) {
                return k - 1;
            }
            depth += opens ? 1 : closes ? -1 : 0;
        }
        return code.size() - 1;
    }

    private int line(int k) {
        return tokens.get(code.get(k)).getLine();
    }

    /**
     * The indexes of the tokens that name type parameters: after the `<` that follows the name of a
     * function or a type, and used in the rest of the declaration.
     */
    static Set<Integer> typeParameters(List<? extends Token> tokens) {
        ParameterTokens parameters = new ParameterTokens(tokens);
        for (int k = 1; k < parameters.code.size(); k++) {
            int before = parameters.type(k - 1);
            boolean declared = before == AxolotlLexer.FN || before == AxolotlLexer.CLASS || before == AxolotlLexer.INTERFACE;
            if (declared && parameters.type(k) == AxolotlLexer.IDENTIFIER && parameters.type(k + 1) == AxolotlLexer.LT) {
                parameters.typeParameters(k + 1, before == AxolotlLexer.FN);
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

    /** The type parameters listed from the `<` at a position, used up to the end of the function or type that takes them. */
    private void typeParameters(int open, boolean function) {
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
        if (type(k) != AxolotlLexer.GT || names.isEmpty()) {
            return;
        }
        int after = k + 1;
        boolean parameters = type(after) == AxolotlLexer.LPAREN;
        int end = !function ? typeEnd(after) : bodyEnd(parameters ? closing(after, AxolotlLexer.LPAREN, AxolotlLexer.RPAREN) + 1 : after);
        mark(names, open, end);
    }

    /** The position after the `<...>` at a position, or the position itself when there is none. */
    private int afterTypeParameters(int k) {
        return type(k) == AxolotlLexer.LT ? closing(k, AxolotlLexer.LT, AxolotlLexer.GT) + 1 : k;
    }

    /**
     * Where the type ends whose header follows a position: at the `}` of its body, or without one,
     * before the next declaration.
     */
    private int typeEnd(int k) {
        int depth = 0;
        for (; k < code.size(); k++) {
            int type = type(k);
            if (depth == 0 && (DECLARATIONS.contains(type) || type == AxolotlLexer.AT || type == AxolotlLexer.RBRACE)) {
                return k - 1;
            }
            if (type == AxolotlLexer.LBRACE) {
                return closing(k);
            }
            depth += type == AxolotlLexer.LPAREN ? 1 : type == AxolotlLexer.RPAREN ? -1 : 0;
        }
        return code.size() - 1;
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
                    && !((type == AxolotlLexer.COMMA || type == AxolotlLexer.STAR) && depth > 0)) {
                return k;
            }
        }
        return k;
    }

    /**
     * Where the body of a function ends that follows a position: its `}`, or for `= expression`
     * the token before the next declaration or before the `}` of the class around it. A method of
     * an interface has none: it ends before the next member or the `}` of the interface.
     */
    private int bodyEnd(int k) {
        while (k < code.size() && type(k) != AxolotlLexer.LBRACE && type(k) != AxolotlLexer.ASSIGN) {
            if (DECLARATIONS.contains(type(k)) || type(k) == AxolotlLexer.AT || type(k) == AxolotlLexer.RBRACE
                    || type(k) == AxolotlLexer.SEMI) {
                return k - 1;
            }
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
        return closing(open, AxolotlLexer.LBRACE, AxolotlLexer.RBRACE);
    }

    /** The position of the bracket that closes the one at a position, or the last token. */
    private int closing(int open, int opening, int closing) {
        int depth = 0;
        for (int k = open; k < code.size(); k++) {
            depth += type(k) == opening ? 1 : type(k) == closing ? -1 : 0;
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
