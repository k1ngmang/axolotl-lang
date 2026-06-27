package org.axl.compiler.parse;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Decl.ClassDecl;
import org.axl.compiler.ast.Decl.DataClass;
import org.axl.compiler.ast.Decl.EnumDecl;
import org.axl.compiler.ast.Decl.Function;
import org.axl.compiler.ast.Decl.Interface;
import org.axl.compiler.ast.Decl.Property;
import org.axl.compiler.ast.Decl.Value;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Expr.Arm;
import org.axl.compiler.ast.Expr.Attempt;
import org.axl.compiler.ast.Expr.Binary;
import org.axl.compiler.ast.Expr.Call;
import org.axl.compiler.ast.Expr.Cond;
import org.axl.compiler.ast.Expr.Conditional;
import org.axl.compiler.ast.Expr.Elvis;
import org.axl.compiler.ast.Expr.GenericNew;
import org.axl.compiler.ast.Expr.Is;
import org.axl.compiler.ast.Expr.Lambda;
import org.axl.compiler.ast.Expr.Literal;
import org.axl.compiler.ast.Expr.ClassLiteral;
import org.axl.compiler.ast.Expr.Member;
import org.axl.compiler.ast.Expr.MethodRef;
import org.axl.compiler.ast.Expr.Name;
import org.axl.compiler.ast.Expr.NotNull;
import org.axl.compiler.ast.Expr.Param;
import org.axl.compiler.ast.Expr.Paren;
import org.axl.compiler.ast.Expr.Pipe;
import org.axl.compiler.ast.Expr.Stage;
import org.axl.compiler.ast.Expr.Throw;
import org.axl.compiler.ast.Expr.Try;
import org.axl.compiler.ast.Expr.Unary;
import org.axl.compiler.ast.Expr.When;
import org.axl.compiler.ast.Import;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.Stmt;
import org.axl.compiler.ast.Stmt.Assign;
import org.axl.compiler.ast.Stmt.Block;
import org.axl.compiler.ast.Stmt.ExprStmt;
import org.axl.compiler.ast.Stmt.For;
import org.axl.compiler.ast.Stmt.If;
import org.axl.compiler.ast.Stmt.Local;
import org.axl.compiler.ast.Stmt.Return;
import org.axl.compiler.ast.Stmt.While;
import org.axl.compiler.ast.TypeRef;
import org.axl.compiler.parse.AxolotlParser.AdditiveContext;
import org.axl.compiler.parse.AxolotlParser.ArgumentsContext;
import org.axl.compiler.parse.AxolotlParser.AssignStatementContext;
import org.axl.compiler.parse.AxolotlParser.AttemptExpressionContext;
import org.axl.compiler.parse.AxolotlParser.BlockContext;
import org.axl.compiler.parse.AxolotlParser.CallSuffixContext;
import org.axl.compiler.parse.AxolotlParser.ClassDeclContext;
import org.axl.compiler.parse.AxolotlParser.ClassMemberContext;
import org.axl.compiler.parse.AxolotlParser.ClassParamContext;
import org.axl.compiler.parse.AxolotlParser.ComparisonContext;
import org.axl.compiler.parse.AxolotlParser.CompilationUnitContext;
import org.axl.compiler.parse.AxolotlParser.ConjunctionContext;
import org.axl.compiler.parse.AxolotlParser.DataClassDeclContext;
import org.axl.compiler.parse.AxolotlParser.DisjunctionContext;
import org.axl.compiler.parse.AxolotlParser.ElvisContext;
import org.axl.compiler.parse.AxolotlParser.EmptyStatementContext;
import org.axl.compiler.parse.AxolotlParser.EnumDeclContext;
import org.axl.compiler.parse.AxolotlParser.EqualityContext;
import org.axl.compiler.parse.AxolotlParser.ExpressionContext;
import org.axl.compiler.parse.AxolotlParser.ExpressionStatementContext;
import org.axl.compiler.parse.AxolotlParser.FieldContext;
import org.axl.compiler.parse.AxolotlParser.ForStatementContext;
import org.axl.compiler.parse.AxolotlParser.FunctionBodyContext;
import org.axl.compiler.parse.AxolotlParser.FunctionDeclContext;
import org.axl.compiler.parse.AxolotlParser.GenericConstructorContext;
import org.axl.compiler.parse.AxolotlParser.IdentifierContext;
import org.axl.compiler.parse.AxolotlParser.IfPrimaryContext;
import org.axl.compiler.parse.AxolotlParser.IfStatementContext;
import org.axl.compiler.parse.AxolotlParser.IfStatementAltContext;
import org.axl.compiler.parse.AxolotlParser.ImportDeclContext;
import org.axl.compiler.parse.AxolotlParser.InterfaceDeclContext;
import org.axl.compiler.parse.AxolotlParser.IsCheckContext;
import org.axl.compiler.parse.AxolotlParser.LambdaContext;
import org.axl.compiler.parse.AxolotlParser.LambdaParamContext;
import org.axl.compiler.parse.AxolotlParser.LambdaPrimaryContext;
import org.axl.compiler.parse.AxolotlParser.LiteralPrimaryContext;
import org.axl.compiler.parse.AxolotlParser.ClassSuffixContext;
import org.axl.compiler.parse.AxolotlParser.MemberSuffixContext;
import org.axl.compiler.parse.AxolotlParser.MethodMemberContext;
import org.axl.compiler.parse.AxolotlParser.MethodRefSuffixContext;
import org.axl.compiler.parse.AxolotlParser.MultiplicativeContext;
import org.axl.compiler.parse.AxolotlParser.NamePrimaryContext;
import org.axl.compiler.parse.AxolotlParser.NotNullSuffixContext;
import org.axl.compiler.parse.AxolotlParser.ParamContext;
import org.axl.compiler.parse.AxolotlParser.ParenPrimaryContext;
import org.axl.compiler.parse.AxolotlParser.PipeStageContext;
import org.axl.compiler.parse.AxolotlParser.PostfixAltContext;
import org.axl.compiler.parse.AxolotlParser.PostfixExpressionContext;
import org.axl.compiler.parse.AxolotlParser.PostfixSuffixContext;
import org.axl.compiler.parse.AxolotlParser.PrefixExpressionContext;
import org.axl.compiler.parse.AxolotlParser.PrimaryContext;
import org.axl.compiler.parse.AxolotlParser.PropertyMemberContext;
import org.axl.compiler.parse.AxolotlParser.QualifiedNameContext;
import org.axl.compiler.parse.AxolotlParser.ReturnStatementContext;
import org.axl.compiler.parse.AxolotlParser.StatementContext;
import org.axl.compiler.parse.AxolotlParser.ThrowExpressionContext;
import org.axl.compiler.parse.AxolotlParser.TopLevelContext;
import org.axl.compiler.parse.AxolotlParser.TryExpressionContext;
import org.axl.compiler.parse.AxolotlParser.TypeContext;
import org.axl.compiler.parse.AxolotlParser.UnaryExpressionContext;
import org.axl.compiler.parse.AxolotlParser.ValDeclContext;
import org.axl.compiler.parse.AxolotlParser.ValStatementContext;
import org.axl.compiler.parse.AxolotlParser.VarStatementContext;
import org.axl.compiler.parse.AxolotlParser.WhenArmContext;
import org.axl.compiler.parse.AxolotlParser.WhenConditionContext;
import org.axl.compiler.parse.AxolotlParser.WhenExpressionContext;
import org.axl.compiler.parse.AxolotlParser.WhenPrimaryContext;
import org.axl.compiler.parse.AxolotlParser.WhileStatementContext;

@NullMarked public final class AstBuilder {
    private AstBuilder() {}
    public static SourceFile build(String path, String className, CompilationUnitContext ctx) {
        final var packageDecl = ctx.packageDecl();
        final @Nullable Pos packagePos = (packageDecl == null ? null : pos(packageDecl.start));
        final @Nullable String packageName = (packageDecl == null ? null : packageDecl.qualifiedName().getText());
        final var imports = new ArrayList<Import>();
        for (final var imp : ctx.importDecl()) {
            imports.add(new Import(pos(imp.start), importKind(imp), imp.qualifiedName().getText()));
        }
        final var decls = new ArrayList<Decl>();
        for (final var top : ctx.topLevel()) {
            final var decl = buildTopLevel(top);
            if (decl != null) {
                decls.add(decl);
            }
        }
        return new SourceFile(path, className, packagePos, packageName, imports, decls);
    }
    public static Pos pos(Token token) {
        return new Pos(token.getLine(), token.getCharPositionInLine() + 1);
    }
    private static Import.Kind importKind(ImportDeclContext imp) {
        final var kind = imp.kind;
        if (kind == null) {
            return Import.Kind.TYPE;
        }
        return (kind.getType() == AxolotlLexer.FN ? Import.Kind.FN : Import.Kind.VAL);
    }
    private static @Nullable Decl buildTopLevel(TopLevelContext top) {
        final var function = top.functionDecl();
        if (function != null) {
            return buildFunction(function);
        }
        final var data = top.dataClassDecl();
        if (data != null) {
            return new DataClass(pos(data.start), pos(data.identifier().start), data.PRIVATE() != null,
                    nameOf(data.identifier()), buildFields(data.field()), buildTypes(data.type()));
        }
        final var enumeration = top.enumDecl();
        if (enumeration != null) {
            return buildEnum(enumeration);
        }
        final var classDecl = top.classDecl();
        if (classDecl != null) {
            return buildClass(classDecl);
        }
        final var iface = top.interfaceDecl();
        if (iface != null) {
            return new Interface(pos(iface.start), pos(iface.identifier().start), iface.PRIVATE() != null,
                    nameOf(iface.identifier()), iface.SEALED() != null, buildFields(iface.field()), buildTypes(iface.type()));
        }
        final var value = top.valDecl();
        if (value != null) {
            return new Value(pos(value.start), pos(value.identifier().start), value.PRIVATE() != null,
                    nameOf(value.identifier()), typeOrNull(value.type()), buildExpr(value.expression()));
        }
        return null;
    }
    private static Decl.EnumDecl buildEnum(EnumDeclContext enumeration) {
        final var constants = enumeration.identifier().stream().skip(1).map(Nulls.nonNull(i -> nameOf(i))).toList();
        return new EnumDecl(pos(enumeration.start), pos(enumeration.identifier(0).start), enumeration.PRIVATE() != null,
                nameOf(enumeration.identifier(0)), constants);
    }
    private static Decl.ClassDecl buildClass(ClassDeclContext ctx) {
        final var parameters = new ArrayList<Property>();
        for (final var p : ctx.classParam()) {
            parameters.add(new Property(pos(p.start), pos(p.identifier().start), false, nameOf(p.identifier()),
                    buildType(p.type()), null, p.VAR() != null));
        }
        final var properties = new ArrayList<Property>();
        final var methods = new ArrayList<Decl.Function>();
        for (final var member : ctx.classMember()) {
            if (member instanceof PropertyMemberContext member$1) {
                properties.add(new Property(pos(member$1.start), pos(member$1.identifier().start), member$1.PRIVATE() != null,
                        nameOf(member$1.identifier()), typeOrNull(member$1.type()), buildExpr(member$1.expression()), member$1.VAR() != null));
            } else if (member instanceof MethodMemberContext member$2) {
                methods.add(buildFunction(member$2.functionDecl()));
            } else {
                throw new IllegalStateException("unknown member " + member.getClass());
            }
        }
        return new ClassDecl(pos(ctx.start), pos(ctx.identifier().start), ctx.PRIVATE() != null, nameOf(ctx.identifier()),
                parameters, properties, methods, buildTypes(ctx.type()));
    }
    private static List<Expr.Param> buildFields(List<FieldContext> contexts) {
        return contexts.stream().map(Nulls.nonNull(f -> new Param(pos(f.start), nameOf(f.identifier()), buildType(f.type())))).toList();
    }
    private static List<TypeRef> buildTypes(List<TypeContext> contexts) {
        return contexts.stream().map(Nulls.nonNull(t -> buildType(t))).toList();
    }
    private static Decl.Function buildFunction(FunctionDeclContext ctx) {
        final var params = new ArrayList<Expr.Param>();
        for (final var p : ctx.param()) {
            params.add(new Param(pos(p.start), nameOf(p.identifier()), buildType(p.type())));
        }
        final var body = ctx.functionBody();
        final var bodyExpr = body.expression();
        final var bodyBlock = body.block();
        final @Nullable Expr exprBody = (bodyExpr == null ? null : buildExpr(bodyExpr));
        final Stmt.@Nullable Block block = (bodyBlock == null ? null : buildBlock(bodyBlock));
        return new Function(pos(ctx.start), pos(ctx.identifier().start), ctx.PRIVATE() != null, nameOf(ctx.identifier()),
                params, typeOrNull(ctx.type()), exprBody, block);
    }
    private static Stmt.Block buildBlock(BlockContext ctx) {
        return new Block(pos(ctx.start), buildStatements(ctx.statement()), pos(ctx.stop));
    }
    private static List<Stmt> buildStatements(List<StatementContext> contexts) {
        final var result = new ArrayList<Stmt>();
        for (final var ctx : contexts) {
            final var stmt = buildStatement(ctx);
            if (stmt != null) {
                result.add(stmt);
            }
        }
        return result;
    }
    private static @Nullable Stmt buildStatement(StatementContext ctx) {
        switch (ctx) {
            case ValStatementContext ctx$1 -> {
                return buildVal(ctx$1);
            }
            case VarStatementContext ctx$2 -> {
                final var decl = ctx$2.varDecl();
                return new Local(pos(decl.start), pos(decl.identifier().start), nameOf(decl.identifier()), typeOrNull(decl.type()),
                        buildExpr(decl.expression()), true);
            }
            case ReturnStatementContext ctx$3 -> {
                return buildReturn(ctx$3);
            }
            case IfStatementAltContext ctx$4 -> {
                return buildIf(ctx$4.ifStatement());
            }
            case WhileStatementContext ctx$5 -> {
                return new While(pos(ctx$5.start), buildExpr(ctx$5.expression()), buildBlock(ctx$5.block()));
            }
            case ForStatementContext ctx$6 -> {
                return new For(pos(ctx$6.start), pos(ctx$6.identifier().start), nameOf(ctx$6.identifier()),
                        buildExpr(ctx$6.expression()), buildBlock(ctx$6.block()));
            }
            case AssignStatementContext ctx$7 -> {
                return buildAssign(ctx$7);
            }
            case ExpressionStatementContext ctx$8 -> {
                return new ExprStmt(pos(ctx$8.start), buildExpr(ctx$8.expression()));
            }
            case EmptyStatementContext ctx$9 -> {
                return null;
            }
            default -> {
                throw new IllegalStateException("unknown statement " + ctx.getClass());
            }
        }
    }
    private static Stmt.Local buildVal(ValStatementContext ctx) {
        final var decl = ctx.valDecl();
        if (decl.PRIVATE() != null) {
            throw new Rejected(pos(ctx.start), "`private` is only allowed on top-level declarations");
        }
        return new Local(pos(decl.start), pos(decl.identifier().start), nameOf(decl.identifier()), typeOrNull(decl.type()),
                buildExpr(decl.expression()), false);
    }
    private static Stmt.Return buildReturn(ReturnStatementContext ctx) {
        final var value = ctx.expression();
        final @Nullable Expr result = (value == null ? null : buildExpr(value));
        return new Return(pos(ctx.start), result);
    }
    private static Stmt.Assign buildAssign(AssignStatementContext ctx) {
        final var target = buildPostfix(ctx.postfixExpression());
        final var assignable = target instanceof Expr.Name target$1 || target instanceof Expr.Member target$2;
        if (!assignable) {
            throw new Rejected(target.pos(), "only a variable or a field can be assigned");
        }
        if (hasSafeStep(target)) {
            throw new Rejected(target.pos(), "cannot assign through `?.`; check for null first: `if (x != null) { x.f = v }`");
        }
        return new Assign(pos(ctx.start), target, ctx.assignOp().getText(), buildExpr(ctx.expression()));
    }
    private static Stmt.If buildIf(IfStatementContext ctx) {
        return new If(pos(ctx.start), buildExpr(ctx.expression()), buildBlock(ctx.block(0)), elseBranch(ctx));
    }
    private static @Nullable Stmt elseBranch(IfStatementContext ctx) {
        final var nested = ctx.ifStatement();
        if (nested != null) {
            return buildIf(nested);
        }
        if (ctx.block().size() > 1) {
            return buildBlock(ctx.block(1));
        }
        return null;
    }
    private static Expr buildExpr(ExpressionContext ctx) {
        final var source = buildDisjunction(ctx.disjunction());
        if (ctx.pipeStage().isEmpty()) {
            return source;
        }
        final var stages = ctx.pipeStage().stream().map(Nulls.nonNull(stage -> buildStage(stage))).toList();
        return new Pipe(source.pos(), source, stages);
    }
    private static Expr.Stage buildStage(PipeStageContext stage) {
        final var lambdaCtx = stage.lambda();
        final Expr.@Nullable Lambda lambda = (lambdaCtx == null ? null : buildLambda(lambdaCtx));
        return new Stage(pos(stage.start), nameOf(stage.identifier()), buildArguments(stage.arguments()), lambda);
    }
    private static Expr buildDisjunction(DisjunctionContext ctx) {
        final var operands = ctx.conjunction().stream().map(Nulls.nonNull(c -> buildConjunction(c))).toList();
        return leftFold(ctx, operands);
    }
    private static Expr buildConjunction(ConjunctionContext ctx) {
        final var operands = ctx.equality().stream().map(Nulls.nonNull(c -> buildEquality(c))).toList();
        return leftFold(ctx, operands);
    }
    private static Expr buildEquality(EqualityContext ctx) {
        final var operands = ctx.comparison().stream().map(Nulls.nonNull(c -> buildComparison(c))).toList();
        return leftFold(ctx, operands);
    }
    private static Expr buildComparison(ComparisonContext ctx) {
        final var operands = ctx.isCheck().stream().map(Nulls.nonNull(c -> buildIsCheck(c))).toList();
        return leftFold(ctx, operands);
    }
    private static Expr buildIsCheck(IsCheckContext ctx) {
        final var target = buildElvis(ctx.elvis());
        if (ctx.IS() == null) {
            return target;
        }
        final var type = ctx.qualifiedName();
        return new Is(target.pos(), target, new TypeRef(pos(type.start), type.getText(), List.of(), false), ctx.BANG() != null);
    }
    private static Expr buildElvis(ElvisContext ctx) {
        final var operands = ctx.additive().stream().map(Nulls.nonNull(a -> buildAdditive(a))).toList();
        var result = operands.getLast();
        var i = operands.size() - 2;
        while (i >= 0) {
            result = new Elvis(operands.get(i).pos(), operands.get(i), result);
            i -= 1;
        }
        return result;
    }
    private static Expr buildAdditive(AdditiveContext ctx) {
        final var operands = ctx.multiplicative().stream().map(Nulls.nonNull(m -> buildMultiplicative(m))).toList();
        return leftFold(ctx, operands);
    }
    private static Expr buildMultiplicative(MultiplicativeContext ctx) {
        final var operands = ctx.prefixExpression().stream().map(Nulls.nonNull(p -> buildPrefix(p))).toList();
        return leftFold(ctx, operands);
    }
    private static Expr leftFold(ParserRuleContext ctx, List<Expr> operands) {
        final var operators = new ArrayList<String>();
        for (final var child : ctx.children) {
            if (child instanceof TerminalNode child$1) {
                operators.add(child$1.getText());
            }
        }
        var result = operands.getFirst();
        var i = 1;
        while (i < operands.size()) {
            result = new Binary(result.pos(), operators.get(i - 1), result, operands.get(i));
            i += 1;
        }
        return result;
    }
    private static Expr buildPrefix(PrefixExpressionContext ctx) {
        switch (ctx) {
            case UnaryExpressionContext ctx$1 -> {
                return buildUnary(ctx$1);
            }
            case TryExpressionContext ctx$2 -> {
                return new Try(pos(ctx$2.start), buildPrefix(ctx$2.prefixExpression()));
            }
            case ThrowExpressionContext ctx$3 -> {
                return new Throw(pos(ctx$3.start), buildPrefix(ctx$3.prefixExpression()));
            }
            case AttemptExpressionContext ctx$4 -> {
                return buildAttempt(ctx$4);
            }
            case PostfixAltContext ctx$5 -> {
                return buildPostfix(ctx$5.postfixExpression());
            }
            default -> {
                throw new IllegalStateException("unknown expression " + ctx.getClass());
            }
        }
    }
    private static Expr buildUnary(UnaryExpressionContext ctx) {
        if (ctx.BANGBANG() != null) {
            return new Unary(pos(ctx.start), "!", new Unary(pos(ctx.start), "!", buildPrefix(ctx.prefixExpression())));
        }
        return new Unary(pos(ctx.start), ctx.start.getText(), buildPrefix(ctx.prefixExpression()));
    }
    private static Expr buildAttempt(AttemptExpressionContext ctx) {
        final var name = ctx.identifier();
        final @Nullable String errorName = (name == null ? null : nameOf(name));
        final @Nullable Pos errorPos = (name == null ? null : pos(name.start));
        return new Attempt(pos(ctx.start), buildExpr(ctx.expression(0)), errorName, errorPos, buildExpr(ctx.expression(1)));
    }
    private static boolean hasSafeStep(Expr e) {
        switch (e) {
            case Expr.Member e$1 -> {
                return e$1.safe() || hasSafeStep(e$1.target());
            }
            case Expr.Call e$2 -> {
                final var target = e$2.target();
                return e$2.safe() || (target != null && hasSafeStep(target));
            }
            default -> {
                return false;
            }
        }
    }
    private static Expr buildPostfix(PostfixExpressionContext ctx) {
        var current = buildPrimary(ctx.primary());
        final var suffixes = ctx.postfixSuffix();
        var i = 0;
        while (i < suffixes.size()) {
            final var suffix = suffixes.get(i);
            if (suffix instanceof MemberSuffixContext suffix$1) {
                final var call = callAfter(suffixes, i);
                final var member = nameOf(suffix$1.identifier());
                final var memberPos = pos(suffix$1.identifier().start);
                final var safe = suffix$1.SAFE_DOT() != null;
                if (call != null) {
                    current = new Call(memberPos, current, member, buildArguments(call.arguments()), safe);
                    i += 1;
                } else {
                    current = new Member(memberPos, current, member, safe);
                }
            } else if (suffix instanceof CallSuffixContext suffix$2) {
                current = namedCall(current, suffix$2);
            } else if (suffix instanceof MethodRefSuffixContext suffix$3) {
                current = new MethodRef(pos(suffix$3.start), current, nameOf(suffix$3.identifier()));
            } else if (suffix instanceof NotNullSuffixContext suffix$4) {
                current = new NotNull(pos(suffix$4.start), current);
            } else if (suffix instanceof ClassSuffixContext suffix$5) {
                current = new ClassLiteral(pos(suffix$5.CLASS().getSymbol()), current);
            } else {
                throw new IllegalStateException("unknown suffix " + suffix.getClass());
            }
            i += 1;
        }
        return current;
    }
    private static @Nullable CallSuffixContext callAfter(List<PostfixSuffixContext> suffixes, int index) {
        if (index + 1 >= suffixes.size()) {
            return null;
        }
        final var next = suffixes.get(index + 1);
        if (next instanceof CallSuffixContext next$1) {
            return next$1;
        }
        return null;
    }
    private static Expr namedCall(Expr target, CallSuffixContext call) {
        if (!(target instanceof Expr.Name target$1)) {
            throw new Rejected(pos(call.start), "only a named function can be called");
        }
        return new Call(target$1.pos(), null, target$1.name(), buildArguments(call.arguments()), false);
    }
    private static List<Expr> buildArguments(@Nullable ArgumentsContext ctx) {
        if (ctx == null) {
            return List.of();
        }
        return ctx.expression().stream().map(Nulls.nonNull(e -> buildExpr(e))).toList();
    }
    private static Expr buildPrimary(PrimaryContext ctx) {
        switch (ctx) {
            case LiteralPrimaryContext ctx$1 -> {
                return new Literal(pos(ctx$1.start), ctx$1.getText());
            }
            case NamePrimaryContext ctx$2 -> {
                return new Name(pos(ctx$2.start), nameOf(ctx$2.identifier()));
            }
            case ParenPrimaryContext ctx$3 -> {
                return new Paren(pos(ctx$3.start), buildExpr(ctx$3.expression()));
            }
            case LambdaPrimaryContext ctx$4 -> {
                return buildLambda(ctx$4.lambda());
            }
            case WhenPrimaryContext ctx$5 -> {
                return buildWhen(ctx$5.whenExpression());
            }
            case IfPrimaryContext ctx$6 -> {
                return buildConditional(ctx$6);
            }
            case GenericConstructorContext ctx$7 -> {
                return buildGenericNew(ctx$7);
            }
            default -> {
                throw new IllegalStateException("unknown primary " + ctx.getClass());
            }
        }
    }
    private static Expr buildConditional(IfPrimaryContext ctx) {
        final var thenExpr = buildExpr(ctx.expression(1));
        final var otherwise = buildExpr(ctx.expression(2));
        for (final var branch : List.of(thenExpr, otherwise)) {
            if (branch instanceof Expr.Lambda branch$1) {
                throw new Rejected(branch$1.pos(), "an `if` that gives a value takes expressions after `if (...)` and `else`, " +
                        "not braces; put a lambda in parentheses");
            }
        }
        return new Conditional(pos(ctx.start), buildExpr(ctx.expression(0)), thenExpr, otherwise);
    }
    private static Expr buildGenericNew(GenericConstructorContext ctx) {
        final var typeArgs = ctx.typeArgs().type().stream().map(Nulls.nonNull(t -> buildType(t))).toList();
        final var path = ctx.qualifiedName().identifier().stream().map(Nulls.nonNull(i -> nameOf(i))).toList();
        return new GenericNew(pos(ctx.start), String.join(".", path), typeArgs, buildArguments(ctx.arguments()));
    }
    private static Expr buildWhen(WhenExpressionContext ctx) {
        final var arms = new ArrayList<Expr.Arm>();
        for (final var arm : ctx.whenArm()) {
            arms.add(buildArm(arm));
        }
        final var binding = ctx.identifier();
        final @Nullable String bindingName = (binding == null ? null : nameOf(binding));
        final @Nullable Pos bindingPos = (binding == null ? null : pos(binding.start));
        return new When(pos(ctx.start), bindingName, bindingPos, buildExpr(ctx.expression()), arms, pos(ctx.stop));
    }
    private static Expr.Arm buildArm(WhenArmContext arm) {
        final var conditions = new ArrayList<Expr.Cond>();
        for (final var condition : arm.whenCondition()) {
            conditions.add(buildCondition(condition));
        }
        final var blockCtx = arm.block();
        final List<Stmt> body = (blockCtx != null ? buildStatements(blockCtx.statement()) :
                List.of(new ExprStmt(pos(arm.expression().start), buildExpr(arm.expression()))));
        return new Arm(pos(arm.start), conditions, body, blockCtx != null);
    }
    private static Expr.Cond buildCondition(WhenConditionContext condition) {
        if (condition.IS() != null) {
            final var type = condition.qualifiedName();
            return new Cond(pos(condition.start), new TypeRef(pos(type.start), type.getText(), List.of(), false), null);
        }
        return new Cond(pos(condition.start), null, new Literal(pos(condition.start), condition.getText()));
    }
    private static Expr.Lambda buildLambda(LambdaContext ctx) {
        final var params = new ArrayList<Expr.Param>();
        for (final var p : ctx.lambdaParam()) {
            params.add(new Param(pos(p.start), nameOf(p.identifier()), typeOrNull(p.type())));
        }
        return new Lambda(pos(ctx.start), params, ctx.ARROW() != null, buildStatements(ctx.statement()), pos(ctx.stop));
    }
    private static @Nullable TypeRef typeOrNull(@Nullable TypeContext ctx) {
        if (ctx == null) {
            return null;
        }
        return buildType(ctx);
    }
    private static TypeRef buildType(TypeContext ctx) {
        final var args = ctx.type().stream().map(Nulls.nonNull(t -> buildType(t))).toList();
        return new TypeRef(pos(ctx.start), ctx.qualifiedName().getText(), args, ctx.QUESTION() != null);
    }
    private static String nameOf(IdentifierContext ctx) {
        return ctx.getText();
    }
}
