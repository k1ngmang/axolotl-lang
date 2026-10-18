package org.axl.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.gen.Generator;

/**
 * The declarations in .axl files that member accesses and type names lead to: fields and methods of
 * data classes, members of interfaces, properties and methods of classes, constants and fields of
 * enums, and types nested in the class of another file. Each comes with the text a hover shows for it.
 */
final class Declarations {

    /** A declaration: the document it is in, the position of its name, and its hover text; privateTo is the class a private member is in. */
    record Found(Document document, Pos pos, String text, Decl.ClassDecl privateTo) {
        Found(Document document, Pos pos, String text) {
            this(document, pos, text, null);
        }

        /** Whether code at a position of a document cannot use the member: it is private to a class the position is not in. */
        boolean hiddenFrom(Document from, Pos at) {
            if (privateTo == null) {
                return false;
            }
            Decl own = from.ast == null || !from.path.equals(document.path) ? null : from.ast.decls().stream()
                    .filter(d -> d instanceof Decl.ClassDecl && d.name().equals(privateTo.name())).findFirst().orElse(null);
            return own == null || !Outline.within(from, own, at);
        }
    }

    /** A type of an .axl file; decl is null for the class of a file that declares no type of its name. */
    record TypeAt(Document document, Decl decl) {}

    private final Workspace workspace;

    Declarations(Workspace workspace) {
        this.workspace = workspace;
    }

    /** The declaration of the member named by a token after a dot; null when none is in an .axl file. */
    Found member(Document document, Token name, Supplier<List<String>> argumentTypes) {
        Token dot = document.previous(name);
        List<Receivers.Step> steps = dot == null ? null : Receivers.steps(document, dot,
                t -> workspace.typeName(document, Document.start(t), t.getText()));
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        JavaModel java = workspace.javaModel(document.module);
        TypeElement owner = JavaModel.elementOf(Receivers.typeOf(java, document.ast, Document.start(name), steps).type());
        return owner == null ? null : inheritedMember(document.module, owner, name.getText(), argumentTypes);
    }

    /** A member of the type or, for a method with a body that it inherits, of an interface it implements. */
    private Found inheritedMember(Module module, TypeElement owner, String name, Supplier<List<String>> argumentTypes) {
        Found found = memberOf(module, owner.getQualifiedName().toString(), name, argumentTypes);
        for (TypeMirror parent : owner.getInterfaces()) {
            TypeElement iface = JavaModel.elementOf(parent);
            if (found == null && iface != null) {
                found = inheritedMember(module, iface, name, argumentTypes);
            }
        }
        return found;
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
        String outerName = dot < 0 ? "" : qualifiedName.substring(0, dot);
        String holder = "." + Generator.PRIVATE_TYPES;
        if (outerName.endsWith(holder)) {
            // The private types of an interface's file are nested in a class nested in the interface.
            outerName = outerName.substring(0, outerName.length() - holder.length());
        }
        Document outer = dot < 0 ? null : axlFile(module, outerName);
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
            case Decl.DataClass data -> {
                Found field = field(document, data.fields(), name);
                yield field != null ? field : method(document, data.methods(), name, argumentTypes);
            }
            case Decl.Interface iface -> {
                Found value = field(document, iface.members(), name);
                yield value != null ? value : method(document, iface.methods(), name, argumentTypes);
            }
            case Decl.ClassDecl classDecl -> classMember(document, classDecl, name, argumentTypes);
            case Decl.EnumDecl enumeration -> {
                Found constant = constant(document, enumeration, name);
                Found field = constant != null ? constant : field(document, enumeration.fields(), name);
                yield field != null ? field : method(document, enumeration.methods(), name, argumentTypes);
            }
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
                return property(document, property, classDecl);
            }
        }
        for (Decl.Property property : classDecl.properties()) {
            if (property.name().equals(name)) {
                return property(document, property, classDecl);
            }
        }
        Found method = method(document, classDecl.methods(), name, argumentTypes);
        boolean hidden = method != null && method.text().startsWith("private ");
        return hidden ? new Found(method.document(), method.pos(), method.text(), classDecl) : method;
    }

    private static Found method(Document document, List<Decl.Function> all, String name, Supplier<List<String>> argumentTypes) {
        List<Decl.Function> methods = all.stream().filter(m -> m.name().equals(name)).toList();
        if (methods.isEmpty()) {
            return null;
        }
        List<String> arguments = methods.size() > 1 ? argumentTypes.get() : null;
        Decl.Function method = methods.stream().filter(m -> arguments == null || m.params().size() == arguments.size())
                .findFirst().orElse(methods.getFirst());
        return new Found(document, method.namePos(), Hovers.signature(method));
    }

    private static Found property(Document document, Decl.Property property, Decl.ClassDecl owner) {
        String type = property.type() == null ? "" : ": " + Hovers.typeText(property.type());
        String text = (property.isPrivate() ? "private " : "") + (property.mutable() ? "var " : "val ") + property.name() + type;
        return new Found(document, property.namePos(), text, property.isPrivate() ? owner : null);
    }

    private static Found constant(Document document, Decl.EnumDecl enumeration, String name) {
        int index = enumeration.constants().indexOf(name);
        List<Pos> positions = document.constantPositions(enumeration);
        return index < 0 || index >= positions.size() ? null
                : new Found(document, positions.get(index), name + ": " + enumeration.name());
    }

    /**
     * A field of a data class or an interface, a constant of an enum, or the `constructor` of a class, whose name is the
     * token; null for other tokens.
     */
    static Found declaredAt(Document document, Token token) {
        Pos pos = Document.start(token);
        for (Decl decl : document.ast.decls()) {
            if (decl instanceof Decl.ClassDecl classDecl && pos.equals(classDecl.constructorPos())) {
                return new Found(document, pos, Hovers.primaryConstructor(classDecl));
            }
            List<Decl.Constructor> constructors = decl instanceof Decl.ClassDecl classDecl ? classDecl.constructors() : List.of();
            for (Decl.Constructor constructor : constructors) {
                if (constructor.namePos().equals(pos)) {
                    return new Found(document, pos, Hovers.constructor(constructor));
                }
            }
            Found found = inType(document, decl, token.getText(), () -> null);
            if (found != null && found.pos().equals(pos)) {
                return found;
            }
        }
        return null;
    }
}
