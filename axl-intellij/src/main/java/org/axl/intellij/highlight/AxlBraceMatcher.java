package org.axl.intellij.highlight;

import com.intellij.lang.BracePair;
import com.intellij.lang.PairedBraceMatcher;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import org.axl.intellij.lexer.AxlTokenTypes;
import org.jetbrains.annotations.NotNull;

public final class AxlBraceMatcher implements PairedBraceMatcher {

    private static final BracePair[] PAIRS = {
        new BracePair(AxlTokenTypes.LBRACE, AxlTokenTypes.RBRACE, true),
        new BracePair(AxlTokenTypes.LPAREN, AxlTokenTypes.RPAREN, false),
    };

    @Override
    public BracePair @NotNull [] getPairs() {
        return PAIRS;
    }

    @Override
    public boolean isPairedBracesAllowedBeforeType(@NotNull IElementType left, IElementType context) {
        return true;
    }

    @Override
    public int getCodeConstructStart(PsiFile file, int openingBraceOffset) {
        return openingBraceOffset;
    }
}
