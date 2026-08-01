package org.axl.intellij.refactor;

import com.intellij.lang.cacheBuilder.DefaultWordsScanner;
import com.intellij.lang.cacheBuilder.WordsScanner;
import com.intellij.lang.findUsages.FindUsagesProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.TokenSet;
import org.axl.intellij.lexer.AxlLexer;
import org.axl.intellij.lexer.AxlTokenTypes;
import org.axl.intellij.psi.AxlFunction;
import org.axl.intellij.psi.AxlNamedElement;
import org.axl.intellij.psi.AxlTypeDeclaration;
import org.axl.intellij.psi.AxlTypeParameter;
import org.axl.intellij.psi.AxlVariable;
import org.jetbrains.annotations.NotNull;

public final class AxlFindUsagesProvider implements FindUsagesProvider {

    @Override
    public WordsScanner getWordsScanner() {
        return new DefaultWordsScanner(new AxlLexer(), TokenSet.create(AxlTokenTypes.IDENTIFIER), AxlTokenTypes.COMMENTS,
                AxlTokenTypes.STRINGS);
    }

    @Override
    public boolean canFindUsagesFor(@NotNull PsiElement element) {
        return element instanceof AxlNamedElement;
    }

    @Override
    public String getHelpId(@NotNull PsiElement element) {
        return null;
    }

    @Override
    public @NotNull String getType(@NotNull PsiElement element) {
        if (element instanceof AxlFunction) {
            return "function";
        }
        if (element instanceof AxlTypeDeclaration declaration) {
            return declaration.keyword();
        }
        if (element instanceof AxlTypeParameter) {
            return "type parameter";
        }
        return element instanceof AxlVariable variable && !variable.isTopLevel() ? "variable" : "value";
    }

    @Override
    public @NotNull String getDescriptiveName(@NotNull PsiElement element) {
        return element instanceof AxlNamedElement named && named.getName() != null ? named.getName() : "";
    }

    @Override
    public @NotNull String getNodeText(@NotNull PsiElement element, boolean useFullName) {
        return getDescriptiveName(element);
    }
}
