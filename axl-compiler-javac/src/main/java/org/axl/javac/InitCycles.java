package org.axl.javac;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;

/**
 * Top-level values of two files that read each other: whichever class Java starts first reads a
 * value of the other before it is set, and gets null or zero. The translator sees one file at a
 * time, so this looks at the generated classes together.
 */
final class InitCycles implements TaskListener {

    static final String PREFIX = "init order: ";

    /** A value of one generated class read while another one is set. */
    private record Read(TypeElement reader, String value, Element read, Tree at, CompilationUnitTree unit) {}

    private final Trees trees;
    private final Map<TypeElement, List<Read>> reads = new LinkedHashMap<>();

    InitCycles(JavacTask task) {
        this.trees = Trees.instance(task);
    }

    @Override
    public void finished(TaskEvent event) {
        if (event.getKind() == TaskEvent.Kind.ANALYZE && event.getTypeElement() != null
                && NullChecker.isGenerated(event.getCompilationUnit())) {
            collect(event.getTypeElement(), event.getCompilationUnit());
        } else if (event.getKind() == TaskEvent.Kind.COMPILATION) {
            report();
        }
    }

    private void collect(TypeElement type, CompilationUnitTree unit) {
        TreePath path = trees.getPath(type);
        if (path == null || !(path.getLeaf() instanceof ClassTree declaration)) {
            return;
        }
        List<Read> found = reads.computeIfAbsent(type, t -> new ArrayList<>());
        for (Tree member : declaration.getMembers()) {
            if (member instanceof VariableTree field && field.getInitializer() != null
                    && field.getModifiers().getFlags().contains(Modifier.STATIC)) {
                scan(field.getInitializer(), type, field.getName().toString(), unit, found);
            } else if (member instanceof BlockTree block && block.isStatic()) {
                scan(block, type, null, unit, found);
            }
        }
    }

    private void scan(Tree tree, TypeElement type, String value, CompilationUnitTree unit, List<Read> found) {
        TreePath unitPath = new TreePath(unit);
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree identifier, Void unused) {
                record(identifier);
                return null;
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree select, Void unused) {
                record(select);
                return super.visitMemberSelect(select, unused);
            }

            private void record(Tree reference) {
                Element element = trees.getElement(new TreePath(unitPath, reference));
                // Constants are copied into the reading class and need no initialization.
                if (element instanceof VariableElement field && field.getKind() == ElementKind.FIELD
                        && field.getModifiers().contains(Modifier.STATIC) && field.getConstantValue() == null
                        && element.getEnclosingElement() instanceof TypeElement owner && !owner.equals(type)) {
                    found.add(new Read(type, value, element, reference, unit));
                }
            }
        }.scan(tree, null);
    }

    private void report() {
        for (List<Read> list : reads.values()) {
            for (Read read : list) {
                TypeElement other = (TypeElement) read.read().getEnclosingElement();
                if (read.value() != null && reads.containsKey(other) && reaches(other, read.reader(), new HashSet<>())) {
                    trees.printMessage(Diagnostic.Kind.ERROR, PREFIX + "`" + read.value() + "` reads `"
                            + other.getQualifiedName() + "." + read.read().getSimpleName() + "`, and the values of `"
                            + other.getQualifiedName() + "` read values of this file; the first file Java starts "
                            + "sees the other's values before they are set, so break the cycle", read.at(), read.unit());
                }
            }
        }
        reads.clear();
    }

    private boolean reaches(TypeElement from, TypeElement target, Set<TypeElement> seen) {
        if (!seen.add(from)) {
            return false;
        }
        for (Read read : reads.getOrDefault(from, List.of())) {
            TypeElement next = (TypeElement) read.read().getEnclosingElement();
            if (next.equals(target) || reads.containsKey(next) && reaches(next, target, seen)) {
                return true;
            }
        }
        return false;
    }
}
