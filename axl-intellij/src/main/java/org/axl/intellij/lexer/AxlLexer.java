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
    private String text;
    private int base;
    private int end;
    private AxolotlLexer lexer;
    private Token token;
    private int tokenStart;
    private int tokenEnd;
    // ANTLR indexes code points, IntelliJ UTF-16 units; the last converted index of each.
    private int codePoint;
    private int utf16;

    @Override
    public void start(CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.base = startOffset;
        this.end = endOffset;
        this.text = buffer.subSequence(startOffset, endOffset).toString();
        this.lexer = new AxolotlLexer(CharStreams.fromString(text));
        this.lexer.removeErrorListeners();
        this.codePoint = 0;
        this.utf16 = 0;
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
        return tokenStart;
    }

    @Override
    public int getTokenEnd() {
        return tokenEnd;
    }

    @Override
    public void advance() {
        token = lexer.nextToken();
        if (token.getType() == Token.EOF) {
            tokenStart = end;
            tokenEnd = end;
            return;
        }
        tokenStart = base + utf16Offset(token.getStartIndex());
        tokenEnd = base + utf16Offset(token.getStopIndex() + 1);
    }

    private int utf16Offset(int codePointIndex) {
        utf16 = text.offsetByCodePoints(utf16, codePointIndex - codePoint);
        codePoint = codePointIndex;
        return utf16;
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
