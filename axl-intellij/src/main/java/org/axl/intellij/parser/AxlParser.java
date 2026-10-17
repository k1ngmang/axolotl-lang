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
    // Where the expression statement being read starts: an `attempt` there may end in an exit.
    private int statementStart = -1;

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
        PsiBuilder.Marker m = b.mark();
        annotations();
        if (at(FN) || at(PRIVATE) && b.lookAhead(1) == AxlTokenTypes.of(FN)) {
            function(m);
        } else if (at(VAL) || at(PRIVATE) && b.lookAhead(1) == AxlTokenTypes.of(VAL)) {
            variable(m, false);
            consume(SEMI);
        } else if (atDataClass()) {
            dataClass(m);
        } else if (at(CLASS) || at(PRIVATE) && b.lookAhead(1) == AxlTokenTypes.of(CLASS)) {
            classDeclaration(m);
        } else if (at(ENUM) || at(PRIVATE) && b.lookAhead(1) == AxlTokenTypes.of(ENUM)) {
            enumDeclaration(m);
        } else if (atInterface()) {
            interfaceDeclaration(m);
        } else if (at(SEMI)) {
            m.drop();
            b.advanceLexer();
        } else {
            m.drop();
            skip("`fn`, `val`, `data class`, `class`, `enum` or `interface` expected");
        }
    }

    /**
     * `@Name` or `@Name(x = 1, "y", { a, b })` before a declaration: a Java annotation, whose
     * arguments are expressions and braced arrays. They go inside the declaration's node.
     */
    private void annotations() {
        while (at(AT)) {
            PsiBuilder.Marker m = b.mark();
            b.advanceLexer();
            referenceChain();
            if (at(LPAREN) && !newlineBefore()) {
                b.advanceLexer();
                if (!at(RPAREN)) {
                    do {
                        if (at(IDENTIFIER) && b.lookAhead(1) == AxlTokenTypes.of(ASSIGN)) {
                            b.advanceLexer();
                            b.advanceLexer();
                        }
                        annotationValue();
                    } while (consume(COMMA));
                }
                expect(RPAREN, "`)` expected");
            }
            m.done(AxlElementTypes.ANNOTATION);
        }
    }

    private void annotationValue() {
        if (!at(LBRACE)) {
            expression();
            return;
        }
        PsiBuilder.Marker m = b.mark();
        b.advanceLexer();
        if (!at(RBRACE)) {
            do {
                annotationValue();
            } while (consume(COMMA));
        }
        expect(RBRACE, "`}` expected");
        m.done(AxlElementTypes.ANNOTATION_ARRAY);
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

    private void dataClass(PsiBuilder.Marker m) {
        consume(PRIVATE);
        if ("data".equals(b.getTokenText())) {
            b.remapCurrentToken(AxlTokenTypes.CONTEXTUAL_KEYWORD);
        } else {
            b.error("`data` expected");
        }
        b.advanceLexer();
        b.advanceLexer();
        expect(IDENTIFIER, "class name expected");
        typeParameters();
        if (expect(LPAREN, "`(` expected")) {
            if (!at(RPAREN)) {
                do {
                    field(false);
                } while (consume(COMMA));
            }
            expect(RPAREN, "`)` expected");
        }
        supertypes(false);
        if (consume(LBRACE)) {
            while (!at(RBRACE) && !b.eof()) {
                dataClassMember();
            }
            expect(RBRACE, "`}` expected");
        }
        m.done(AxlElementTypes.DATA_CLASS);
    }

    /** The body of a data class, or of an enum after its constants, holds methods only. */
    private void dataClassMember() {
        PsiBuilder.Marker m = b.mark();
        annotations();
        if (at(FN) || at(PRIVATE) && b.lookAhead(1) == AxlTokenTypes.of(FN)) {
            function(m);
        } else {
            m.drop();
            skip("`fn` expected");
        }
    }

    /**
     * `class Counter(val name: String, var step: Int) : Named { var count: Int = 0; fn next() -> Int { ... } }`;
     * `class Worker : Thread("w")` extends the Java class with those constructor arguments.
     */
    private void classDeclaration(PsiBuilder.Marker m) {
        consume(PRIVATE);
        b.advanceLexer();
        expect(IDENTIFIER, "class name expected");
        typeParameters();
        if (atPrimaryConstructor()) {
            PsiBuilder.Marker primary = b.mark();
            annotations();
            consume(PRIVATE);
            // Reading the token first moves past the space before it, as in constructor().
            if (at(IDENTIFIER)) {
                b.remapCurrentToken(AxlTokenTypes.CONTEXTUAL_KEYWORD);
            }
            b.advanceLexer();
            primary.done(AxlElementTypes.PRIMARY_CONSTRUCTOR);
        }
        if (consume(LPAREN)) {
            if (!at(RPAREN)) {
                do {
                    field(true);
                } while (consume(COMMA));
            }
            expect(RPAREN, "`)` expected");
        }
        supertypes(true);
        if (consume(LBRACE)) {
            while (!at(RBRACE) && !b.eof()) {
                classMember();
            }
            expect(RBRACE, "`}` expected");
        }
        m.done(AxlElementTypes.CLASS);
    }

    /**
     * `private constructor(` or `@Deprecated constructor(` after the name of a class: the modifiers of the constructor its
     * parameters make. Without them `constructor` is not a word there.
     */
    private boolean atPrimaryConstructor() {
        if (!at(AT) && !at(PRIVATE)) {
            return false;
        }
        PsiBuilder.Marker probe = b.mark();
        annotations();
        consume(PRIVATE);
        boolean found = at(IDENTIFIER) && "constructor".equals(b.getTokenText()) && b.lookAhead(1) == AxlTokenTypes.LPAREN;
        probe.rollbackTo();
        return found;
    }

    private void classMember() {
        PsiBuilder.Marker m = b.mark();
        annotations();
        int word = at(PRIVATE) ? 1 : 0;
        if (b.lookAhead(word) == AxlTokenTypes.of(FN)) {
            function(m);
        } else if (b.lookAhead(word) == AxlTokenTypes.of(VAL) || b.lookAhead(word) == AxlTokenTypes.of(VAR)) {
            variable(m, true);
            consume(SEMI);
        } else if (atConstructor(word)) {
            constructor(m);
        } else {
            m.drop();
            skip("`val`, `var`, `fn` or `constructor` expected");
        }
    }

    /** `constructor(` after `private` when word is 1: `constructor` is a word only at the start of a member of a class. */
    private boolean atConstructor(int word) {
        PsiBuilder.Marker probe = b.mark();
        for (int i = 0; i < word; i++) {
            b.advanceLexer();
        }
        boolean found = at(IDENTIFIER) && "constructor".equals(b.getTokenText()) && b.lookAhead(1) == AxlTokenTypes.LPAREN;
        probe.rollbackTo();
        return found;
    }

    /** `constructor(opt: String?) = this(opt, null)`: its parameters, and the call that passes them on. */
    private void constructor(PsiBuilder.Marker m) {
        consume(PRIVATE);
        // Reading the token first moves past the space after `private`, which a remap would hit otherwise.
        if (at(IDENTIFIER)) {
            b.remapCurrentToken(AxlTokenTypes.CONTEXTUAL_KEYWORD);
        }
        b.advanceLexer();
        parameters();
        if (consume(ASSIGN)) {
            expression();
            consume(SEMI);
        } else if (at(LBRACE)) {
            block();
        } else {
            b.error("`=` expected");
        }
        m.done(AxlElementTypes.CONSTRUCTOR);
    }

    /**
     * `enum Color { RED, GREEN }`, or `enum Planet(val mass: Double) { MERCURY(3.3e23), EARTH(5.97e24) }` with
     * fields that each constant gives a value for; a trailing comma is fine. Interfaces may follow a colon, and
     * methods the constants, after an optional `;`.
     */
    private void enumDeclaration(PsiBuilder.Marker m) {
        consume(PRIVATE);
        b.advanceLexer();
        expect(IDENTIFIER, "enum name expected");
        if (consume(LPAREN)) {
            do {
                field(false);
            } while (consume(COMMA));
            expect(RPAREN, "`)` expected");
        }
        supertypes(false);
        if (expect(LBRACE, "`{` expected")) {
            while (at(IDENTIFIER)) {
                PsiBuilder.Marker constant = b.mark();
                b.advanceLexer();
                if (at(LPAREN)) {
                    arguments();
                }
                constant.done(AxlElementTypes.ENUM_CONSTANT);
                if (!consume(COMMA)) {
                    break;
                }
            }
            consume(SEMI);
            while (!at(RBRACE) && !b.eof()) {
                dataClassMember();
            }
            expect(RBRACE, "`}` expected");
        }
        m.done(AxlElementTypes.ENUM);
    }

    private void interfaceDeclaration(PsiBuilder.Marker m) {
        consume(PRIVATE);
        consume(SEALED);
        b.advanceLexer();
        expect(IDENTIFIER, "interface name expected");
        typeParameters();
        supertypes(false);
        if (consume(LBRACE)) {
            while (!at(RBRACE) && !b.eof()) {
                interfaceMember();
            }
            expect(RBRACE, "`}` expected");
        }
        m.done(AxlElementTypes.INTERFACE);
    }

    /** `val name: Type` or `fn name(params) -> Type` without a body, each maybe after annotations. */
    private void interfaceMember() {
        PsiBuilder.Marker m = b.mark();
        annotations();
        if (at(VAL)) {
            fieldRest(m, false);
        } else if (at(FN)) {
            abstractFunction(m);
        } else {
            m.drop();
            skip("`val` or `fn` expected");
        }
    }

    /** A method an interface requires: the signature of a function and an optional `;`. */
    private void abstractFunction(PsiBuilder.Marker m) {
        b.advanceLexer();
        expect(IDENTIFIER, "function name expected");
        typeParameters();
        parameters();
        if (consume(ARROW)) {
            type();
        }
        consume(SEMI);
        m.done(AxlElementTypes.FUNCTION);
    }

    /**
     * `: Shape, Other` after the name of a type. withArguments: a class may follow the first type,
     * the Java class it extends, with the arguments of its constructor: `: Thread("w"), Runnable`.
     */
    private void supertypes(boolean withArguments) {
        if (!consume(COLON)) {
            return;
        }
        type();
        if (withArguments && at(LPAREN)) {
            arguments();
        }
        while (consume(COMMA)) {
            type();
        }
    }

    /**
     * `val name: Type`, a component of a data class or a value an interface requires; a class takes `var` too, and
     * `private` for a value only its own code reads.
     */
    private void field(boolean allowVar) {
        PsiBuilder.Marker m = b.mark();
        annotations();
        if (allowVar) {
            consume(PRIVATE);
        }
        fieldRest(m, allowVar);
    }

    private void fieldRest(PsiBuilder.Marker m, boolean allowVar) {
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

    private void function(PsiBuilder.Marker m) {
        consume(PRIVATE);
        b.advanceLexer();
        expect(IDENTIFIER, "function name expected");
        typeParameters();
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
        annotations();
        if (!expect(IDENTIFIER, "parameter name expected")) {
            param.drop();
            return;
        }
        if (expect(COLON, "`:` expected")) {
            type();
        }
        param.done(AxlElementTypes.PARAMETER);
    }

    /**
     * `<T>` or `<K, V : Comparable<V>>` after the name of a type or a function. Each parameter is a
     * declaration, which the `T`s in the rest of the declaration refer to.
     */
    private void typeParameters() {
        if (!consume(LT)) {
            return;
        }
        do {
            PsiBuilder.Marker m = b.mark();
            if (!expect(IDENTIFIER, "type parameter name expected")) {
                m.drop();
                break;
            }
            if (consume(COLON)) {
                type();
            }
            m.done(AxlElementTypes.TYPE_PARAMETER);
        } while (consume(COMMA));
        expect(GT, "`>` expected");
    }

    private void type() {
        PsiBuilder.Marker m = b.mark();
        referenceChain();
        if (at(LT)) {
            PsiBuilder.Marker args = b.mark();
            b.advanceLexer();
            typeArgument();
            while (consume(COMMA)) {
                typeArgument();
            }
            expect(GT, "`>` expected");
            args.done(AxlElementTypes.TYPE_ARGUMENTS);
        }
        consume(QUESTION);
        m.done(AxlElementTypes.TYPE);
    }

    /** A type, or `*` for a type argument that is not known, Java's `?`. */
    private void typeArgument() {
        if (!consume(STAR)) {
            type();
        }
    }

    private void variable() {
        variable(b.mark(), false);
    }

    /** A `private` before `var` is only a class property, not a local or a top-level value. */
    private void variable(PsiBuilder.Marker m, boolean property) {
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
        } else if (at(BREAK) || at(CONTINUE)) {
            jump();
        } else if (atUseStatement()) {
            useStatement();
        } else if (at(IF) && !ifExpressionAhead()) {
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
            statementStart = b.getCurrentOffset();
            expression();
            boolean assigned = assignment(target);
            consume(SEMI);
            m.done(assigned ? AxlElementTypes.ASSIGNMENT : AxlElementTypes.EXPRESSION_STATEMENT);
        } else {
            skip("statement expected");
        }
    }

    /** The handler of an `attempt` on a line of its own, and a branch of a `when`, may be `break`, `continue` or `return value`. */
    private void exit() {
        PsiBuilder.Marker m = b.mark();
        boolean returns = at(RETURN);
        b.advanceLexer();
        if (returns && !newlineBefore() && startsExpression()) {
            expression();
        }
        m.done(returns ? AxlElementTypes.RETURN : AxlElementTypes.JUMP);
    }

    private void jump() {
        PsiBuilder.Marker m = b.mark();
        b.advanceLexer();
        consume(SEMI);
        m.done(AxlElementTypes.JUMP);
    }

    /**
     * The operator and the value of an assignment after its target, the expression just parsed, when
     * an assignment operator follows; the target marker is dropped, or marks what cannot be assigned.
     */
    private boolean assignment(PsiBuilder.Marker target) {
        if (!AxlTokenTypes.ASSIGN_OPERATORS.contains(b.getTokenType())) {
            target.drop();
            return false;
        }
        if (isPostfix(b.getLatestDoneMarker())) {
            target.drop();
        } else {
            target.error("only a name, a member or a call result can be assigned");
        }
        b.advanceLexer();
        expression();
        return true;
    }

    /** `use` is a word only before `(val`; a call `use(x)` or a value `use` stays a name. */
    private boolean atUseStatement() {
        return at(IDENTIFIER) && "use".equals(b.getTokenText()) && b.lookAhead(1) == AxlTokenTypes.LPAREN
                && b.lookAhead(2) == AxlTokenTypes.of(VAL);
    }

    /**
     * `use (val reader = open()) { ... } else e -> handler`: the resource is a local of the block, the
     * error name a parameter of the handler, which is an expression as the handler of an `attempt`.
     */
    private void useStatement() {
        PsiBuilder.Marker m = b.mark();
        b.remapCurrentToken(AxlTokenTypes.CONTEXTUAL_KEYWORD);
        b.advanceLexer();
        b.advanceLexer();
        variable();
        expect(RPAREN, "`)` expected");
        block(LBRACE);
        if (consume(ELSE)) {
            handlerParameter();
            expression();
        }
        m.done(AxlElementTypes.USE);
    }

    /** `e ->` or `e: IOException ->` after the `else` of `attempt` and `use`: a parameter of the handler, maybe typed. */
    private void handlerParameter() {
        boolean typed = b.lookAhead(1) == AxlTokenTypes.of(COLON);
        if (!at(IDENTIFIER) || b.lookAhead(1) != AxlTokenTypes.of(ARROW) && !typed) {
            return;
        }
        PsiBuilder.Marker param = b.mark();
        b.advanceLexer();
        if (consume(COLON)) {
            type();
        }
        param.done(AxlElementTypes.LAMBDA_PARAMETER);
        expect(ARROW, "`->` expected");
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
            if (atWord("until") || atWord("downTo")) {
                b.remapCurrentToken(AxlTokenTypes.CONTEXTUAL_KEYWORD);
                b.advanceLexer();
                expression();
            } else if (at(DOT) && b.lookAhead(1) == AxlTokenTypes.of(DOT)) {
                b.advanceLexer();
                b.advanceLexer();
                expression();
            }
            if (atWord("step")) {
                b.remapCurrentToken(AxlTokenTypes.CONTEXTUAL_KEYWORD);
                b.advanceLexer();
                expression();
            }
            expect(RPAREN, "`)` expected");
        }
        block(LBRACE);
        m.done(AxlElementTypes.FOR);
    }

    /** `until`, `downTo` and `step` are words only in the header of a counting `for`. */
    private boolean atWord(String word) {
        return at(IDENTIFIER) && word.equals(b.getTokenText());
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
        if (at(PIPE)) {
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
            m = m.precede();
        }
        // `xs |> first ?: fallback`: the fallback belongs to the whole pipe, as in the grammar.
        if (at(ELVIS)) {
            b.advanceLexer();
            binary(ELVIS_LEVEL + 1);
            m.done(AxlElementTypes.ELVIS);
        } else {
            m.drop();
        }
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
        if (b.lookAhead(i + 1) == AxlTokenTypes.of(LT) && b.lookAhead(i + 2) == AxlTokenTypes.of(STAR)) {
            i += 2;
            while (b.lookAhead(i + 1) == AxlTokenTypes.of(COMMA) && b.lookAhead(i + 2) == AxlTokenTypes.of(STAR)) {
                i += 2;
            }
            i += 1;
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

    /** A type without arguments, as after `is`, where `List<*>` is `List`. */
    private void typeName() {
        PsiBuilder.Marker m = b.mark();
        referenceChain();
        if (at(LT) && b.lookAhead(1) == AxlTokenTypes.of(STAR)) {
            PsiBuilder.Marker args = b.mark();
            b.advanceLexer();
            b.advanceLexer();
            while (at(COMMA) && b.lookAhead(1) == AxlTokenTypes.of(STAR)) {
                b.advanceLexer();
                b.advanceLexer();
            }
            expect(GT, "`>` expected");
            args.done(AxlElementTypes.TYPE_ARGUMENTS);
        }
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
            boolean statement = b.getCurrentOffset() == statementStart;
            PsiBuilder.Marker m = b.mark();
            b.advanceLexer();
            expression();
            if (expect(ELSE, "`else` expected")) {
                handlerParameter();
                if (statement && (at(BREAK) || at(CONTINUE) || at(RETURN))) {
                    exit();
                } else {
                    expression();
                }
            }
            m.done(AxlElementTypes.ATTEMPT);
        } else {
            postfix();
        }
    }

    private void postfix() {
        PsiBuilder.Marker expr = primary();
        while (expr != null) {
            if (at(DOT) && b.lookAhead(1) == AxlTokenTypes.of(CLASS)) {
                expr = expr.precede();
                b.advanceLexer();
                b.advanceLexer();
                expr.done(AxlElementTypes.CLASS_LITERAL);
            } else if (at(DOT) && b.lookAhead(1) != AxlTokenTypes.of(DOT) || at(SAFE_DOT)) {
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
            if ((b.lookAhead(1) == AxlTokenTypes.of(LT) || b.lookAhead(1) == AxlTokenTypes.of(DOT)) && genericConstructorAhead()) {
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
        if (at(IF)) {
            return ifExpressionAhead() ? conditional() : ifWithBraces();
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

    /**
     * `= if (c) { 1 } else 2`: the grammar accepts it as a value, so that the translator can explain
     * that braces make an `if` a statement.
     */
    private PsiBuilder.Marker ifWithBraces() {
        PsiBuilder.Marker m = b.mark();
        b.advanceLexer();
        condition();
        block(LBRACE);
        if (consume(ELSE)) {
            if (at(LBRACE)) {
                block();
            } else {
                expression();
            }
        }
        m.done(AxlElementTypes.IF);
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
            if (at(LBRACE) && !lambdaAhead()) {
                block();
            } else if (at(BREAK) || at(CONTINUE) || at(RETURN)) {
                exit();
            } else {
                // A value, or an assignment, a statement elsewhere, which stands in a `when` statement like a jump.
                PsiBuilder.Marker body = b.mark();
                PsiBuilder.Marker target = b.mark();
                expression();
                if (assignment(target)) {
                    body.done(AxlElementTypes.ASSIGNMENT);
                } else {
                    body.drop();
                }
            }
        }
        consume(SEMI);
        m.done(AxlElementTypes.WHEN_ARM);
    }

    /** Whether the brace ahead starts a lambda with an arrow, `{ s: String -> ... }`, which no block can be. */
    private boolean lambdaAhead() {
        int i = 1;
        while (b.lookAhead(i) == AxlTokenTypes.IDENTIFIER) {
            i++;
            if (b.lookAhead(i) == AxlTokenTypes.of(COLON)) {
                i = afterType(i + 1);
            }
            if (b.lookAhead(i) != AxlTokenTypes.of(COMMA)) {
                break;
            }
            i++;
        }
        return b.lookAhead(i) == AxlTokenTypes.of(ARROW);
    }

    /** The offset ahead after a type that starts at an offset, or where the tokens stop looking like one. */
    private int afterType(int i) {
        int depth = 0;
        for (IElementType t = b.lookAhead(i); ; t = b.lookAhead(++i)) {
            if (t == AxlTokenTypes.of(LT)) {
                depth++;
            } else if (t == AxlTokenTypes.of(GT) && depth > 0) {
                depth--;
            } else if (t != AxlTokenTypes.IDENTIFIER && t != AxlTokenTypes.of(DOT) && t != AxlTokenTypes.of(QUESTION)
                    && !((t == AxlTokenTypes.of(COMMA) || t == AxlTokenTypes.of(STAR)) && depth > 0)) {
                return i;
            }
        }
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

    /**
     * As the grammar's `genericConstructor()`: `Name<T>(args)`, maybe with the package before the
     * name, `java.util.HashMap<String, Int>()`. `LOW < v, v > (1)` compares, because `v` does not
     * look like a type.
     */
    private boolean genericConstructorAhead() {
        PsiBuilder.Marker probe = b.mark();
        try {
            skipQualifier();
            String last = b.getTokenText();
            if (!at(IDENTIFIER) || last == null || !Character.isUpperCase(last.charAt(0)) || b.lookAhead(1) != AxlTokenTypes.of(LT)) {
                return false;
            }
            b.advanceLexer();
            b.advanceLexer();
            int depth = 1;
            boolean argumentStart = true;
            while (!b.eof()) {
                boolean opens = at(LT) || at(COMMA);
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
                    argumentStart = false;
                    continue;
                } else if (at(STAR)) {
                    // `HashMap<Class<*>, String>()`: a `*` stands alone as an argument of a nested type.
                    IElementType next = b.lookAhead(1);
                    if (depth < 2 || !argumentStart || (next != AxlTokenTypes.of(GT) && next != AxlTokenTypes.of(COMMA))) {
                        return false;
                    }
                } else if (!at(DOT) && !at(COMMA) && !at(QUESTION)) {
                    return false;
                }
                argumentStart = opens;
                b.advanceLexer();
            }
            return false;
        } finally {
            probe.rollbackTo();
        }
    }

    /** The `a.b.` before the last name of a qualified name. */
    private void skipQualifier() {
        while (at(IDENTIFIER) && b.lookAhead(1) == AxlTokenTypes.of(DOT)) {
            b.advanceLexer();
            b.advanceLexer();
        }
    }

    private PsiBuilder.Marker genericNew() {
        PsiBuilder.Marker m = b.mark();
        skipQualifier();
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
