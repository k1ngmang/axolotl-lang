package org.axl.intellij.highlight;

import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.axl.compiler.parse.AxolotlLexer;
import org.axl.intellij.lexer.AxlLexer;
import org.axl.intellij.lexer.AxlTokenTypes;
import org.jetbrains.annotations.NotNull;

public final class AxlSyntaxHighlighter extends SyntaxHighlighterBase {

    public static final TextAttributesKey KEYWORD = createTextAttributesKey("AXL_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey STRING = createTextAttributesKey("AXL_STRING", DefaultLanguageHighlighterColors.STRING);
    public static final TextAttributesKey NUMBER = createTextAttributesKey("AXL_NUMBER", DefaultLanguageHighlighterColors.NUMBER);
    public static final TextAttributesKey LINE_COMMENT = createTextAttributesKey("AXL_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
    public static final TextAttributesKey BLOCK_COMMENT = createTextAttributesKey("AXL_BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT);
    public static final TextAttributesKey NULL_OPERATOR = createTextAttributesKey("AXL_NULL_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN);
    public static final TextAttributesKey OPERATOR = createTextAttributesKey("AXL_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN);
    public static final TextAttributesKey BRACES = createTextAttributesKey("AXL_BRACES", DefaultLanguageHighlighterColors.BRACES);
    public static final TextAttributesKey PARENTHESES = createTextAttributesKey("AXL_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES);
    public static final TextAttributesKey FUNCTION_DECLARATION = createTextAttributesKey("AXL_FUNCTION_DECLARATION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION);
    public static final TextAttributesKey BAD_CHARACTER = createTextAttributesKey("AXL_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER);

    @Override
    public @NotNull Lexer getHighlightingLexer() {
        return new AxlLexer();
    }

    @Override
    public TextAttributesKey @NotNull [] getTokenHighlights(IElementType type) {
        if (AxlTokenTypes.KEYWORDS.contains(type)) {
            return pack(KEYWORD);
        }
        if (AxlTokenTypes.STRINGS.contains(type)) {
            return pack(STRING);
        }
        if (AxlTokenTypes.NUMBERS.contains(type)) {
            return pack(NUMBER);
        }
        if (type == AxlTokenTypes.LINE_COMMENT) {
            return pack(LINE_COMMENT);
        }
        if (type == AxlTokenTypes.BLOCK_COMMENT) {
            return pack(BLOCK_COMMENT);
        }
        if (AxlTokenTypes.NULL_OPERATORS.contains(type)) {
            return pack(NULL_OPERATOR);
        }
        if (type == AxlTokenTypes.LBRACE || type == AxlTokenTypes.RBRACE) {
            return pack(BRACES);
        }
        if (type == AxlTokenTypes.LPAREN || type == AxlTokenTypes.RPAREN) {
            return pack(PARENTHESES);
        }
        if (type == AxlTokenTypes.of(AxolotlLexer.ARROW) || type == AxlTokenTypes.of(AxolotlLexer.PIPE)
                || AxlTokenTypes.ASSIGN_OPERATORS.contains(type)) {
            return pack(OPERATOR);
        }
        if (type == TokenType.BAD_CHARACTER) {
            return pack(BAD_CHARACTER);
        }
        return TextAttributesKey.EMPTY_ARRAY;
    }
}
