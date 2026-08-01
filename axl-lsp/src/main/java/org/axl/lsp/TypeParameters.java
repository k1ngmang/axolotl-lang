package org.axl.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.ast.SourceFile;

/** The type parameters of declarations: types inside the declaration that takes them, and nowhere else. */
final class TypeParameters {

    /** Where a type parameter is declared: a top-level declaration, and the method of a type when it is the method's. */
    record Owner(Decl decl, Decl.Function method) {
        /** The declaration that takes the parameter, as a hover shows it. */
        Decl declaring() {
            return method != null ? method : decl;
        }
    }

    private TypeParameters() {}

    /** The type parameters a declaration takes; none for a value or an enum. */
    static List<Decl.TypeParam> of(Decl decl) {
        return switch (decl) {
            case Decl.Function fn -> fn.typeParams();
            case Decl.DataClass data -> data.typeParams();
            case Decl.Interface iface -> iface.typeParams();
            case Decl.ClassDecl classDecl -> classDecl.typeParams();
            default -> List.of();
        };
    }

    /** `<T, U : Bound>` as it is written after a name; nothing for no parameters. */
    static String text(List<Decl.TypeParam> parameters) {
        if (parameters.isEmpty()) {
            return "";
        }
        return parameters.stream().map(p -> p.name() + (p.bound() == null ? "" : " : " + Hovers.typeText(p.bound())))
                .collect(Collectors.joining(", ", "<", ">"));
    }

    /** The type parameters in scope at a position: those of a method before those of its type. */
    static List<Decl.TypeParam> inScope(SourceFile file, Pos pos) {
        Decl decl = Symbols.enclosingDecl(file, pos);
        if (decl == null) {
            return List.of();
        }
        List<Decl.TypeParam> result = new ArrayList<>();
        Decl.Function method = methodAt(decl, pos);
        if (method != null) {
            result.addAll(method.typeParams());
        }
        result.addAll(of(decl));
        return result;
    }

    /** Where a name at a position is a type parameter; null when it is not one there. */
    static Owner ownerOf(SourceFile file, Pos pos, String name) {
        Decl decl = Symbols.enclosingDecl(file, pos);
        if (decl == null) {
            return null;
        }
        Decl.Function method = methodAt(decl, pos);
        if (method != null && named(method.typeParams(), name) != null) {
            return new Owner(decl, method);
        }
        return named(of(decl), name) != null ? new Owner(decl, null) : null;
    }

    /** The type parameter a name at a position refers to, or null. */
    static Decl.TypeParam at(SourceFile file, Pos pos, String name) {
        Owner owner = ownerOf(file, pos, name);
        return owner == null ? null : named(of(owner.declaring()), name);
    }

    static Decl.TypeParam named(List<Decl.TypeParam> parameters, String name) {
        return parameters.stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
    }

    /** The method of a class or an interface whose text holds a position: each covers its text up to the next. */
    private static Decl.Function methodAt(Decl decl, Pos pos) {
        List<Decl.Function> methods = switch (decl) {
            case Decl.ClassDecl classDecl -> classDecl.methods();
            case Decl.Interface iface -> iface.methods();
            default -> List.of();
        };
        Decl.Function found = null;
        for (Decl.Function method : methods) {
            if (Symbols.before(method.pos(), pos)) {
                found = method;
            }
        }
        return found;
    }
}
