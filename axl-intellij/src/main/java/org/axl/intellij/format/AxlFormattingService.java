package org.axl.intellij.format;

import com.intellij.formatting.FormattingContext;
import com.intellij.formatting.service.AbstractDocumentFormattingService;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import java.util.List;
import java.util.Set;
import org.axl.compiler.format.Formatter;
import org.axl.intellij.psi.AxlFile;
import org.jetbrains.annotations.NotNull;

/**
 * Reformat Code runs the compiler's formatter, the one `axl fmt` and the language server use, on the whole
 * file: Axolotl has one style and no settings. A file with syntax errors is left as it is.
 */
public final class AxlFormattingService extends AbstractDocumentFormattingService {

    private static final Logger LOG = Logger.getInstance(AxlFormattingService.class);

    @Override
    public @NotNull Set<Feature> getFeatures() {
        return Set.of();
    }

    @Override
    public boolean canFormat(@NotNull PsiFile file) {
        return file instanceof AxlFile;
    }

    @Override
    public void formatDocument(@NotNull Document document, @NotNull List<TextRange> formattingRanges,
                               @NotNull FormattingContext formattingContext, boolean canChangeWhiteSpaceOnly, boolean quickFormat) {
        String text = document.getText();
        Formatter.Result result = Formatter.format(formattingContext.getContainingFile().getName(), text);
        if (result.internalError() != null) {
            LOG.warn("Formatting " + formattingContext.getContainingFile().getName() + " would change its meaning: "
                    + result.internalError());
        }
        String formatted = result.text();
        // Sorting the imports changes more than whitespace, which some callers do not allow.
        if (result.failed() || formatted.equals(text) || canChangeWhiteSpaceOnly && !sameWithoutWhitespace(text, formatted)) {
            return;
        }
        // Only the part between what stays the same, so that the caret and folding outside it stay where they are.
        int start = 0;
        while (start < text.length() && start < formatted.length() && text.charAt(start) == formatted.charAt(start)) {
            start++;
        }
        int end = 0;
        while (end < text.length() - start && end < formatted.length() - start
                && text.charAt(text.length() - 1 - end) == formatted.charAt(formatted.length() - 1 - end)) {
            end++;
        }
        document.replaceString(start, text.length() - end, formatted.substring(start, formatted.length() - end));
    }

    private static boolean sameWithoutWhitespace(String a, String b) {
        return a.replaceAll("\\s", "").equals(b.replaceAll("\\s", ""));
    }
}
