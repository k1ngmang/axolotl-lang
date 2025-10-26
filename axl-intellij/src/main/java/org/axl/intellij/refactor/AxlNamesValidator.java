package org.axl.intellij.refactor;

import com.intellij.lang.refactoring.NamesValidator;
import com.intellij.openapi.project.Project;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/** Names Axolotl accepts: Java identifiers without `$`, no Axolotl or Java keywords. */
public final class AxlNamesValidator implements NamesValidator {

    private static final Set<String> KEYWORDS = Set.of("package", "import", "fn", "val", "var", "private", "return",
            "if", "else", "while", "try", "attempt", "true", "false", "null");

    @Override
    public boolean isKeyword(@NotNull String name, Project project) {
        return KEYWORDS.contains(name) || javax.lang.model.SourceVersion.isKeyword(name);
    }

    @Override
    public boolean isIdentifier(@NotNull String name, Project project) {
        return javax.lang.model.SourceVersion.isIdentifier(name) && name.indexOf('$') < 0 && !isKeyword(name, project);
    }
}
