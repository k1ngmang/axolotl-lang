package org.axl.javac;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

/**
 * `MessageFormat.format("{0}-{1}", values)` with a list `values`: a list alone where Java takes any number
 * of `Object` values is one value, not the values it holds, and Java says nothing.
 */
final class ListAsValues implements TaskListener {

    static final String PREFIX = "list as values: ";

    // Error Prone checks their formats; `Objects.hash(list)` hashes the list, as meant.
    private static final Set<String> CHECKED_OWNERS = Set.of("java.lang.String", "java.io.PrintStream", "java.io.PrintWriter",
            "java.io.Console", "java.util.Formatter", "java.util.Objects");

    private final Trees trees;
    private final Types types;
    private final JavacTask task;
    // Printed once every class is analyzed, as RawTypes does.
    private final List<Runnable> found = new ArrayList<>();

    ListAsValues(JavacTask task) {
        this.task = task;
        this.trees = Trees.instance(task);
        this.types = task.getTypes();
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

    static String message(String method, String list) {
        String one = " is one value here, since `" + method + "` takes any number of values; ";
        return list == null ? "this list" + one + "add `.toArray()` to pass its elements, or pass the values one by one"
                : "the list `" + list + "`" + one + "pass its elements with `" + list + ".toArray()`, or the values one by one";
    }

    private final class Scanner extends TreePathScanner<Void, Void> {

        private final CompilationUnitTree unit;

        Scanner(CompilationUnitTree unit) {
            this.unit = unit;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
            Element element = trees.getElement(getCurrentPath());
            if (element instanceof ExecutableElement method && method.isVarArgs()
                    && call.getArguments().size() == method.getParameters().size()
                    && !CHECKED_OWNERS.contains(((TypeElement) method.getEnclosingElement()).getQualifiedName().toString())
                    && method.getParameters().getLast().asType() instanceof ArrayType array && isObject(array.getComponentType())) {
                ExpressionTree argument = call.getArguments().getLast();
                if (isCollection(trees.getTypeMirror(new TreePath(getCurrentPath(), argument)))) {
                    String list = argument instanceof IdentifierTree name ? name.getName().toString() : null;
                    String text = PREFIX + message(method.getSimpleName().toString(), list);
                    found.add(() -> trees.printMessage(Diagnostic.Kind.WARNING, text, argument, unit));
                }
            }
            return super.visitMethodInvocation(call, unused);
        }

        private boolean isObject(TypeMirror type) {
            return type instanceof DeclaredType declared
                    && ((TypeElement) declared.asElement()).getQualifiedName().contentEquals("java.lang.Object");
        }

        private boolean isCollection(TypeMirror type) {
            TypeElement collection = task.getElements().getTypeElement("java.util.Collection");
            return type != null && type.getKind() == TypeKind.DECLARED && collection != null
                    && types.isAssignable(types.erasure(type), types.erasure(collection.asType()));
        }
    }
}
