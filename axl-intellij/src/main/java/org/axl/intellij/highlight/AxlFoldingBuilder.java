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

/** Folds blocks, lambdas, the bodies of `when`, `interface`, `class`, `data class` and `enum`, block comments and the import list. */
public final class AxlFoldingBuilder extends FoldingBuilderEx {

    @Override
    public FoldingDescriptor @NotNull [] buildFoldRegions(@NotNull PsiElement root, @NotNull Document document, boolean quick) {
        List<FoldingDescriptor> regions = new ArrayList<>();
        for (AxlPsiElement element : PsiTreeUtil.findChildrenOfType(root, AxlPsiElement.class)) {
            TextRange body = bodyOf(element);
            if (body != null && spansLines(body, document)) {
                regions.add(new FoldingDescriptor(element.getNode(), body));
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

    /** The braces and what they enclose; the head of a `when` or a type stays visible. */
    private static TextRange bodyOf(AxlPsiElement element) {
        var type = element.getNode().getElementType();
        if (type == AxlElementTypes.BLOCK || type == AxlElementTypes.LAMBDA) {
            return element.getTextRange();
        }
        if (type == AxlElementTypes.WHEN || type == AxlElementTypes.INTERFACE || type == AxlElementTypes.CLASS
                || type == AxlElementTypes.DATA_CLASS || type == AxlElementTypes.ENUM) {
            ASTNode brace = element.getNode().findChildByType(AxlTokenTypes.LBRACE);
            return brace == null ? null : new TextRange(brace.getStartOffset(), element.getTextRange().getEndOffset());
        }
        return null;
    }

    private static boolean spansLines(PsiElement element, Document document) {
        return spansLines(element.getTextRange(), document);
    }

    private static boolean spansLines(TextRange range, Document document) {
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
