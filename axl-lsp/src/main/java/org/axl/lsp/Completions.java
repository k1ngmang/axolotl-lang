package org.axl.lsp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.parse.AxolotlLexer;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

/** Keywords that fit the place, visible names, and members of types after a dot. */
final class Completions {

    private static final List<String> PRELUDE = List.of("println", "Ok", "Err");
    static final List<String> PIPE_STAGES = List.of("filter", "map", "mapResult", "peek", "sorted", "distinct", "limit",
            "skip", "forEach", "anyMatch", "allMatch", "noneMatch", "count", "first");
    private static final Pattern PIPE_STAGE = Pattern.compile("\\|>\\s*\\w*$");

    private final Workspace workspace;

    Completions(Workspace workspace) {
        this.workspace = workspace;
    }

    List<CompletionItem> complete(String uri, int line, int character) {
        Document current = workspace.document(uri);
        Document parsed = workspace.parsed(uri);
        if (current == null || line < 0 || character < 0 || line >= current.lines.size()) {
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
        Token dot = memberDot(current, line, character);
        if (dot != null) {
            List<Receivers.Step> receiver = Receivers.steps(current, dot);
            return parsed == null || receiver == null ? List.of() : members(parsed, pos, receiver);
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
        for (String keyword : Keywords.at(current, line, character)) {
            CompletionItem item = new CompletionItem(keyword);
            item.setKind(CompletionItemKind.Keyword);
            items.add(item);
        }
        return items;
    }

    /**
     * The position in the version that parsed. The line being typed is usually the edited one: the
     * start of the edit on its earlier version sees the names declared before it on that line, as
     * the parameters of a one-line lambda. A new line sees what the end of the line above sees.
     */
    private static Pos positionIn(Document current, Document parsed, int line, int character) {
        int[] same = current.linesIn(parsed);
        if (same[line] >= 0) {
            return parsed.pos(same[line], character);
        }
        int above = line - 1;
        while (above >= 0 && same[above] < 0) {
            above--;
        }
        int earlier = above < 0 ? line : same[above] + line - above;
        if (earlier < nextKeptLine(same, line, parsed)) {
            String was = parsed.lines.get(earlier);
            return parsed.pos(earlier, Math.min(character, sharedPrefix(current.lines.get(line), was)));
        }
        return above < 0 ? new Pos(1, 1) : parsed.pos(same[above], parsed.lines.get(same[above]).length());
    }

    /** The line of the parsed version that the first unchanged line after a line is. */
    private static int nextKeptLine(int[] same, int line, Document parsed) {
        for (int i = line + 1; i < same.length; i++) {
            if (same[i] >= 0) {
                return same[i];
            }
        }
        return parsed.lines.size();
    }

    private static int sharedPrefix(String a, String b) {
        int length = 0;
        while (length < a.length() && length < b.length() && a.charAt(length) == b.charAt(length)) {
            length++;
        }
        return length;
    }

    /**
     * The `.` or `?.` before the member being typed, or null. The member's name ends at the cursor
     * and may start a line after the dot; without a name, the dot is on the cursor's line.
     */
    private static Token memberDot(Document document, int line, int character) {
        int column = document.codePointColumn(line, character);
        Token last = null;
        for (Token token : document.tokens) {
            int tokenLine = token.getLine() - 1;
            if (tokenLine > line || tokenLine == line && token.getCharPositionInLine() >= column) {
                break;
            }
            if (token.getChannel() == Token.DEFAULT_CHANNEL) {
                last = token;
            }
        }
        if (last != null && last.getType() == AxolotlLexer.IDENTIFIER) {
            boolean typed = last.getLine() - 1 == line
                    && last.getCharPositionInLine() + last.getStopIndex() - last.getStartIndex() + 1 == column;
            last = typed ? document.previous(last) : null;
        } else if (last != null && last.getLine() - 1 != line) {
            return null;
        }
        return last != null && (last.getType() == AxolotlLexer.DOT || last.getType() == AxolotlLexer.SAFE_DOT) ? last : null;
    }

    private List<CompletionItem> members(Document document, Pos pos, List<Receivers.Step> receiver) {
        JavaModel java = workspace.javaModel(document.module);
        Receivers.Receiver type = Receivers.typeOf(java, document.ast, pos, receiver);
        TypeElement element = JavaModel.elementOf(type.type());
        if (element == null) {
            return List.of();
        }
        List<CompletionItem> items = new ArrayList<>();
        for (Element member : java.members(element, type.statics())) {
            CompletionItem item = new CompletionItem(member.getSimpleName().toString());
            item.setKind(member.getKind() == ElementKind.METHOD ? CompletionItemKind.Method
                    : member instanceof TypeElement ? CompletionItemKind.Class : CompletionItemKind.Field);
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
            case TYPE, DECLARED_TYPE -> CompletionItemKind.Class;
            case PARAMETER, LOCAL, VARIABLE -> CompletionItemKind.Variable;
        };
    }
}
