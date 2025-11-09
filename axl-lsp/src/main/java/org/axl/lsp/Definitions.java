package org.axl.lsp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    Definitions(Workspace workspace) {
        this.workspace = workspace;
    }

    /** While the text does not parse, an earlier version answers for the lines that are unchanged. */
    List<Location> definition(String uri, int line, int character) {
        Document current = workspace.document(uri);
        Document document = workspace.parsed(uri);
        if (current == null || document == null) {
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
        String name = token.getText();
        Token previous = document.previous(token);
        if (previous != null && (previous.getType() == AxolotlLexer.DOT || previous.getType() == AxolotlLexer.SAFE_DOT)) {
            Token receiver = document.previous(previous);
            if (receiver == null || receiver.getType() != AxolotlLexer.IDENTIFIER) {
                return List.of();
            }
            String type = typeName(document, receiver);
            return type == null ? List.of() : member(type, name);
        }
        Symbols.Symbol symbol = Symbols.lookup(document.ast, document.pos(line, character), name);
        if (symbol == null) {
            String type = typeName(document, token);
            return type == null ? List.of() : classLocation(type);
        }
        return switch (symbol.kind()) {
            case IMPORTED_FUNCTION, IMPORTED_VALUE -> {
                String imported = symbol.imported().name();
                yield member(imported.substring(0, imported.lastIndexOf('.')), symbol.name());
            }
            case TYPE -> classLocation(symbol.imported().name());
            default -> local(current, document, symbol.pos());
        };
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
        return workspace.sourceOf(sibling) != null ? sibling : null;
    }

    private List<Location> classLocation(String qualifiedName) {
        Path source = workspace.sourceOf(qualifiedName);
        return source == null ? List.of() : List.of(location(source, new Position(0, 0)));
    }

    private List<Location> member(String className, String name) {
        Path source = workspace.sourceOf(className);
        if (source == null) {
            return List.of();
        }
        if (source.toString().endsWith(".axl")) {
            Document other = workspace.load(source);
            if (other.ast != null) {
                Decl decl = Symbols.topLevel(other.ast).get(name);
                if (decl != null) {
                    return List.of(location(source, other.position(decl.pos())));
                }
            }
            return List.of(location(source, new Position(0, 0)));
        }
        return List.of(location(source, javaMember(source, name)));
    }

    /** The first declaration of a method or field with that name in a Java file. */
    private static Position javaMember(Path file, String name) {
        Pattern declaration = Pattern.compile("\\b[\\w<>\\[\\],.? ]+\\s+(" + Pattern.quote(name) + ")\\s*[(=;]");
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = declaration.matcher(lines.get(i));
                if (m.find() && !lines.get(i).strip().startsWith("return")) {
                    return new Position(i, m.start(1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Position(0, 0);
    }

    private static Location location(Path file, Position position) {
        return new Location(file.toUri().toString(), new Range(position, position));
    }
}
