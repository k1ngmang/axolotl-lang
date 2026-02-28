package org.axl.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;

/**
 * Runs an action once javac has analyzed every class it entered, before it writes class files and
 * prints its summary, so that errors found by looking at all classes count like javac's own. When
 * not every class gets analyzed, the action runs at the end of the compilation.
 */
final class AnalysisEnd implements TaskListener {

    private final JavacTask task;
    private final Runnable action;
    private int entered;
    private int analyzed;
    private boolean listening;
    private boolean done;

    AnalysisEnd(JavacTask task, Runnable action) {
        this.task = task;
        this.action = action;
    }

    @Override
    public void started(TaskEvent event) {
        if (event.getKind() == TaskEvent.Kind.ANALYZE && !listening) {
            listening = true;
            // Error Prone skips a class once there are errors, so the action must come after its listener,
            // which every plugin has added by now.
            task.addTaskListener(new TaskListener() {
                @Override
                public void finished(TaskEvent e) {
                    if (e.getKind() == TaskEvent.Kind.ANALYZE && ++analyzed == entered) {
                        run();
                    }
                }
            });
        }
    }

    @Override
    public void finished(TaskEvent event) {
        if (event.getKind() == TaskEvent.Kind.ENTER) {
            entered += (int) event.getCompilationUnit().getTypeDecls().stream().filter(ClassTree.class::isInstance).count();
        } else if (event.getKind() == TaskEvent.Kind.COMPILATION) {
            run();
        }
    }

    private void run() {
        if (!done) {
            done = true;
            action.run();
        }
    }
}
