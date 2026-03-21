package org.axl.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import javax.lang.model.element.TypeElement;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.ast.SourceFile;

/**
 * The declarations in .axl files that member accesses and type names lead to: fields of data
 * classes, members of interfaces, properties and methods of classes, enum constants, and types
 * nested in the class of another file. Each comes with the text a hover shows for it.
 */
final class Declarations {

    /** A declaration: the document it is in, the position of its name, and its hover text. */
    record Found(Document document, Pos pos, String text) {}

    /** A type of an .axl file; decl is null for the class of a file that declares no type of its name. */
    record TypeAt(Document document, Decl decl) {}

    private final Workspace workspace;

    Declarations(Workspace workspace) {
        this.workspace = workspace;
    }

    /** The declaration of the member named by a token after a dot; null when none is in an .axl file. */
    Found member(Document document, Token name, Supplier<List<String>> argumentTypes) {
        Token dot = document.previous(name);
        List<Receivers.Step> steps = dot == null ? null : Receivers.steps(document, dot);
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        JavaModel java = workspace.javaModel(document.module);
        TypeElement owner = JavaModel.elementOf(Receivers.typeOf(java, document.ast, Document.start(name), steps).type());
        return owner == null ? null : memberOf(document.module, owner.getQualifiedName().toString(), name.getText(), argumentTypes);
    }

    /** A member of a class of an .axl file: of its type declaration, or a function or value of the file for the file's class. */
    Found memberOf(Module module, String className, String name, Supplier<List<String>> argumentTypes) {
        TypeAt type = type(module, className);
        if (type == null) {
            return null;
        }
        Document document = type.document();
        Found found = type.decl() == null ? null : inType(document, type.decl(), name, argumentTypes);
        boolean fileClass = type.decl() == null || type.decl().name().equals(document.ast.className());
        if (found != null || !fileClass) {
            return found;
        }
        Decl decl = Symbols.topLevel(document.ast, name, argumentTypes);
        return decl instanceof Decl.Function || decl instanceof Decl.Value
                ? new Found(document, decl.namePos(), Hovers.declaration(decl)) : null;
    }

    /** The .axl type of a qualified class name: the class of a file, or a type nested in it. */
    TypeAt type(Module module, String qualifiedName) {
        Document file = axlFile(module, qualifiedName);
        if (file != null) {
            return new TypeAt(file, typeNamed(file.ast, file.ast.className()));
        }
        int dot = qualifiedName.lastIndexOf('.');
        Document outer = dot < 0 ? null : axlFile(module, qualifiedName.substring(0, dot));
        Decl nested = outer == null ? null : typeNamed(outer.ast, qualifiedName.substring(dot + 1));
        return nested == null ? null : new TypeAt(outer, nested);
    }

    private Document axlFile(Module module, String qualifiedName) {
        Path source = workspace.sourceOf(module, qualifiedName);
        if (source == null || !source.toString().endsWith(".axl")) {
            return null;
        }
        Document document = workspace.load(source);
        return document.ast == null ? null : document;
    }

    private static Decl typeNamed(SourceFile file, String name) {
        return file.decls().stream().filter(d -> !(d instanceof Decl.Function) && !(d instanceof Decl.Value))
                .filter(d -> d.name().equals(name)).findFirst().orElse(null);
    }

    private static Found inType(Document document, Decl decl, String name, Supplier<List<String>> argumentTypes) {
        return switch (decl) {
            case Decl.DataClass data -> field(document, data.fields(), name);
            case Decl.Interface iface -> field(document, iface.members(), name);
            case Decl.ClassDecl classDecl -> classMember(document, classDecl, name, argumentTypes);
            case Decl.EnumDecl enumeration -> constant(document, enumeration, name);
            default -> null;
        };
    }

    private static Found field(Document document, List<Expr.Param> fields, String name) {
        for (Expr.Param field : fields) {
            if (field.name().equals(name)) {
                return new Found(document, Outline.fieldName(document, field), "val " + name + ": " + Hovers.typeText(field.type()));
            }
        }
        return null;
    }

    private static Found classMember(Document document, Decl.ClassDecl classDecl, String name, Supplier<List<String>> argumentTypes) {
        for (Decl.Property property : classDecl.parameters()) {
            if (property.name().equals(name)) {
                return property(document, property);
            }
        }
        for (Decl.Property property : classDecl.properties()) {
            if (property.name().equals(name)) {
                return property(document, property);
            }
        }
        List<Decl.Function> methods = classDecl.methods().stream().filter(m -> m.name().equals(name)).toList();
        if (methods.isEmpty()) {
            return null;
        }
        List<String> arguments = methods.size() > 1 ? argumentTypes.get() : null;
        Decl.Function method = methods.stream().filter(m -> arguments == null || m.params().size() == arguments.size())
                .findFirst().orElse(methods.getFirst());
        return new Found(document, method.namePos(), Hovers.signature(method));
    }

    private static Found property(Document document, Decl.Property property) {
        String type = property.type() == null ? "" : ": " + Hovers.typeText(property.type());
        return new Found(document, property.namePos(), (property.mutable() ? "var " : "val ") + property.name() + type);
    }

    private static Found constant(Document document, Decl.EnumDecl enumeration, String name) {
        int index = enumeration.constants().indexOf(name);
        List<Pos> positions = document.constantPositions(enumeration);
        return index < 0 || index >= positions.size() ? null
                : new Found(document, positions.get(index), name + ": " + enumeration.name());
    }

    /** A field of a data class or an interface, or a constant of an enum, whose name is the token; null for other tokens. */
    static Found declaredAt(Document document, Token token) {
        Pos pos = Document.start(token);
        for (Decl decl : document.ast.decls()) {
            Found found = inType(document, decl, token.getText(), () -> null);
            if (found != null && found.pos().equals(pos)) {
                return found;
            }
        }
        return null;
    }
}
