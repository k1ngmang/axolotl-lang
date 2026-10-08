package org.axl.compiler.check;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.axl.compiler.JavaClasses;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.TypeRef;

@NullMarked public final class Exhaustiveness {
    private Exhaustiveness() {}
    public static @Nullable String missingConstants(Expr.When w, SourceFile file, List<SourceFile> siblings) {
        final var named = constantNames(w);
        for (final var decl : declsOf(file, siblings)) {
            if (decl instanceof Decl.EnumDecl decl$1) {
                final var prefix = decl$1.name() + ".";
                final var mentioned = Nulls.stream(decl$1.constants()).anyMatch(c -> named.contains(c) || named.contains(prefix + c));
                final var missing = Nulls.stream(decl$1.constants()).filter(c -> !named.contains(c) && !named.contains(prefix + c)).toList();
                if (mentioned && !missing.isEmpty()) {
                    return quoted(Nulls.stream(missing).map(Nulls.nonNull(c -> prefix + c)).toList());
                }
            }
        }
        return null;
    }
    public static @Nullable String typesProblem(Expr.When w, SourceFile file, List<SourceFile> siblings) {
        final var tested = testedNames(w);
        for (final var decl : declsOf(file, siblings)) {
            if (decl instanceof Decl.Interface decl$1) {
                final var implementations = implementationsOf(decl$1.name(), file, siblings);
                if (!tested.isEmpty() && implementations.containsAll(tested)) {
                    return problemWith(decl$1, implementations, tested);
                }
            }
        }
        return null;
    }
    public static @Nullable String libraryTypesProblem(Expr.When w, SourceFile file, List<SourceFile> siblings, JavaClasses classes) {
        final var declared = declsOf(file, siblings).stream().map(Nulls.nonNull(d -> nameOf(d))).toList();
        final var tested = new ArrayList<String>();
        final var parents = new ArrayList<String>();
        for (final var name : writtenTypes(w)) {
            final var library = Libraries.classNamed(file, classes, name);
            if (library == null || declared.contains(simpleName(name))) {
                return null;
            }
            tested.add(library.name());
            parents.addAll(Nulls.stream(library.shape().supertypes()).map(Nulls.nonNull(t -> t.name())).toList());
        }
        for (final var parent : parents) {
            final var shape = classes.shape(parent);
            final List<String> permitted = (shape == null ? List.of() : shape.permitted());
            if (!permitted.isEmpty() && permitted.containsAll(tested)) {
                final var missing = permitted.stream().filter(c -> !tested.contains(c)).toList();
                return (missing.isEmpty() ? null :
                        "this `when` does not test " + quoted(missing.stream().map(Nulls.nonNull(c -> "is " + simpleName(c))).toList()) + "; add a branch for each, or an `else ->` branch");
            }
        }
        return null;
    }
    @SuppressWarnings("EffectivelyPrivate") private record LibraryEnum(String name, List<String> constants, Set<String> named) {}
    private static @Nullable LibraryEnum libraryEnum(Expr.When w, SourceFile file, JavaClasses classes) {
        @Nullable String prefix = null;
        final var named = new HashSet<String>();
        for (final var text : constantNames(w)) {
            final var dot = text.lastIndexOf('.');
            if (text.equals("null")) {
                continue;
            }
            if (dot < 0 || (prefix != null && !prefix.equals(text.substring(0, dot)))) {
                return null;
            }
            prefix = text.substring(0, dot);
            named.add(text.substring(dot + 1));
        }
        final var name = prefix;
        if (name == null) {
            return null;
        }
        final var library = Libraries.classNamed(file, classes, name);
        if (library == null || library.shape().constants().isEmpty() || !library.shape().constants().containsAll(named)) {
            return null;
        }
        return new LibraryEnum(name, library.shape().constants(), named);
    }
    public static boolean coversLibraryEnum(Expr.When w, SourceFile file, JavaClasses classes) {
        final var e = libraryEnum(w, file, classes);
        return e != null && e.named().containsAll(e.constants());
    }
    public static @Nullable String missingLibraryConstants(Expr.When w, SourceFile file, JavaClasses classes) {
        final var e = libraryEnum(w, file, classes);
        if (e == null) {
            return null;
        }
        final var missing = e.constants().stream().filter(c -> !e.named().contains(c)).toList();
        return (missing.isEmpty() ? null : quoted(missing.stream().map(Nulls.nonNull(c -> e.name() + "." + c)).toList()));
    }
    private static @Nullable String problemWith(Decl.Interface i, List<String> implementations, Set<String> tested) {
        if (!i.isSealed()) {
            return "`" + i.name() + "` is not sealed, so this `when` needs an `else ->` branch; or declare it `sealed interface`";
        }
        final var missing = implementations.stream().filter(c -> !tested.contains(c)).toList();
        if (missing.isEmpty()) {
            return null;
        }
        return "this `when` does not test " + quoted(missing.stream().map(Nulls.nonNull(c -> "is " + c)).toList()) + "; add a branch for each, or an `else ->` branch";
    }
    private static List<String> implementationsOf(String name, SourceFile file, List<SourceFile> siblings) {
        return declsOf(file, siblings).stream().filter(d -> extendsType(d, name)).map(Nulls.nonNull(d -> nameOf(d))).toList();
    }
    public static List<Decl> declsOf(SourceFile file, List<SourceFile> siblings) {
        final var result = new ArrayList<Decl>(file.decls());
        for (final var sibling : siblings) {
            if (samePackage(file, sibling)) {
                result.addAll(sibling.decls());
            }
        }
        return result;
    }
    private static boolean samePackage(SourceFile file, SourceFile other) {
        final var own = file.packageName();
        final var theirs = other.packageName();
        return (own == null ? Boolean.valueOf(theirs == null) : own.equals(theirs));
    }
    private static boolean extendsType(Decl decl, String name) {
        return supertypesOf(decl).stream().anyMatch(t -> simpleName(t.name()).equals(name));
    }
    private static List<TypeRef> supertypesOf(Decl decl) {
        switch (decl) {
            case Decl.DataClass decl$1 -> {
                return decl$1.supertypes();
            }
            case Decl.ClassDecl decl$2 -> {
                return decl$2.supertypes();
            }
            case Decl.Interface decl$3 -> {
                return decl$3.supertypes();
            }
            default -> {
                return List.of();
            }
        }
    }
    private static String nameOf(Decl decl) {
        switch (decl) {
            case Decl.DataClass decl$1 -> {
                return decl$1.name();
            }
            case Decl.ClassDecl decl$2 -> {
                return decl$2.name();
            }
            case Decl.Interface decl$3 -> {
                return decl$3.name();
            }
            default -> {
                return "";
            }
        }
    }
    private static Set<String> constantNames(Expr.When w) {
        final var names = new HashSet<String>();
        for (final var arm : w.arms()) {
            for (final var condition : arm.conditions()) {
                final var constant = condition.constant();
                if (constant != null) {
                    names.add(constant.text());
                }
            }
        }
        return names;
    }
    private static List<String> writtenTypes(Expr.When w) {
        final var names = new ArrayList<String>();
        for (final var arm : w.arms()) {
            for (final var condition : arm.conditions()) {
                final var type = condition.type();
                if (type != null && !names.contains(type.name())) {
                    names.add(type.name());
                }
            }
        }
        return names;
    }
    private static Set<String> testedNames(Expr.When w) {
        final var names = new HashSet<String>();
        for (final var arm : w.arms()) {
            for (final var condition : arm.conditions()) {
                final var type = condition.type();
                if (type != null) {
                    names.add(simpleName(type.name()));
                }
            }
        }
        return names;
    }
    private static String simpleName(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }
    private static String quoted(List<String> names) {
        return String.join(", ", names.stream().map(Nulls.nonNull(n -> "`" + n + "`")).toList());
    }
}
