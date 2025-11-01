package org.axl.lsp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.ast.SourceFile;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

/** Keywords, visible names, and members of Java types after a dot. */
final class Completions {

    static final List<String> KEYWORDS = List.of("package", "import", "fn", "val", "var", "private", "return", "if",
            "else", "while", "try", "attempt", "true", "false", "null");
    private static final List<String> PRELUDE = List.of("println", "Ok", "Err");
    static final List<String> PIPE_STAGES = List.of("filter", "map", "mapResult", "peek", "sorted", "distinct", "limit",
            "skip", "forEach", "anyMatch", "allMatch", "noneMatch", "count", "first");
    private static final Pattern PIPE_STAGE = Pattern.compile("\\|>\\s*\\w*$");
    private static final Pattern MEMBER = Pattern.compile("([\\w.]+)\\??\\.(\\w*)$");

    private final Workspace workspace;

    Completions(Workspace workspace) {
        this.workspace = workspace;
    }

    List<CompletionItem> complete(String uri, int line, int character) {
        Document current = workspace.document(uri);
        Document parsed = workspace.parsed(uri);
        if (current == null || line >= current.lines.size()) {
            return List.of();
        }
        String prefix = current.lines.get(line).substring(0, Math.min(character, current.lines.get(line).length()));
        Pos pos = parsed == null ? null : positionIn(current, parsed, line, character);
        if (PIPE_STAGE.matcher(prefix).find()) {
            return PIPE_STAGES.stream().map(name -> {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Method);
                return item;
            }).toList();
        }
        Matcher member = MEMBER.matcher(prefix);
        if (member.find()) {
            return parsed == null ? List.of() : members(parsed, pos, member.group(1));
        }
        List<CompletionItem> items = new ArrayList<>();
        if (parsed != null) {
            Map<String, Symbols.Symbol> names = new LinkedHashMap<>();
            Symbols.visibleAt(parsed.ast, pos).forEach(s -> names.put(s.name(), s));
            for (Symbols.Symbol symbol : names.values()) {
                CompletionItem item = new CompletionItem(symbol.name());
                item.setKind(kind(symbol.kind()));
                if (symbol.type() != null) {
                    item.setDetail(Hovers.typeText(symbol.type()));
                }
                items.add(item);
            }
        }
        for (String name : PRELUDE) {
            CompletionItem item = new CompletionItem(name);
            item.setKind(CompletionItemKind.Function);
            items.add(item);
        }
        for (String keyword : KEYWORDS) {
            CompletionItem item = new CompletionItem(keyword);
            item.setKind(CompletionItemKind.Keyword);
            items.add(item);
        }
        return items;
    }

    /**
     * The position in the version that parsed. The line being typed is usually the edited one; the
     * start of the edit sees the same names.
     */
    private static Pos positionIn(Document current, Document parsed, int line, int character) {
        int same = current.sameLineIn(parsed, line);
        return same >= 0 ? parsed.pos(same, character) : new Pos(current.sharedLeadingLines(parsed) + 1, 1);
    }

    private List<CompletionItem> members(Document document, Pos pos, String receiver) {
        SourceFile ast = document.ast;
        JavaModel java = workspace.javaModel(document.module);
        TypeElement type = null;
        boolean statics = false;
        Symbols.Symbol symbol = receiver.contains(".") ? null : Symbols.lookup(ast, pos, receiver);
        if (symbol != null && symbol.kind() != Symbols.Kind.TYPE) {
            if (symbol.type() != null) {
                type = java.resolveType(ast, symbol.type());
            } else if (symbol.function() != null) {
                type = JavaModel.elementOf(java.localType(ast, symbol.function().name(),
                        symbol.function().params().size(), symbol.name()));
            }
        } else {
            type = java.resolveType(ast, receiver);
            statics = true;
        }
        if (type == null) {
            return List.of();
        }
        List<CompletionItem> items = new ArrayList<>();
        for (Element member : java.members(type, statics)) {
            CompletionItem item = new CompletionItem(member.getSimpleName().toString());
            item.setKind(member.getKind() == ElementKind.METHOD ? CompletionItemKind.Method : CompletionItemKind.Field);
            item.setLabel(JavaModel.signature(member));
            item.setInsertText(member.getSimpleName().toString());
            item.setFilterText(member.getSimpleName().toString());
            item.setDetail(JavaModel.typeOf(member));
            items.add(item);
        }
        return items;
    }

    private static CompletionItemKind kind(Symbols.Kind kind) {
        return switch (kind) {
            case FUNCTION, IMPORTED_FUNCTION -> CompletionItemKind.Function;
            case VALUE, IMPORTED_VALUE -> CompletionItemKind.Constant;
            case TYPE -> CompletionItemKind.Class;
            case PARAMETER, LOCAL, VARIABLE -> CompletionItemKind.Variable;
        };
    }
}
