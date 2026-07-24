package org.axl.javac;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.util.JCDiagnostic;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticType;
import com.sun.tools.javac.util.Log;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * Remembers where NullAway reported errors, so that NullChecker does not report the same problem again.
 * Drops NullAway's error for a value from Java code without nullness annotations stored where its
 * type arguments have `?`, such as `val l: List<String?> = J.list()`: Java says nothing about them.
 */
final class NullAwayReports extends Log.DiagnosticHandler implements TaskListener {

    private static final Pattern INCOMPATIBLE =
            Pattern.compile("^\\[NullAway] incompatible types: (.+) cannot be converted to (.+)$");
    private static final Pattern NULLABLE = Pattern.compile("@(?:[\\w.]+\\.)?Nullable ");

    private record Reported(URI file, long start) {}

    private final Trees trees;
    private final List<Reported> reported = new ArrayList<>();
    private final Map<URI, CompilationUnitTree> units = new HashMap<>();

    NullAwayReports(BasicJavacTask task) {
        this.trees = Trees.instance(task);
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
        if (diagnostic.getType() == DiagnosticType.ERROR && diagnostic.getSource() != null && message.startsWith("[NullAway]")) {
            long start = diagnostic.getStartPosition() < 0 ? diagnostic.getPosition() : diagnostic.getStartPosition();
            URI file = diagnostic.getSource().toUri();
            if (addsQuestionMarks(message) && units.containsKey(file)) {
                TreePath stored = storedValue(units.get(file), start);
                // `attempt` whose `else` gives a nullable error: NullAway then also doubts the value side, which only widens.
                if (fromUnannotated(stored) || isAttempt(stored)) {
                    return;
                }
            }
            reported.add(new Reported(file, start));
        }
        prev.report(diagnostic);
    }

    /** Error Prone gives its errors no end position. */
    List<Long> starts(URI file) {
        return reported.stream().filter(r -> r.file().equals(file)).map(Reported::start).toList();
    }

    /** `List<String>` where `List<@Nullable String>` is expected: only the type arguments differ, by `?`. */
    private static boolean addsQuestionMarks(String message) {
        Matcher incompatible = INCOMPATIBLE.matcher(message.lines().findFirst().orElse("").strip());
        return incompatible.matches() && !NULLABLE.matcher(incompatible.group(1)).find()
                && NULLABLE.matcher(incompatible.group(2)).replaceAll("").equals(incompatible.group(1));
    }

    /**
     * The value stored by the tree NullAway reported at a position: the largest one that starts
     * there or else, for the name of a variable, the smallest one around it.
     */
    private TreePath storedValue(CompilationUnitTree unit, long position) {
        TreePath[] found = new TreePath[2];
        new TreePathScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree == null) {
                    return null;
                }
                long start = trees.getSourcePositions().getStartPosition(unit, tree);
                long end = trees.getSourcePositions().getEndPosition(unit, tree);
                if (start == position && found[0] == null) {
                    found[0] = new TreePath(getCurrentPath(), tree);
                }
                if (start <= position && position <= end) {
                    found[1] = new TreePath(getCurrentPath(), tree);
                }
                return super.scan(tree, unused);
            }
        }.scan(unit, null);
        TreePath reported = found[0] != null ? found[0] : found[1];
        if (reported == null) {
            return null;
        }
        // javac places a call where its method is named.
        TreePath parent = reported.getParentPath();
        if (parent != null && parent.getLeaf() instanceof MethodInvocationTree call && call.getMethodSelect() == reported.getLeaf()) {
            reported = parent;
        }
        Tree value = switch (reported.getLeaf()) {
            case VariableTree variable -> variable.getInitializer();
            case AssignmentTree assignment -> assignment.getExpression();
            case ReturnTree ret -> ret.getExpression();
            default -> reported.getLeaf();
        };
        return value == null ? null : new TreePath(reported, value);
    }

    /** `Result.attempt(...)`, which `attempt` becomes. */
    private boolean isAttempt(TreePath path) {
        return path != null && path.getLeaf() instanceof MethodInvocationTree
                && trees.getElement(path) instanceof ExecutableElement method && method.getSimpleName().contentEquals("attempt")
                && method.getEnclosingElement() instanceof TypeElement owner && owner.getQualifiedName().contentEquals("axl.lang.Result");
    }

    /** A call or field of Java code without nullness annotations, also behind `!!` or `?:`. */
    private boolean fromUnannotated(TreePath path) {
        if (path == null) {
            return false;
        }
        Tree value = path.getLeaf();
        if (value instanceof ParenthesizedTree p) {
            return fromUnannotated(new TreePath(path, p.getExpression()));
        }
        if (value instanceof TypeCastTree cast) {
            return fromUnannotated(new TreePath(path, cast.getExpression()));
        }
        if (value instanceof LambdaExpressionTree lambda) {
            return lambda.getBody() instanceof ExpressionTree body && fromUnannotated(new TreePath(path, body));
        }
        Element element = trees.getElement(path);
        if (element == null || !(element.getEnclosingElement() instanceof TypeElement owner)) {
            return false;
        }
        String type = owner.getQualifiedName().toString();
        if (value instanceof MethodInvocationTree call && (type.equals("java.util.Objects") || type.equals("axl.lang.Nulls"))) {
            return call.getArguments().stream().allMatch(a -> fromUnannotated(new TreePath(path, a)));
        }
        boolean member = element.getKind() == ElementKind.METHOD || element.getKind() == ElementKind.FIELD;
        return member && !PlatformValues.trusted(element);
    }
}
