package org.axl.compiler.parse;

import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Import;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.Stmt;
import org.axl.compiler.ast.TypeRef;
import org.axl.compiler.parse.AxolotlParser.*;

/** Turns an ANTLR parse tree without syntax errors into the AST. */
final class AstBuilder {

    /** A construct the grammar accepts but the language does not, reported as a syntax error. */
    static final class Rejected extends RuntimeException {
        final Pos pos;

        Rejected(Pos pos, String message) {
            super(message, null, false, false);
            this.pos = pos;
        }
    }

    SourceFile build(String path, String className, CompilationUnitContext ctx) {
        Pos packagePos = null;
        String packageName = null;
        if (ctx.packageDecl() != null) {
            packagePos = pos(ctx.packageDecl().start);
            packageName = ctx.packageDecl().qualifiedName().getText();
        }
        List<Import> imports = new ArrayList<>();
        for (ImportDeclContext imp : ctx.importDecl()) {
            Import.Kind kind = imp.kind == null ? Import.Kind.TYPE
                    : imp.kind.getType() == AxolotlLexer.FN ? Import.Kind.FN : Import.Kind.VAL;
            imports.add(new Import(pos(imp.start), kind, imp.qualifiedName().getText()));
        }
        List<Decl> decls = new ArrayList<>();
        for (TopLevelContext top : ctx.topLevel()) {
            if (top.functionDecl() != null) {
                decls.add(function(top.functionDecl()));
            } else if (top.valDecl() != null) {
                ValDeclContext val = top.valDecl();
                decls.add(new Decl.Value(pos(val.start), pos(val.identifier().start), val.PRIVATE() != null, name(val.identifier()),
                        typeOrNull(val.type()), expr(val.expression())));
            }
        }
        return new SourceFile(path, className, packagePos, packageName, imports, decls);
    }

    private Decl.Function function(FunctionDeclContext ctx) {
        List<Expr.Param> params = new ArrayList<>();
        for (ParamContext p : ctx.param()) {
            params.add(new Expr.Param(pos(p.start), name(p.identifier()), type(p.type())));
        }
        FunctionBodyContext body = ctx.functionBody();
        Expr exprBody = body.expression() == null ? null : expr(body.expression());
        Stmt.Block block = body.block() == null ? null : block(body.block());
        return new Decl.Function(pos(ctx.start), pos(ctx.identifier().start), ctx.PRIVATE() != null, name(ctx.identifier()), params,
                typeOrNull(ctx.type()), exprBody, block);
    }

    private Stmt.Block block(BlockContext ctx) {
        return new Stmt.Block(pos(ctx.start), statements(ctx.statement()), pos(ctx.stop));
    }

    private List<Stmt> statements(List<StatementContext> contexts) {
        List<Stmt> result = new ArrayList<>();
        for (StatementContext ctx : contexts) {
            Stmt stmt = statement(ctx);
            if (stmt != null) {
                result.add(stmt);
            }
        }
        return result;
    }

    private Stmt statement(StatementContext ctx) {
        return switch (ctx) {
            case ValStatementContext s -> {
                if (s.valDecl().PRIVATE() != null) {
                    throw new Rejected(pos(s.start), "`private` is only allowed on top-level declarations");
                }
                ValDeclContext val = s.valDecl();
                yield new Stmt.Local(pos(val.start), pos(val.identifier().start), name(val.identifier()), typeOrNull(val.type()),
                        expr(val.expression()), false);
            }
            case VarStatementContext s -> {
                VarDeclContext var = s.varDecl();
                yield new Stmt.Local(pos(var.start), pos(var.identifier().start), name(var.identifier()), typeOrNull(var.type()),
                        expr(var.expression()), true);
            }
            case ReturnStatementContext s ->
                    new Stmt.Return(pos(s.start), s.expression() == null ? null : expr(s.expression()));
            case IfStatementAltContext s -> ifStatement(s.ifStatement());
            case WhileStatementContext s ->
                    new Stmt.While(pos(s.start), expr(s.expression()), block(s.block()));
            case ForStatementContext s -> new Stmt.For(pos(s.start), pos(s.identifier().start), name(s.identifier()),
                    expr(s.expression()), block(s.block()));
            case AssignStatementContext s -> {
                Expr target = postfix(s.postfixExpression());
                boolean assignable = target instanceof Expr.Name || target instanceof Expr.Member;
                if (!assignable) {
                    throw new Rejected(target.pos(), "only a variable or a field can be assigned");
                }
                if (hasSafeStep(target)) {
                    throw new Rejected(target.pos(), "cannot assign through `?.`; check for null first: `if (x != null) { x.f = v }`");
                }
                yield new Stmt.Assign(pos(s.start), target, s.assignOp().getText(), expr(s.expression()));
            }
            case ExpressionStatementContext s -> new Stmt.ExprStmt(pos(s.start), expr(s.expression()));
            case EmptyStatementContext s -> null;
            default -> throw new IllegalStateException("unknown statement " + ctx.getClass());
        };
    }

    private Stmt.If ifStatement(IfStatementContext ctx) {
        Stmt elseBranch = null;
        if (ctx.ifStatement() != null) {
            elseBranch = ifStatement(ctx.ifStatement());
        } else if (ctx.block().size() > 1) {
            elseBranch = block(ctx.block(1));
        }
        return new Stmt.If(pos(ctx.start), expr(ctx.expression()), block(ctx.block(0)), elseBranch);
    }

    private Expr expr(ExpressionContext ctx) {
        Expr source = disjunction(ctx.disjunction());
        if (ctx.pipeStage().isEmpty()) {
            return source;
        }
        List<Expr.Stage> stages = new ArrayList<>();
        for (PipeStageContext stage : ctx.pipeStage()) {
            Expr.Lambda lambda = stage.lambda() == null ? null : (Expr.Lambda) lambda(stage.lambda());
            stages.add(new Expr.Stage(pos(stage.start), name(stage.identifier()), arguments(stage.arguments()), lambda));
        }
        return new Expr.Pipe(source.pos(), source, stages);
    }

    private Expr disjunction(DisjunctionContext ctx) {
        return leftFold(ctx, ctx.conjunction().stream().map(this::conjunction).toList());
    }

    private Expr conjunction(ConjunctionContext ctx) {
        return leftFold(ctx, ctx.equality().stream().map(this::equality).toList());
    }

    private Expr equality(EqualityContext ctx) {
        return leftFold(ctx, ctx.comparison().stream().map(this::comparison).toList());
    }

    private Expr comparison(ComparisonContext ctx) {
        return leftFold(ctx, ctx.elvis().stream().map(this::elvis).toList());
    }

    private Expr elvis(ElvisContext ctx) {
        List<Expr> operands = ctx.additive().stream().map(this::additive).toList();
        // Right-nested so that `a ?: b ?: c` evaluates c only when both a and b are null.
        Expr result = operands.getLast();
        for (int i = operands.size() - 2; i >= 0; i--) {
            result = new Expr.Elvis(operands.get(i).pos(), operands.get(i), result);
        }
        return result;
    }

    private Expr additive(AdditiveContext ctx) {
        return leftFold(ctx, ctx.multiplicative().stream().map(this::multiplicative).toList());
    }

    private Expr multiplicative(MultiplicativeContext ctx) {
        return leftFold(ctx, ctx.prefixExpression().stream().map(this::prefix).toList());
    }

    /** Operators are the terminal children between the operand subtrees. */
    private Expr leftFold(ParserRuleContext ctx, List<Expr> operands) {
        List<String> operators = new ArrayList<>();
        for (var child : ctx.children) {
            if (child instanceof TerminalNode t) {
                operators.add(t.getText());
            }
        }
        Expr result = operands.getFirst();
        for (int i = 1; i < operands.size(); i++) {
            result = new Expr.Binary(result.pos(), operators.get(i - 1), result, operands.get(i));
        }
        return result;
    }

    private Expr prefix(PrefixExpressionContext ctx) {
        return switch (ctx) {
            case UnaryExpressionContext u when u.BANGBANG() != null -> new Expr.Unary(pos(u.start), "!",
                    new Expr.Unary(pos(u.start), "!", prefix(u.prefixExpression())));
            case UnaryExpressionContext u -> new Expr.Unary(pos(u.start), u.start.getText(), prefix(u.prefixExpression()));
            case TryExpressionContext t -> new Expr.Try(pos(t.start), prefix(t.prefixExpression()));
            case AttemptExpressionContext a -> new Expr.Attempt(pos(a.start), expr(a.expression(0)),
                    a.identifier() == null ? null : name(a.identifier()),
                    a.identifier() == null ? null : pos(a.identifier().start), expr(a.expression(1)));
            case PostfixAltContext p -> postfix(p.postfixExpression());
            default -> throw new IllegalStateException("unknown expression " + ctx.getClass());
        };
    }

    private static boolean hasSafeStep(Expr expr) {
        return switch (expr) {
            case Expr.Member m -> m.safe() || hasSafeStep(m.target());
            case Expr.Call c -> c.safe() || c.target() != null && hasSafeStep(c.target());
            default -> false;
        };
    }

    private Expr postfix(PostfixExpressionContext ctx) {
        Expr current = primary(ctx.primary());
        List<PostfixSuffixContext> suffixes = ctx.postfixSuffix();
        for (int i = 0; i < suffixes.size(); i++) {
            PostfixSuffixContext suffix = suffixes.get(i);
            switch (suffix) {
                case MemberSuffixContext m -> {
                    boolean safe = m.SAFE_DOT() != null;
                    String member = name(m.identifier());
                    Pos memberPos = pos(m.identifier().start);
                    if (i + 1 < suffixes.size() && suffixes.get(i + 1) instanceof CallSuffixContext call) {
                        current = new Expr.Call(memberPos, current, member, arguments(call.arguments()), safe);
                        i++;
                    } else {
                        current = new Expr.Member(memberPos, current, member, safe);
                    }
                }
                case CallSuffixContext call -> {
                    if (!(current instanceof Expr.Name n)) {
                        throw new Rejected(pos(call.start), "only a named function can be called");
                    }
                    current = new Expr.Call(n.pos(), null, n.name(), arguments(call.arguments()), false);
                }
                case MethodRefSuffixContext r -> current = new Expr.MethodRef(pos(r.start), current, name(r.identifier()));
                case NotNullSuffixContext n -> current = new Expr.NotNull(pos(n.start), current);
                default -> throw new IllegalStateException("unknown suffix " + suffix.getClass());
            }
        }
        return current;
    }

    private List<Expr> arguments(ArgumentsContext ctx) {
        return ctx == null ? List.of() : ctx.expression().stream().map(this::expr).toList();
    }

    private Expr primary(PrimaryContext ctx) {
        return switch (ctx) {
            case LiteralPrimaryContext l -> new Expr.Literal(pos(l.start), l.getText());
            case NamePrimaryContext n -> new Expr.Name(pos(n.start), name(n.identifier()));
            case ParenPrimaryContext p -> new Expr.Paren(pos(p.start), expr(p.expression()));
            case LambdaPrimaryContext l -> lambda(l.lambda());
            case GenericConstructorContext g -> new Expr.GenericNew(pos(g.start), name(g.identifier()),
                    g.typeArgs().type().stream().map(this::type).toList(), arguments(g.arguments()));
            default -> throw new IllegalStateException("unknown primary " + ctx.getClass());
        };
    }

    private Expr lambda(LambdaContext ctx) {
        List<Expr.Param> params = new ArrayList<>();
        for (LambdaParamContext p : ctx.lambdaParam()) {
            params.add(new Expr.Param(pos(p.start), name(p.identifier()), typeOrNull(p.type())));
        }
        return new Expr.Lambda(pos(ctx.start), params, ctx.ARROW() != null, statements(ctx.statement()), pos(ctx.stop));
    }

    private TypeRef typeOrNull(TypeContext ctx) {
        return ctx == null ? null : type(ctx);
    }

    private TypeRef type(TypeContext ctx) {
        return new TypeRef(pos(ctx.start), ctx.qualifiedName().getText(),
                ctx.type().stream().map(this::type).toList(), ctx.QUESTION() != null);
    }

    private static String name(IdentifierContext ctx) {
        return ctx.getText();
    }

    static Pos pos(Token token) {
        return new Pos(token.getLine(), token.getCharPositionInLine() + 1);
    }
}
