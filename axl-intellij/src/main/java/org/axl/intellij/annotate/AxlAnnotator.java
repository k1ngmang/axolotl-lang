package org.axl.intellij.annotate;

import com.intellij.codeInsight.intention.CommonIntentionAction;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.ErrorCode;
import org.axl.compiler.Translator;
import org.axl.intellij.highlight.AxlSyntaxHighlighter;
import org.axl.intellij.parser.AxlElementTypes;
import org.axl.intellij.psi.AxlFile;
import org.axl.intellij.psi.AxlFunction;
import org.axl.intellij.psi.AxlNamedElement;
import org.jetbrains.annotations.NotNull;

/**
 * Runs the translator's checks (shadowing, `try` placement, `$` in names, ...) on the file.
 * Syntax errors come from the PSI parser, so the translator's own syntax errors are skipped.
 */
public final class AxlAnnotator implements Annotator {

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (element instanceof AxlFunction function && function.getNameIdentifier() != null) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(function.getNameIdentifier())
                    .textAttributes(AxlSyntaxHighlighter.FUNCTION_DECLARATION).create();
        }
        if (!(element instanceof AxlFile file)) {
            return;
        }
        Document document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (document == null) {
            return;
        }
        Translator.Result result = Translator.translate(file.getName(), file.getName(), file.getText());
        for (AxlDiagnostic d : result.diagnostics()) {
            if (d.code() == ErrorCode.SYNTAX || d.line() < 1 || d.line() > document.getLineCount()) {
                continue;
            }
            int offset = Math.min(document.getLineStartOffset(d.line() - 1) + d.column() - 1, document.getTextLength());
            PsiElement leaf = file.findElementAt(offset);
            // Errors about a declaration point at its keyword; the name is the better place to mark.
            if (leaf != null && leaf.getParent() instanceof AxlNamedElement named && named.getNameIdentifier() != null
                    && leaf != named.getNameIdentifier()) {
                leaf = named.getNameIdentifier();
            }
            TextRange range = leaf != null ? leaf.getTextRange() : new TextRange(offset, offset);
            var annotation = holder.newAnnotation(d.isError() ? HighlightSeverity.ERROR : HighlightSeverity.WARNING,
                    d.message()).range(range);
            CommonIntentionAction fix = fixFor(d.code(), leaf);
            if (fix != null) {
                annotation = annotation.withFix(fix);
            }
            annotation.create();
        }
    }

    private static CommonIntentionAction fixFor(ErrorCode code, PsiElement leaf) {
        PsiElement tryExpression = PsiTreeUtil.findFirstParent(leaf, e -> e.getNode() != null
                && e.getNode().getElementType() == AxlElementTypes.TRY);
        if (tryExpression == null) {
            return null;
        }
        return switch (code) {
            case RETURN_TRY -> new ReturnTryFix(tryExpression);
            case TRY_IN_EXPRESSION -> new ExtractTryFix(tryExpression);
            default -> null;
        };
    }
}
