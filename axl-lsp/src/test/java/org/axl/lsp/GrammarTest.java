package org.axl.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The VS Code grammar's rules, with Java's regular expressions standing in for Oniguruma's. */
class GrammarTest {

    private static final JsonObject REPOSITORY = repository();

    private static JsonObject repository() {
        try {
            Path grammar = Path.of("..", "editors", "vscode", "syntaxes", "axl.tmLanguage.json");
            return JsonParser.parseString(Files.readString(grammar)).getAsJsonObject().getAsJsonObject("repository");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void highlightsDeclarationKeywordsWithoutAName() {
        assertEquals("keyword.declaration.type.axl", scope("keywords", "class lower(val a: Int)", 0));
        assertEquals("keyword.declaration.type.axl", scope("keywords", "class", 0));
        assertEquals("keyword.declaration.type.axl", scope("keywords", "data class", 0));
        assertEquals("keyword.declaration.function.axl", scope("keywords", "fn", 0));
        assertEquals("keyword.declaration.variable.axl", scope("keywords", "val", 0));
        assertEquals("keyword.other.import.axl", scope("keywords", "package", 0));
        assertEquals("keyword.control.axl", scope("keywords", "    in xs) {", 0));
        assertNull(scope("keywords", "val data = in + 1", 4));
        assertNull(scope("keywords", "val data = in + 1", 11));
    }

    @Test
    void doesNotHighlightEnumConstantsAsTypes() {
        assertEquals("variable.other.constant.axl", scope("types", "Color.RED", 6));
        assertEquals("entity.name.type.axl", scope("types", "Color.RED", 0));
        assertEquals("entity.name.type.axl", scope("types", "Thread.State.NEW", 7));
    }

    /** The scope of the first pattern of a rule that matches at an offset, as TextMate tries them in order. */
    private static String scope(String rule, String text, int offset) {
        for (JsonElement element : REPOSITORY.getAsJsonObject(rule).getAsJsonArray("patterns")) {
            JsonObject pattern = element.getAsJsonObject();
            if (!pattern.has("match") || !pattern.has("name")) {
                continue;
            }
            Matcher matcher = Pattern.compile(pattern.get("match").getAsString()).matcher(text);
            if (matcher.find(offset) && matcher.start() == offset) {
                return pattern.get("name").getAsString();
            }
        }
        return null;
    }
}
