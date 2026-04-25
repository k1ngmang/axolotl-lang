package org.axl.javac;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;

/**
 * Top-level values of two files that read each other: whichever class Java starts first reads a
 * value of the other before it is set, and gets null or zero. The same happens to a value that
 * reads itself or a later value of its file through a function of another file. The translator
 * sees one file at a time, so this looks at the generated classes together.
 */
final class InitCycles implements TaskListener {

    static final String PREFIX = "init order: ";

    /** A static field read, a static function called or an object of a generated class created, and where. */
    private record Use(Element element, Tree at) {}

    /** The initializer of a value, or a static block when value is null. */
    private record Init(TypeElement type, VariableElement value, CompilationUnitTree unit, List<Use> uses) {}

    /** A value of one generated class read while another one is set. */
    private record Read(TypeElement reader, String value, Element read, Tree at, CompilationUnitTree unit) {}

    private final Trees trees;
    private final List<Init> inits = new ArrayList<>();
    // What the static functions of generated classes read and call. Classes are attributed one at a
    // time, so calls are followed only at the end.
    private final Map<ExecutableElement, List<Use>> functions = new HashMap<>();
    // What creating an object of a class of a generated file reads: its constructors and property initializers.
    private final Map<TypeElement, List<Use>> constructors = new HashMap<>();
    private final Map<TypeElement, List<Read>> reads = new LinkedHashMap<>();

    InitCycles(JavacTask task) {
        this.trees = Trees.instance(task);
    }

    @Override
    public void finished(TaskEvent event) {
        if (event.getKind() == TaskEvent.Kind.ANALYZE && event.getTypeElement() != null
                && NullChecker.isGenerated(event.getCompilationUnit())) {
            collect(event.getTypeElement(), event.getCompilationUnit());
        }
    }

    /** Called once every class is analyzed. */
    void report() {
        inits.forEach(this::follow);
        reportCycles();
        inits.clear();
        functions.clear();
        constructors.clear();
    }

    private void collect(TypeElement type, CompilationUnitTree unit) {
        TreePath path = trees.getPath(type);
        if (path == null || !(path.getLeaf() instanceof ClassTree declaration)) {
            return;
        }
        reads.computeIfAbsent(type, t -> new ArrayList<>());
        for (Tree member : declaration.getMembers()) {
            boolean isStatic = member instanceof VariableTree v && v.getModifiers().getFlags().contains(Modifier.STATIC)
                    || member instanceof MethodTree m && m.getModifiers().getFlags().contains(Modifier.STATIC);
            Element element = trees.getElement(new TreePath(path, member));
            if (member instanceof VariableTree field && field.getInitializer() != null && isStatic
                    && element instanceof VariableElement value) {
                inits.add(new Init(type, value, unit, uses(field.getInitializer(), unit)));
            } else if (member instanceof BlockTree block && block.isStatic()) {
                inits.add(new Init(type, null, unit, uses(block, unit)));
            } else if (member instanceof MethodTree method && method.getBody() != null && isStatic
                    && element instanceof ExecutableElement function) {
                functions.put(function, uses(method.getBody(), unit));
            } else if (member instanceof ClassTree nested && element instanceof TypeElement nestedType) {
                constructors.put(nestedType, constructorUses(nested, unit));
            }
        }
    }

    private List<Use> constructorUses(ClassTree declaration, CompilationUnitTree unit) {
        List<Use> uses = new ArrayList<>();
        for (Tree member : declaration.getMembers()) {
            if (member instanceof VariableTree field && field.getInitializer() != null
                    && !field.getModifiers().getFlags().contains(Modifier.STATIC)) {
                uses.addAll(uses(field.getInitializer(), unit));
            } else if (member instanceof MethodTree method && method.getBody() != null
                    && method.getName().contentEquals("<init>")) {
                uses.addAll(uses(method.getBody(), unit));
            } else if (member instanceof MethodTree method && method.getBody() != null
                    && trees.getElement(TreePath.getPath(unit, method)) instanceof ExecutableElement instanceMethod) {
                functions.put(instanceMethod, uses(method.getBody(), unit));
            }
        }
        return uses;
    }

    private List<Use> uses(Tree tree, CompilationUnitTree unit) {
        TreePath unitPath = new TreePath(unit);
        List<Use> uses = new ArrayList<>();
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

            @Override
            public Void visitNewClass(NewClassTree creation, Void unused) {
                if (trees.getElement(new TreePath(unitPath, creation)) instanceof ExecutableElement constructor
                        && constructor.getEnclosingElement() instanceof TypeElement created) {
                    uses.add(new Use(created, creation));
                }
                return super.visitNewClass(creation, unused);
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                // Instance methods count only if they are of a generated class; follow() checks that.
                if (trees.getElement(new TreePath(unitPath, call)) instanceof ExecutableElement method) {
                    uses.add(new Use(method, call));
                }
                return super.visitMethodInvocation(call, unused);
            }

            private void record(Tree reference) {
                Element element = trees.getElement(new TreePath(unitPath, reference));
                // Constants are copied into the reading class and need no initialization.
                if (element instanceof VariableElement field && field.getKind() == ElementKind.FIELD
                        && field.getModifiers().contains(Modifier.STATIC) && field.getConstantValue() == null) {
                    uses.add(new Use(element, reference));
                }
            }
        }.scan(tree, null);
        return uses;
    }

    private void follow(Init init) {
        Set<Element> followed = new HashSet<>();
        for (Use use : init.uses()) {
            follow(init, use.element(), use.at(), false, followed);
        }
    }

    /**
     * @param at        where the initializer reads the value or calls the function that does
     * @param otherFile whether a function of another file was called on the way
     */
    private void follow(Init init, Element used, Tree at, boolean otherFile, Set<Element> followed) {
        if (!(used.getEnclosingElement() instanceof TypeElement owner)) {
            return;
        }
        if (used instanceof ExecutableElement function) {
            if (functions.containsKey(function) && followed.add(function)) {
                boolean fromOtherFile = otherFile || !outermost(owner).equals(init.type());
                // Calling a function of another file starts that class, which sets its values.
                if (!owner.equals(init.type()) && function.getModifiers().contains(Modifier.STATIC)) {
                    reads.get(init.type()).add(new Read(init.type(), valueName(init), function, at, init.unit()));
                }
                functions.get(function).forEach(u -> follow(init, u.element(), at, fromOtherFile, followed));
            }
            return;
        }
        if (used instanceof TypeElement created) {
            if (constructors.containsKey(created) && followed.add(created)) {
                boolean fromOtherFile = otherFile || !outermost(owner).equals(init.type());
                constructors.get(created).forEach(u -> follow(init, u.element(), at, fromOtherFile, followed));
            }
            return;
        }
        String value = valueName(init);
        if (!owner.equals(init.type())) {
            reads.get(init.type()).add(new Read(init.type(), value, used, at, init.unit()));
        } else if (otherFile && value != null && !declaredBefore(used, init.value())) {
            // Reads within the file, also through its own functions, are checked by the translator.
            String name = used.getSimpleName().toString();
            String message = used.equals(init.value())
                    ? "`" + value + "` reads itself through code of another file, before it is set; compute it without that read"
                    : "`" + value + "` is computed from `" + name + "` through code of another file, and `" + name
                    + "` is declared later; top-level values are set in order, so declare `" + name + "` first";
            trees.printMessage(Diagnostic.Kind.ERROR, PREFIX + message, at, init.unit());
        }
    }

    private static TypeElement outermost(TypeElement type) {
        TypeElement current = type;
        while (current.getEnclosingElement() instanceof TypeElement outer) {
            current = outer;
        }
        return current;
    }

    private static String valueName(Init init) {
        return init.value() == null ? null : init.value().getSimpleName().toString();
    }

    private static boolean declaredBefore(Element field, VariableElement value) {
        List<? extends Element> members = value.getEnclosingElement().getEnclosedElements();
        return members.indexOf(field) < members.indexOf(value);
    }

    private void reportCycles() {
        for (List<Read> list : reads.values()) {
            for (Read read : list) {
                TypeElement other = (TypeElement) read.read().getEnclosingElement();
                if (read.value() != null && reads.containsKey(other) && reaches(other, read.reader(), new HashSet<>())) {
                    String verb = read.read() instanceof ExecutableElement ? "` calls `" : "` reads `";
                    trees.printMessage(Diagnostic.Kind.ERROR, PREFIX + "`" + read.value() + verb
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
