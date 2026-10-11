package org.axl.javac;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.util.JCDiagnostic;
import com.sun.tools.javac.util.Log;
import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * Error Prone's finding about `==` on boxed values names no type, and Axolotl calls a `Character` no number:
 * the comparison, which javac has typed by then, tells the finding that its operands are characters.
 */
final class CharacterEquality extends Log.DiagnosticHandler implements TaskListener {

    private static final String TAG = "[BoxedPrimitiveEquality] ";

    private final Trees trees;
    private final JCDiagnostic.Factory factory;
    private final Map<URI, CompilationUnitTree> units = new HashMap<>();

    CharacterEquality(BasicJavacTask task) {
        this.trees = Trees.instance(task);
        this.factory = JCDiagnostic.Factory.instance(task.getContext());
        task.addTaskListener(this);
        install(Log.instance(task.getContext()));
    }

    @Override
    public void finished(TaskEvent event) {
        if (event.getKind() == TaskEvent.Kind.PARSE) {
            units.put(event.getCompilationUnit().getSourceFile().toUri(), event.getCompilationUnit());
        }
    }

    @Override
    public void report(JCDiagnostic diagnostic) {
        String message = diagnostic.getMessage(Locale.ROOT);
        CompilationUnitTree unit = diagnostic.getSource() == null ? null : units.get(diagnostic.getSource().toUri());
        if (message.startsWith(TAG) && unit != null && comparesCharacters(unit, diagnostic.getPosition())) {
            prev.report(factory.create(diagnostic.getType(), diagnostic.getDiagnosticSource(), diagnostic.getDiagnosticPosition(),
                    "proc.messager", TAG + ErrorProneMessages.CHARACTER_OPERANDS + message.substring(TAG.length())));
            return;
        }
        prev.report(diagnostic);
    }

    /** Whether the smallest `==` or `!=` around the position compares two `Character`s. */
    private boolean comparesCharacters(CompilationUnitTree unit, long position) {
        TreePath[] found = new TreePath[1];
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitBinary(BinaryTree binary, Void unused) {
                long start = trees.getSourcePositions().getStartPosition(unit, binary);
                long end = trees.getSourcePositions().getEndPosition(unit, binary);
                boolean equality = binary.getKind() == Tree.Kind.EQUAL_TO || binary.getKind() == Tree.Kind.NOT_EQUAL_TO;
                if (equality && start <= position && position <= end) {
                    found[0] = getCurrentPath();
                }
                return super.visitBinary(binary, unused);
            }
        }.scan(unit, null);
        if (found[0] == null) {
            return false;
        }
        BinaryTree binary = (BinaryTree) found[0].getLeaf();
        return isCharacter(trees.getTypeMirror(new TreePath(found[0], binary.getLeftOperand())))
                && isCharacter(trees.getTypeMirror(new TreePath(found[0], binary.getRightOperand())));
    }

    private static boolean isCharacter(TypeMirror type) {
        return type instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element
                && element.getQualifiedName().contentEquals("java.lang.Character");
    }
}
