package org.axl.lsp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.parse.AxolotlLexer;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;

/** The declarations of a file, with the fields of data classes and interfaces, the members of classes and the constants of enums below them. */
final class Outline {

    private final Workspace workspace;

    Outline(Workspace workspace) {
        this.workspace = workspace;
    }

    /** From the last version that parsed, so that it does not vanish while the user is typing. */
    List<DocumentSymbol> symbols(String uri) {
        Document document = workspace.parsed(uri);
        if (document == null) {
            return List.of();
        }
        return document.ast.decls().stream().map(decl -> symbol(document, decl)).toList();
    }

    private static DocumentSymbol symbol(Document document, Decl decl) {
        return switch (decl) {
            case Decl.Function fn -> symbol(document, fn.namePos(), fn.name(), SymbolKind.Function, List.of());
            case Decl.Value value -> symbol(document, value.namePos(), value.name(), SymbolKind.Constant, List.of());
            case Decl.EnumDecl enumeration -> symbol(document, enumeration.namePos(), enumeration.name(), SymbolKind.Enum,
                    constants(document, enumeration));
            case Decl.ClassDecl classDecl -> symbol(document, classDecl.namePos(), classDecl.name(), SymbolKind.Class,
                    members(document, classDecl));
            case Decl.DataClass data -> symbol(document, data.namePos(), data.name(), SymbolKind.Class,
                    fields(document, data.fields()));
            case Decl.Interface iface -> symbol(document, iface.namePos(), iface.name(), SymbolKind.Interface,
                    fields(document, iface.members()));
        };
    }

    private static List<DocumentSymbol> members(Document document, Decl.ClassDecl classDecl) {
        List<DocumentSymbol> result = new ArrayList<>();
        for (Decl.Property p : classDecl.parameters()) {
            result.add(symbol(document, p.namePos(), p.name(), SymbolKind.Field, List.of()));
        }
        for (Decl.Property p : classDecl.properties()) {
            result.add(symbol(document, p.namePos(), p.name(), SymbolKind.Field, List.of()));
        }
        for (Decl.Function m : classDecl.methods()) {
            result.add(symbol(document, m.namePos(), m.name(), SymbolKind.Method, List.of()));
        }
        return result;
    }

    /** The AST keeps only the names of constants, so they are found among the tokens after the braces open. */
    private static List<DocumentSymbol> constants(Document document, Decl.EnumDecl enumeration) {
        List<DocumentSymbol> result = new ArrayList<>();
        Iterator<String> names = enumeration.constants().iterator();
        boolean open = false;
        for (Token token : document.tokens) {
            if (token.getChannel() != Token.DEFAULT_CHANNEL || !names.hasNext()) {
                continue;
            }
            if (!open) {
                open = token.getType() == AxolotlLexer.LBRACE && Symbols.before(enumeration.namePos(), Document.start(token));
            } else if (token.getType() == AxolotlLexer.IDENTIFIER) {
                String name = names.next();
                result.add(symbol(document, Document.start(token), name, SymbolKind.EnumMember, List.of()));
            }
        }
        return result;
    }

    private static List<DocumentSymbol> fields(Document document, List<Expr.Param> fields) {
        return fields.stream().map(f -> symbol(document, nameIn(document, f), f.name(), SymbolKind.Field, List.of())).toList();
    }

    /** A field is positioned at its `val`; its name follows on the same line. */
    private static Pos nameIn(Document document, Expr.Param field) {
        String line = document.lines.get(field.pos().line() - 1);
        int from = document.position(field.pos()).getCharacter();
        return new Pos(field.pos().line(), line.codePointCount(0, line.indexOf(field.name(), from)) + 1);
    }

    private static DocumentSymbol symbol(Document document, Pos namePos, String name, SymbolKind kind,
                                         List<DocumentSymbol> children) {
        Range range = new Range(document.position(namePos), document.position(new Pos(namePos.line(), namePos.column() + name.length())));
        return new DocumentSymbol(name, kind, range, range, null, children);
    }
}
