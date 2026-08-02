package org.axl.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import javax.lang.model.element.TypeElement;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Import;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.parse.AxolotlLexer;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/** Go to definition: names in .axl files, functions of other .axl files and Java classes and members. */
final class Definitions {

    private final Workspace workspace;
    private final Declarations declarations;

    Definitions(Workspace workspace) {
        this.workspace = workspace;
        this.declarations = new Declarations(workspace);
    }

    /** While the text does not parse, an earlier version answers for the lines that are unchanged. */
    List<Location> definition(String uri, int line, int character) {
        Document current = workspace.document(uri);
        Document document = workspace.parsed(uri);
        if (current == null || document == null || line < 0 || character < 0) {
            return List.of();
        }
        line = current.sameLineIn(document, line);
        if (line < 0) {
            return List.of();
        }
        Token token = document.tokenAt(line, character);
        if (token == null || token.getType() != AxolotlLexer.IDENTIFIER) {
            return List.of();
        }
        Import imp = document.ast.imports().stream().filter(i -> i.pos().line() == token.getLine())
                .findFirst().orElse(null);
        if (imp != null) {
            return imported(document, imp, line, token);
        }
        String name = token.getText();
        Supplier<List<String>> argumentTypes = () -> workspace.argumentTypes(document, token);
        Token previous = document.previous(token);
        if (previous != null && (previous.getType() == AxolotlLexer.DOT || previous.getType() == AxolotlLexer.SAFE_DOT)) {
            Declarations.Found found = declarations.member(document, token, argumentTypes);
            if (found != null) {
                return found.document() == document ? local(current, document, found.pos())
                        : List.of(location(found.document().path, found.document().position(found.pos())));
            }
            List<Location> javaMember = javaMember(document, token, argumentTypes);
            if (!javaMember.isEmpty()) {
                return javaMember;
            }
            Token receiver = document.previous(previous);
            if (receiver == null || receiver.getType() != AxolotlLexer.IDENTIFIER) {
                return List.of();
            }
            String type = typeName(document, receiver);
            return type == null ? List.of() : member(document.module, type, name, argumentTypes);
        }
        Decl.TypeParam parameter = TypeParameters.at(document.ast, Document.start(token), name);
        if (parameter != null) {
            return local(current, document, parameter.pos());
        }
        Symbols.Symbol symbol = Symbols.lookup(document.ast, Document.start(token), name, argumentTypes);
        if (symbol == null) {
            String type = typeName(document, token);
            return type == null ? List.of() : classLocation(document.module, type);
        }
        return switch (symbol.kind()) {
            case IMPORTED_FUNCTION, IMPORTED_VALUE -> {
                String imported = symbol.imported().name();
                String owner = imported.substring(0, imported.lastIndexOf('.'));
                yield member(document.module, owner, symbol.name(), argumentTypes);
            }
            case TYPE -> classLocation(document.module, symbol.imported().name());
            default -> local(current, document, namePos(document, symbol));
        };
    }

    /** A field of a data class is positioned at its `val`; its name follows. Other symbols are positioned at their names. */
    private static Pos namePos(Document document, Symbols.Symbol symbol) {
        int index = document.firstTokenFrom(symbol.pos());
        boolean atVal = index < document.tokens.size() && document.tokens.get(index).getType() == AxolotlLexer.VAL;
        return atVal ? document.nameAfter(symbol.pos(), symbol.name()) : symbol.pos();
    }

    /** A member of a Java class after a dot: of the type of what stands before the dot, or inherited by it. */
    private List<Location> javaMember(Document document, Token name, Supplier<List<String>> argumentTypes) {
        List<Receivers.Step> steps = Receivers.steps(document, document.previous(name),
                t -> workspace.typeName(document, Document.start(t), t.getText()));
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        JavaModel java = workspace.javaModel(document.module);
        TypeElement owner = JavaModel.elementOf(Receivers.typeOf(java, document.ast, Document.start(name), steps).type());
        Location found = owner == null ? null : java.declaration(java.member(owner, name.getText(), argumentTypes.get()));
        return found == null ? List.of() : List.of(found);
    }

    /** A segment of an import: the imported class or member for the last one, a class for the others. */
    private List<Location> imported(Document document, Import imp, int line, Token token) {
        String text = document.lines.get(line);
        int start = text.indexOf(imp.name());
        int tokenEnd = token.getCharPositionInLine() + token.getStopIndex() - token.getStartIndex() + 1;
        int end = document.utf16Column(line, tokenEnd);
        if (start < 0 || end <= start) {
            return List.of();
        }
        String prefix = text.substring(start, end);
        if (!prefix.equals(imp.name())) {
            return classLocation(document.module, prefix);
        }
        if (imp.kind() == Import.Kind.TYPE) {
            return classLocation(document.module, imp.name());
        }
        String owner = imp.name().substring(0, imp.name().lastIndexOf('.'));
        return member(document.module, owner, imp.simpleName(), () -> null);
    }

    /** A declaration in the document, moved to the current text; none when its line was edited. */
    private static List<Location> local(Document current, Document parsed, Pos pos) {
        Position position = parsed.position(pos);
        int line = parsed.sameLineIn(current, position.getLine());
        return line < 0 ? List.of() : List.of(location(current.path, new Position(line, position.getCharacter())));
    }

    /** The qualified class a name refers to: an imported type or a class of the same package. */
    private String typeName(Document document, Token token) {
        SourceFile ast = document.ast;
        for (Import imp : ast.imports()) {
            if (imp.kind() == Import.Kind.TYPE && imp.simpleName().equals(token.getText())) {
                return imp.name();
            }
        }
        String pkg = ast.packageName() == null ? "" : ast.packageName();
        String sibling = pkg.isEmpty() ? token.getText() : pkg + "." + token.getText();
        return workspace.sourceOf(document.module, sibling) != null ? sibling : null;
    }

    /** The declaration of a class: the type of an .axl file, or the start of its file. */
    private List<Location> classLocation(Module module, String qualifiedName) {
        Declarations.TypeAt type = declarations.type(module, qualifiedName);
        if (type != null) {
            Position position = type.decl() == null ? new Position(0, 0) : type.document().position(type.decl().namePos());
            return List.of(location(type.document().path, position));
        }
        JavaModel java = workspace.javaModel(module);
        Location found = java.declaration(java.type(qualifiedName));
        if (found != null) {
            return List.of(found);
        }
        Path source = workspace.sourceOf(module, qualifiedName);
        return source == null ? List.of() : List.of(location(source, new Position(0, 0)));
    }

    private List<Location> member(Module module, String className, String name, Supplier<List<String>> argumentTypes) {
        Path source = workspace.sourceOf(module, className);
        if (source != null && source.toString().endsWith(".axl")) {
            Document other = workspace.load(source);
            if (other.ast != null) {
                Decl decl = Symbols.topLevel(other.ast, name, argumentTypes);
                if (decl != null) {
                    return List.of(location(source, other.position(decl.namePos())));
                }
            }
            return List.of(location(source, new Position(0, 0)));
        }
        JavaModel java = workspace.javaModel(module);
        TypeElement type = java.type(className);
        Location found = type == null ? null : java.declaration(java.member(type, name, argumentTypes.get()));
        if (found != null) {
            return List.of(found);
        }
        return source == null ? List.of() : List.of(location(source, new Position(0, 0)));
    }

    private static Location location(Path file, Position position) {
        return new Location(file.toUri().toString(), new Range(position, position));
    }
}
