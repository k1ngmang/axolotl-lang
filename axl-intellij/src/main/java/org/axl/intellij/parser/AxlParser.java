package org.axl.intellij.parser;

import static org.axl.compiler.parse.AxolotlLexer.*;

import com.intellij.lang.ASTNode;
import com.intellij.lang.LighterASTNode;
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
        } else if (atDataClass()) {
            dataClass();
        } else if (at(CLASS) || at(PRIVATE) && b.lookAhead(1) == AxlTokenTypes.of(CLASS)) {
            classDeclaration();
        } else if (at(ENUM) || at(PRIVATE) && b.lookAhead(1) == AxlTokenTypes.of(ENUM)) {
            enumDeclaration();
        } else if (atInterface()) {
            interfaceDeclaration();
        } else if (at(SEMI)) {
            b.advanceLexer();
        } else {
            skip("`fn`, `val`, `data class`, `class`, `enum` or `interface` expected");
        }
    }

    /** `data` is a word only before `class`; an identifier followed by `class` cannot be anything else. */
    private boolean atDataClass() {
        int word = at(PRIVATE) ? 1 : 0;
        return b.lookAhead(word) == AxlTokenTypes.IDENTIFIER && b.lookAhead(word + 1) == AxlTokenTypes.of(CLASS);
    }

    private boolean atInterface() {
        int modifiers = at(PRIVATE) ? 1 : 0;
        if (b.lookAhead(modifiers) == AxlTokenTypes.of(SEALED)) {
            modifiers++;
        }
        return b.lookAhead(modifiers) == AxlTokenTypes.of(INTERFACE);
    }

    private void dataClass() {
        PsiBuilder.Marker m = b.mark();
        consume(PRIVATE);
        if ("data".equals(b.getTokenText())) {
            b.remapCurrentToken(AxlTokenTypes.CONTEXTUAL_KEYWORD);
        } else {
            b.error("`data` expected");
        }
        b.advanceLexer();
        b.advanceLexer();
        expect(IDENTIFIER, "class name expected");
        if (expect(LPAREN, "`(` expected")) {
            if (!at(RPAREN)) {
                do {
                    field(false);
                } while (consume(COMMA));
            }
            expect(RPAREN, "`)` expected");
        }
        supertypes();
        m.done(AxlElementTypes.DATA_CLASS);
    }

    /** `class Counter(val name: String, var step: Int) : Named { var count: Int = 0; fn next() -> Int { ... } }` */
    private void classDeclaration() {
        PsiBuilder.Marker m = b.mark();
        consume(PRIVATE);
        b.advanceLexer();
        expect(IDENTIFIER, "class name expected");
        if (consume(LPAREN)) {
            if (!at(RPAREN)) {
                do {
                    field(true);
                } while (consume(COMMA));
            }
            expect(RPAREN, "`)` expected");
        }
        supertypes();
        if (consume(LBRACE)) {
            while (!at(RBRACE) && !b.eof()) {
                classMember();
            }
            expect(RBRACE, "`}` expected");
        }
        m.done(AxlElementTypes.CLASS);
    }

    private void classMember() {
        int word = at(PRIVATE) ? 1 : 0;
        if (b.lookAhead(word) == AxlTokenTypes.of(FN)) {
            function();
        } else if (b.lookAhead(word) == AxlTokenTypes.of(VAL) || b.lookAhead(word) == AxlTokenTypes.of(VAR)) {
            variable(true);
            consume(SEMI);
        } else {
            skip("`val`, `var` or `fn` expected");
        }
    }

    /** `enum Color { RED, GREEN }`: constants only, a trailing comma is fine. */
    private void enumDeclaration() {
        PsiBuilder.Marker m = b.mark();
        consume(PRIVATE);
        b.advanceLexer();
        expect(IDENTIFIER, "enum name expected");
        if (expect(LBRACE, "`{` expected")) {
            while (at(IDENTIFIER)) {
                PsiBuilder.Marker constant = b.mark();
                b.advanceLexer();
                constant.done(AxlElementTypes.ENUM_CONSTANT);
                if (!consume(COMMA)) {
                    break;
                }
            }
            expect(RBRACE, "`}` expected");
        }
        m.done(AxlElementTypes.ENUM);
    }

    private void interfaceDeclaration() {
        PsiBuilder.Marker m = b.mark();
        consume(PRIVATE);
        consume(SEALED);
        b.advanceLexer();
        expect(IDENTIFIER, "interface name expected");
        supertypes();
        if (consume(LBRACE)) {
            while (!at(RBRACE) && !b.eof()) {
                if (at(VAL)) {
                    field(false);
                } else {
                    skip("`val` expected");
                }
            }
            expect(RBRACE, "`}` expected");
        }
        m.done(AxlElementTypes.INTERFACE);
    }

    /** `: Shape, Other` after the name of a data class or an interface. */
    private void supertypes() {
        if (consume(COLON)) {
            do {
                type();
            } while (consume(COMMA));
        }
    }

    /** `val name: Type`, a component of a data class or a value an interface requires; a class takes `var` too. */
    private void field(boolean allowVar) {
        PsiBuilder.Marker m = b.mark();
        if (!consume(VAL) && !(allowVar && consume(VAR))) {
            b.error(allowVar ? "`val` or `var` expected" : "`val` expected");
            m.drop();
            return;
        }
        expect(IDENTIFIER, "name expected");
        if (expect(COLON, "`:` expected")) {
            type();
        }
        m.done(AxlElementTypes.FIELD);
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
        if (!at(RPAREN)) {
            do {
                parameter();
            } while (consume(COMMA));
        }
        expect(RPAREN, "`)` expected");
        m.done(AxlElementTypes.PARAMETER_LIST);
    }

    private void parameter() {
        PsiBuilder.Marker param = b.mark();
        if (!expect(IDENTIFIER, "parameter name expected")) {
            param.drop();
            return;
        }
        if (expect(COLON, "`:` expected")) {
            type();
        }
        param.done(AxlElementTypes.PARAMETER);
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
        variable(false);
    }

    /** A `private` before `var` is only a class property, not a local or a top-level value. */
    private void variable(boolean property) {
        PsiBuilder.Marker m = b.mark();
        if (consume(PRIVATE) && !at(VAL) && !(property && at(VAR))) {
            b.error("`val` expected");
        }
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
        } else if (at(FOR)) {
            forStatement();
        } else if (at(SEMI)) {
            b.advanceLexer();
        } else if (startsExpression()) {
            PsiBuilder.Marker m = b.mark();
            PsiBuilder.Marker target = b.mark();
            expression();
            if (AxlTokenTypes.ASSIGN_OPERATORS.contains(b.getTokenType())) {
                if (isPostfix(b.getLatestDoneMarker())) {
                    target.drop();
                } else {
                    target.error("only a name, a member or a call result can be assigned");
                }
                b.advanceLexer();
                expression();
                consume(SEMI);
                m.done(AxlElementTypes.ASSIGNMENT);
            } else {
                target.drop();
                consume(SEMI);
                m.done(AxlElementTypes.EXPRESSION_STATEMENT);
            }
        } else {
            skip("statement expected");
        }
    }

    private void forStatement() {
        PsiBuilder.Marker m = b.mark();
        b.advanceLexer();
        if (expect(LPAREN, "`(` expected")) {
            PsiBuilder.Marker variable = b.mark();
            if (expect(IDENTIFIER, "loop variable expected")) {
                variable.done(AxlElementTypes.LOOP_VARIABLE);
            } else {
                variable.drop();
            }
            if (at(IDENTIFIER) && "in".equals(b.getTokenText())) {
                b.remapCurrentToken(AxlTokenTypes.CONTEXTUAL_KEYWORD);
                b.advanceLexer();
            } else {
                b.error("`in` expected");
            }
            expression();
            expect(RPAREN, "`)` expected");
        }
        block(LBRACE);
        m.done(AxlElementTypes.FOR);
    }

    /** The grammar assigns only to a primary expression with postfix operators. */
    private static boolean isPostfix(LighterASTNode expression) {
        if (expression == null) {
            return false;
        }
        IElementType type = expression.getTokenType();
        return type != AxlElementTypes.BINARY && type != AxlElementTypes.UNARY && type != AxlElementTypes.ELVIS
                && type != AxlElementTypes.PIPE && type != AxlElementTypes.TRY && type != AxlElementTypes.ATTEMPT
                && type != AxlElementTypes.IS_CHECK;
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
                || at(TRUE) || at(FALSE) || at(NULL) || at(LPAREN) || at(LBRACE) || at(BANG) || at(BANGBANG)
                || at(MINUS) || at(PLUS) || at(TRY) || at(ATTEMPT) || at(WHEN) || at(THROW) || at(IF);
    }

    private void expression() {
        PsiBuilder.Marker m = b.mark();
        binary(0);
        if (!at(PIPE)) {
            m.drop();
            return;
        }
        while (consume(PIPE)) {
            PsiBuilder.Marker stage = b.mark();
            expect(IDENTIFIER, "pipe stage expected");
            if (at(LPAREN) && !newlineBefore()) {
                arguments();
            }
            if (at(LBRACE) && !newlineBefore()) {
                lambda();
            }
            stage.done(AxlElementTypes.PIPE_STAGE);
        }
        m.done(AxlElementTypes.PIPE);
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
        if (level == ELVIS_LEVEL) {
            typeTest();
            return;
        }
        PsiBuilder.Marker left = b.mark();
        binary(level + 1);
        while (atAny(LEVELS[level]) && (level != 5 || !newlineBefore())) {
            b.advanceLexer();
            binary(level + 1);
            left.done(AxlElementTypes.BINARY);
            left = left.precede();
        }
        left.drop();
    }

    /** `x is Circle` and `x !is Circle`, which bind weaker than `?:` and stay on the line of `x`. */
    private void typeTest() {
        PsiBuilder.Marker m = b.mark();
        elvis();
        boolean negated = at(BANG) && b.lookAhead(1) == AxlTokenTypes.of(IS);
        if (!newlineBefore() && (negated || at(IS)) && !startsWhenArm(negated ? 2 : 1)) {
            consume(BANG);
            b.advanceLexer();
            typeName();
            m.done(AxlElementTypes.IS_CHECK);
        } else {
            m.drop();
        }
    }

    /**
     * Whether the type that starts some tokens ahead is followed by `->`: then the `is` starts the
     * next arm of a one-line `when`, as in `is Circle -> "c" is Square -> "s"`, since no expression
     * is followed by `->`.
     */
    private boolean startsWhenArm(int typeStart) {
        int i = typeStart;
        if (b.lookAhead(i) != AxlTokenTypes.IDENTIFIER) {
            return false;
        }
        while (b.lookAhead(i + 1) == AxlTokenTypes.of(DOT) && b.lookAhead(i + 2) == AxlTokenTypes.IDENTIFIER) {
            i += 2;
        }
        return b.lookAhead(i + 1) == AxlTokenTypes.of(ARROW);
    }

    private void elvis() {
        PsiBuilder.Marker left = b.mark();
        binary(ELVIS_LEVEL + 1);
        if (at(ELVIS)) {
            b.advanceLexer();
            elvis();
            left.done(AxlElementTypes.ELVIS);
        } else {
            left.drop();
        }
    }

    /** A type without arguments, as after `is`. */
    private void typeName() {
        PsiBuilder.Marker m = b.mark();
        referenceChain();
        m.done(AxlElementTypes.TYPE);
    }

    private void prefix() {
        if (at(BANG) || at(BANGBANG) || at(MINUS) || at(PLUS)) {
            PsiBuilder.Marker m = b.mark();
            b.advanceLexer();
            prefix();
            m.done(AxlElementTypes.UNARY);
        } else if (at(TRY)) {
            PsiBuilder.Marker m = b.mark();
            b.advanceLexer();
            prefix();
            m.done(AxlElementTypes.TRY);
        } else if (at(THROW)) {
            PsiBuilder.Marker m = b.mark();
            b.advanceLexer();
            prefix();
            m.done(AxlElementTypes.THROW);
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
            } else if (at(BANGBANG)) {
                expr = expr.precede();
                b.advanceLexer();
                expr.done(AxlElementTypes.NOT_NULL);
            } else {
                break;
            }
        }
    }

    /** The completed marker of the primary expression, which postfix operators wrap; null on error. */
    private PsiBuilder.Marker primary() {
        if (atLiteral()) {
            PsiBuilder.Marker m = b.mark();
            b.advanceLexer();
            m.done(AxlElementTypes.LITERAL);
            return m;
        }
        if (at(IDENTIFIER)) {
            if (Character.isUpperCase(b.getTokenText().charAt(0)) && b.lookAhead(1) == AxlTokenTypes.of(LT)
                    && typeArgumentsAhead()) {
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
        if (at(WHEN)) {
            return whenExpression();
        }
        if (at(IF) && ifExpressionAhead()) {
            return conditional();
        }
        b.error("expression expected");
        return null;
    }

    /** `if (c) a else b` is a value; `if (c) { ... }` is a statement, told apart by the token after the condition. */
    private boolean ifExpressionAhead() {
        if (b.lookAhead(1) != AxlTokenTypes.of(LPAREN)) {
            return false;
        }
        int depth = 0;
        for (int i = 1; b.lookAhead(i) != null; i++) {
            if (b.lookAhead(i) == AxlTokenTypes.of(LPAREN)) {
                depth++;
            } else if (b.lookAhead(i) == AxlTokenTypes.of(RPAREN) && --depth == 0) {
                return b.lookAhead(i + 1) != AxlTokenTypes.of(LBRACE);
            }
        }
        return false;
    }

    private PsiBuilder.Marker conditional() {
        PsiBuilder.Marker m = b.mark();
        b.advanceLexer();
        condition();
        expression();
        if (expect(ELSE, "`else` expected")) {
            expression();
        }
        m.done(AxlElementTypes.CONDITIONAL);
        return m;
    }

    private boolean atLiteral() {
        return at(INT_LITERAL) || at(FLOAT_LITERAL) || at(STRING_LITERAL) || at(CHAR_LITERAL) || at(TRUE) || at(FALSE)
                || at(NULL);
    }

    /** `when (subject) { is A -> ...; 1, 2 -> ...; else -> ... }`, also `when (val e = subject)`. */
    private PsiBuilder.Marker whenExpression() {
        PsiBuilder.Marker m = b.mark();
        b.advanceLexer();
        if (expect(LPAREN, "`(` expected")) {
            if (at(VAL)) {
                whenBinding();
            } else {
                expression();
            }
            expect(RPAREN, "`)` expected");
        }
        if (expect(LBRACE, "`{` expected")) {
            while (!at(RBRACE) && !b.eof()) {
                whenArm();
            }
            expect(RBRACE, "`}` expected");
        }
        m.done(AxlElementTypes.WHEN);
        return m;
    }

    private void whenBinding() {
        PsiBuilder.Marker m = b.mark();
        b.advanceLexer();
        expect(IDENTIFIER, "name expected");
        if (expect(ASSIGN, "`=` expected")) {
            expression();
        }
        m.done(AxlElementTypes.VARIABLE);
    }

    private void whenArm() {
        PsiBuilder.Marker m = b.mark();
        if (!consume(ELSE)) {
            do {
                whenCondition();
            } while (consume(COMMA));
        }
        if (expect(ARROW, "`->` expected")) {
            if (at(LBRACE)) {
                block();
            } else {
                expression();
            }
        }
        consume(SEMI);
        m.done(AxlElementTypes.WHEN_ARM);
    }

    private void whenCondition() {
        if (consume(IS)) {
            typeName();
        } else if (atLiteral() || at(MINUS) && (b.lookAhead(1) == AxlTokenTypes.of(INT_LITERAL)
                || b.lookAhead(1) == AxlTokenTypes.of(FLOAT_LITERAL))) {
            prefix();
        } else if (at(IDENTIFIER)) {
            // The name of a constant, such as `Color.RED`.
            referenceChain();
        } else {
            skip("`is`, a constant or `else` expected");
        }
    }

    /** `Name<T>(args)`; rolls back when the `<` turns out to be a comparison. */
    /** As in the grammar: `LOW < v, v > (1)` compares, because `v` does not look like a type. */
    private boolean typeArgumentsAhead() {
        PsiBuilder.Marker probe = b.mark();
        try {
            b.advanceLexer();
            b.advanceLexer();
            int depth = 1;
            while (!b.eof()) {
                if (at(LT)) {
                    depth++;
                } else if (at(GT)) {
                    if (--depth == 0) {
                        b.advanceLexer();
                        return at(LPAREN);
                    }
                } else if (at(IDENTIFIER)) {
                    String name = b.getTokenText();
                    b.advanceLexer();
                    if (!at(DOT) && (name == null || !Character.isUpperCase(name.charAt(0)))) {
                        return false;
                    }
                    continue;
                } else if (!at(DOT) && !at(COMMA) && !at(QUESTION)) {
                    return false;
                }
                b.advanceLexer();
            }
            return false;
        } finally {
            probe.rollbackTo();
        }
    }

    private PsiBuilder.Marker genericNew() {
        PsiBuilder.Marker m = b.mark();
        b.advanceLexer();
        PsiBuilder.Marker args = b.mark();
        b.advanceLexer();
        if (!at(GT)) {
            do {
                if (!at(IDENTIFIER)) {
                    m.rollbackTo();
                    return null;
                }
                type();
            } while (consume(COMMA));
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
            if (!at(IDENTIFIER)) {
                b.error("parameter name expected");
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
        if (startsExpression()) {
            do {
                expression();
            } while (consume(COMMA));
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
            if (type != TokenType.WHITE_SPACE && !AxlTokenTypes.COMMENTS.contains(type)) {
                return false;
            }
            // A block comment can hold the line break too.
            CharSequence text = b.getOriginalText().subSequence(b.rawTokenTypeStart(i), b.rawTokenTypeStart(i + 1));
            if (text.toString().indexOf('\n') >= 0) {
                return true;
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
