package org.axl.javac;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

/**
 * A generic class created or written without type arguments, `ArrayList()` or `val xs: ArrayList`,
 * lets values of any type in, and so does `o` after `o is List` stored as a `List<String>`. This runs in the javac plugin, so builds that pass no lint options see it too.
 */
final class RawTypes implements TaskListener {

    static final String PREFIX = "raw type: ";

    private final Trees trees;
    // Printed once every class is analyzed: Error Prone skips a class that already has an error.
    private final List<Runnable> found = new ArrayList<>();

    RawTypes(JavacTask task) {
        this.trees = Trees.instance(task);
    }

    @Override
    public void finished(TaskEvent event) {
        if (event.getKind() == TaskEvent.Kind.ANALYZE && NullChecker.isGenerated(event.getCompilationUnit())) {
            new Scanner(event.getCompilationUnit()).scan(new TreePath(event.getCompilationUnit()), null);
        }
    }

    void report() {
        found.forEach(Runnable::run);
        found.clear();
    }

    static String message(String type) {
        return "`" + type + "` needs its type arguments, such as `" + type + "<String>`, or any value could go in; "
                + "a new object written `" + type + "<>()` takes them from the type it is stored as";
    }

    static String uncheckedMessage(String raw, String target) {
        return "`is " + raw + "` cannot check type arguments, so nothing checks that the value holds what `" + target
                + "` promises; use it as a `" + raw + "` and test its elements with `is`";
    }

    private final class Scanner extends TreePathScanner<Void, Void> {

        private final CompilationUnitTree unit;

        Scanner(CompilationUnitTree unit) {
            this.unit = unit;
        }

        @Override
        public Void visitNewClass(NewClassTree creation, Void unused) {
            check(creation.getIdentifier());
            return super.visitNewClass(creation, unused);
        }

        @Override
        public Void visitVariable(VariableTree variable, Void unused) {
            // `x instanceof List o` is tested raw on purpose: Java keeps no type arguments to test.
            boolean binding = getCurrentPath().getParentPath().getLeaf() instanceof BindingPatternTree;
            if (variable.getType() != null && !binding) {
                check(variable.getType());
            }
            if (variable.getInitializer() != null) {
                converted(variable.getInitializer(), trees.getTypeMirror(getCurrentPath()));
            }
            return super.visitVariable(variable, unused);
        }

        @Override
        public Void visitAssignment(AssignmentTree assignment, Void unused) {
            converted(assignment.getExpression(), type(assignment.getVariable()));
            return super.visitAssignment(assignment, unused);
        }

        @Override
        public Void visitReturn(ReturnTree ret, Void unused) {
            Tree function = enclosingFunction();
            if (ret.getExpression() != null && function instanceof MethodTree method && method.getReturnType() != null) {
                converted(ret.getExpression(), type(method.getReturnType()));
            }
            return super.visitReturn(ret, unused);
        }

        private Tree enclosingFunction() {
            for (TreePath path = getCurrentPath(); path != null; path = path.getParentPath()) {
                if (path.getLeaf() instanceof MethodTree || path.getLeaf() instanceof LambdaExpressionTree) {
                    return path.getLeaf();
                }
            }
            return null;
        }

        private TypeMirror type(Tree tree) {
            return trees.getTypeMirror(new TreePath(getCurrentPath(), tree));
        }

        /** A raw value stored where type arguments are written: Java only warns of the unchecked conversion. */
        private void converted(ExpressionTree value, TypeMirror target) {
            TypeMirror source = type(value);
            if (isRaw(source) && target instanceof DeclaredType declared && !declared.getTypeArguments().isEmpty()
                    && testedName(value)) {
                String message = PREFIX + uncheckedMessage(simpleName(source), simpleName(target));
                found.add(() -> trees.printMessage(Diagnostic.Kind.ERROR, message, value, unit));
            }
        }

        /** NullChecker reports raw values from Java code; this is a name narrowed by `is` to a raw type. */
        private boolean testedName(ExpressionTree value) {
            Element element = trees.getElement(new TreePath(getCurrentPath(), value));
            return element != null && element.getKind() == ElementKind.BINDING_VARIABLE;
        }

        private boolean isRaw(TypeMirror type) {
            return type instanceof DeclaredType declared && declared.getTypeArguments().isEmpty()
                    && !((TypeElement) declared.asElement()).getTypeParameters().isEmpty();
        }

        private String simpleName(TypeMirror type) {
            DeclaredType declared = (DeclaredType) type;
            String name = declared.asElement().getSimpleName().toString();
            if (declared.getTypeArguments().isEmpty()) {
                return name;
            }
            StringBuilder sb = new StringBuilder(name).append('<');
            for (TypeMirror argument : declared.getTypeArguments()) {
                sb.append(sb.charAt(sb.length() - 1) == '<' ? "" : ", ")
                        .append(argument instanceof DeclaredType ? simpleName(argument) : argument.toString());
            }
            return sb.append('>').toString();
        }

        private void check(Tree type) {
            if (type == null || type instanceof ParameterizedTypeTree) {
                return;
            }
            Element element = trees.getElement(new TreePath(getCurrentPath(), type));
            if (element instanceof TypeElement generic && !generic.getTypeParameters().isEmpty()) {
                String message = PREFIX + message(generic.getSimpleName().toString());
                found.add(() -> trees.printMessage(Diagnostic.Kind.ERROR, message, type, unit));
            }
        }
    }
}
