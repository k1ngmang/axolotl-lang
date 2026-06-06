package org.axl.javac;

import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
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
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * A generic class created or written without type arguments, `ArrayList()` or `val xs: ArrayList`,
 * lets values of any type in. This runs in the javac plugin, so builds that pass no lint options see
 * it too.
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
            return super.visitVariable(variable, unused);
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
