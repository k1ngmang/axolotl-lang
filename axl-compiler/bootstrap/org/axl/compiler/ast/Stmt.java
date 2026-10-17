package org.axl.compiler.ast;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public sealed interface Stmt {
    Pos pos();
    public record Local(
            Pos pos,
            Pos namePos,
            String name,
            @Nullable TypeRef type,
            Expr init,
            boolean mutable) implements Stmt {
        public Local {
            Nulls.param(pos, "pos");
            Nulls.param(namePos, "namePos");
            Nulls.param(name, "name");
            Nulls.param(init, "init");
        }
    }
    public record Assign(Pos pos, Expr target, String op, Expr value) implements Stmt {
        public Assign {
            Nulls.param(pos, "pos");
            Nulls.param(target, "target");
            Nulls.param(op, "op");
            Nulls.param(value, "value");
        }
    }
    public record Return(Pos pos, @Nullable Expr value) implements Stmt {
        public Return {
            Nulls.param(pos, "pos");
        }
    }
    public record Use(
            Pos pos,
            @Nullable Pos namePos,
            @Nullable String name,
            @Nullable TypeRef type,
            Expr resource,
            Block body,
            @Nullable String errorName,
            @Nullable Pos errorNamePos,
            @Nullable TypeRef errorType,
            @Nullable Expr handler) implements Stmt {
        public Use {
            Nulls.param(pos, "pos");
            Nulls.param(resource, "resource");
            Nulls.param(body, "body");
        }
    }
    public record Jump(Pos pos, boolean isBreak) implements Stmt {
        public Jump {
            Nulls.param(pos, "pos");
        }
    }
    public record AttemptExit(
            Pos pos,
            Expr body,
            @Nullable String errorName,
            @Nullable Pos errorNamePos,
            @Nullable TypeRef errorType,
            Stmt exit) implements Stmt {
        public AttemptExit {
            Nulls.param(pos, "pos");
            Nulls.param(body, "body");
            Nulls.param(exit, "exit");
        }
    }
    public record If(Pos pos, Expr condition, Block then, @Nullable Stmt elseBranch) implements Stmt {
        public If {
            Nulls.param(pos, "pos");
            Nulls.param(condition, "condition");
            Nulls.param(then, "then");
        }
    }
    public record While(Pos pos, Expr condition, Block body) implements Stmt {
        public While {
            Nulls.param(pos, "pos");
            Nulls.param(condition, "condition");
            Nulls.param(body, "body");
        }
    }
    public record For(Pos pos, Pos namePos, String name, Expr iterable, Block body) implements Stmt {
        public For {
            Nulls.param(pos, "pos");
            Nulls.param(namePos, "namePos");
            Nulls.param(name, "name");
            Nulls.param(iterable, "iterable");
            Nulls.param(body, "body");
        }
    }
    public record Count(
            Pos pos,
            Pos namePos,
            String name,
            Expr from,
            String kind,
            Expr to,
            @Nullable Expr step,
            Block body) implements Stmt {
        public Count {
            Nulls.param(pos, "pos");
            Nulls.param(namePos, "namePos");
            Nulls.param(name, "name");
            Nulls.param(from, "from");
            Nulls.param(kind, "kind");
            Nulls.param(to, "to");
            Nulls.param(body, "body");
        }
    }
    public record ExprStmt(Pos pos, Expr expr) implements Stmt {
        public ExprStmt {
            Nulls.param(pos, "pos");
            Nulls.param(expr, "expr");
        }
    }
    public record Block(Pos pos, List<Stmt> statements, Pos end) implements Stmt {
        public Block {
            Nulls.param(pos, "pos");
            Nulls.param(statements, "statements");
            Nulls.param(end, "end");
        }
    }
}
