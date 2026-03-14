package org.axl.intellij.lexer;

import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import org.axl.compiler.parse.AxolotlLexer;
import org.axl.intellij.AxlLanguage;

/** One IntelliJ token type per token of the ANTLR lexer, shared with the compiler. */
public final class AxlTokenTypes {

    private static final IElementType[] TYPES = new IElementType[AxolotlLexer.VOCABULARY.getMaxTokenType() + 1];

    static {
        for (int i = 1; i < TYPES.length; i++) {
            TYPES[i] = new IElementType(AxolotlLexer.VOCABULARY.getSymbolicName(i), AxlLanguage.INSTANCE);
        }
        TYPES[AxolotlLexer.WS] = TokenType.WHITE_SPACE;
        TYPES[AxolotlLexer.UNKNOWN_CHAR] = TokenType.BAD_CHARACTER;
    }

    public static final IElementType IDENTIFIER = of(AxolotlLexer.IDENTIFIER);
    public static final IElementType LINE_COMMENT = of(AxolotlLexer.LINE_COMMENT);
    public static final IElementType BLOCK_COMMENT = of(AxolotlLexer.BLOCK_COMMENT);
    public static final IElementType STRING = of(AxolotlLexer.STRING_LITERAL);
    public static final IElementType CHAR = of(AxolotlLexer.CHAR_LITERAL);
    public static final IElementType INT = of(AxolotlLexer.INT_LITERAL);
    public static final IElementType FLOAT = of(AxolotlLexer.FLOAT_LITERAL);
    public static final IElementType LBRACE = of(AxolotlLexer.LBRACE);
    public static final IElementType RBRACE = of(AxolotlLexer.RBRACE);
    public static final IElementType LPAREN = of(AxolotlLexer.LPAREN);
    public static final IElementType RPAREN = of(AxolotlLexer.RPAREN);

    public static final TokenSet WHITESPACE = TokenSet.create(TokenType.WHITE_SPACE);
    public static final TokenSet COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT);
    public static final TokenSet STRINGS = TokenSet.create(STRING, CHAR);
    public static final TokenSet NUMBERS = TokenSet.create(INT, FLOAT);
    public static final TokenSet KEYWORDS = TokenSet.create(of(AxolotlLexer.PACKAGE), of(AxolotlLexer.IMPORT),
            of(AxolotlLexer.FN), of(AxolotlLexer.VAL), of(AxolotlLexer.VAR), of(AxolotlLexer.PRIVATE),
            of(AxolotlLexer.RETURN), of(AxolotlLexer.IF), of(AxolotlLexer.ELSE), of(AxolotlLexer.WHILE),
            of(AxolotlLexer.FOR), of(AxolotlLexer.WHEN), of(AxolotlLexer.IS), of(AxolotlLexer.CLASS),
            of(AxolotlLexer.INTERFACE), of(AxolotlLexer.SEALED), of(AxolotlLexer.TRY), of(AxolotlLexer.ATTEMPT),
            of(AxolotlLexer.TRUE), of(AxolotlLexer.FALSE), of(AxolotlLexer.NULL));
    public static final TokenSet NULL_OPERATORS = TokenSet.create(of(AxolotlLexer.SAFE_DOT), of(AxolotlLexer.ELVIS));
    public static final TokenSet ASSIGN_OPERATORS = TokenSet.create(of(AxolotlLexer.ASSIGN), of(AxolotlLexer.PLUS_ASSIGN),
            of(AxolotlLexer.MINUS_ASSIGN), of(AxolotlLexer.STAR_ASSIGN), of(AxolotlLexer.SLASH_ASSIGN),
            of(AxolotlLexer.PERCENT_ASSIGN));

    private AxlTokenTypes() {}

    /** The IntelliJ token type of an ANTLR token type. */
    public static IElementType of(int antlrType) {
        return TYPES[antlrType];
    }
}
