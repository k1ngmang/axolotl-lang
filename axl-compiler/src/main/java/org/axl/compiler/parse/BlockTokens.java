package org.axl.compiler.parse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenSource;

/**
 * Shows the parser the `{` of an `if` statement, and of its `else`, as BLOCK_START. Without it the
 * parser also tries those braces as a lambda in an `if` that gives a value, once for each enclosing
 * `if`. Other code reading the tokens still sees `{`.
 */
final class BlockTokens extends CommonTokenStream {

    private final Map<Integer, Token> marked = new HashMap<>();

    BlockTokens(TokenSource source) {
        super(source);
    }

    /** Called once the tokens are read. */
    void markIfBlocks() {
        List<Token> code = getTokens().stream().filter(t -> t.getChannel() == Token.DEFAULT_CHANNEL).toList();
        for (int i = 0; i < code.size(); i++) {
            if (code.get(i).getType() == AxolotlLexer.IF && type(code, i + 1) == AxolotlLexer.LPAREN) {
                int brace = closing(code, i + 1, AxolotlLexer.LPAREN, AxolotlLexer.RPAREN) + 1;
                if (type(code, brace) == AxolotlLexer.LBRACE) {
                    mark(code.get(brace));
                    int afterBlock = closing(code, brace, AxolotlLexer.LBRACE, AxolotlLexer.RBRACE) + 1;
                    if (type(code, afterBlock) == AxolotlLexer.ELSE && type(code, afterBlock + 1) == AxolotlLexer.LBRACE) {
                        mark(code.get(afterBlock + 1));
                    }
                }
            }
        }
    }

    @Override
    public Token LT(int k) {
        Token t = super.LT(k);
        Token block = t == null ? null : marked.get(t.getTokenIndex());
        return block != null ? block : t;
    }

    private void mark(Token brace) {
        CommonToken block = new CommonToken(brace);
        block.setType(AxolotlParser.BLOCK_START);
        marked.put(brace.getTokenIndex(), block);
    }

    private static int type(List<Token> code, int i) {
        return i < code.size() ? code.get(i).getType() : Token.EOF;
    }

    /** The index of the token that closes the one at open, or the last index when it is not closed. */
    private static int closing(List<Token> code, int open, int opening, int closing) {
        int depth = 0;
        for (int i = open; i < code.size(); i++) {
            int type = code.get(i).getType();
            depth += type == opening ? 1 : type == closing ? -1 : 0;
            if (depth == 0) {
                return i;
            }
        }
        return code.size() - 1;
    }
}
