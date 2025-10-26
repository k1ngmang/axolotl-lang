package org.axl.intellij.lexer;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.tree.IElementType;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.parse.AxolotlLexer;

/**
 * Adapts the compiler's ANTLR lexer. The lexer has no modes, so every token boundary is a valid
 * restart point and the state is always 0.
 */
public final class AxlLexer extends LexerBase {

    private CharSequence buffer;
    private int base;
    private int end;
    private AxolotlLexer lexer;
    private Token token;

    @Override
    public void start(CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.base = startOffset;
        this.end = endOffset;
        this.lexer = new AxolotlLexer(CharStreams.fromString(buffer.subSequence(startOffset, endOffset).toString()));
        this.lexer.removeErrorListeners();
        advance();
    }

    @Override
    public int getState() {
        return 0;
    }

    @Override
    public IElementType getTokenType() {
        return token == null || token.getType() == Token.EOF ? null : AxlTokenTypes.of(token.getType());
    }

    @Override
    public int getTokenStart() {
        return token.getType() == Token.EOF ? end : base + token.getStartIndex();
    }

    @Override
    public int getTokenEnd() {
        return token.getType() == Token.EOF ? end : base + token.getStopIndex() + 1;
    }

    @Override
    public void advance() {
        token = lexer.nextToken();
    }

    @Override
    public CharSequence getBufferSequence() {
        return buffer;
    }

    @Override
    public int getBufferEnd() {
        return end;
    }
}
