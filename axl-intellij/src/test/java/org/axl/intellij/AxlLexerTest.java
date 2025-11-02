package org.axl.intellij;

import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.ArrayList;
import java.util.List;
import org.axl.intellij.lexer.AxlLexer;

public class AxlLexerTest extends BasePlatformTestCase {

    public void testLexesTextWithCharactersOutsideTheBmp() {
        String text = "package app\n\nfn main() {\n    val s = \"\uD83D\uDE00\"\n    println(s)\n}\n";
        List<String> tokens = lex(text);
        assertTrue(tokens.toString(), tokens.contains("STRING_LITERAL[37,41)"));
        assertTrue(tokens.toString(), tokens.contains("IDENTIFIER[54,55)"));
        PsiFile file = myFixture.configureByText("Emoji.axl", text);
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
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
