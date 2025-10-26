package org.axl.intellij.highlight;

import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.List;
import org.axl.intellij.lexer.AxlTokenTypes;
import org.axl.intellij.parser.AxlElementTypes;
import org.axl.intellij.psi.AxlImport;
import org.axl.intellij.psi.AxlPsiElement;
import org.jetbrains.annotations.NotNull;

/** Folds blocks, lambdas, block comments and the import list. */
public final class AxlFoldingBuilder extends FoldingBuilderEx {

    @Override
    public FoldingDescriptor @NotNull [] buildFoldRegions(@NotNull PsiElement root, @NotNull Document document, boolean quick) {
        List<FoldingDescriptor> regions = new ArrayList<>();
        for (AxlPsiElement element : PsiTreeUtil.findChildrenOfType(root, AxlPsiElement.class)) {
            var type = element.getNode().getElementType();
            if ((type == AxlElementTypes.BLOCK || type == AxlElementTypes.LAMBDA) && spansLines(element, document)) {
                regions.add(new FoldingDescriptor(element.getNode(), element.getTextRange()));
            }
        }
        for (PsiElement comment : PsiTreeUtil.findChildrenOfType(root, com.intellij.psi.PsiComment.class)) {
            if (comment.getNode().getElementType() == AxlTokenTypes.BLOCK_COMMENT && spansLines(comment, document)) {
                regions.add(new FoldingDescriptor(comment.getNode(), comment.getTextRange()));
            }
        }
        List<AxlImport> imports = PsiTreeUtil.getChildrenOfTypeAsList(root, AxlImport.class);
        if (imports.size() > 1) {
            TextRange range = new TextRange(imports.getFirst().getTextRange().getStartOffset() + "import ".length(),
                    imports.getLast().getTextRange().getEndOffset());
            regions.add(new FoldingDescriptor(imports.getFirst().getNode(), range));
        }
        return regions.toArray(FoldingDescriptor.EMPTY_ARRAY);
    }

    private static boolean spansLines(PsiElement element, Document document) {
        TextRange range = element.getTextRange();
        return document.getLineNumber(range.getStartOffset()) < document.getLineNumber(range.getEndOffset());
    }

    @Override
    public String getPlaceholderText(@NotNull ASTNode node) {
        if (node.getElementType() == AxlElementTypes.IMPORT) {
            return "...";
        }
        if (node.getElementType() == AxlTokenTypes.BLOCK_COMMENT) {
            return "/*...*/";
        }
        return "{...}";
    }

    @Override
    public boolean isCollapsedByDefault(@NotNull ASTNode node) {
        return node.getElementType() == AxlElementTypes.IMPORT;
    }
}
