package org.axl.intellij;

import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.ArrayList;
import java.util.List;
import org.axl.intellij.lexer.AxlLexer;
import org.axl.intellij.lexer.AxlTokenTypes;

public class AxlLexerTest extends BasePlatformTestCase {

    public void testLexesTextWithCharactersOutsideTheBmp() {
        String text = "package app\n\nfn main() {\n    val s = \"\uD83D\uDE00\"\n    println(s)\n}\n";
        List<String> tokens = lex(text);
        assertTrue(tokens.toString(), tokens.contains("STRING_LITERAL[37,41)"));
        assertTrue(tokens.toString(), tokens.contains("IDENTIFIER[54,55)"));
        PsiFile file = myFixture.configureByText("Emoji.axl", text);
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
    }

    public void testHighlightingFollowsATypedString() {
        myFixture.configureByText("S.axl", "package app\n\nfn f() {\n    val s = <caret>\n}\n");
        myFixture.type("\"hello big world\"");
        assertHighlightingMatchesFreshLexing();
    }

    public void testHighlightingFollowsATypedBlockComment() {
        myFixture.configureByText("C.axl", "package app\n\n<caret>fn f() {\n    val s = 1\n}\n");
        myFixture.type("/* ");
        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getEditor().getDocument().getText().indexOf("}\n"));
        myFixture.type("*/");
        assertHighlightingMatchesFreshLexing();
    }

    public void testHighlightsTheKeywordsOfClassesEnumsAndThrow() {
        String text = "class enum throw";
        AxlLexer lexer = new AxlLexer();
        lexer.start(text, 0, text.length(), 0);
        int keywords = 0;
        for (; lexer.getTokenType() != null; lexer.advance()) {
            if (AxlTokenTypes.KEYWORDS.contains(lexer.getTokenType())) {
                keywords++;
            }
        }
        assertEquals(3, keywords);
    }

    public void testHighlightsTheKeywordsOfLoopsDataClassesAndWhen() {
        String text = "for is when class interface sealed";
        AxlLexer lexer = new AxlLexer();
        lexer.start(text, 0, text.length(), 0);
        int keywords = 0;
        for (; lexer.getTokenType() != null; lexer.advance()) {
            if (AxlTokenTypes.KEYWORDS.contains(lexer.getTokenType())) {
                keywords++;
            }
        }
        assertEquals(6, keywords);
    }

    /** `use` is a name to the lexer; the parser makes it a keyword before `(val`. */
    public void testHighlightsBreakAndContinueButNotUseAsKeywords() {
        String text = "break continue use";
        AxlLexer lexer = new AxlLexer();
        lexer.start(text, 0, text.length(), 0);
        List<String> keywords = new ArrayList<>();
        for (; lexer.getTokenType() != null; lexer.advance()) {
            if (AxlTokenTypes.KEYWORDS.contains(lexer.getTokenType())) {
                keywords.add(text.substring(lexer.getTokenStart(), lexer.getTokenEnd()));
            }
        }
        assertEquals(List.of("break", "continue"), keywords);
    }

    private void assertHighlightingMatchesFreshLexing() {
        EditorEx editor = (EditorEx) myFixture.getEditor();
        List<String> incremental = new ArrayList<>();
        for (HighlighterIterator it = editor.getHighlighter().createIterator(0); !it.atEnd(); it.advance()) {
            incremental.add(it.getTokenType() + "[" + it.getStart() + "," + it.getEnd() + ")");
        }
        assertEquals(lex(editor.getDocument().getText()), incremental);
    }

    private static List<String> lex(String text) {
        AxlLexer lexer = new AxlLexer();
        lexer.start(text, 0, text.length(), 0);
        List<String> tokens = new ArrayList<>();
        int previousEnd = 0;
        while (lexer.getTokenType() != null) {
            assertEquals("gap before " + tokens, previousEnd, lexer.getTokenStart());
            tokens.add(lexer.getTokenType() + "[" + lexer.getTokenStart() + "," + lexer.getTokenEnd() + ")");
            previousEnd = lexer.getTokenEnd();
            lexer.advance();
        }
        assertEquals(text.length(), previousEnd);
        return tokens;
    }
}
