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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

/**
 * Strict null mode: a value that comes from Java code without nullness annotations may be null, as
 * if it had the type `T?`. Dereferencing it without a check is an error. Checks only generated
 * classes; null checks, early returns and `&&`/`||` narrow locals as NullAway does.
 */
final class StrictNullChecker implements TaskListener {

    static final String PREFIX = "strict null: ";

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
        // Locals that hold a value from unannotated Java code and are not checked yet.
        private Set<Element> nullable = new HashSet<>();

        Scanner(CompilationUnitTree unit) {
            this.unit = unit;
        }

        @Override
        public Void visitVariable(VariableTree variable, Void unused) {
            super.visitVariable(variable, unused);
            Element element = trees.getElement(getCurrentPath());
            if (element != null && variable.getInitializer() != null && isPlatform(variable.getInitializer())) {
                nullable.add(element);
            }
            return null;
        }

        @Override
        public Void visitAssignment(AssignmentTree assignment, Void unused) {
            super.visitAssignment(assignment, unused);
            Element target = element(assignment.getVariable());
            if (target != null) {
                if (isPlatform(assignment.getExpression())) {
                    nullable.add(target);
                } else {
                    nullable.remove(target);
                }
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
            if (!isTypeOrPackage) {
                String problem = problem(target);
                if (problem != null) {
                    trees.printMessage(Diagnostic.Kind.ERROR, PREFIX + problem, select, unit);
                }
            }
            return super.visitMemberSelect(select, unused);
        }

        @Override
        public Void visitIf(IfTree ifTree, Void unused) {
            scan(ifTree.getCondition(), null);
            Set<Element> before = new HashSet<>(nullable);
            Set<Element> checkedInThen = checked(ifTree.getCondition(), true);
            Set<Element> checkedInElse = checked(ifTree.getCondition(), false);
            nullable.removeAll(checkedInThen);
            scan(ifTree.getThenStatement(), null);
            Set<Element> afterThen = nullable;
            nullable = new HashSet<>(before);
            nullable.removeAll(checkedInElse);
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
            Set<Element> before = new HashSet<>(nullable);
            nullable.removeAll(checked(loop.getCondition(), true));
            scan(loop.getStatement(), null);
            before.addAll(nullable);
            nullable = before;
            return null;
        }

        @Override
        public Void visitBinary(BinaryTree binary, Void unused) {
            boolean and = binary.getKind() == Tree.Kind.CONDITIONAL_AND;
            if (!and && binary.getKind() != Tree.Kind.CONDITIONAL_OR) {
                return super.visitBinary(binary, unused);
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
            super.visitLambdaExpression(lambda, unused);
            nullable = before;
            return null;
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

        /** Why dereferencing the expression may fail, or null. */
        private String problem(ExpressionTree expression) {
            ExpressionTree e = unwrap(expression);
            if (e instanceof IdentifierTree identifier) {
                Element element = element(identifier);
                if (element != null && nullable.contains(element)) {
                    return "`" + identifier.getName() + "` may be null: it holds a value from Java code without nullness "
                            + "annotations; use `?.`, `?:`, a null check or `!!`";
                }
                return null;
            }
            if (isPlatform(e)) {
                return "the result of `" + name(e) + "` comes from Java code without nullness annotations and may be null; "
                        + "use `?.`, `?:`, a null check or `!!`";
            }
            return null;
        }

        /** A call or field read whose declaration says nothing about null. */
        private boolean isPlatform(ExpressionTree expression) {
            ExpressionTree e = unwrap(expression);
            if (!(e instanceof MethodInvocationTree) && !(e instanceof MemberSelectTree) && !(e instanceof IdentifierTree)) {
                if (e instanceof ConditionalExpressionTree c) {
                    return isPlatform(c.getTrueExpression()) || isPlatform(c.getFalseExpression());
                }
                return false;
            }
            Element element = element(e);
            if (element == null || element.getKind() == ElementKind.LOCAL_VARIABLE || element.getKind() == ElementKind.PARAMETER
                    || element.getKind() == ElementKind.ENUM_CONSTANT || element.getKind().isClass()
                    || element.getKind() == ElementKind.PACKAGE || element.getKind() == ElementKind.EXCEPTION_PARAMETER) {
                return false;
            }
            // Constants like `System.out` are set once at class init; flagging them would bury real problems.
            if (element.getKind() == ElementKind.FIELD
                    && element.getModifiers().containsAll(Set.of(Modifier.STATIC, Modifier.FINAL))) {
                return false;
            }
            TypeMirror type = element instanceof ExecutableElement method ? method.getReturnType() : element.asType();
            if (type.getKind().isPrimitive() || type.getKind() == TypeKind.VOID) {
                return false;
            }
            return !trusted(element) && !annotatedNonNull(element, type);
        }

        /** Axolotl's runtime, `Objects.requireNonNull` and code in a `@NullMarked` scope state their nullness. */
        private boolean trusted(Element element) {
            for (Element e = element; e != null; e = e.getEnclosingElement()) {
                if (e instanceof PackageElement pkg && pkg.getQualifiedName().contentEquals("axl.lang")) {
                    return true;
                }
                if (e instanceof TypeElement type && type.getQualifiedName().contentEquals("java.util.Objects")
                        && element.getSimpleName().toString().startsWith("requireNonNull")) {
                    return true;
                }
                if (hasAnnotation(e.getAnnotationMirrors(), "NullMarked")) {
                    return true;
                }
            }
            return false;
        }

        private boolean annotatedNonNull(Element element, TypeMirror type) {
            List<String> names = List.of("NonNull", "NotNull", "Nonnull");
            return names.stream().anyMatch(n -> hasAnnotation(element.getAnnotationMirrors(), n)
                    || hasAnnotation(type.getAnnotationMirrors(), n));
        }

        private boolean hasAnnotation(List<? extends AnnotationMirror> annotations, String simpleName) {
            return annotations.stream().anyMatch(a -> a.getAnnotationType().asElement().getSimpleName().contentEquals(simpleName));
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
        return statement instanceof ReturnTree || statement instanceof ThrowTree || statement instanceof BreakTree
                || statement instanceof ContinueTree;
    }
}
