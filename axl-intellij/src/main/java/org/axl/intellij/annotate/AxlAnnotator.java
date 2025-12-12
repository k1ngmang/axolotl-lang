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
import org.axl.compiler.Options;
import org.axl.compiler.Translator;
import org.axl.intellij.java.PsiJavaTypes;
import org.axl.intellij.highlight.AxlSyntaxHighlighter;
import org.axl.intellij.parser.AxlElementTypes;
import org.axl.intellij.psi.AxlFile;
import org.axl.intellij.psi.AxlFunction;
import org.axl.intellij.psi.AxlNamedElement;
import org.jetbrains.annotations.NotNull;

/**
 * Runs the translator's checks (shadowing, `try` placement, `$` in names, ...) on the file. The
 * translator's syntax errors are shown only when the PSI parser found none: it accepts a few
 * constructs that the translator rejects, such as `g() = 2`.
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
        Translator.Result result = Translator.translate(file.getName(), file.getName(), file.getText(),
                new Options(false).withJavaTypes(new PsiJavaTypes(file)));
        boolean psiErrors = PsiTreeUtil.hasErrorElements(file);
        for (AxlDiagnostic d : result.diagnostics()) {
            if (d.code() == ErrorCode.SYNTAX && psiErrors || d.line() < 1 || d.line() > document.getLineCount()) {
                continue;
            }
            int offset = offsetOf(document, d.line(), d.column());
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

    /** The offset of a 1-based line and column; the compiler counts code points, the document UTF-16 units. */
    static int offsetOf(Document document, int line, int column) {
        int start = document.getLineStartOffset(line - 1);
        String text = document.getText(new TextRange(start, document.getLineEndOffset(line - 1)));
        int codePoints = Math.clamp(column - 1, 0, text.codePointCount(0, text.length()));
        return start + text.offsetByCodePoints(0, codePoints);
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
