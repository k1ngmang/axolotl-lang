package org.axl.javac;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

/**
 * Strict null mode: a value that comes from Java code without nullness annotations may be null, as
 * if it had the type `T?`. Using it where null is not accepted is an error: dereferencing or
 * unboxing it, passing it to an Axolotl parameter, returning it from an Axolotl function, or storing
 * it where the type is written without `?`. Checks only generated classes; null checks, early exits
 * and `&&`/`||` narrow locals as NullAway does.
 */
final class StrictNullChecker implements TaskListener {

    static final String PREFIX = "strict null: ";

    private static final String FIX = "use `?.`, `?:`, a null check or `!!`";
    private static final Set<String> BOXES = Set.of("java.lang.Integer", "java.lang.Long", "java.lang.Double",
            "java.lang.Boolean", "java.lang.Character", "java.lang.Short", "java.lang.Byte", "java.lang.Float");
    // Enough for the nullable locals of any loop body to settle.
    private static final int MAX_LOOP_PASSES = 8;

    private final Trees trees;

    StrictNullChecker(JavacTask task) {
        this.trees = Trees.instance(task);
    }

    @Override
    public void finished(TaskEvent event) {
        if (event.getKind() != TaskEvent.Kind.ANALYZE || event.getCompilationUnit() == null
                || !isGenerated(event.getCompilationUnit()) || event.getTypeElement() == null) {
            return;
        }
        TreePath path = trees.getPath(event.getTypeElement());
        if (path != null) {
            new Scanner(event.getCompilationUnit()).scan(path, null);
        }
    }

    /** Generated files have their .axl line map next to them. */
    private static boolean isGenerated(CompilationUnitTree unit) {
        var uri = unit.getSourceFile().toUri();
        if (!"file".equals(uri.getScheme())) {
            return false;
        }
        Path java = Path.of(uri);
        String name = java.getFileName().toString();
        return name.endsWith(".java") && Files.exists(java.resolveSibling(name.substring(0, name.length() - 5) + ".axlmap"));
    }

    private final class Scanner extends TreePathScanner<Void, Void> {

        private final CompilationUnitTree unit;
        private final String source;
        // Locals that hold a value from unannotated Java code and are not checked yet.
        private Set<Element> nullable = new HashSet<>();
        // Locals declared with `var`, whose nullness follows their value.
        private final Set<Element> inferredLocals = new HashSet<>();
        // Off while a loop body is scanned only to see which locals it leaves nullable.
        private boolean reporting = true;

        Scanner(CompilationUnitTree unit) {
            this.unit = unit;
            try {
                this.source = unit.getSourceFile().getCharContent(true).toString();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Void visitVariable(VariableTree variable, Void unused) {
            super.visitVariable(variable, unused);
            Element element = trees.getElement(getCurrentPath());
            ExpressionTree init = variable.getInitializer();
            if (element == null) {
                return null;
            }
            if (element.getKind() == ElementKind.LOCAL_VARIABLE && isVar(variable)) {
                inferredLocals.add(element);
                if (init != null && mayBeNull(init)) {
                    nullable.add(element);
                }
            } else if (init != null && element.getKind() != ElementKind.PARAMETER) {
                checkStore(init, element, "`" + element.getSimpleName() + "` is not nullable");
            }
            return null;
        }

        @Override
        public Void visitAssignment(AssignmentTree assignment, Void unused) {
            super.visitAssignment(assignment, unused);
            Element target = element(assignment.getVariable());
            if (target == null) {
                return null;
            }
            if (inferredLocals.contains(target)) {
                if (mayBeNull(assignment.getExpression())) {
                    nullable.add(target);
                } else {
                    nullable.remove(target);
                }
            } else if (target.getKind() == ElementKind.LOCAL_VARIABLE || isAxolotlCode(target)) {
                checkStore(assignment.getExpression(), target, "`" + target.getSimpleName() + "` is not nullable");
            }
            return null;
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree select, Void unused) {
            ExpressionTree target = select.getExpression();
            Element targetElement = element(target);
            boolean isTypeOrPackage = targetElement != null
                    && (targetElement.getKind().isClass() || targetElement.getKind().isInterface()
                    || targetElement.getKind() == ElementKind.PACKAGE);
            if (!isTypeOrPackage && mayBeNull(target)) {
                report(select, origin(target) + "; " + FIX);
            }
            return super.visitMemberSelect(select, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
            super.visitMethodInvocation(call, unused);
            if (element(call) instanceof ExecutableElement method) {
                checkArguments(method, call.getArguments());
            }
            return null;
        }

        @Override
        public Void visitNewClass(NewClassTree newClass, Void unused) {
            super.visitNewClass(newClass, unused);
            if (trees.getElement(getCurrentPath()) instanceof ExecutableElement constructor) {
                checkArguments(constructor, newClass.getArguments());
            }
            return null;
        }

        @Override
        public Void visitReturn(ReturnTree ret, Void unused) {
            super.visitReturn(ret, unused);
            if (ret.getExpression() == null) {
                return null;
            }
            for (TreePath p = getCurrentPath().getParentPath(); p != null; p = p.getParentPath()) {
                if (p.getLeaf() instanceof LambdaExpressionTree) {
                    return null;
                }
                if (p.getLeaf() instanceof MethodTree && trees.getElement(p) instanceof ExecutableElement method) {
                    checkValue(ret.getExpression(), method.getReturnType(), method, "the function does not return null");
                    return null;
                }
            }
            return null;
        }

        @Override
        public Void visitUnary(UnaryTree unary, Void unused) {
            super.visitUnary(unary, unused);
            checkUnboxing(unary.getExpression());
            return null;
        }

        @Override
        public Void visitIf(IfTree ifTree, Void unused) {
            scan(ifTree.getCondition(), null);
            Set<Element> before = new HashSet<>(nullable);
            nullable.removeAll(checked(ifTree.getCondition(), true));
            scan(ifTree.getThenStatement(), null);
            Set<Element> afterThen = nullable;
            nullable = new HashSet<>(before);
            nullable.removeAll(checked(ifTree.getCondition(), false));
            if (ifTree.getElseStatement() != null) {
                scan(ifTree.getElseStatement(), null);
            }
            Set<Element> afterElse = nullable;
            // A branch that always leaves contributes nothing to what follows.
            if (exits(ifTree.getThenStatement())) {
                nullable = afterElse;
            } else if (ifTree.getElseStatement() != null && exits(ifTree.getElseStatement())) {
                nullable = afterThen;
            } else {
                afterThen.addAll(afterElse);
                nullable = afterThen;
            }
            return null;
        }

        @Override
        public Void visitWhileLoop(WhileLoopTree loop, Void unused) {
            scan(loop.getCondition(), null);
            // What the body leaves nullable reaches the next iteration, so repeat until nothing changes.
            Set<Element> entry = new HashSet<>(nullable);
            boolean wasReporting = reporting;
            reporting = false;
            for (int i = 0; i < MAX_LOOP_PASSES; i++) {
                nullable = new HashSet<>(entry);
                nullable.removeAll(checked(loop.getCondition(), true));
                scan(loop.getStatement(), null);
                if (entry.containsAll(nullable)) {
                    break;
                }
                entry.addAll(nullable);
            }
            reporting = wasReporting;
            nullable = new HashSet<>(entry);
            nullable.removeAll(checked(loop.getCondition(), true));
            scan(loop.getStatement(), null);
            entry.addAll(nullable);
            entry.removeAll(checked(loop.getCondition(), false));
            nullable = entry;
            return null;
        }

        @Override
        public Void visitBinary(BinaryTree binary, Void unused) {
            boolean and = binary.getKind() == Tree.Kind.CONDITIONAL_AND;
            if (!and && binary.getKind() != Tree.Kind.CONDITIONAL_OR) {
                super.visitBinary(binary, unused);
                boolean nullCheck = binary.getKind() == Tree.Kind.EQUAL_TO || binary.getKind() == Tree.Kind.NOT_EQUAL_TO;
                boolean concatenation = isString(binary.getLeftOperand()) || isString(binary.getRightOperand());
                if (!nullCheck && !concatenation) {
                    checkUnboxing(binary.getLeftOperand());
                    checkUnboxing(binary.getRightOperand());
                }
                return null;
            }
            scan(binary.getLeftOperand(), null);
            Set<Element> before = new HashSet<>(nullable);
            nullable.removeAll(checked(binary.getLeftOperand(), and));
            scan(binary.getRightOperand(), null);
            nullable = before;
            return null;
        }

        @Override
        public Void visitConditionalExpression(ConditionalExpressionTree conditional, Void unused) {
            scan(conditional.getCondition(), null);
            Set<Element> before = new HashSet<>(nullable);
            nullable.removeAll(checked(conditional.getCondition(), true));
            scan(conditional.getTrueExpression(), null);
            nullable = new HashSet<>(before);
            nullable.removeAll(checked(conditional.getCondition(), false));
            scan(conditional.getFalseExpression(), null);
            nullable = before;
            return null;
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree lambda, Void unused) {
            Set<Element> before = new HashSet<>(nullable);
            if (calledFromUnannotatedJava(getCurrentPath())) {
                for (VariableTree param : lambda.getParameters()) {
                    Element element = trees.getElement(new TreePath(getCurrentPath(), param));
                    if (element != null) {
                        nullable.add(element);
                        inferredLocals.add(element);
                    }
                }
            }
            super.visitLambdaExpression(lambda, unused);
            nullable = before;
            return null;
        }

        /** A lambda passed to a Java method that says nothing about null may get null arguments. */
        private boolean calledFromUnannotatedJava(TreePath lambda) {
            Tree parent = lambda.getParentPath().getLeaf();
            if (!(parent instanceof MethodInvocationTree) && !(parent instanceof NewClassTree)) {
                return false;
            }
            Element method = trees.getElement(lambda.getParentPath());
            return method != null && !PlatformValues.trusted(method);
        }

        private void checkArguments(ExecutableElement method, List<? extends ExpressionTree> args) {
            if (!isAxolotlCode(method)) {
                return;
            }
            List<? extends VariableElement> params = method.getParameters();
            int checkedCount = method.isVarArgs() ? params.size() - 1 : params.size();
            for (int i = 0; i < Math.min(checkedCount, args.size()); i++) {
                VariableElement param = params.get(i);
                checkValue(args.get(i), param.asType(), param, "the parameter `" + param.getSimpleName() + "` does not accept null");
            }
        }

        /** Declarations of Axolotl code and of null-marked Java, but not Axolotl's runtime. */
        private boolean isAxolotlCode(Element element) {
            for (Element e = element; e != null; e = e.getEnclosingElement()) {
                if (e instanceof PackageElement pkg && pkg.getQualifiedName().contentEquals("axl.lang")) {
                    return false;
                }
                if (PlatformValues.hasAnnotation(e.getAnnotationMirrors(), "NullMarked")) {
                    return true;
                }
            }
            return false;
        }

        private void checkStore(ExpressionTree value, Element target, String why) {
            checkValue(value, target.asType(), target, why);
        }

        private void checkValue(ExpressionTree value, TypeMirror type, Element declaration, String why) {
            if (type.getKind().isPrimitive()) {
                checkUnboxing(value);
            } else if (type.getKind() != TypeKind.VOID && !PlatformValues.isNullable(declaration, type) && mayBeNull(value)) {
                report(value, origin(value) + ", but " + why + "; " + FIX);
            }
        }

        private void checkUnboxing(ExpressionTree value) {
            TypeMirror type = typeOf(value);
            if (type != null && BOXES.contains(type.toString().replaceAll("@\\S+ ", "")) && mayBeNull(value)) {
                report(value, origin(value) + ", but is used as a number or boolean here; " + FIX);
            }
        }

        /** Locals known to be non-null where the condition has the given value. */
        private Set<Element> checked(ExpressionTree condition, boolean when) {
            Set<Element> result = new HashSet<>();
            ExpressionTree c = unwrap(condition);
            if (c instanceof BinaryTree b && (b.getKind() == Tree.Kind.NOT_EQUAL_TO || b.getKind() == Tree.Kind.EQUAL_TO)) {
                ExpressionTree other = isNullLiteral(b.getLeftOperand()) ? b.getRightOperand()
                        : isNullLiteral(b.getRightOperand()) ? b.getLeftOperand() : null;
                Element local = other == null ? null : element(other);
                boolean nonNullWhenTrue = b.getKind() == Tree.Kind.NOT_EQUAL_TO;
                if (local != null && nonNullWhenTrue == when) {
                    result.add(local);
                }
            } else if (c instanceof BinaryTree b && b.getKind() == Tree.Kind.CONDITIONAL_AND && when) {
                result.addAll(checked(b.getLeftOperand(), true));
                result.addAll(checked(b.getRightOperand(), true));
            } else if (c instanceof BinaryTree b && b.getKind() == Tree.Kind.CONDITIONAL_OR && !when) {
                result.addAll(checked(b.getLeftOperand(), false));
                result.addAll(checked(b.getRightOperand(), false));
            } else if (c.getKind() == Tree.Kind.LOGICAL_COMPLEMENT) {
                result.addAll(checked(((UnaryTree) c).getExpression(), !when));
            }
            return result;
        }

        private boolean mayBeNull(ExpressionTree expression) {
            ExpressionTree e = unwrap(expression);
            if (e instanceof ConditionalExpressionTree c) {
                return mayBeNull(c.getTrueExpression()) || mayBeNull(c.getFalseExpression());
            }
            if (!(e instanceof MethodInvocationTree) && !(e instanceof MemberSelectTree) && !(e instanceof IdentifierTree)) {
                return false;
            }
            Element element = element(e);
            return element != null && (nullable.contains(element) || PlatformValues.isPlatform(element));
        }

        /** Where a value that may be null came from. */
        private String origin(ExpressionTree expression) {
            ExpressionTree e = unwrap(expression);
            if (e instanceof IdentifierTree identifier && nullable.contains(element(identifier))) {
                return "`" + identifier.getName() + "` may be null: it holds a value from Java code without nullness annotations";
            }
            return "the result of `" + name(e) + "` comes from Java code without nullness annotations and may be null";
        }

        private void report(Tree tree, String message) {
            if (reporting) {
                trees.printMessage(Diagnostic.Kind.ERROR, PREFIX + message, tree, unit);
            }
        }

        /** `var x = ...`: javac keeps the declared type tree, so look at the source. */
        private boolean isVar(VariableTree variable) {
            if (variable.getType() == null) {
                return true;
            }
            long start = trees.getSourcePositions().getStartPosition(unit, variable.getType());
            return start < 0 || source.startsWith("var", (int) start);
        }

        private boolean isString(ExpressionTree e) {
            TypeMirror type = typeOf(e);
            return type != null && type.toString().replaceAll("@\\S+ ", "").equals("java.lang.String");
        }

        private TypeMirror typeOf(ExpressionTree expression) {
            TreePath path = TreePath.getPath(unit, expression);
            return path == null ? null : trees.getTypeMirror(path);
        }

        private Element element(ExpressionTree expression) {
            ExpressionTree e = unwrap(expression);
            TreePath path = TreePath.getPath(unit, e);
            return path == null ? null : trees.getElement(path);
        }

        private String name(ExpressionTree e) {
            if (e instanceof MethodInvocationTree call) {
                ExpressionTree select = call.getMethodSelect();
                String method = select instanceof MemberSelectTree m ? m.getIdentifier().toString() : select.toString();
                return method + "()";
            }
            if (e instanceof MemberSelectTree select) {
                return select.getIdentifier().toString();
            }
            return e.toString();
        }
    }

    private static ExpressionTree unwrap(ExpressionTree expression) {
        ExpressionTree e = expression;
        while (true) {
            if (e instanceof ParenthesizedTree p) {
                e = p.getExpression();
            } else if (e instanceof TypeCastTree cast) {
                e = cast.getExpression();
            } else {
                return e;
            }
        }
    }

    private static boolean isNullLiteral(ExpressionTree expression) {
        return unwrap(expression).getKind() == Tree.Kind.NULL_LITERAL;
    }

    /** A statement after which control never continues. */
    private static boolean exits(StatementTree statement) {
        if (statement instanceof BlockTree block) {
            List<? extends StatementTree> statements = block.getStatements();
            return !statements.isEmpty() && exits(statements.getLast());
        }
        if (statement instanceof IfTree ifTree) {
            return ifTree.getElseStatement() != null && exits(ifTree.getThenStatement()) && exits(ifTree.getElseStatement());
        }
        return statement instanceof ReturnTree || statement instanceof ThrowTree || statement instanceof BreakTree
                || statement instanceof ContinueTree;
    }
}
