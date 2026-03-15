package org.axl.intellij.refactor;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.util.ProcessingContext;
import java.util.List;
import org.axl.intellij.AxlLanguage;
import org.axl.intellij.parser.AxlElementTypes;
import org.axl.intellij.psi.AxlReferenceExpression;
import org.jetbrains.annotations.NotNull;

/** Keywords and prelude functions; names and members come from the references themselves. */
public final class AxlCompletionContributor extends CompletionContributor {

    private static final List<String> KEYWORDS = List.of("val", "var", "fn", "private", "return", "if", "else",
            "while", "for", "when", "is", "data", "class", "enum", "interface", "sealed", "throw", "try", "attempt", "true", "false", "null",
            "import", "package");
    private static final List<String> PRELUDE = List.of("println", "Ok", "Err");
    private static final List<String> PIPE_STAGES = List.of("filter", "map", "mapResult", "peek", "sorted", "distinct",
            "limit", "skip", "forEach", "anyMatch", "allMatch", "noneMatch", "count", "first");

    public AxlCompletionContributor() {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement().withLanguage(AxlLanguage.INSTANCE), new CompletionProvider<>() {
            @Override
            protected void addCompletions(@NotNull CompletionParameters parameters, @NotNull ProcessingContext context,
                                          @NotNull CompletionResultSet result) {
                PsiElement position = parameters.getPosition();
                if (position.getParent().getNode().getElementType() == AxlElementTypes.PIPE_STAGE) {
                    PIPE_STAGES.forEach(stage -> result.addElement(LookupElementBuilder.create(stage)));
                    return;
                }
                if (position.getParent() instanceof AxlReferenceExpression ref && ref.qualifier() != null) {
                    return;
                }
                KEYWORDS.forEach(k -> result.addElement(LookupElementBuilder.create(k).bold()));
                PRELUDE.forEach(p -> result.addElement(LookupElementBuilder.create(p).withTailText("()", true)));
            }
        });
    }
}
