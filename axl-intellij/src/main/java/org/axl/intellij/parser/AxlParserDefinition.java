package org.axl.intellij.parser;

import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import org.axl.intellij.lexer.AxlLexer;
import org.axl.intellij.lexer.AxlTokenTypes;
import org.axl.intellij.psi.AxlFile;
import org.axl.intellij.psi.AxlFunction;
import org.axl.intellij.psi.AxlImport;
import org.axl.intellij.psi.AxlPsiElement;
import org.axl.intellij.psi.AxlReferenceExpression;
import org.axl.intellij.psi.AxlType;
import org.axl.intellij.psi.AxlTypeDeclaration;
import org.axl.intellij.psi.AxlTypeParameter;
import org.axl.intellij.psi.AxlVariable;
import org.jetbrains.annotations.NotNull;

public final class AxlParserDefinition implements ParserDefinition {

    @Override
    public @NotNull Lexer createLexer(Project project) {
        return new AxlLexer();
    }

    @Override
    public @NotNull PsiParser createParser(Project project) {
        return new AxlParser();
    }

    @Override
    public @NotNull IFileElementType getFileNodeType() {
        return AxlElementTypes.FILE;
    }

    @Override
    public @NotNull TokenSet getWhitespaceTokens() {
        return AxlTokenTypes.WHITESPACE;
    }

    @Override
    public @NotNull TokenSet getCommentTokens() {
        return AxlTokenTypes.COMMENTS;
    }

    @Override
    public @NotNull TokenSet getStringLiteralElements() {
        return AxlTokenTypes.STRINGS;
    }

    @Override
    public @NotNull PsiElement createElement(ASTNode node) {
        IElementType type = node.getElementType();
        if (type == AxlElementTypes.FUNCTION) {
            return new AxlFunction(node);
        }
        if (type == AxlElementTypes.VARIABLE || type == AxlElementTypes.PARAMETER || type == AxlElementTypes.LAMBDA_PARAMETER
                || type == AxlElementTypes.LOOP_VARIABLE || type == AxlElementTypes.FIELD
                || type == AxlElementTypes.ENUM_CONSTANT) {
            return new AxlVariable(node);
        }
        if (type == AxlElementTypes.DATA_CLASS || type == AxlElementTypes.INTERFACE || type == AxlElementTypes.CLASS
                || type == AxlElementTypes.ENUM) {
            return new AxlTypeDeclaration(node);
        }
        if (type == AxlElementTypes.TYPE_PARAMETER) {
            return new AxlTypeParameter(node);
        }
        if (type == AxlElementTypes.REFERENCE) {
            return new AxlReferenceExpression(node);
        }
        if (type == AxlElementTypes.TYPE) {
            return new AxlType(node);
        }
        if (type == AxlElementTypes.IMPORT) {
            return new AxlImport(node);
        }
        return new AxlPsiElement(node);
    }

    @Override
    public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
        return new AxlFile(viewProvider);
    }
}
