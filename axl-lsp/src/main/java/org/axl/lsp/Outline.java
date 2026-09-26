package org.axl.lsp;

import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.parse.AxolotlLexer;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;

/**
 * The declarations of a file, with the fields and methods of data classes and interfaces, the
 * members of classes and the fields and constants of enums below them.
 */
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
        List<Decl> decls = document.ast.decls();
        List<DocumentSymbol> result = new ArrayList<>();
        for (int i = 0; i < decls.size(); i++) {
            Pos next = i + 1 < decls.size() ? decls.get(i + 1).pos() : null;
            DocumentSymbol symbol = symbol(document, decls.get(i));
            symbol.setRange(new Range(document.position(decls.get(i).pos()), end(document, decls.get(i).pos(), next)));
            result.add(symbol);
        }
        return result;
    }

    private static DocumentSymbol symbol(Document document, Decl decl) {
        return switch (decl) {
            case Decl.Function fn -> symbol(document, fn.namePos(), fn.name(), SymbolKind.Function, List.of());
            case Decl.Value value -> symbol(document, value.namePos(), value.name(), SymbolKind.Constant, List.of());
            case Decl.EnumDecl enumeration -> symbol(document, enumeration.namePos(), enumeration.name(), SymbolKind.Enum,
                    enumMembers(document, enumeration));
            case Decl.ClassDecl classDecl -> symbol(document, classDecl.namePos(), classDecl.name(), SymbolKind.Class,
                    members(document, classDecl));
            case Decl.DataClass data -> symbol(document, data.namePos(), data.name(), SymbolKind.Class,
                    dataClassMembers(document, data));
            case Decl.Interface iface -> symbol(document, iface.namePos(), iface.name(), SymbolKind.Interface,
                    interfaceMembers(document, iface));
        };
    }

    /**
     * Where a declaration that starts at a position ends: at its last token before the next
     * declaration, or before the brace that closes the class around it. The AST keeps only starts.
     */
    private static Position end(Document document, Pos start, Pos next) {
        Token last = lastToken(document, start, next);
        return document.position(last == null ? start : Document.end(last));
    }

    /**
     * Whether a position is in the text of a top-level declaration: after its start and up to its
     * last token, but not after its closing brace.
     */
    static boolean inDeclaration(Document document, Pos pos) {
        List<Decl> decls = document.ast.decls();
        for (int i = 0; i < decls.size() && Symbols.before(decls.get(i).pos(), pos); i++) {
            Token last = lastToken(document, decls.get(i).pos(), i + 1 < decls.size() ? decls.get(i + 1).pos() : null);
            boolean afterBrace = last != null && last.getType() == AxolotlLexer.RBRACE && pos.equals(Document.end(last));
            if (!pos.equals(decls.get(i).pos()) && last != null && Symbols.before(pos, Document.end(last)) && !afterBrace) {
                return true;
            }
        }
        return false;
    }

    /** Whether a position is in the text of a top-level declaration of the document, from its start to its last token. */
    static boolean within(Document document, Decl decl, Pos pos) {
        List<Decl> decls = document.ast.decls();
        int index = decls.indexOf(decl);
        Token last = lastToken(document, decl.pos(), index >= 0 && index + 1 < decls.size() ? decls.get(index + 1).pos() : null);
        return Symbols.before(decl.pos(), pos) && last != null && !Symbols.before(Document.end(last), pos);
    }

    private static Token lastToken(Document document, Pos start, Pos next) {
        Token last = null;
        int depth = 0;
        for (Token token : document.tokens.subList(document.firstTokenFrom(start), document.tokens.size())) {
            Pos at = Document.start(token);
            if (token.getChannel() != Token.DEFAULT_CHANNEL) {
                continue;
            }
            int type = token.getType();
            boolean closing = type == AxolotlLexer.RBRACE || type == AxolotlLexer.RPAREN;
            if (next != null && Symbols.before(next, at) || closing && depth == 0) {
                break;
            }
            if (closing) {
                depth--;
            } else if (type == AxolotlLexer.LBRACE || type == AxolotlLexer.LPAREN) {
                depth++;
            }
            last = token;
        }
        return last;
    }

    private static List<DocumentSymbol> interfaceMembers(Document document, Decl.Interface iface) {
        List<DocumentSymbol> result = new ArrayList<>(fields(document, iface.members()));
        for (Decl.Function m : iface.methods()) {
            result.add(symbol(document, m.namePos(), m.name(), SymbolKind.Method, List.of()));
        }
        return result;
    }

    private static List<DocumentSymbol> members(Document document, Decl.ClassDecl classDecl) {
        List<DocumentSymbol> result = new ArrayList<>();
        for (Decl.Property p : classDecl.parameters()) {
            result.add(symbol(document, p.namePos(), p.name(), SymbolKind.Field, List.of()));
        }
        List<Pos> starts = new ArrayList<>(classDecl.properties().stream().map(Decl.Property::pos).toList());
        classDecl.methods().forEach(m -> starts.add(m.pos()));
        classDecl.constructors().forEach(k -> starts.add(k.pos()));
        starts.sort((a, b) -> a.equals(b) ? 0 : Symbols.before(a, b) ? -1 : 1);
        for (Decl.Constructor k : classDecl.constructors()) {
            DocumentSymbol symbol = symbol(document, k.namePos(), "constructor", SymbolKind.Constructor, List.of());
            symbol.setDetail(Hovers.parameters(k.params()));
            result.add(withBody(document, symbol, k.pos(), starts));
        }
        for (Decl.Property p : classDecl.properties()) {
            result.add(withBody(document, symbol(document, p.namePos(), p.name(), SymbolKind.Field, List.of()), p.pos(), starts));
        }
        for (Decl.Function m : classDecl.methods()) {
            result.add(withBody(document, symbol(document, m.namePos(), m.name(), SymbolKind.Method, List.of()), m.pos(), starts));
        }
        return result;
    }

    /** A member's range runs from its start to the next member's. */
    private static DocumentSymbol withBody(Document document, DocumentSymbol symbol, Pos start, List<Pos> starts) {
        int index = starts.indexOf(start);
        Pos next = index + 1 < starts.size() ? starts.get(index + 1) : null;
        symbol.setRange(new Range(document.position(start), end(document, start, next)));
        return symbol;
    }

    /** The fields of a data class, then the methods of its body with their text. */
    private static List<DocumentSymbol> dataClassMembers(Document document, Decl.DataClass data) {
        List<DocumentSymbol> result = new ArrayList<>(fields(document, data.fields()));
        List<Pos> starts = data.methods().stream().map(Decl.Function::pos).toList();
        for (Decl.Function m : data.methods()) {
            result.add(withBody(document, symbol(document, m.namePos(), m.name(), SymbolKind.Method, List.of()), m.pos(), starts));
        }
        return result;
    }

    /** The fields of an enum, then its constants. */
    private static List<DocumentSymbol> enumMembers(Document document, Decl.EnumDecl enumeration) {
        List<DocumentSymbol> result = new ArrayList<>(fields(document, enumeration.fields()));
        result.addAll(constants(document, enumeration));
        return result;
    }

    private static List<DocumentSymbol> constants(Document document, Decl.EnumDecl enumeration) {
        List<Pos> positions = document.constantPositions(enumeration);
        List<DocumentSymbol> result = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            result.add(symbol(document, positions.get(i), enumeration.constants().get(i), SymbolKind.EnumMember, List.of()));
        }
        return result;
    }

    private static List<DocumentSymbol> fields(Document document, List<Expr.Param> fields) {
        return fields.stream().map(f -> symbol(document, fieldName(document, f), f.name(), SymbolKind.Field, List.of())).toList();
    }

    /** A field is positioned at its `val`, which may hold the letters of its name: `val a`. */
    static Pos fieldName(Document document, Expr.Param field) {
        return document.nameAfter(field.pos(), field.name());
    }

    private static DocumentSymbol symbol(Document document, Pos namePos, String name, SymbolKind kind,
                                         List<DocumentSymbol> children) {
        Range range = new Range(document.position(namePos), document.position(new Pos(namePos.line(), namePos.column() + name.length())));
        return new DocumentSymbol(name, kind, range, range, null, children);
    }
}
