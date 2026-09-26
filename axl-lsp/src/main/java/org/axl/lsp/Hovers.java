package org.axl.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.GeneratedUnit;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.TypeRef;
import org.axl.compiler.parse.AxolotlLexer;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;

/** The declaration or inferred type of a name, and the Java generated from the line. */
final class Hovers {

    private static final int MAX_JAVA_LINES = 20;
    private static final int MAX_JAVA_LINE = 200;

    private final Workspace workspace;
    private final Declarations declarations;

    Hovers(Workspace workspace) {
        this.workspace = workspace;
        this.declarations = new Declarations(workspace);
    }

    /** While the text does not parse, an earlier version answers for the lines that are unchanged. */
    Hover hover(String uri, int line, int character) {
        Document current = workspace.document(uri);
        Document document = workspace.parsed(uri);
        if (current == null || document == null || line < 0 || character < 0) {
            return null;
        }
        line = current.sameLineIn(document, line);
        if (line < 0) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        Token token = document.tokenAt(line, character);
        String axl = token != null && token.getType() == AxolotlLexer.IDENTIFIER ? axl(document, token) : null;
        if (axl != null) {
            parts.add("```axl\n" + axl + "\n```");
        }
        String java = generatedJava(document.translation.unit(), line + 1);
        if (!java.isEmpty()) {
            parts.add("Generated Java:\n```java\n" + java + "\n```");
        }
        return parts.isEmpty() ? null : new Hover(new MarkupContent(MarkupKind.MARKDOWN, String.join("\n\n", parts)));
    }

    /** A member, a type parameter, a name in scope, a field or constant being declared, or a class of the package; null if none. */
    private String axl(Document document, Token token) {
        Supplier<List<String>> argumentTypes = () -> workspace.argumentTypes(document, token);
        if (isMember(document, token)) {
            Declarations.Found member = declarations.member(document, token, argumentTypes);
            return member == null || member.hiddenFrom(document, Document.start(token)) ? null : member.text();
        }
        TypeParameters.Owner owner = TypeParameters.ownerOf(document.ast, Document.start(token), token.getText());
        if (owner != null) {
            // A type parameter is explained by the declaration that takes it.
            return declaration(owner.declaring());
        }
        Symbols.Symbol symbol = Symbols.lookup(document.ast, Document.start(token), token.getText(), argumentTypes);
        if (symbol != null) {
            return describe(document, symbol);
        }
        Declarations.Found declared = Declarations.declaredAt(document, token);
        if (declared != null) {
            return declared.text();
        }
        return siblingClass(document, token.getText());
    }

    /** A class of another file of the package, such as `Counter` in `Counter.start()`. */
    private String siblingClass(Document document, String name) {
        String pkg = document.ast.packageName() == null ? "" : document.ast.packageName();
        Declarations.TypeAt type = declarations.type(document.module, pkg.isEmpty() ? name : pkg + "." + name);
        if (type == null) {
            return null;
        }
        return type.decl() != null ? declaration(type.decl()) : "class " + name;
    }

    private static boolean isMember(Document document, Token token) {
        Token previous = document.previous(token);
        return previous != null && (previous.getType() == AxolotlLexer.DOT || previous.getType() == AxolotlLexer.SAFE_DOT);
    }

    private String describe(Document document, Symbols.Symbol symbol) {
        return switch (symbol.kind()) {
            case FUNCTION -> signature(symbol.function());
            case VALUE -> "val " + symbol.name() + ": " + type(document, symbol);
            case IMPORTED_FUNCTION -> "import fn " + symbol.imported().name();
            case IMPORTED_VALUE -> "import val " + symbol.imported().name();
            case TYPE -> "import " + symbol.imported().name();
            case DECLARED_TYPE -> declaration(Symbols.topLevel(document.ast, symbol.name(), () -> null));
            case PARAMETER, LOCAL, VARIABLE -> {
                String keyword = symbol.kind() == Symbols.Kind.VARIABLE ? "var " : symbol.kind() == Symbols.Kind.LOCAL ? "val " : "";
                yield keyword + symbol.name() + ": " + type(document, symbol);
            }
        };
    }

    private String type(Document document, Symbols.Symbol symbol) {
        if (symbol.type() != null) {
            return typeText(symbol.type());
        }
        JavaModel java = workspace.javaModel(document.module);
        String text = JavaModel.display(java.typeOf(document.ast, symbol));
        return !text.endsWith("?") && java.isNullable(document.ast, symbol) ? text + "?" : text;
    }

    static String declaration(Decl decl) {
        String visibility = decl.isPrivate() ? "private " : "";
        return switch (decl) {
            case Decl.DataClass data -> visibility + "data class " + data.name() + TypeParameters.text(data.typeParams())
                    + fields(data.fields()) + supertypes(data.supertypes(), false)
                    + body(data.methods().stream().map(Hovers::signature).toList());
            case Decl.Interface iface -> visibility + (iface.isSealed() ? "sealed " : "") + "interface " + iface.name()
                    + TypeParameters.text(iface.typeParams()) + supertypes(iface.supertypes(), false) + interfaceBody(iface);
            case Decl.ClassDecl classDecl -> visibility + "class " + classDecl.name() + TypeParameters.text(classDecl.typeParams())
                    + classDecl.parameters().stream()
                    .map(p -> (p.isPrivate() ? "private " : "") + (p.mutable() ? "var " : "val ") + p.name() + ": " + typeText(p.type()))
                    .collect(Collectors.joining(", ", "(", ")"))
                    + supertypes(classDecl.supertypes(), classDecl.superArgs() != null);
            case Decl.EnumDecl enumeration -> visibility + "enum " + enumeration.name()
                    + (enumeration.fields().isEmpty() ? "" : fields(enumeration.fields()));
            case Decl.Function fn -> signature(fn);
            case Decl.Value value -> "val " + value.name();
        };
    }

    /** `(val x: Int, val y: Int)`: the fields of a data class or an enum. */
    private static String fields(List<Expr.Param> fields) {
        return fields.stream().map(f -> "val " + f.name() + ": " + typeText(f.type())).collect(Collectors.joining(", ", "(", ")"));
    }

    /** The values and methods an interface requires, one to a line; nothing for an interface that requires none. */
    private static String interfaceBody(Decl.Interface iface) {
        List<String> lines = new ArrayList<>();
        iface.members().forEach(m -> lines.add("val " + m.name() + ": " + typeText(m.type())));
        iface.methods().forEach(m -> lines.add(signature(m)));
        return body(lines);
    }

    /** Members in braces, one to a line; nothing for none. */
    private static String body(List<String> lines) {
        return lines.isEmpty() ? "" : lines.stream().map(l -> "    " + l).collect(Collectors.joining("\n", " {\n", "\n}"));
    }

    /** `: Shape, Other`; extended: the first supertype is a Java class extended with constructor arguments, shown as `Thread(...)`. */
    private static String supertypes(List<TypeRef> supertypes, boolean extended) {
        List<String> names = new ArrayList<>();
        for (TypeRef type : supertypes) {
            names.add(typeText(type) + (extended && names.isEmpty() ? "(...)" : ""));
        }
        return names.isEmpty() ? "" : " : " + String.join(", ", names);
    }

    static String signature(Decl.Function fn) {
        String params = fn.params().stream().map(p -> p.name() + ": " + typeText(p.type())).collect(Collectors.joining(", "));
        String result = fn.returnType() == null ? "" : " -> " + typeText(fn.returnType());
        return (fn.isPrivate() ? "private " : "") + "fn " + fn.name() + TypeParameters.text(fn.typeParams()) + "(" + params + ")" + result;
    }

    static String typeText(TypeRef type) {
        String args = type.args().isEmpty() ? ""
                : type.args().stream().map(Hovers::typeText).collect(Collectors.joining(", ", "<", ">"));
        return type.name() + args + (type.nullable() ? "?" : "");
    }

    /**
     * The Java lines generated from one .axl line, without their indentation, cut to a size that a
     * hover can show: a long line would otherwise be sent and rendered whole.
     */
    static String generatedJava(GeneratedUnit unit, int axlLine) {
        if (unit == null) {
            return "";
        }
        String[] lines = unit.javaSource().split("\n");
        List<String> result = new ArrayList<>();
        for (int javaLine = 1; javaLine <= lines.length; javaLine++) {
            if (unit.lineMap().axlLine(javaLine) != axlLine) {
                continue;
            }
            if (result.size() == MAX_JAVA_LINES) {
                result.add("...");
                break;
            }
            String line = lines[javaLine - 1].strip();
            result.add(line.length() > MAX_JAVA_LINE ? line.substring(0, MAX_JAVA_LINE) + " ..." : line);
        }
        return String.join("\n", result);
    }
}
