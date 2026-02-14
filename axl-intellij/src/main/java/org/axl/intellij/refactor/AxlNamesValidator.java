package org.axl.intellij.refactor;

import com.intellij.lang.refactoring.NamesValidator;
import com.intellij.openapi.project.Project;
import java.util.List;
import java.util.Set;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.parse.AxolotlLexer;
import org.jetbrains.annotations.NotNull;

/**
 * Names Axolotl accepts: one identifier token of the Axolotl lexer without `$`, and no Axolotl or
 * Java keyword, including the restricted identifiers the translator rejects.
 */
public final class AxlNamesValidator implements NamesValidator {

    private static final Set<String> KEYWORDS = Set.of("package", "import", "fn", "val", "var", "private", "return",
            "if", "else", "while", "try", "attempt", "true", "false", "null",
            "yield", "record", "sealed", "permits", "_");

    @Override
    public boolean isKeyword(@NotNull String name, Project project) {
        return KEYWORDS.contains(name) || javax.lang.model.SourceVersion.isKeyword(name);
    }

    @Override
    public boolean isIdentifier(@NotNull String name, Project project) {
        AxolotlLexer lexer = new AxolotlLexer(CharStreams.fromString(name));
        lexer.removeErrorListeners();
        List<? extends Token> tokens = lexer.getAllTokens();
        return tokens.size() == 1 && tokens.getFirst().getType() == AxolotlLexer.IDENTIFIER
                && tokens.getFirst().getText().equals(name) && name.indexOf('$') < 0 && !isKeyword(name, project);
    }
}
