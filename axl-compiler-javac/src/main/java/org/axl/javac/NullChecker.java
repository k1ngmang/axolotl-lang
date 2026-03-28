package org.axl.javac;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.DefaultCaseLabelTree;
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
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.tree.YieldTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
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
    // JDK methods that take null for a type argument without `?` and then return what they were given.
    private static final Set<String> NULL_ARGUMENT_ALLOWED = Set.of("java.util.Optional.orElse",
            "java.util.Map.getOrDefault");
    // Factories that throw on null elements.
    private static final Set<String> NULL_REJECTING_FACTORIES = Set.of("java.util.List.of", "java.util.List.copyOf",
            "java.util.Set.of", "java.util.Set.copyOf", "java.util.Map.of", "java.util.Map.copyOf", "java.util.Map.entry");

    /**
     * Why a value may be null, ordered so that merging two paths keeps the larger one. NullAway sees
     * {@code DECLARED} values itself, so where it checks a use, only the other kinds are reported here.
     */
    private enum Origin { NONE, HIDDEN, PLATFORM, DECLARED }

    /**
     * Which parts of a value may be null: the value itself and, by position, its type arguments. javac
     * drops the `?` of type arguments when it infers types, so they are followed by hand.
     */
    private record Shape(boolean nullable, List<Shape> args) {
        static final Shape NONE = new Shape(false, List.of());

        Shape arg(int index) {
            return index < args.size() ? args.get(index) : NONE;
        }

        Shape nonNull() {
            return new Shape(false, args);
        }

        Shape withArg(int index, Shape arg) {
            List<Shape> changed = new ArrayList<>(args);
            while (changed.size() <= index) {
                changed.add(NONE);
            }
            changed.set(index, arg);
            return new Shape(nullable, changed);
        }

        Shape or(Shape other) {
            List<Shape> merged = new ArrayList<>();
            for (int i = 0; i < Math.max(args.size(), other.args.size()); i++) {
                merged.add(arg(i).or(other.arg(i)));
            }
            return new Shape(nullable || other.nullable, merged);
        }

        /** Whether a type argument that has no `?` in the declared shape may be null in this one. */
        boolean losesQuestionMark(Shape declared) {
            for (int i = 0; i < declared.args.size(); i++) {
                if (!declared.arg(i).nullable && arg(i).nullable || arg(i).losesQuestionMark(declared.arg(i))) {
                    return true;
                }
            }
            return false;
        }
    }

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

    // Error Prone skips its analysis once the compilation has errors, so these wait until every class is analyzed.
    private final List<Report> reports = new ArrayList<>();

    void report() {
        reports.forEach(r -> trees.printMessage(Diagnostic.Kind.ERROR, r.message(), r.tree(), r.unit()));
        reports.clear();
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
        // The type arguments of `var` locals, lambda parameters and loop variables; their own nullness is in `state`.
        private final Map<Element, Shape> shapes = new HashMap<>();
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
                shapes.put(element, shape(init).nonNull());
            } else if (element.getKind() == ElementKind.LOCAL_VARIABLE) {
                store(init, element, variable.getType());
                checkTypeArguments(init, element.asType(), "`" + element.getSimpleName() + "`");
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
            if (inferredLocals.contains(target) && target.asType().getKind().isPrimitive()) {
                checkUnboxing(assignment.getExpression(), true);
            } else if (inferredLocals.contains(target)) {
                state.put(target, origin(assignment.getExpression()));
                shapes.merge(target, shape(assignment.getExpression()).nonNull(), Shape::or);
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
                // NullAway checks unboxing, unlike stores.
                checkUnboxing(value, true);
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
            } else {
                checkPrimitiveArguments(method, call.getArguments());
            }
            if (call.getMethodSelect() instanceof MemberSelectTree select && !isTypeName(select.getExpression())) {
                ExpressionTree receiver = select.getExpression();
                if (method.getEnclosingElement() instanceof TypeElement owner) {
                    checkTypeArgumentParameters(receiverBindings(receiver, method), asSuper(typeOf(receiver), owner),
                            method, call.getArguments());
                }
                if (method.getSimpleName().contentEquals("sorted") && call.getArguments().isEmpty()
                        && isStream(receiver) && shape(receiver).arg(0).nullable()) {
                    report(call, Origin.HIDDEN, false, "the elements may be null, and `sorted` compares them; "
                            + "drop the nulls first: `|> filter { it != null }`");
                }
            } else if (method.getModifiers().contains(Modifier.STATIC)) {
                checkFactoryArguments(method, call.getArguments());
                if (method.getSimpleName().contentEquals("sort") && call.getArguments().size() == 1
                        && erasedName(method.getEnclosingElement().asType()).equals("java.util.Collections")
                        && elementShape(call.getArguments().getFirst()).nullable()) {
                    report(call, Origin.HIDDEN, false, "the elements may be null, and `sort` compares them; "
                            + "drop the nulls first: `|> filter { it != null }`");
                }
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
            } else {
                checkPrimitiveArguments(constructor, newClass.getArguments());
            }
            if (newClass.getIdentifier() instanceof ParameterizedTypeTree generic && !generic.getTypeArguments().isEmpty()
                    && typeOf(newClass) instanceof DeclaredType created) {
                checkTypeArgumentParameters(typeBindings((TypeElement) constructor.getEnclosingElement(),
                        shapeOf(generic)), created, constructor, newClass.getArguments());
            }
            String owner = ((TypeElement) constructor.getEnclosingElement()).getQualifiedName().toString();
            if ((owner.equals("axl.lang.Ok") || owner.equals("axl.lang.Err")) && newClass.getArguments().size() == 1) {
                Boolean nullable = targetTypeArgNullable(getCurrentPath(), owner.equals("axl.lang.Ok") ? 0 : 1);
                ExpressionTree value = newClass.getArguments().getFirst();
                if (Boolean.FALSE.equals(nullable)) {
                    report(value, origin(value), !resultOfLambda(getCurrentPath()),
                            describe(value) + ", but the result type has no `?` there; " + FIX);
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
                    checkTypeArguments(ret.getExpression(), method.getReturnType(), "the result type");
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
        public Void visitSwitch(SwitchTree tree, Void unused) {
            return cases(tree.getExpression(), tree.getCases());
        }

        @Override
        public Void visitSwitchExpression(SwitchExpressionTree tree, Void unused) {
            return cases(tree.getExpression(), tree.getCases());
        }

        /** Each branch starts from what was known before the switch; what follows may come from any of them. */
        private Void cases(ExpressionTree selector, List<? extends CaseTree> cases) {
            scan(selector, null);
            // NullAway checks the selector, but not for values from Java code without annotations.
            report(selector, origin(selector), true, describe(selector) + ", but `when` needs a value to test; " + FIX);
            Map<Element, Origin> entry = new HashMap<>(state);
            boolean hasDefault = cases.stream().anyMatch(c -> c.getLabels().stream().anyMatch(l -> l instanceof DefaultCaseLabelTree));
            Map<Element, Origin> result = hasDefault ? null : entry;
            for (CaseTree branch : cases) {
                state = new HashMap<>(entry);
                scan(branch, null);
                boolean leaves = branch.getBody() instanceof StatementTree body && exits(body);
                if (!leaves) {
                    result = result == null ? state : merged(result, state);
                }
            }
            state = result == null ? entry : result;
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
            report(loop.getExpression(), origin(loop.getExpression()), true,
                    describe(loop.getExpression()) + ", but `for` needs something to go through; " + FIX);
            Element variable = trees.getElement(new TreePath(getCurrentPath(), loop.getVariable()));
            Shape loopElements = elementShape(loop.getExpression());
            if (variable != null) {
                inferredLocals.add(variable);
                shapes.put(variable, loopElements.nonNull());
            }
            // NullAway sees the elements of an iterable whose type javac knows with its `?`.
            boolean seen = shapeAs(shapeOf(typeOf(loop.getExpression()), Map.of()), typeOf(loop.getExpression()),
                    elements.getTypeElement("java.lang.Iterable")).arg(0).nullable();
            Origin elementOrigin = !loopElements.nullable() ? Origin.NONE : seen ? Origin.DECLARED : Origin.HIDDEN;
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
            if (!reporting) {
                // The last pass started from the final entry state; scanning again would only take
                // time, which grows with each level of nested loops.
                return merged(entry, state);
            }
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

        /** NullAway does not check what is thrown, and throwing null throws a NullPointerException. */
        @Override
        public Void visitThrow(ThrowTree tree, Void unused) {
            super.visitThrow(tree, unused);
            report(tree.getExpression(), origin(tree.getExpression()), false,
                    describe(tree.getExpression()) + ", but `throw` needs an exception; " + FIX);
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
            List<Shape> passed = passedShapes(getCurrentPath());
            List<Shape> declared = parameterShapes(trees.getTypeMirror(getCurrentPath()), Map.of());
            boolean fromJava = strict && passedToJava(getCurrentPath());
            for (int i = 0; i < lambda.getParameters().size(); i++) {
                Element element = trees.getElement(new TreePath(getCurrentPath(), lambda.getParameters().get(i)));
                if (element == null) {
                    continue;
                }
                inferredLocals.add(element);
                Shape fromCall = i < passed.size() ? passed.get(i) : Shape.NONE;
                Shape fromType = i < declared.size() ? declared.get(i) : Shape.NONE;
                shapes.put(element, fromCall.or(fromType).nonNull());
                state.put(element, element.asType().getKind().isPrimitive() ? Origin.NONE
                        : fromType.nullable() ? Origin.DECLARED : fromCall.nullable() ? Origin.HIDDEN
                        : fromJava ? Origin.PLATFORM : Origin.NONE);
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
            if (element(reference) instanceof ExecutableElement method && checkReference(reference, method)) {
                return null;
            }
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
         * A method reference gets what a lambda in its place would get as parameters, and must return
         * what it would return. Returns whether anything is wrong.
         */
        private boolean checkReference(MemberReferenceTree reference, ExecutableElement method) {
            TypeMirror target = trees.getTypeMirror(getCurrentPath());
            if (!(target instanceof DeclaredType declared) || !(declared.asElement() instanceof TypeElement iface)
                    || functionalMethod(iface) == null || method.getKind() == ElementKind.CONSTRUCTOR) {
                return false;
            }
            ExecutableElement sam = functionalMethod(iface);
            List<Shape> passed = passedShapes(getCurrentPath());
            List<Shape> written = parameterShapes(target, Map.of());
            boolean nullAwayChecks = isNullMarked(iface);
            boolean unbound = !method.getModifiers().contains(Modifier.STATIC) && isTypeName(reference.getQualifierExpression());
            String name = "`" + reference + "`";
            for (int i = 0; i < sam.getParameters().size(); i++) {
                Origin origin = i < passed.size() && passed.get(i).nullable() ? Origin.HIDDEN
                        : i < written.size() && written.get(i).nullable() ? Origin.DECLARED : Origin.NONE;
                int index = unbound ? i - 1 : i;
                if (unbound && i == 0) {
                    if (report(reference, origin, nullAwayChecks, name + " is called on values that may be null; "
                            + "use a lambda with `?.`, or drop the nulls first")) {
                        return true;
                    }
                } else if (isAxolotlCode(method) && index < method.getParameters().size()) {
                    VariableElement param = method.getParameters().get(index);
                    if (!param.asType().getKind().isPrimitive() && !PlatformValues.isNullable(param, param.asType())
                            && report(reference, origin, nullAwayChecks, name + " may get null, but the parameter `"
                            + param.getSimpleName() + "` does not accept null; pass a lambda that checks for null")) {
                        return true;
                    }
                }
            }
            TypeMirror returned = ((ExecutableType) types.asMemberOf(declared, sam)).getReturnType();
            if (returned.getKind() == TypeKind.VOID || returned.getKind().isPrimitive()
                    || !PlatformValues.isNullable(method, method.getReturnType())
                    || resultMayBeNull(getCurrentPath(), iface, sam, returned)) {
                return false;
            }
            return report(reference, Origin.DECLARED, nullAwayChecks, name + " may return null, but `"
                    + iface.getSimpleName() + "." + sam.getSimpleName() + "` returns `" + typeName(returned)
                    + "` here; use a lambda with `?:`");
        }

        /** What may be null in the parameters that the call a lambda or method reference is passed to gives it. */
        private List<Shape> passedShapes(TreePath function) {
            TreePath parent = function.getParentPath();
            while (parent.getLeaf() instanceof ParenthesizedTree) {
                parent = parent.getParentPath();
            }
            List<? extends ExpressionTree> args;
            Map<Element, Shape> fixed;
            if (parent.getLeaf() instanceof MethodInvocationTree call) {
                args = call.getArguments();
                fixed = call.getMethodSelect() instanceof MemberSelectTree select && !isTypeName(select.getExpression())
                        && trees.getElement(parent) instanceof ExecutableElement method
                        ? receiverBindings(select.getExpression(), method) : Map.of();
            } else if (parent.getLeaf() instanceof NewClassTree newClass) {
                args = newClass.getArguments();
                fixed = Map.of();
            } else {
                return List.of();
            }
            if (!(trees.getElement(parent) instanceof ExecutableElement callee)) {
                return List.of();
            }
            for (int i = 0; i < args.size(); i++) {
                if (unwrap(args.get(i)) == function.getLeaf()) {
                    return parameterShapes(parameterType(callee, i, args.size()), bindings(fixed, callee, args, false));
                }
            }
            return List.of();
        }

        /** The parameters of the single method of a functional interface type. */
        private List<Shape> parameterShapes(TypeMirror type, Map<Element, Shape> bindings) {
            if (!(type instanceof DeclaredType declared) || !(declared.asElement() instanceof TypeElement iface)
                    || functionalMethod(iface) == null) {
                return List.of();
            }
            Map<Element, Shape> own = new HashMap<>();
            for (int i = 0; i < iface.getTypeParameters().size() && i < declared.getTypeArguments().size(); i++) {
                own.put(iface.getTypeParameters().get(i), shapeOf(declared.getTypeArguments().get(i), bindings));
            }
            ExecutableType sam = (ExecutableType) types.asMemberOf((DeclaredType) iface.asType(), functionalMethod(iface));
            return sam.getParameterTypes().stream().map(p -> shapeOf(p, own)).toList();
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
            TypeMirror type = trees.getTypeMirror(function);
            boolean javaType = type instanceof DeclaredType declared && !PlatformValues.trusted(declared.asElement());
            if (parent.getLeaf() instanceof MethodInvocationTree || parent.getLeaf() instanceof NewClassTree) {
                Element method = trees.getElement(parent);
                // An Axolotl function may hand a parameter of a JDK interface type on to Java code.
                return method != null && (!PlatformValues.trusted(method) || isAxolotlCode(method) && javaType);
            }
            return javaType;
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
            TreePath parent = lambda.getParentPath();
            while (parent.getLeaf() instanceof ParenthesizedTree) {
                parent = parent.getParentPath();
            }
            boolean attemptInLambda = parent.getLeaf() instanceof MethodInvocationTree
                    && trees.getElement(parent) instanceof ExecutableElement callee
                    && callee.getSimpleName().contentEquals("attempt") && isRuntime(callee) && resultOfLambda(parent);
            if (attemptInLambda) {
                report(result, origin, false, describe(result) + ", but the result type has no `?` there; " + FIX);
                return;
            }
            report(result, origin, isNullMarked(iface), describe(result) + ", but the lambda must not return null: " + name
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
                ExpressionTree receiver = select.getExpression();
                return shapeAs(shape(receiver), typeOf(receiver), elements.getTypeElement("java.util.Map")).arg(1).nullable();
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
                        target = lambdaResultType(p);
                        if (target == null) {
                            return null;
                        }
                    } else if (p.getLeaf() instanceof MethodTree && trees.getElement(p) instanceof ExecutableElement method) {
                        target = method.getReturnType();
                    }
                }
            } else if (parent.getLeaf() instanceof LambdaExpressionTree) {
                target = lambdaResultType(parent);
            } else if (parent.getLeaf() instanceof VariableTree variable && !isVar(variable)) {
                Element element = trees.getElement(parent);
                target = element == null ? null : element.asType();
            }
            if (target instanceof DeclaredType declared && index < declared.getTypeArguments().size()) {
                return nullableAnnotated(declared.getTypeArguments().get(index));
            }
            return null;
        }

        /** NullAway checks `Ok(x)` against the result type of a function, but not of a lambda. */
        private boolean resultOfLambda(TreePath expression) {
            TreePath parent = expression.getParentPath();
            while (parent.getLeaf() instanceof ParenthesizedTree) {
                parent = parent.getParentPath();
            }
            if (parent.getLeaf() instanceof LambdaExpressionTree) {
                return true;
            }
            for (TreePath p = parent; parent.getLeaf() instanceof ReturnTree && p != null; p = p.getParentPath()) {
                if (p.getLeaf() instanceof LambdaExpressionTree) {
                    return true;
                }
                if (p.getLeaf() instanceof MethodTree) {
                    return false;
                }
            }
            return false;
        }

        /**
         * What a lambda kept in a local of a written type returns. javac infers the types of other
         * lambdas and drops the `?` of their type arguments, so those are unknown: null.
         */
        private TypeMirror lambdaResultType(TreePath lambda) {
            TreePath parent = lambda.getParentPath();
            while (parent.getLeaf() instanceof ParenthesizedTree) {
                parent = parent.getParentPath();
            }
            if (!(parent.getLeaf() instanceof VariableTree variable) || isVar(variable)
                    || !(trees.getElement(parent) instanceof VariableElement local)
                    || !(local.asType() instanceof DeclaredType type) || !(type.asElement() instanceof TypeElement iface)
                    || functionalMethod(iface) == null) {
                return null;
            }
            return ((ExecutableType) types.asMemberOf(type, functionalMethod(iface))).getReturnType();
        }

        private void checkArguments(ExecutableElement method, List<? extends ExpressionTree> args) {
            List<? extends VariableElement> params = method.getParameters();
            int checkedCount = method.isVarArgs() ? params.size() - 1 : params.size();
            for (int i = 0; i < Math.min(checkedCount, args.size()); i++) {
                VariableElement param = params.get(i);
                checkValue(args.get(i), param.asType(), param, true, "the parameter `" + param.getSimpleName() + "` does not accept null");
            }
        }

        /** `Math.abs(x)` unboxes `x`. */
        private void checkPrimitiveArguments(ExecutableElement method, List<? extends ExpressionTree> args) {
            for (int i = 0; i < args.size() && !method.getParameters().isEmpty(); i++) {
                if (parameterType(method, i, args.size()).getKind().isPrimitive()) {
                    checkUnboxing(args.get(i), true);
                }
            }
        }

        /**
         * `xs.add(x)` on an `ArrayList<String>`: Java says nothing about null there, but the type argument
         * without `?` promises values that are not null.
         *
         * @param owner the type that declares the method, with the type arguments of the receiver
         */
        private void checkTypeArgumentParameters(Map<Element, Shape> bindings, DeclaredType owner, ExecutableElement method,
                                                 List<? extends ExpressionTree> args) {
            if (owner == null || !(owner.asElement() instanceof TypeElement type) || isNullMarked(type)
                    || isOneOf(method, NULL_ARGUMENT_ALLOWED)) {
                return;
            }
            List<? extends VariableElement> params = method.getParameters();
            int checkedCount = method.isVarArgs() ? params.size() - 1 : params.size();
            for (int i = 0; i < Math.min(checkedCount, args.size()); i++) {
                if (!(params.get(i).asType() instanceof TypeVariable variable) || nullableAnnotated(variable)) {
                    continue;
                }
                int position = type.getTypeParameters().indexOf((TypeParameterElement) variable.asElement());
                Shape bound = bindings.get(variable.asElement());
                if (position >= 0 && position < owner.getTypeArguments().size()
                        && owner.getTypeArguments().get(position).getKind() == TypeKind.DECLARED
                        && bound != null && !bound.nullable()) {
                    ExpressionTree arg = args.get(i);
                    String name = method.getKind() == ElementKind.CONSTRUCTOR ? type.getSimpleName().toString()
                            : method.getSimpleName().toString();
                    report(arg, origin(arg), false, describe(arg) + ", but `" + name
                            + "` takes a type argument written without `?` here; add `?` to the type argument or " + FIX);
                }
            }
        }

        /** `List.of(a, null)` throws. */
        private void checkFactoryArguments(ExecutableElement method, List<? extends ExpressionTree> args) {
            if (!isOneOf(method, NULL_REJECTING_FACTORIES)) {
                return;
            }
            for (int i = 0; i < args.size(); i++) {
                ExpressionTree arg = args.get(i);
                TypeMirror param = parameterType(method, i, args.size());
                Map<Element, Shape> bound = new HashMap<>();
                bind(param, valueShape(arg), typeOf(arg), bound);
                if (method.getTypeParameters().stream().noneMatch(v -> bound.containsKey(v) && bound.get(v).nullable())) {
                    continue;
                }
                if (param instanceof TypeVariable) {
                    report(arg, origin(arg), false, describe(arg) + ", but `" + method.getSimpleName()
                            + "` does not take null; " + FIX);
                } else {
                    report(arg, Origin.HIDDEN, false, "the elements of the value may be null, but `" + method.getSimpleName()
                            + "` does not take null elements; drop the nulls first: `|> filter { it != null }`");
                }
            }
        }

        /** `val ys: List<String> = xs` where the elements of `xs` may be null. */
        private void checkTypeArguments(ExpressionTree value, TypeMirror declared, String target) {
            if (!(declared instanceof DeclaredType type) || !(type.asElement() instanceof TypeElement element)) {
                return;
            }
            // `Ok(x)` and `attempt` are checked against the type they go to.
            if (element(value) != null && isRuntime(element(value))) {
                return;
            }
            Shape wanted = shapeOf(declared, Map.of());
            TypeMirror valueType = typeOf(value);
            // NullAway compares the types javac knows itself.
            if (shapeAs(shapeOf(valueType, Map.of()), valueType, element).losesQuestionMark(wanted)) {
                return;
            }
            if (shapeAs(shape(value), valueType, element).losesQuestionMark(wanted)) {
                report(value, Origin.HIDDEN, false, "a type argument of the value has `?`, but " + target
                        + " has none there; add `?` to the type argument of " + target + " or drop the nulls first");
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
            if (unwrap(value) instanceof SwitchExpressionTree tree) {
                // A switch takes the primitive type it is assigned to, and NullAway does not look into its branches.
                yielded(tree).forEach(branch -> checkUnboxing(branch, false));
            } else if (type != null && BOXES.contains(erasedName(type))) {
                report(value, origin(value), nullAwayChecks, describe(value) + ", but is used as a number or boolean here; " + FIX);
            }
        }

        /** The values the branches of a switch expression give, without those of switches nested in them. */
        private List<ExpressionTree> yielded(SwitchExpressionTree tree) {
            List<ExpressionTree> values = new ArrayList<>();
            for (CaseTree branch : tree.getCases()) {
                if (branch.getBody() instanceof ExpressionTree value) {
                    values.add(value);
                } else if (branch.getBody() != null) {
                    new TreeScanner<Void, Void>() {
                        @Override
                        public Void visitYield(YieldTree yield, Void unused) {
                            values.add(yield.getValue());
                            return null;
                        }

                        @Override
                        public Void visitSwitchExpression(SwitchExpressionTree nested, Void unused) {
                            return null;
                        }

                        @Override
                        public Void visitLambdaExpression(LambdaExpressionTree lambda, Void unused) {
                            return null;
                        }
                    }.scan(branch.getBody(), null);
                }
            }
            return values;
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
            if (e instanceof SwitchExpressionTree tree) {
                return yielded(tree).stream().map(this::origin).max(Origin::compareTo).orElse(Origin.NONE);
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
            return declared == Origin.NONE && (nullableAnnotated(typeOf(e)) || shape(e).nullable()) ? Origin.HIDDEN : declared;
        }

        private Origin callOrigin(MethodInvocationTree call, ExecutableElement method) {
            if (method.getSimpleName().contentEquals("or") && isRuntime(method) && call.getArguments().size() == 2
                    && unwrap(call.getArguments().get(1)) instanceof LambdaExpressionTree fallback
                    && fallback.getBody() instanceof ExpressionTree body) {
                return origin(body);
            }
            Origin declared = unknownOrigin(method);
            if (declared == Origin.PLATFORM && returnsWrittenTypeArgument(call, method)) {
                // `List<String>.get` returns what went in, and a type argument without `?` holds no nulls.
                declared = Origin.NONE;
            }
            if (declared != Origin.NONE) {
                return declared;
            }
            if (isOneOf(method, NULL_ARGUMENT_ALLOWED) && !call.getArguments().isEmpty()) {
                Origin given = origin(call.getArguments().getLast());
                if (given != Origin.NONE) {
                    return given;
                }
            }
            if (!shape(call).nullable()) {
                return Origin.NONE;
            }
            return nullAwaySees(call, method) ? Origin.DECLARED : Origin.HIDDEN;
        }

        private boolean returnsWrittenTypeArgument(MethodInvocationTree call, ExecutableElement method) {
            if (!(method.getReturnType() instanceof TypeVariable variable)
                    || !(method.getEnclosingElement() instanceof TypeElement owner)
                    || !(call.getMethodSelect() instanceof MemberSelectTree select) || isTypeName(select.getExpression())) {
                return false;
            }
            int position = owner.getTypeParameters().indexOf((TypeParameterElement) variable.asElement());
            DeclaredType seen = asSuper(typeOf(select.getExpression()), owner);
            return position >= 0 && seen != null && position < seen.getTypeArguments().size()
                    && seen.getTypeArguments().get(position).getKind() == TypeKind.DECLARED;
        }

        /** NullAway infers the type arguments of a null-marked method from the types javac knows. */
        private boolean nullAwaySees(MethodInvocationTree call, ExecutableElement method) {
            if (!isNullMarked(method)) {
                return false;
            }
            Map<Element, Shape> bound = new HashMap<>();
            List<? extends ExpressionTree> args = call.getArguments();
            for (int i = 0; i < args.size(); i++) {
                TypeMirror type = typeOf(args.get(i));
                bind(parameterType(method, i, args.size()), shapeOf(type, Map.of()), type, bound);
            }
            return shapeOf(method.getReturnType(), bound).nullable();
        }

        /** What may be null in the type arguments of a value, and in the value itself where a type argument makes it so. */
        private Shape shape(ExpressionTree expression) {
            ExpressionTree e = unwrap(expression);
            Shape known = shapeOf(typeOf(e), Map.of());
            Element element = element(e);
            if (e instanceof ConditionalExpressionTree c) {
                return known.or(shape(c.getTrueExpression())).or(shape(c.getFalseExpression()));
            }
            if (e instanceof AssignmentTree assignment) {
                return known.or(shape(assignment.getExpression()));
            }
            if (e instanceof NewClassTree newClass && element instanceof ExecutableElement constructor) {
                return known.or(newShape(newClass, constructor));
            }
            if (e instanceof MethodInvocationTree call && element instanceof ExecutableElement method) {
                return known.or(callShape(call, method));
            }
            if (e instanceof IdentifierTree) {
                // The nullness of a variable itself depends on where it is read, as `origin` knows.
                return shapes.containsKey(element) ? known.nonNull().or(shapes.get(element)) : known.nonNull();
            }
            if (e instanceof MemberSelectTree select && element instanceof VariableElement field && !isTypeName(select.getExpression())) {
                return known.or(shapeOf(field.asType(), receiverBindings(select.getExpression(), field)));
            }
            return known;
        }

        /** The shape of a value with its own nullness as far as `?` goes. */
        private Shape valueShape(ExpressionTree expression) {
            Origin origin = origin(expression);
            Shape shape = shape(expression);
            return origin == Origin.HIDDEN || origin == Origin.DECLARED ? new Shape(true, shape.args()) : shape.nonNull();
        }

        private Shape callShape(MethodInvocationTree call, ExecutableElement method) {
            ExpressionTree receiver = call.getMethodSelect() instanceof MemberSelectTree select
                    && !isTypeName(select.getExpression()) ? select.getExpression() : null;
            List<? extends ExpressionTree> args = call.getArguments();
            if (isRequireNonNull(method) && !args.isEmpty()) {
                return shape(args.getFirst()).nonNull();
            }
            if (receiver != null && isStream(receiver) && method.getSimpleName().contentEquals("collect")
                    && args.size() == 1 && collectsElements(args.getFirst())) {
                return new Shape(false, List.of(shape(receiver).arg(0)));
            }
            Map<Element, Shape> fixed = new HashMap<>(receiver == null ? Map.of() : receiverBindings(receiver, method));
            List<? extends Tree> written = call.getTypeArguments();
            for (int i = 0; i < written.size() && i < method.getTypeParameters().size(); i++) {
                fixed.put(method.getTypeParameters().get(i), shapeOf(written.get(i)));
            }
            Shape result = shapeOf(method.getReturnType(), bindings(fixed, method, args, true));
            if (method.getSimpleName().contentEquals("ofNullable") || receiver != null && filtersNulls(call, method)) {
                result = result.withArg(0, result.arg(0).nonNull());
            }
            return result;
        }

        /** `Collectors.toList()` and the like keep the elements of the stream. */
        private boolean collectsElements(ExpressionTree collector) {
            return unwrap(collector) instanceof MethodInvocationTree call && element(call) instanceof ExecutableElement method
                    && ((TypeElement) method.getEnclosingElement()).getQualifiedName().contentEquals("java.util.stream.Collectors")
                    && Set.of("toList", "toSet", "toUnmodifiableList", "toUnmodifiableSet").contains(method.getSimpleName().toString());
        }

        /** `ArrayList<String?>()` says what its type arguments are; `ArrayList<>(xs)` takes them from the arguments. */
        private Shape newShape(NewClassTree newClass, ExecutableElement constructor) {
            if (newClass.getIdentifier() instanceof ParameterizedTypeTree generic && !generic.getTypeArguments().isEmpty()) {
                return shapeOf(generic).nonNull();
            }
            TypeElement owner = (TypeElement) constructor.getEnclosingElement();
            return shapeOf(owner.asType(), bindings(Map.of(), constructor, newClass.getArguments(), true)).nonNull();
        }

        /** The type arguments of the receiver as those of the type that declares the member. */
        private Map<Element, Shape> receiverBindings(ExpressionTree receiver, Element member) {
            if (!(member.getEnclosingElement() instanceof TypeElement owner)) {
                return Map.of();
            }
            return typeBindings(owner, shapeAs(shape(receiver), typeOf(receiver), owner));
        }

        private static Map<Element, Shape> typeBindings(TypeElement type, Shape shape) {
            Map<Element, Shape> bindings = new HashMap<>();
            List<? extends TypeParameterElement> params = type.getTypeParameters();
            for (int i = 0; i < params.size(); i++) {
                bindings.put(params.get(i), shape.arg(i));
            }
            return bindings;
        }

        /**
         * The type variables of a call: the fixed ones come from the receiver or the written type
         * arguments, the others from the arguments. A lambda gives what it returns.
         */
        private Map<Element, Shape> bindings(Map<Element, Shape> fixed, ExecutableElement method,
                                             List<? extends ExpressionTree> args, boolean withLambdas) {
            Map<Element, Shape> bound = new HashMap<>();
            for (int i = 0; i < args.size() && !method.getParameters().isEmpty(); i++) {
                TypeMirror param = parameterType(method, i, args.size());
                ExpressionTree arg = unwrap(args.get(i));
                if (arg instanceof LambdaExpressionTree lambda) {
                    if (withLambdas && lambda.getBody() instanceof ExpressionTree body) {
                        bindResult(param, body, bound);
                    }
                } else if (!(arg instanceof MemberReferenceTree)) {
                    bind(param, valueShape(arg), typeOf(arg), bound);
                }
            }
            // Java code says nothing about null: `<T> T f(T)` may return anything, but what goes into a
            // container comes out of it.
            for (TypeParameterElement variable : method.getTypeParameters()) {
                Shape shape = bound.get(variable);
                if (shape != null && shape.nullable() && !isRuntime(method) && !isTypeArgumentOf(method.getReturnType(), variable)) {
                    bound.put(variable, shape.nonNull());
                }
            }
            bound.putAll(fixed);
            return bound;
        }

        private void bindResult(TypeMirror param, ExpressionTree body, Map<Element, Shape> bound) {
            if (!(param instanceof DeclaredType declared) || !(declared.asElement() instanceof TypeElement iface)
                    || functionalMethod(iface) == null
                    || !(functionalMethod(iface).getReturnType() instanceof TypeVariable result)) {
                return;
            }
            int position = iface.getTypeParameters().indexOf((TypeParameterElement) result.asElement());
            if (position >= 0 && position < declared.getTypeArguments().size()) {
                // Elsewhere the lambda must not return null, which `checkLambdaResult` reports.
                TypeMirror target = declared.getTypeArguments().get(position);
                boolean mayReturnNull = target instanceof TypeVariable variable && nullableBound(variable);
                bind(target, mayReturnNull ? valueShape(body) : shape(body).nonNull(), typeOf(body), bound);
            }
        }

        /** Matches a parameter type against the shape of an argument of the given type. */
        private void bind(TypeMirror param, Shape arg, TypeMirror argType, Map<Element, Shape> bound) {
            if (param instanceof TypeVariable variable) {
                bound.merge(variable.asElement(), nullableAnnotated(param) ? arg.nonNull() : arg, Shape::or);
            } else if (param instanceof WildcardType wildcard) {
                TypeMirror limit = wildcard.getExtendsBound() != null ? wildcard.getExtendsBound() : wildcard.getSuperBound();
                if (limit != null) {
                    bind(limit, arg, argType, bound);
                }
            } else if (param instanceof DeclaredType declared && declared.asElement() instanceof TypeElement type) {
                DeclaredType actual = asSuper(argType, type);
                Shape seen = shapeAs(arg, argType, type);
                List<? extends TypeMirror> params = declared.getTypeArguments();
                for (int i = 0; i < params.size(); i++) {
                    TypeMirror actualArg = actual != null && i < actual.getTypeArguments().size() ? actual.getTypeArguments().get(i) : null;
                    bind(params.get(i), seen.arg(i), actualArg, bound);
                }
            } else if (param instanceof ArrayType array && argType instanceof ArrayType actual) {
                bind(array.getComponentType(), arg.arg(0), actual.getComponentType(), bound);
            }
        }

        private Shape elementShape(ExpressionTree iterable) {
            if (typeOf(iterable) instanceof ArrayType) {
                return shape(iterable).arg(0);
            }
            return shapeAs(shape(iterable), typeOf(iterable), elements.getTypeElement("java.lang.Iterable")).arg(0);
        }

        /** `filter { it != null }` and `filter(Objects::nonNull)` leave only values that are not null. */
        private boolean filtersNulls(MethodInvocationTree call, ExecutableElement method) {
            if (!method.getSimpleName().contentEquals("filter") || call.getArguments().size() != 1) {
                return false;
            }
            if (unwrap(call.getArguments().getFirst()) instanceof MemberReferenceTree reference) {
                return element(reference) instanceof ExecutableElement predicate && isObjectsNonNull(predicate)
                        && predicate.getSimpleName().contentEquals("nonNull");
            }
            if (!(unwrap(call.getArguments().getFirst()) instanceof LambdaExpressionTree lambda)
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
                // `x$e1` holds the subject of `when (val x = ...)` and `x$1` a narrowed `x`.
                String plain = identifier.getName().toString().replaceAll("\\$e?\\d+$", "");
                String name = plain.isEmpty() ? "the subject of this `when` may be null" : "`" + plain + "` may be null";
                return switch (origin) {
                    case PLATFORM -> name + ": it holds a value from Java code without nullness annotations";
                    case HIDDEN -> name + ": it holds a value of a type argument written with `?`";
                    default -> name;
                };
            }
            if (e instanceof MethodInvocationTree call && element(call) instanceof ExecutableElement method
                    && method.getSimpleName().contentEquals("map") && isRuntime(method)) {
                return "the value of `?.` may be null";
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
                Tree at = position(tree);
                reports.add(new Report(prefix + message, at, unit));
            }
            return true;
        }

        /**
         * Where the .axl column can be found: javac places a call at its parenthesis, and `a?.b` and
         * `a ?: b` become calls of Nulls named nowhere in the .axl line, so they are placed at `a`.
         */
        private Tree position(Tree tree) {
            Tree at = tree instanceof ExpressionTree e ? unwrap(e) : tree;
            while (at instanceof MethodInvocationTree call && call.getMethodSelect() instanceof MemberSelectTree select
                    && select.getExpression() instanceof IdentifierTree owner && owner.getName().contentEquals("Nulls")
                    && !call.getArguments().isEmpty()) {
                at = unwrap(call.getArguments().getFirst());
            }
            return at instanceof MethodInvocationTree call ? call.getMethodSelect() : at;
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

    /** The type of the parameter an argument goes to; the extra arguments of varargs go to its elements. */
    private static TypeMirror parameterType(ExecutableElement method, int index, int argCount) {
        List<? extends VariableElement> params = method.getParameters();
        TypeMirror type = params.get(Math.min(index, params.size() - 1)).asType();
        if (method.isVarArgs() && index >= params.size() - 1 && argCount != params.size() && type instanceof ArrayType array) {
            return array.getComponentType();
        }
        return type;
    }

    /** `List<T>` but not `T` or `List<List<T>>`. */
    private static boolean isTypeArgumentOf(TypeMirror type, TypeParameterElement variable) {
        if (!(type instanceof DeclaredType declared)) {
            return false;
        }
        for (TypeMirror arg : declared.getTypeArguments()) {
            TypeMirror inner = arg instanceof WildcardType wildcard && wildcard.getExtendsBound() != null ? wildcard.getExtendsBound() : arg;
            if (inner instanceof TypeVariable v && v.asElement().equals(variable)) {
                return true;
            }
        }
        return false;
    }

    /** The supertype of the type that the target class declares, with its type arguments, or null. */
    private DeclaredType asSuper(TypeMirror type, TypeElement target) {
        if (!(type instanceof DeclaredType declared)) {
            return null;
        }
        if (declared.asElement().equals(target)) {
            return declared;
        }
        for (TypeMirror parent : types.directSupertypes(type)) {
            DeclaredType found = asSuper(parent, target);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The shape of a value of the type seen as its supertype that the target class declares. */
    private Shape shapeAs(Shape shape, TypeMirror type, TypeElement target) {
        if (!(type instanceof DeclaredType declared) || !(declared.asElement() instanceof TypeElement element)) {
            return Shape.NONE;
        }
        DeclaredType generic = asSuper(element.asType(), target);
        if (generic == null) {
            return Shape.NONE;
        }
        Map<Element, Shape> bindings = new HashMap<>();
        List<? extends TypeParameterElement> params = element.getTypeParameters();
        for (int i = 0; i < params.size(); i++) {
            bindings.put(params.get(i), shape.arg(i));
        }
        return new Shape(shape.nullable(), shapeOf(generic, bindings).args());
    }

    /** The shape of a type, with the given shapes for its type variables. */
    private static Shape shapeOf(TypeMirror type, Map<Element, Shape> bindings) {
        if (type instanceof TypeVariable variable) {
            Shape bound = bindings.getOrDefault(variable.asElement(), Shape.NONE);
            return nullableAnnotated(type) ? new Shape(true, bound.args()) : bound;
        }
        if (type instanceof WildcardType wildcard) {
            TypeMirror limit = wildcard.getExtendsBound() != null ? wildcard.getExtendsBound() : wildcard.getSuperBound();
            Shape inner = limit == null ? Shape.NONE : shapeOf(limit, bindings);
            return nullableAnnotated(type) ? new Shape(true, inner.args()) : inner;
        }
        List<Shape> args = new ArrayList<>();
        if (type instanceof DeclaredType declared) {
            declared.getTypeArguments().forEach(a -> args.add(shapeOf(a, bindings)));
        } else if (type instanceof ArrayType array) {
            args.add(shapeOf(array.getComponentType(), bindings));
        }
        return new Shape(nullableAnnotated(type), args);
    }

    /** The shape of a written type. */
    private static Shape shapeOf(Tree typeTree) {
        Tree tree = typeTree instanceof AnnotatedTypeTree annotated ? annotated.getUnderlyingType() : typeTree;
        List<Shape> args = new ArrayList<>();
        if (tree instanceof ParameterizedTypeTree generic) {
            generic.getTypeArguments().forEach(a -> args.add(shapeOf(a)));
        }
        return new Shape(annotatedNullable(typeTree), args);
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
            } else if (typedValue(e) != null) {
                e = typedValue(e);
            } else {
                return e;
            }
        }
    }

    /** `x` of `Nulls.typed(x).value()`, which only gives `x` a type of its own, or null. */
    private static ExpressionTree typedValue(ExpressionTree e) {
        if (e instanceof MethodInvocationTree value && value.getArguments().isEmpty()
                && value.getMethodSelect() instanceof MemberSelectTree select && select.getIdentifier().contentEquals("value")
                && select.getExpression() instanceof MethodInvocationTree typed && typed.getArguments().size() == 1
                && typed.getMethodSelect() instanceof MemberSelectTree typedSelect
                && typedSelect.getIdentifier().contentEquals("typed")
                && typedSelect.getExpression() instanceof IdentifierTree nulls && nulls.getName().contentEquals("Nulls")) {
            return typed.getArguments().getFirst();
        }
        return null;
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
