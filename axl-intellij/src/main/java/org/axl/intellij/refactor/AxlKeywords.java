package org.axl.intellij.refactor;

import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.List;
import org.axl.compiler.parse.AxolotlLexer;
import org.axl.intellij.lexer.AxlTokenTypes;
import org.axl.intellij.parser.AxlElementTypes;
import org.axl.intellij.psi.AxlFile;
import org.axl.intellij.psi.AxlFunction;
import org.axl.intellij.psi.AxlTypeDeclaration;

/** The keywords that can stand at a place: at the top level, in a class, a block, a `when` or an expression. */
final class AxlKeywords {

    private static final List<String> TOP_LEVEL = List.of("package", "import", "fn", "val", "private", "data", "class",
            "enum", "interface", "sealed");
    private static final List<String> IN_CLASS = List.of("val", "var", "fn", "private");
    private static final List<String> IN_INTERFACE = List.of("val");
    private static final List<String> STATEMENT = List.of("val", "var", "return", "if", "while", "for", "when", "throw",
            "try", "attempt", "true", "false", "null");
    private static final List<String> ARM = List.of("is", "else", "true", "false", "null");
    private static final List<String> EXPRESSION = List.of("if", "when", "throw", "try", "attempt", "true", "false", "null");

    /** Tokens after which an expression starts. */
    private static final TokenSet BEFORE_EXPRESSION = TokenSet.orSet(AxlTokenTypes.ASSIGN_OPERATORS, TokenSet.create(
            AxlTokenTypes.LPAREN, of(AxolotlLexer.COMMA), of(AxolotlLexer.ARROW), of(AxolotlLexer.RETURN),
            of(AxolotlLexer.THROW), of(AxolotlLexer.PLUS), of(AxolotlLexer.MINUS), of(AxolotlLexer.STAR),
            of(AxolotlLexer.SLASH), of(AxolotlLexer.PERCENT), of(AxolotlLexer.ANDAND), of(AxolotlLexer.OROR),
            of(AxolotlLexer.EQ), of(AxolotlLexer.NEQ), of(AxolotlLexer.LT), of(AxolotlLexer.LE), of(AxolotlLexer.GT),
            of(AxolotlLexer.GE), of(AxolotlLexer.ELVIS), of(AxolotlLexer.BANG), of(AxolotlLexer.BANGBANG),
            of(AxolotlLexer.ELSE), of(AxolotlLexer.TRY)));

    private AxlKeywords() {}

    static List<String> at(PsiElement position) {
        PsiElement previous = PsiTreeUtil.prevCodeLeaf(position);
        if (previous != null && (BEFORE_EXPRESSION.contains(previous.getNode().getElementType()) || isLoopIn(previous))) {
            return EXPRESSION;
        }
        for (PsiElement parent = position.getParent(); parent != null && !(parent instanceof AxlFile); parent = parent.getParent()) {
            IElementType type = parent.getNode().getElementType();
            if (type == AxlElementTypes.WHEN_ARM || type == AxlElementTypes.WHEN) {
                return ARM;
            }
            if (type == AxlElementTypes.BLOCK || type == AxlElementTypes.LAMBDA || parent instanceof AxlFunction) {
                return previous != null && previous.getNode().getElementType() == AxlTokenTypes.RBRACE ? withElse() : STATEMENT;
            }
            if (parent instanceof AxlTypeDeclaration declaration) {
                return declaration.isEnum() ? List.of() : declaration.isInterface() ? IN_INTERFACE : IN_CLASS;
            }
        }
        return TOP_LEVEL;
    }

    /** `in` in `for (x in `, where the iterable follows. */
    private static boolean isLoopIn(PsiElement previous) {
        return previous.getNode().getElementType() == AxlTokenTypes.CONTEXTUAL_KEYWORD && previous.getText().equals("in");
    }

    private static List<String> withElse() {
        List<String> result = new ArrayList<>(STATEMENT);
        result.add("else");
        return result;
    }

    private static IElementType of(int token) {
        return AxlTokenTypes.of(token);
    }
}
