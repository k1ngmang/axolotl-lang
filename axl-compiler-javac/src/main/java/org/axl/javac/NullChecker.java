package org.axl.javac;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

/**
 * The null checks NullAway cannot make in generated code. NullAway trusts JDK generics and lambdas,
 * so it misses what a lambda returns to a JDK functional interface, values whose type is a type
 * argument written with `?` (`List<String?>.get`, the elements of a pipe), values passed where a
 * type argument without `?` is expected, and stores into locals whose type has no `?`.
 *
 * In strict null mode a value from Java code without nullness annotations may also be null, as if it
 * had the type `T?`. Checks only generated classes; null checks, early exits and `&&`/`||` narrow
 * values as NullAway does.
 */
final class NullChecker implements TaskListener {

    static final String PREFIX = "null: ";
    static final String STRICT_PREFIX = "strict null: ";

    private static final String FIX = "use `?.`, `?:`, a null check or `!!`";
    private static final Set<String> BOXES = Set.of("java.lang.Integer", "java.lang.Long", "java.lang.Double",
            "java.lang.Boolean", "java.lang.Character", "java.lang.Short", "java.lang.Byte", "java.lang.Float");
    // JDK methods whose function argument may return null: Java documents what null means for them.
    // The Map methods then store or return null, so they allow it only for a value type with `?`.
    private static final Set<String> NULL_RESULT_ALLOWED = Set.of("java.util.Optional.map");
    private static final Set<String> NULL_VALUE_ALLOWED = Set.of("java.util.Map.computeIfAbsent",
            "java.util.Map.computeIfPresent", "java.util.Map.compute", "java.util.Map.merge");

    /**
     * Why a value may be null, ordered so that merging two paths keeps the larger one. NullAway sees
     * {@code DECLARED} values itself, so where it checks a use, only the other kinds are reported here.
     */
    private enum Origin { NONE, HIDDEN, PLATFORM, DECLARED }

    private final Trees trees;
    private final Types types;
    private final Elements elements;
    private final boolean strict;

    NullChecker(JavacTask task, boolean strict) {
        this.trees = Trees.instance(task);
        this.types = task.getTypes();
        this.elements = task.getElements();
        this.strict = strict;
    }

    private record Report(String message, Tree tree, CompilationUnitTree unit) {}

    // Error Prone skips its analysis once the compilation has errors, so these wait until the end.
    private final List<Report> reports = new ArrayList<>();

    @Override
    public void finished(TaskEvent event) {
        if (event.getKind() == TaskEvent.Kind.COMPILATION) {
            reports.forEach(r -> trees.printMessage(Diagnostic.Kind.ERROR, r.message(), r.tree(), r.unit()));
            reports.clear();
            return;
        }
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
    static boolean isGenerated(CompilationUnitTree unit) {
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
        private final TreePath unitPath;
        private final String source;
        // Values whose nullness is known at this point; locals that are absent are not null.
        private Map<Element, Origin> state = new HashMap<>();
        // Locals declared with `var`, whose nullness follows their value.
        private final Set<Element> inferredLocals = new HashSet<>();
        // Locals declared with `var` whose value has these type arguments written with `?`.
        private final Map<Element, Set<Integer>> nullableTypeArgs = new HashMap<>();
        // Off while a loop body is scanned only to see which values it leaves nullable.
        private boolean reporting = true;

        Scanner(CompilationUnitTree unit) {
            this.unit = unit;
            this.unitPath = new TreePath(unit);
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
            if (element == null || init == null) {
                return null;
            }
            if (element.getKind() == ElementKind.LOCAL_VARIABLE && isVar(variable)) {
                inferredLocals.add(element);
                state.put(element, origin(init));
                Set<Integer> args = nullableTypeArgs(init);
                if (!args.isEmpty()) {
                    nullableTypeArgs.computeIfAbsent(element, e -> new HashSet<>()).addAll(args);
                }
            } else if (element.getKind() == ElementKind.LOCAL_VARIABLE) {
                store(init, element, variable.getType());
            } else if (element.getKind() == ElementKind.FIELD) {
                checkValue(init, element.asType(), element, true, "`" + element.getSimpleName() + "` is not nullable");
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
                state.put(target, origin(assignment.getExpression()));
                Set<Integer> args = nullableTypeArgs(assignment.getExpression());
                if (!args.isEmpty()) {
                    nullableTypeArgs.computeIfAbsent(target, e -> new HashSet<>()).addAll(args);
                }
            } else if (target.getKind() == ElementKind.LOCAL_VARIABLE) {
                store(assignment.getExpression(), target, null);
            } else if (isAxolotlCode(target)) {
                checkValue(assignment.getExpression(), target.asType(), target, true, "`" + target.getSimpleName() + "` is not nullable");
            }
            return null;
        }

        /** NullAway ignores the declared types of locals, so a store into a local without `?` is checked here. */
        private void store(ExpressionTree value, Element local, Tree typeTree) {
            TypeMirror type = local.asType();
            boolean nullable = PlatformValues.isNullable(local, type) || typeTree != null && annotatedNullable(typeTree);
            if (type.getKind().isPrimitive()) {
                checkUnboxing(value, false);
            } else if (!nullable && report(value, origin(value), false,
                    describe(value) + ", but `" + local.getSimpleName() + "` is not nullable; " + FIX)) {
                state.put(local, Origin.NONE);
            } else {
                state.put(local, nullable ? origin(value) : Origin.NONE);
            }
        }

        @Override
        public Void visitCompoundAssignment(CompoundAssignmentTree assignment, Void unused) {
            super.visitCompoundAssignment(assignment, unused);
            if (!isString(assignment.getVariable())) {
                checkUnboxing(assignment.getVariable(), true);
                checkUnboxing(assignment.getExpression(), true);
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
                report(select, origin(target), true, describe(target) + "; " + FIX);
            }
            return super.visitMemberSelect(select, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
            super.visitMethodInvocation(call, unused);
            if (!(element(call) instanceof ExecutableElement method)) {
                return null;
            }
            if (isAxolotlCode(method)) {
                checkArguments(method, call.getArguments());
            }
            if (call.getMethodSelect() instanceof MemberSelectTree select && !isTypeName(select.getExpression())) {
                checkTypeArgumentParameters(select.getExpression(), method, call.getArguments());
                if (method.getSimpleName().contentEquals("sorted") && call.getArguments().isEmpty()
                        && isStream(select.getExpression()) && nullableTypeArgs(select.getExpression()).contains(0)) {
                    report(call, Origin.HIDDEN, false, "the elements may be null, and `sorted` compares them; "
                            + "drop the nulls first: `|> filter { it != null }`");
                }
            } else if (method.getModifiers().contains(Modifier.STATIC)) {
                checkFactoryArguments(method, call.getArguments());
            }
            if (isRequireNonNull(method) && call.getArguments().size() == 1
                    && getCurrentPath().getParentPath().getLeaf() instanceof ExpressionStatementTree) {
                Element checked = narrowable(call.getArguments().getFirst());
                if (checked != null) {
                    state.put(checked, Origin.NONE);
                }
            }
            return null;
        }

        @Override
        public Void visitNewClass(NewClassTree newClass, Void unused) {
            super.visitNewClass(newClass, unused);
            if (!(trees.getElement(getCurrentPath()) instanceof ExecutableElement constructor)) {
                return null;
            }
            if (isAxolotlCode(constructor)) {
                checkArguments(constructor, newClass.getArguments());
            }
            String owner = ((TypeElement) constructor.getEnclosingElement()).getQualifiedName().toString();
            if ((owner.equals("axl.lang.Ok") || owner.equals("axl.lang.Err")) && newClass.getArguments().size() == 1) {
                Boolean nullable = targetTypeArgNullable(getCurrentPath(), owner.equals("axl.lang.Ok") ? 0 : 1);
                ExpressionTree value = newClass.getArguments().getFirst();
                if (Boolean.FALSE.equals(nullable)) {
                    report(value, origin(value), true, describe(value) + ", but the result type has no `?` there; " + FIX);
                }
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
                    checkLambdaResult(p, ret.getExpression());
                    return null;
                }
                if (p.getLeaf() instanceof MethodTree && trees.getElement(p) instanceof ExecutableElement method) {
                    checkValue(ret.getExpression(), method.getReturnType(), method, true, "the function does not return null");
                    return null;
                }
            }
            return null;
        }

        @Override
        public Void visitUnary(UnaryTree unary, Void unused) {
            super.visitUnary(unary, unused);
            checkUnboxing(unary.getExpression(), true);
            return null;
        }

        @Override
        public Void visitIf(IfTree ifTree, Void unused) {
            scan(ifTree.getCondition(), null);
            checkUnboxing(ifTree.getCondition(), true);
            Map<Element, Origin> before = new HashMap<>(state);
            narrow(ifTree.getCondition(), true);
            scan(ifTree.getThenStatement(), null);
            Map<Element, Origin> afterThen = state;
            state = new HashMap<>(before);
            narrow(ifTree.getCondition(), false);
            if (ifTree.getElseStatement() != null) {
                scan(ifTree.getElseStatement(), null);
            }
            Map<Element, Origin> afterElse = state;
            // A branch that always leaves contributes nothing to what follows.
            if (exits(ifTree.getThenStatement())) {
                state = afterElse;
            } else if (ifTree.getElseStatement() != null && exits(ifTree.getElseStatement())) {
                state = afterThen;
            } else {
                state = merged(afterThen, afterElse);
            }
            return null;
        }

        @Override
        public Void visitWhileLoop(WhileLoopTree loop, Void unused) {
            scan(loop.getCondition(), null);
            checkUnboxing(loop.getCondition(), true);
            Map<Element, Origin> exit = loopBody(loop.getCondition(), loop.getStatement(), null);
            state = exit;
            narrow(loop.getCondition(), false);
            return null;
        }

        @Override
        public Void visitEnhancedForLoop(EnhancedForLoopTree loop, Void unused) {
            scan(loop.getExpression(), null);
            Element variable = trees.getElement(new TreePath(getCurrentPath(), loop.getVariable()));
            if (variable != null) {
                inferredLocals.add(variable);
            }
            Origin elementOrigin = nullableTypeArgs(loop.getExpression()).contains(0) ? Origin.HIDDEN : Origin.NONE;
            state = loopBody(null, loop.getStatement(), variable == null ? null : Map.entry(variable, elementOrigin));
            return null;
        }

        /**
         * What the body leaves nullable reaches the next iteration, so the body is scanned without
         * reporting until nothing changes, then once more to report. Returns the state after the loop.
         */
        private Map<Element, Origin> loopBody(ExpressionTree condition, StatementTree body, Map.Entry<Element, Origin> variable) {
            Map<Element, Origin> entry = new HashMap<>(state);
            boolean wasReporting = reporting;
            reporting = false;
            while (true) {
                state = new HashMap<>(entry);
                enterIteration(condition, variable);
                scan(body, null);
                Map<Element, Origin> next = merged(entry, state);
                if (next.equals(entry)) {
                    break;
                }
                entry = next;
            }
            reporting = wasReporting;
            state = new HashMap<>(entry);
            enterIteration(condition, variable);
            scan(body, null);
            return merged(entry, state);
        }

        private void enterIteration(ExpressionTree condition, Map.Entry<Element, Origin> variable) {
            if (condition != null) {
                narrow(condition, true);
            }
            if (variable != null) {
                state.put(variable.getKey(), variable.getValue());
            }
        }

        @Override
        public Void visitBinary(BinaryTree binary, Void unused) {
            boolean and = binary.getKind() == Tree.Kind.CONDITIONAL_AND;
            if (!and && binary.getKind() != Tree.Kind.CONDITIONAL_OR) {
                super.visitBinary(binary, unused);
                boolean equality = binary.getKind() == Tree.Kind.EQUAL_TO || binary.getKind() == Tree.Kind.NOT_EQUAL_TO;
                boolean concatenation = isString(binary.getLeftOperand()) || isString(binary.getRightOperand());
                if (equality) {
                    // `==` unboxes only when the other side is a primitive.
                    if (isPrimitive(binary.getRightOperand())) {
                        checkUnboxing(binary.getLeftOperand(), true);
                    }
                    if (isPrimitive(binary.getLeftOperand())) {
                        checkUnboxing(binary.getRightOperand(), true);
                    }
                } else if (!concatenation) {
                    checkUnboxing(binary.getLeftOperand(), true);
                    checkUnboxing(binary.getRightOperand(), true);
                }
                return null;
            }
            scan(binary.getLeftOperand(), null);
            checkUnboxing(binary.getLeftOperand(), true);
            Map<Element, Origin> before = new HashMap<>(state);
            narrow(binary.getLeftOperand(), and);
            scan(binary.getRightOperand(), null);
            checkUnboxing(binary.getRightOperand(), true);
            state = before;
            return null;
        }

        @Override
        public Void visitConditionalExpression(ConditionalExpressionTree conditional, Void unused) {
            scan(conditional.getCondition(), null);
            checkUnboxing(conditional.getCondition(), true);
            Map<Element, Origin> before = new HashMap<>(state);
            narrow(conditional.getCondition(), true);
            scan(conditional.getTrueExpression(), null);
            state = new HashMap<>(before);
            narrow(conditional.getCondition(), false);
            scan(conditional.getFalseExpression(), null);
            state = before;
            return null;
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree lambda, Void unused) {
            Map<Element, Origin> before = new HashMap<>(state);
            Origin params = parameterOrigin(getCurrentPath());
            for (VariableTree param : lambda.getParameters()) {
                Element element = trees.getElement(new TreePath(getCurrentPath(), param));
                if (element != null) {
                    inferredLocals.add(element);
                    state.put(element, element.asType().getKind().isPrimitive() ? Origin.NONE : params);
                }
            }
            super.visitLambdaExpression(lambda, unused);
            if (lambda.getBody() instanceof ExpressionTree body) {
                checkLambdaResult(getCurrentPath(), body);
            }
            state = before;
            return null;
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree reference, Void unused) {
            super.visitMemberReference(reference, unused);
            if (strict && passedToJava(getCurrentPath()) && element(reference) instanceof ExecutableElement method
                    && isAxolotlCode(method)) {
                for (VariableElement param : method.getParameters()) {
                    if (!param.asType().getKind().isPrimitive() && !PlatformValues.isNullable(param, param.asType())) {
                        report(reference, Origin.PLATFORM, false, "Java code without nullness annotations may pass null to `"
                                + method.getSimpleName() + "`, but the parameter `" + param.getSimpleName()
                                + "` does not accept null; pass a lambda that checks for null");
                        break;
                    }
                }
            }
            return null;
        }

        /**
         * Lambda parameters get the elements of the collection or stream whose method takes the lambda,
         * and in strict mode whatever Java code without nullness annotations passes.
         */
        private Origin parameterOrigin(TreePath lambda) {
            TreePath parent = lambda.getParentPath();
            while (parent.getLeaf() instanceof ParenthesizedTree) {
                parent = parent.getParentPath();
            }
            if (parent.getLeaf() instanceof MethodInvocationTree call && call.getMethodSelect() instanceof MemberSelectTree select
                    && !isTypeName(select.getExpression()) && hasOneTypeParameter(select.getExpression())
                    && nullableTypeArgs(select.getExpression()).contains(0)) {
                return Origin.HIDDEN;
            }
            return strict && passedToJava(lambda) ? Origin.PLATFORM : Origin.NONE;
        }

        /**
         * A lambda or method reference that Java code without nullness annotations calls: passed to such
         * a method, or kept in a value of a JDK functional interface type.
         */
        private boolean passedToJava(TreePath function) {
            TreePath parent = function.getParentPath();
            while (parent.getLeaf() instanceof ParenthesizedTree) {
                parent = parent.getParentPath();
            }
            if (parent.getLeaf() instanceof MethodInvocationTree || parent.getLeaf() instanceof NewClassTree) {
                Element method = trees.getElement(parent);
                return method != null && !PlatformValues.trusted(method);
            }
            TypeMirror type = trees.getTypeMirror(function);
            return type instanceof DeclaredType declared && !PlatformValues.trusted(declared.asElement());
        }

        /** What a lambda returns must fit the result of its functional interface. */
        private void checkLambdaResult(TreePath lambda, ExpressionTree result) {
            if (!(trees.getTypeMirror(lambda) instanceof DeclaredType target)
                    || !(target.asElement() instanceof TypeElement iface)
                    || iface.getQualifiedName().contentEquals("axl.lang.Nulls.Fallback")) {
                // `a ?: b` has the nullness of `b`, which the uses of the whole expression check.
                return;
            }
            ExecutableElement method = functionalMethod(iface);
            if (method == null) {
                return;
            }
            TypeMirror returned = ((ExecutableType) types.asMemberOf(target, method)).getReturnType();
            String name = "`" + iface.getSimpleName() + "." + method.getSimpleName() + "`";
            if (returned.getKind() == TypeKind.VOID) {
                return;
            }
            if (returned.getKind().isPrimitive()) {
                checkUnboxing(result, false);
                return;
            }
            Origin origin = origin(result);
            if (origin == Origin.NONE || resultMayBeNull(lambda, iface, method, returned)) {
                return;
            }
            boolean nullAwayChecks = isNullMarked(iface);
            report(result, origin, nullAwayChecks, describe(result) + ", but the lambda must not return null: " + name
                    + " returns `" + typeName(returned) + "` here; " + FIX);
        }

        private boolean resultMayBeNull(TreePath lambda, TypeElement iface, ExecutableElement method, TypeMirror returned) {
            if (PlatformValues.isNullable(method, returned)) {
                return true;
            }
            TreePath parent = lambda.getParentPath();
            while (parent.getLeaf() instanceof ParenthesizedTree) {
                parent = parent.getParentPath();
            }
            ExecutableElement callee = parent.getLeaf() instanceof MethodInvocationTree
                    && trees.getElement(parent) instanceof ExecutableElement e ? e : null;
            if (isNullMarked(iface)) {
                if (!(method.getReturnType() instanceof TypeVariable variable) || !nullableBound(variable)) {
                    return false;
                }
                // `attempt`: the result type of the enclosing function says whether the value may be null.
                if (callee != null && callee.getSimpleName().contentEquals("attempt") && isRuntime(callee)) {
                    int index = ((MethodInvocationTree) parent.getLeaf()).getArguments().indexOf(lambda.getLeaf());
                    return !Boolean.FALSE.equals(targetTypeArgNullable(parent, index));
                }
                return true;
            }
            if (callee == null) {
                return false;
            }
            if (isOneOf(callee, NULL_RESULT_ALLOWED)) {
                return true;
            }
            if (isOneOf(callee, NULL_VALUE_ALLOWED)
                    && ((MethodInvocationTree) parent.getLeaf()).getMethodSelect() instanceof MemberSelectTree select) {
                return nullableTypeArgs(select.getExpression()).contains(1);
            }
            int index = ((MethodInvocationTree) parent.getLeaf()).getArguments().indexOf(lambda.getLeaf());
            if (!isNullMarked(callee) || index < 0 || index >= callee.getParameters().size()) {
                return false;
            }
            // A null-marked method such as `Nulls.map` says in its parameter type whether the result may be null.
            TypeMirror declared = callee.getParameters().get(index).asType();
            if (declared instanceof DeclaredType parameter && method.getReturnType() instanceof TypeVariable variable) {
                int position = iface.getTypeParameters().indexOf((TypeParameterElement) variable.asElement());
                if (position >= 0 && position < parameter.getTypeArguments().size()) {
                    TypeMirror argument = parameter.getTypeArguments().get(position);
                    return nullableAnnotated(argument)
                            || argument instanceof WildcardType wildcard && wildcard.getExtendsBound() != null
                            && nullableAnnotated(wildcard.getExtendsBound());
                }
            }
            return false;
        }

        /** Whether the method is one of the given `Type.method` names or overrides one. */
        private boolean isOneOf(ExecutableElement callee, Set<String> methods) {
            TypeMirror owner = types.erasure(callee.getEnclosingElement().asType());
            for (String allowed : methods) {
                int dot = allowed.lastIndexOf('.');
                TypeElement type = elements.getTypeElement(allowed.substring(0, dot));
                if (type != null && callee.getSimpleName().contentEquals(allowed.substring(dot + 1))
                        && types.isSubtype(owner, types.erasure(type.asType()))) {
                    return true;
                }
            }
            return false;
        }

        /**
         * `Ok(x)` and `attempt` take the nullness of their type arguments from where the value goes: the
         * result type of the function it is returned from, or the written type of a local. Null when unknown.
         */
        private Boolean targetTypeArgNullable(TreePath expression, int index) {
            TreePath parent = expression.getParentPath();
            while (parent.getLeaf() instanceof ParenthesizedTree) {
                parent = parent.getParentPath();
            }
            TypeMirror target = null;
            if (parent.getLeaf() instanceof ReturnTree) {
                for (TreePath p = parent.getParentPath(); p != null && target == null; p = p.getParentPath()) {
                    if (p.getLeaf() instanceof LambdaExpressionTree) {
                        return null;
                    }
                    if (p.getLeaf() instanceof MethodTree && trees.getElement(p) instanceof ExecutableElement method) {
                        target = method.getReturnType();
                    }
                }
            } else if (parent.getLeaf() instanceof VariableTree variable && !isVar(variable)) {
                Element element = trees.getElement(parent);
                target = element == null ? null : element.asType();
            }
            if (target instanceof DeclaredType declared && index < declared.getTypeArguments().size()) {
                return nullableAnnotated(declared.getTypeArguments().get(index));
            }
            return null;
        }

        private void checkArguments(ExecutableElement method, List<? extends ExpressionTree> args) {
            List<? extends VariableElement> params = method.getParameters();
            int checkedCount = method.isVarArgs() ? params.size() - 1 : params.size();
            for (int i = 0; i < Math.min(checkedCount, args.size()); i++) {
                VariableElement param = params.get(i);
                checkValue(args.get(i), param.asType(), param, true, "the parameter `" + param.getSimpleName() + "` does not accept null");
            }
        }

        /**
         * `xs.add(x)` on an `ArrayList<String>`: Java says nothing about null there, but the type argument
         * without `?` promises values that are not null.
         */
        private void checkTypeArgumentParameters(ExpressionTree receiver, ExecutableElement method,
                                                 List<? extends ExpressionTree> args) {
            if (!(typeOf(receiver) instanceof DeclaredType receiverType) || receiverType.getTypeArguments().isEmpty()
                    || !(receiverType.asElement() instanceof TypeElement owner) || isNullMarked(owner)) {
                return;
            }
            ExecutableType member;
            try {
                member = (ExecutableType) types.asMemberOf((DeclaredType) owner.asType(), method);
            } catch (IllegalArgumentException e) {
                return;
            }
            Set<Integer> nullable = nullableTypeArgs(receiver);
            List<? extends TypeMirror> params = member.getParameterTypes();
            int checkedCount = method.isVarArgs() ? params.size() - 1 : params.size();
            for (int i = 0; i < Math.min(checkedCount, args.size()); i++) {
                int typeArg = typeParameterIndex(owner, params.get(i));
                if (typeArg >= 0 && typeArg < receiverType.getTypeArguments().size()
                        && receiverType.getTypeArguments().get(typeArg).getKind() == TypeKind.DECLARED
                        && !nullable.contains(typeArg)) {
                    ExpressionTree arg = args.get(i);
                    report(arg, origin(arg), false, describe(arg) + ", but `" + method.getSimpleName()
                            + "` takes a type argument written without `?` here; add `?` to the type argument or " + FIX);
                }
            }
        }

        /** `List.of(a, null)`: the values become the elements of a type that has no `?`. */
        private void checkFactoryArguments(ExecutableElement method, List<? extends ExpressionTree> args) {
            if (isNullMarked(method) || method.getTypeParameters().isEmpty()
                    || !(method.getReturnType() instanceof DeclaredType result)) {
                return;
            }
            List<? extends VariableElement> params = method.getParameters();
            for (int i = 0; i < args.size() && !params.isEmpty(); i++) {
                TypeMirror param = params.get(Math.min(i, params.size() - 1)).asType();
                if (method.isVarArgs() && i >= params.size() - 1 && param instanceof ArrayType array) {
                    param = array.getComponentType();
                }
                if (param instanceof TypeVariable variable && result.getTypeArguments().stream()
                        .anyMatch(t -> t instanceof TypeVariable v && v.asElement().equals(variable.asElement()))) {
                    ExpressionTree arg = args.get(i);
                    report(arg, origin(arg), false, describe(arg) + ", but `" + method.getSimpleName()
                            + "` makes it an element of a type without `?`; " + FIX);
                }
            }
        }

        /** Declarations of Axolotl code and of null-marked Java, but not Axolotl's runtime. */
        private boolean isAxolotlCode(Element element) {
            return !isRuntime(element) && isNullMarked(element);
        }

        private void checkValue(ExpressionTree value, TypeMirror type, Element declaration, boolean nullAwayChecks, String why) {
            if (type.getKind().isPrimitive()) {
                checkUnboxing(value, nullAwayChecks);
            } else if (type.getKind() != TypeKind.VOID && !PlatformValues.isNullable(declaration, type)) {
                report(value, origin(value), nullAwayChecks, describe(value) + ", but " + why + "; " + FIX);
            }
        }

        private void checkUnboxing(ExpressionTree value, boolean nullAwayChecks) {
            TypeMirror type = typeOf(value);
            if (type != null && BOXES.contains(erasedName(type))) {
                report(value, origin(value), nullAwayChecks, describe(value) + ", but is used as a number or boolean here; " + FIX);
            }
        }

        /**
         * A check narrows a variable named on its own: a local, a parameter or a field of this class.
         * The element of `xs.get(0)` or `a.name` stands for every call or access, not for this one.
         */
        private Element narrowable(ExpressionTree expression) {
            if (!(unwrap(expression) instanceof IdentifierTree)) {
                return null;
            }
            Element element = element(expression);
            return element instanceof VariableElement ? element : null;
        }

        @Override
        public Void visitMethod(MethodTree method, Void unused) {
            // What one function checked says nothing about another.
            Map<Element, Origin> outer = state;
            state = new HashMap<>();
            super.visitMethod(method, unused);
            state = outer;
            return null;
        }

        /** Marks the values known to be non-null where the condition has the given value. */
        private void narrow(ExpressionTree condition, boolean when) {
            ExpressionTree c = unwrap(condition);
            if (c instanceof BinaryTree b && (b.getKind() == Tree.Kind.NOT_EQUAL_TO || b.getKind() == Tree.Kind.EQUAL_TO)) {
                ExpressionTree other = isNullLiteral(b.getLeftOperand()) ? b.getRightOperand()
                        : isNullLiteral(b.getRightOperand()) ? b.getLeftOperand() : null;
                Element checked = other == null ? null : narrowable(other);
                boolean nonNullWhenTrue = b.getKind() == Tree.Kind.NOT_EQUAL_TO;
                if (checked != null && nonNullWhenTrue == when) {
                    state.put(checked, Origin.NONE);
                }
            } else if (c instanceof MethodInvocationTree call && call.getArguments().size() == 1
                    && element(call) instanceof ExecutableElement method && isObjectsNonNull(method)) {
                Element checked = narrowable(call.getArguments().getFirst());
                if (checked != null && when == method.getSimpleName().contentEquals("nonNull")) {
                    state.put(checked, Origin.NONE);
                }
            } else if (c instanceof BinaryTree b && b.getKind() == Tree.Kind.CONDITIONAL_AND && when) {
                narrow(b.getLeftOperand(), true);
                narrow(b.getRightOperand(), true);
            } else if (c instanceof BinaryTree b && b.getKind() == Tree.Kind.CONDITIONAL_OR && !when) {
                narrow(b.getLeftOperand(), false);
                narrow(b.getRightOperand(), false);
            } else if (c.getKind() == Tree.Kind.LOGICAL_COMPLEMENT) {
                narrow(((UnaryTree) c).getExpression(), !when);
            }
        }

        private Map<Element, Origin> merged(Map<Element, Origin> a, Map<Element, Origin> b) {
            Map<Element, Origin> result = new HashMap<>();
            Set<Element> keys = new HashSet<>(a.keySet());
            keys.addAll(b.keySet());
            for (Element key : keys) {
                Origin x = a.containsKey(key) ? a.get(key) : unknownOrigin(key);
                Origin y = b.containsKey(key) ? b.get(key) : unknownOrigin(key);
                result.put(key, x.compareTo(y) >= 0 ? x : y);
            }
            return result;
        }

        /** Nullness of a value that no assignment or check has told anything about. */
        private Origin unknownOrigin(Element element) {
            if (element.getKind() == ElementKind.LOCAL_VARIABLE || element.getKind() == ElementKind.EXCEPTION_PARAMETER) {
                return Origin.NONE;
            }
            if (element.getKind() == ElementKind.PARAMETER) {
                return PlatformValues.isNullable(element, element.asType()) ? Origin.DECLARED : Origin.NONE;
            }
            TypeMirror type = element instanceof ExecutableElement method ? method.getReturnType() : element.asType();
            if (type.getKind().isPrimitive() || type.getKind() == TypeKind.VOID) {
                return Origin.NONE;
            }
            if (PlatformValues.isNullable(element, type)) {
                return Origin.DECLARED;
            }
            return strict && PlatformValues.isPlatform(element) ? Origin.PLATFORM : Origin.NONE;
        }

        private Origin origin(ExpressionTree expression) {
            ExpressionTree e = unwrap(expression);
            if (e.getKind() == Tree.Kind.NULL_LITERAL) {
                return Origin.DECLARED;
            }
            if (e instanceof ConditionalExpressionTree c) {
                Map<Element, Origin> before = new HashMap<>(state);
                narrow(c.getCondition(), true);
                Origin whenTrue = origin(c.getTrueExpression());
                state = new HashMap<>(before);
                narrow(c.getCondition(), false);
                Origin whenFalse = origin(c.getFalseExpression());
                state = before;
                return whenTrue.compareTo(whenFalse) >= 0 ? whenTrue : whenFalse;
            }
            if (e instanceof AssignmentTree assignment) {
                return origin(assignment.getExpression());
            }
            if (!(e instanceof MethodInvocationTree) && !(e instanceof MemberSelectTree) && !(e instanceof IdentifierTree)) {
                return Origin.NONE;
            }
            Element element = element(e);
            if (element == null || element.getKind().isClass() || element.getKind().isInterface()
                    || element.getKind() == ElementKind.PACKAGE) {
                return Origin.NONE;
            }
            if (state.containsKey(element)) {
                return state.get(element);
            }
            if (e instanceof MethodInvocationTree call && element instanceof ExecutableElement method) {
                return callOrigin(call, method);
            }
            Origin declared = unknownOrigin(element);
            return declared == Origin.NONE && nullableAnnotated(typeOf(e)) ? Origin.HIDDEN : declared;
        }

        private Origin callOrigin(MethodInvocationTree call, ExecutableElement method) {
            if (method.getSimpleName().contentEquals("or") && isRuntime(method) && call.getArguments().size() == 2
                    && unwrap(call.getArguments().get(1)) instanceof LambdaExpressionTree fallback
                    && fallback.getBody() instanceof ExpressionTree body) {
                return origin(body);
            }
            Origin declared = unknownOrigin(method);
            if (declared != Origin.NONE) {
                return declared;
            }
            if (nullableAnnotated(typeOf(call))) {
                return Origin.HIDDEN;
            }
            if (call.getMethodSelect() instanceof MemberSelectTree select && !isTypeName(select.getExpression())
                    && typeOf(select.getExpression()) instanceof DeclaredType receiverType
                    && receiverType.asElement() instanceof TypeElement owner) {
                TypeMirror returned = memberReturnType(owner, method);
                int typeArg = typeParameterIndex(owner, returned);
                if (typeArg >= 0 && nullableTypeArgs(select.getExpression()).contains(typeArg)) {
                    return Origin.HIDDEN;
                }
            }
            return Origin.NONE;
        }

        /**
         * Positions of the type arguments of the value's type that are written with `?`. javac drops
         * the annotations when it infers types, so streams and `var` locals are followed by hand.
         */
        private Set<Integer> nullableTypeArgs(ExpressionTree expression) {
            ExpressionTree e = unwrap(expression);
            Set<Integer> result = new HashSet<>();
            if (typeOf(e) instanceof DeclaredType type) {
                List<? extends TypeMirror> args = type.getTypeArguments();
                for (int i = 0; i < args.size(); i++) {
                    if (nullableAnnotated(args.get(i))) {
                        result.add(i);
                    }
                }
            }
            if (e instanceof NewClassTree newClass && newClass.getIdentifier() instanceof ParameterizedTypeTree generic) {
                for (int i = 0; i < generic.getTypeArguments().size(); i++) {
                    if (annotatedNullable(generic.getTypeArguments().get(i))) {
                        result.add(i);
                    }
                }
            }
            Element element = element(e);
            if (element != null && nullableTypeArgs.containsKey(element)) {
                result.addAll(nullableTypeArgs.get(element));
            }
            if (e instanceof MethodInvocationTree call && call.getMethodSelect() instanceof MemberSelectTree select
                    && !isTypeName(select.getExpression()) && element instanceof ExecutableElement method
                    && typeOf(select.getExpression()) instanceof DeclaredType receiverType
                    && receiverType.asElement() instanceof TypeElement owner) {
                Set<Integer> receiverArgs = nullableTypeArgs(select.getExpression());
                if (!receiverArgs.isEmpty() && !filtersNulls(call, method)
                        && memberReturnType(owner, method) instanceof DeclaredType returned) {
                    List<? extends TypeMirror> args = returned.getTypeArguments();
                    for (int i = 0; i < args.size(); i++) {
                        if (receiverArgs.contains(typeParameterIndex(owner, args.get(i)))) {
                            result.add(i);
                        }
                    }
                }
            }
            return result;
        }

        /** `filter { it != null }` leaves only values that are not null. */
        private boolean filtersNulls(MethodInvocationTree call, ExecutableElement method) {
            if (!method.getSimpleName().contentEquals("filter") || call.getArguments().size() != 1
                    || !(unwrap(call.getArguments().getFirst()) instanceof LambdaExpressionTree lambda)
                    || lambda.getParameters().size() != 1 || !(lambda.getBody() instanceof ExpressionTree body)) {
                return false;
            }
            Element param = trees.getElement(new TreePath(new TreePath(unitPath, lambda), lambda.getParameters().getFirst()));
            Map<Element, Origin> before = new HashMap<>(state);
            state.put(param, Origin.DECLARED);
            narrow(body, true);
            boolean checked = state.get(param) == Origin.NONE;
            state = before;
            return checked;
        }

        private TypeMirror memberReturnType(TypeElement owner, ExecutableElement method) {
            try {
                return ((ExecutableType) types.asMemberOf((DeclaredType) owner.asType(), method)).getReturnType();
            } catch (IllegalArgumentException e) {
                return method.getReturnType();
            }
        }

        private boolean hasOneTypeParameter(ExpressionTree receiver) {
            return typeOf(receiver) instanceof DeclaredType type && type.getTypeArguments().size() == 1;
        }

        private boolean isStream(ExpressionTree e) {
            return erasedName(typeOf(e)).equals("java.util.stream.Stream");
        }

        /** Where a value that may be null came from. */
        private String describe(ExpressionTree expression) {
            ExpressionTree e = unwrap(expression);
            Origin origin = origin(e);
            if (e.getKind() == Tree.Kind.NULL_LITERAL) {
                return "the value is `null`";
            }
            if (e instanceof IdentifierTree identifier) {
                String name = "`" + identifier.getName() + "` may be null";
                return switch (origin) {
                    case PLATFORM -> name + ": it holds a value from Java code without nullness annotations";
                    case HIDDEN -> name + ": it holds a value of a type argument written with `?`";
                    default -> name;
                };
            }
            if (e instanceof MethodInvocationTree call && element(call) instanceof ExecutableElement method
                    && method.getSimpleName().contentEquals("or") && isRuntime(method)) {
                return origin == Origin.PLATFORM ? "the value of `?:` may be null, since its fallback comes from Java "
                        + "code without nullness annotations" : "the value of `?:` may be null, since its fallback may be null";
            }
            if (e instanceof MethodInvocationTree || e instanceof MemberSelectTree) {
                Element element = element(e);
                String name = element != null && element.getKind() == ElementKind.FIELD
                        ? "the value of `" + name(e) + "`" : "the result of `" + name(e) + "`";
                return switch (origin) {
                    case PLATFORM -> name + " comes from Java code without nullness annotations and may be null";
                    case HIDDEN -> name + " may be null: its type is a type argument written with `?`";
                    default -> name + " may be null";
                };
            }
            return "the value may be null";
        }

        /** Reports unless NullAway reports the same problem; returns whether anything is wrong. */
        private boolean report(Tree tree, Origin origin, boolean nullAwayChecks, String message) {
            if (origin == Origin.NONE || nullAwayChecks && origin == Origin.DECLARED) {
                return false;
            }
            if (reporting) {
                String prefix = origin == Origin.PLATFORM ? STRICT_PREFIX : PREFIX;
                // javac places a call at its parenthesis, where the .axl column cannot be found.
                Tree at = tree instanceof MethodInvocationTree call ? call.getMethodSelect() : tree;
                reports.add(new Report(prefix + message, at, unit));
            }
            return true;
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
            return erasedName(typeOf(e)).equals("java.lang.String");
        }

        private boolean isPrimitive(ExpressionTree e) {
            TypeMirror type = typeOf(e);
            return type != null && type.getKind().isPrimitive();
        }

        private boolean isTypeName(ExpressionTree e) {
            Element element = element(e);
            return element != null && (element.getKind().isClass() || element.getKind().isInterface()
                    || element.getKind() == ElementKind.PACKAGE);
        }

        private TypeMirror typeOf(ExpressionTree expression) {
            return trees.getTypeMirror(new TreePath(unitPath, expression));
        }

        private Element element(ExpressionTree expression) {
            return trees.getElement(new TreePath(unitPath, unwrap(expression)));
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

    /** The single abstract method of a functional interface. */
    private ExecutableElement functionalMethod(TypeElement iface) {
        for (ExecutableElement method : ElementFilter.methodsIn(elements.getAllMembers(iface))) {
            if (method.getModifiers().contains(Modifier.ABSTRACT) && !isObjectMethod(method)) {
                return method;
            }
        }
        return null;
    }

    private static boolean isObjectMethod(ExecutableElement method) {
        String name = method.getSimpleName().toString();
        int params = method.getParameters().size();
        return name.equals("equals") && params == 1 || (name.equals("hashCode") || name.equals("toString")) && params == 0;
    }

    /** Which type parameter of the class a member type is, or -1. */
    private static int typeParameterIndex(TypeElement owner, TypeMirror type) {
        if (!(type instanceof TypeVariable variable)) {
            return -1;
        }
        return owner.getTypeParameters().indexOf((TypeParameterElement) variable.asElement());
    }

    private static boolean nullableBound(TypeVariable variable) {
        return nullableAnnotated(variable.getUpperBound());
    }

    private static boolean nullableAnnotated(TypeMirror type) {
        return type != null && PlatformValues.hasAnnotation(type.getAnnotationMirrors(), "Nullable");
    }

    /** javac loses type annotations inside method bodies when it infers types, but the trees keep them. */
    private static boolean annotatedNullable(Tree typeTree) {
        Tree tree = typeTree;
        while (tree instanceof ParameterizedTypeTree generic) {
            tree = generic.getType();
        }
        if (tree instanceof AnnotatedTypeTree annotated) {
            return annotated.getAnnotations().stream().anyMatch(NullChecker::isNullableAnnotation);
        }
        if (tree instanceof MemberSelectTree select && select.getExpression() instanceof AnnotatedTypeTree annotated) {
            return annotated.getAnnotations().stream().anyMatch(NullChecker::isNullableAnnotation);
        }
        return false;
    }

    private static boolean isNullableAnnotation(AnnotationTree annotation) {
        String name = annotation.getAnnotationType().toString();
        return name.equals("Nullable") || name.endsWith(".Nullable");
    }

    private static boolean isNullMarked(Element element) {
        for (Element e = element; e != null; e = e.getEnclosingElement()) {
            if (PlatformValues.hasAnnotation(e.getAnnotationMirrors(), "NullMarked")) {
                return true;
            }
        }
        return false;
    }

    private boolean isRuntime(Element element) {
        return elements.getPackageOf(element).getQualifiedName().contentEquals("axl.lang");
    }

    private static boolean isRequireNonNull(ExecutableElement method) {
        return method.getSimpleName().contentEquals("requireNonNull")
                && ((TypeElement) method.getEnclosingElement()).getQualifiedName().contentEquals("java.util.Objects");
    }

    private static boolean isObjectsNonNull(ExecutableElement method) {
        return (method.getSimpleName().contentEquals("nonNull") || method.getSimpleName().contentEquals("isNull"))
                && ((TypeElement) method.getEnclosingElement()).getQualifiedName().contentEquals("java.util.Objects");
    }

    private static String erasedName(TypeMirror type) {
        return type instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element
                ? element.getQualifiedName().toString() : "";
    }

    /** The type as Axolotl writes it, for messages. */
    private static String typeName(TypeMirror type) {
        String name = type.toString().replaceAll("@\\S+ ", "").replaceAll("\\b(?:[a-z]\\w*\\.)+([A-Z])", "$1");
        return name.replace("Integer", "Int").replace("Character", "Char");
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
