package org.axl.javac;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
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
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;

/**
 * The classes of a file are nested in its Java class, where Java lets any of them reach the private
 * members of the others. Axolotl keeps `p.x` on a data class and a private method of a class closed in
 * the same file too, as other files see them.
 */
final class PrivateAccess implements TaskListener {

    static final String PREFIX = "private access: ";

    private final Trees trees;
    // Printed once every class is analyzed, as RawTypes does.
    private final List<Runnable> found = new ArrayList<>();

    PrivateAccess(JavacTask task) {
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

    static String message(Element member) {
        String name = member.getSimpleName().toString();
        String owner = member.getEnclosingElement().getSimpleName().toString();
        if (member.getEnclosingElement().getKind() == ElementKind.RECORD) {
            return "read the field `" + name + "` of the data class `" + owner + "` with parentheses: `" + name + "()`";
        }
        return "`" + name + "` is private to `" + owner + "`; only the code of `" + owner + "` can use it";
    }

    private final class Scanner extends TreePathScanner<Void, Void> {

        private final CompilationUnitTree unit;

        Scanner(CompilationUnitTree unit) {
            this.unit = unit;
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree select, Void unused) {
            Element member = trees.getElement(getCurrentPath());
            boolean checked = member != null && member.getModifiers().contains(Modifier.PRIVATE)
                    && (member.getKind() == ElementKind.FIELD || member.getKind() == ElementKind.METHOD);
            boolean reported = !assigned(select) || !member.getModifiers().contains(Modifier.FINAL);
            if (checked && reported && sameFile(member) && !inside(member.getEnclosingElement())) {
                String message = PREFIX + message(member);
                found.add(() -> trees.printMessage(Diagnostic.Kind.ERROR, message, select, unit));
            }
            return super.visitMemberSelect(select, unused);
        }

        /** javac itself reports members of other files, and an assignment to a `val` as one to a final field. */
        private boolean sameFile(Element member) {
            TreePath declaration = trees.getPath(member);
            return declaration != null && declaration.getCompilationUnit() == unit;
        }

        private boolean assigned(MemberSelectTree select) {
            Tree parent = getCurrentPath().getParentPath().getLeaf();
            return parent instanceof AssignmentTree a && a.getVariable() == select
                    || parent instanceof CompoundAssignmentTree c && c.getVariable() == select;
        }

        private boolean inside(Element owner) {
            for (TreePath path = getCurrentPath(); path != null; path = path.getParentPath()) {
                if (path.getLeaf() instanceof ClassTree && owner.equals(trees.getElement(path))) {
                    return true;
                }
            }
            return false;
        }
    }
}
