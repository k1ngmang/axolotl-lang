package org.axl.intellij.parser;

import static org.axl.compiler.parse.AxolotlLexer.*;

import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.axl.intellij.lexer.AxlTokenTypes;
import org.jetbrains.annotations.NotNull;

/**
 * Recursive descent over the same grammar as the compiler's ANTLR parser, with error recovery so
 * that a broken line does not lose the rest of the file.
 */
public final class AxlParser implements PsiParser {

    private PsiBuilder b;

    @Override
    public @NotNull ASTNode parse(@NotNull IElementType root, @NotNull PsiBuilder builder) {
        b = builder;
        PsiBuilder.Marker file = b.mark();
        if (at(PACKAGE)) {
            packageDecl();
        }
        while (at(IMPORT)) {
            importDecl();
        }
        while (!b.eof()) {
            topLevel();
        }
        file.done(root);
        return b.getTreeBuilt();
    }

    private void packageDecl() {
        PsiBuilder.Marker m = b.mark();
        b.advanceLexer();
        qualifiedName();
        consume(SEMI);
        m.done(AxlElementTypes.PACKAGE);
    }

    private void qualifiedName() {
        PsiBuilder.Marker m = b.mark();
        expect(IDENTIFIER, "name expected");
        while (at(DOT)) {
            b.advanceLexer();
            expect(IDENTIFIER, "name expected");
        }
        m.done(AxlElementTypes.QUALIFIED_NAME);
    }

    private void importDecl() {
        PsiBuilder.Marker m = b.mark();
        b.advanceLexer();
        if (at(FN) || at(VAL)) {
            b.advanceLexer();
        }
        referenceChain();
        consume(SEMI);
        m.done(AxlElementTypes.IMPORT);
    }

    /** `a.b.c` as nested references, so that every segment resolves on its own. */
    private void referenceChain() {
        PsiBuilder.Marker ref = b.mark();
        if (!expect(IDENTIFIER, "name expected")) {
            ref.drop();
            return;
        }
        ref.done(AxlElementTypes.REFERENCE);
        while (at(DOT) && b.lookAhead(1) == AxlTokenTypes.IDENTIFIER) {
            ref = ref.precede();
            b.advanceLexer();
            b.advanceLexer();
            ref.done(AxlElementTypes.REFERENCE);
        }
    }

    private void topLevel() {
        if (at(FN) || at(PRIVATE) && b.lookAhead(1) == AxlTokenTypes.of(FN)) {
            function();
        } else if (at(VAL) || at(PRIVATE) && b.lookAhead(1) == AxlTokenTypes.of(VAL)) {
            variable();
            consume(SEMI);
        } else if (at(SEMI)) {
            b.advanceLexer();
        } else {
            skip("`fn` or `val` expected");
        }
    }

    private void function() {
        PsiBuilder.Marker m = b.mark();
        consume(PRIVATE);
        b.advanceLexer();
        expect(IDENTIFIER, "function name expected");
        parameters();
        if (consume(ARROW)) {
            type();
        }
        if (consume(ASSIGN)) {
            expression();
            consume(SEMI);
        } else if (at(LBRACE)) {
            block();
        } else {
            b.error("`=` or `{` expected");
        }
        m.done(AxlElementTypes.FUNCTION);
    }

    private void parameters() {
        PsiBuilder.Marker m = b.mark();
        if (!expect(LPAREN, "`(` expected")) {
            m.done(AxlElementTypes.PARAMETER_LIST);
            return;
        }
        while (!at(RPAREN) && !b.eof()) {
            PsiBuilder.Marker param = b.mark();
            expect(IDENTIFIER, "parameter name expected");
            if (expect(COLON, "`:` expected")) {
                type();
            }
            param.done(AxlElementTypes.PARAMETER);
            if (!consume(COMMA)) {
                break;
            }
        }
        expect(RPAREN, "`)` expected");
        m.done(AxlElementTypes.PARAMETER_LIST);
    }

    private void type() {
        PsiBuilder.Marker m = b.mark();
        referenceChain();
        if (at(LT)) {
            PsiBuilder.Marker args = b.mark();
            b.advanceLexer();
            type();
            while (consume(COMMA)) {
                type();
            }
            expect(GT, "`>` expected");
            args.done(AxlElementTypes.TYPE_ARGUMENTS);
        }
        consume(QUESTION);
        m.done(AxlElementTypes.TYPE);
    }

    private void variable() {
        PsiBuilder.Marker m = b.mark();
        consume(PRIVATE);
        b.advanceLexer();
        expect(IDENTIFIER, "name expected");
        if (consume(COLON)) {
            type();
        }
        if (expect(ASSIGN, "`=` expected")) {
            expression();
        }
        m.done(AxlElementTypes.VARIABLE);
    }

    private void block() {
        PsiBuilder.Marker m = b.mark();
        b.advanceLexer();
        while (!at(RBRACE) && !b.eof()) {
            statement();
        }
        expect(RBRACE, "`}` expected");
        m.done(AxlElementTypes.BLOCK);
    }

    private void statement() {
        if (at(VAL) || at(VAR) || at(PRIVATE)) {
            variable();
            consume(SEMI);
        } else if (at(RETURN)) {
            PsiBuilder.Marker m = b.mark();
            b.advanceLexer();
            if (!newlineBefore() && startsExpression()) {
                expression();
            }
            consume(SEMI);
            m.done(AxlElementTypes.RETURN);
        } else if (at(IF)) {
            ifStatement();
        } else if (at(WHILE)) {
            PsiBuilder.Marker m = b.mark();
            b.advanceLexer();
            condition();
            block(LBRACE);
            m.done(AxlElementTypes.WHILE);
        } else if (at(SEMI)) {
            b.advanceLexer();
        } else if (startsExpression()) {
            PsiBuilder.Marker m = b.mark();
            expression();
            if (AxlTokenTypes.ASSIGN_OPERATORS.contains(b.getTokenType())) {
                b.advanceLexer();
                expression();
                consume(SEMI);
                m.done(AxlElementTypes.ASSIGNMENT);
            } else {
                consume(SEMI);
                m.done(AxlElementTypes.EXPRESSION_STATEMENT);
            }
        } else {
            skip("statement expected");
        }
    }

    private void ifStatement() {
        PsiBuilder.Marker m = b.mark();
        b.advanceLexer();
        condition();
        block(LBRACE);
        if (consume(ELSE)) {
            if (at(IF)) {
                ifStatement();
            } else {
                block(LBRACE);
            }
        }
        m.done(AxlElementTypes.IF);
    }

    private void block(int open) {
        if (at(open)) {
            block();
        } else {
            b.error("`{` expected");
        }
    }

    private void condition() {
        if (expect(LPAREN, "`(` expected")) {
            expression();
            expect(RPAREN, "`)` expected");
        }
    }

    private boolean startsExpression() {
        return at(IDENTIFIER) || at(INT_LITERAL) || at(FLOAT_LITERAL) || at(STRING_LITERAL) || at(CHAR_LITERAL)
                || at(TRUE) || at(FALSE) || at(NULL) || at(LPAREN) || at(LBRACE) || at(BANG) || at(MINUS)
                || at(PLUS) || at(TRY) || at(ATTEMPT);
    }

    private void expression() {
        binary(0);
    }

    private static final int[][] LEVELS = {
        {OROR}, {ANDAND}, {EQ, NEQ}, {LT, LE, GT, GE}, {}, {PLUS, MINUS}, {STAR, SLASH, PERCENT}
    };
    private static final int ELVIS_LEVEL = 4;

    /** Left-associative binary levels; `?:` is right-associative and sits between comparison and +. */
    private void binary(int level) {
        if (level == LEVELS.length) {
            prefix();
            return;
        }
        PsiBuilder.Marker left = b.mark();
        binary(level + 1);
        if (level == ELVIS_LEVEL) {
            if (at(ELVIS)) {
                b.advanceLexer();
                binary(level);
                left.done(AxlElementTypes.ELVIS);
            } else {
                left.drop();
            }
            return;
        }
        while (atAny(LEVELS[level]) && (level != 5 || !newlineBefore())) {
            b.advanceLexer();
            binary(level + 1);
            left.done(AxlElementTypes.BINARY);
            left = left.precede();
        }
        left.drop();
    }

    private void prefix() {
        if (at(BANG) || at(MINUS) || at(PLUS)) {
            PsiBuilder.Marker m = b.mark();
            b.advanceLexer();
            prefix();
            m.done(AxlElementTypes.UNARY);
        } else if (at(TRY)) {
            PsiBuilder.Marker m = b.mark();
            b.advanceLexer();
            expression();
            m.done(AxlElementTypes.TRY);
        } else if (at(ATTEMPT)) {
            PsiBuilder.Marker m = b.mark();
            b.advanceLexer();
            expression();
            if (expect(ELSE, "`else` expected")) {
                if (at(IDENTIFIER) && b.lookAhead(1) == AxlTokenTypes.of(ARROW)) {
                    PsiBuilder.Marker param = b.mark();
                    b.advanceLexer();
                    param.done(AxlElementTypes.LAMBDA_PARAMETER);
                    b.advanceLexer();
                }
                expression();
            }
            m.done(AxlElementTypes.ATTEMPT);
        } else {
            postfix();
        }
    }

    private void postfix() {
        PsiBuilder.Marker expr = primary();
        while (expr != null) {
            if (at(DOT) || at(SAFE_DOT)) {
                expr = expr.precede();
                b.advanceLexer();
                expect(IDENTIFIER, "name expected");
                expr.done(AxlElementTypes.REFERENCE);
            } else if (at(LPAREN) && !newlineBefore()) {
                expr = expr.precede();
                arguments();
                expr.done(AxlElementTypes.CALL);
            } else if (at(COLONCOLON)) {
                expr = expr.precede();
                b.advanceLexer();
                expect(IDENTIFIER, "name expected");
                expr.done(AxlElementTypes.METHOD_REFERENCE);
            } else {
                break;
            }
        }
    }

    /** The completed marker of the primary expression, which postfix operators wrap; null on error. */
    private PsiBuilder.Marker primary() {
        if (at(INT_LITERAL) || at(FLOAT_LITERAL) || at(STRING_LITERAL) || at(CHAR_LITERAL) || at(TRUE) || at(FALSE) || at(NULL)) {
            PsiBuilder.Marker m = b.mark();
            b.advanceLexer();
            m.done(AxlElementTypes.LITERAL);
            return m;
        }
        if (at(IDENTIFIER)) {
            if (Character.isUpperCase(b.getTokenText().charAt(0)) && b.lookAhead(1) == AxlTokenTypes.of(LT)) {
                PsiBuilder.Marker generic = genericNew();
                if (generic != null) {
                    return generic;
                }
            }
            PsiBuilder.Marker m = b.mark();
            b.advanceLexer();
            m.done(AxlElementTypes.REFERENCE);
            return m;
        }
        if (at(LPAREN)) {
            PsiBuilder.Marker m = b.mark();
            b.advanceLexer();
            expression();
            expect(RPAREN, "`)` expected");
            m.done(AxlElementTypes.PARENTHESIZED);
            return m;
        }
        if (at(LBRACE)) {
            return lambda();
        }
        b.error("expression expected");
        return null;
    }

    /** `Name<T>(args)`; rolls back when the `<` turns out to be a comparison. */
    private PsiBuilder.Marker genericNew() {
        PsiBuilder.Marker m = b.mark();
        b.advanceLexer();
        PsiBuilder.Marker args = b.mark();
        b.advanceLexer();
        while (!at(GT) && !b.eof()) {
            if (!at(IDENTIFIER)) {
                m.rollbackTo();
                return null;
            }
            type();
            if (!consume(COMMA)) {
                break;
            }
        }
        if (!consume(GT) || !at(LPAREN)) {
            m.rollbackTo();
            return null;
        }
        args.done(AxlElementTypes.TYPE_ARGUMENTS);
        arguments();
        m.done(AxlElementTypes.GENERIC_NEW);
        return m;
    }

    private PsiBuilder.Marker lambda() {
        PsiBuilder.Marker m = b.mark();
        b.advanceLexer();
        PsiBuilder.Marker params = b.mark();
        boolean hasParams = false;
        while (at(IDENTIFIER)) {
            PsiBuilder.Marker param = b.mark();
            b.advanceLexer();
            if (consume(COLON)) {
                type();
            }
            param.done(AxlElementTypes.LAMBDA_PARAMETER);
            if (!consume(COMMA)) {
                break;
            }
        }
        if (at(ARROW)) {
            b.advanceLexer();
            hasParams = true;
        }
        if (hasParams) {
            params.drop();
        } else {
            params.rollbackTo();
        }
        while (!at(RBRACE) && !b.eof()) {
            statement();
        }
        expect(RBRACE, "`}` expected");
        m.done(AxlElementTypes.LAMBDA);
        return m;
    }

    private void arguments() {
        PsiBuilder.Marker m = b.mark();
        b.advanceLexer();
        while (!at(RPAREN) && !b.eof() && startsExpression()) {
            expression();
            if (!consume(COMMA)) {
                break;
            }
        }
        expect(RPAREN, "`)` expected");
        m.done(AxlElementTypes.ARGUMENTS);
    }

    /** A call or a binary +/- on the next line starts a new statement instead. */
    private boolean newlineBefore() {
        for (int i = -1; ; i--) {
            IElementType type = b.rawLookup(i);
            if (type == null) {
                return false;
            }
            if (type == TokenType.WHITE_SPACE) {
                CharSequence text = b.getOriginalText().subSequence(b.rawTokenTypeStart(i), b.rawTokenTypeStart(i + 1));
                if (text.toString().indexOf('\n') >= 0) {
                    return true;
                }
            } else if (!AxlTokenTypes.COMMENTS.contains(type)) {
                return false;
            }
        }
    }

    private void skip(String message) {
        PsiBuilder.Marker error = b.mark();
        b.advanceLexer();
        error.error(message);
    }

    private boolean at(int antlrType) {
        return b.getTokenType() == AxlTokenTypes.of(antlrType);
    }

    private boolean atAny(int[] antlrTypes) {
        for (int type : antlrTypes) {
            if (at(type)) {
                return true;
            }
        }
        return false;
    }

    private boolean consume(int antlrType) {
        if (at(antlrType)) {
            b.advanceLexer();
            return true;
        }
        return false;
    }

    private boolean expect(int antlrType, String message) {
        if (consume(antlrType)) {
            return true;
        }
        b.error(message);
        return false;
    }
}
