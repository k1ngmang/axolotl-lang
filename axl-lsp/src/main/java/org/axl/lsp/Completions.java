package org.axl.lsp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.parse.AxolotlLexer;
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

    /** A name or a call in a receiver such as `a.b().c`; arguments is -1 for a name. */
    private record Step(String name, int arguments) {}

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
        Token dot = memberDot(current, line, character);
        if (dot != null) {
            List<Step> receiver = receiver(current, dot);
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

    /** The names and calls before a dot, first to last; null when it is another expression. */
    private static List<Step> receiver(Document document, Token dot) {
        List<Step> steps = new ArrayList<>();
        Token token = dot;
        while (token != null && (token.getType() == AxolotlLexer.DOT || token.getType() == AxolotlLexer.SAFE_DOT)) {
            token = document.previous(token);
            if (token != null && token.getType() == AxolotlLexer.BANGBANG) {
                token = document.previous(token);
            }
            int arguments = -1;
            if (token != null && token.getType() == AxolotlLexer.RPAREN) {
                Token open = openingParenthesis(document, token);
                token = open == null ? null : document.previous(open);
                List<String> argumentTypes = token == null ? null : document.argumentTypes(token);
                if (argumentTypes == null) {
                    return null;
                }
                arguments = argumentTypes.size();
            }
            if (token == null || token.getType() != AxolotlLexer.IDENTIFIER) {
                return null;
            }
            steps.addFirst(new Step(token.getText(), arguments));
            Token name = token;
            token = document.previous(token);
            if (token != null && token.getLine() < name.getLine()) {
                // A dot at the end of the line above is being typed there: `label.` over `Integer.`.
                break;
            }
        }
        return steps;
    }

    private static Token openingParenthesis(Document document, Token close) {
        int depth = 0;
        for (Token token = close; token != null; token = document.previous(token)) {
            if (token.getType() == AxolotlLexer.RPAREN || token.getType() == AxolotlLexer.RBRACE) {
                depth++;
            } else if (token.getType() == AxolotlLexer.LPAREN || token.getType() == AxolotlLexer.LBRACE) {
                depth--;
            }
            if (depth == 0) {
                return token.getType() == AxolotlLexer.LPAREN ? token : null;
            }
        }
        return null;
    }

    private List<CompletionItem> members(Document document, Pos pos, List<Step> receiver) {
        SourceFile ast = document.ast;
        JavaModel java = workspace.javaModel(document.module);
        TypeElement type = null;
        boolean statics = false;
        int used = 1;
        Step first = receiver.getFirst();
        Symbols.Symbol symbol = Symbols.lookup(ast, pos, first.name());
        if (symbol != null && symbol.kind() != Symbols.Kind.TYPE) {
            boolean member = first.arguments() >= 0 || symbol.function() == null && symbol.type() == null;
            type = member ? memberOfOwner(java, ast, symbol, first) : valueType(java, ast, symbol);
        } else {
            // A class, maybe written with its package: `java.util.List`.
            statics = true;
            for (used = 0; type == null && used < receiver.size() && receiver.get(used).arguments() < 0; used++) {
                String name = receiver.subList(0, used + 1).stream().map(Step::name).collect(Collectors.joining("."));
                type = java.resolveType(ast, name);
            }
        }
        for (Step step : receiver.subList(used, receiver.size())) {
            type = type == null ? null : JavaModel.elementOf(java.memberType(type, step.name(), step.arguments()));
            statics = false;
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

    /** The type of a local, a parameter or a value with a written type. */
    private static TypeElement valueType(JavaModel java, SourceFile ast, Symbols.Symbol symbol) {
        if (symbol.type() != null) {
            return java.resolveType(ast, symbol.type());
        }
        return JavaModel.elementOf(java.localType(ast, symbol.function().name(), symbol.function().params().size(),
                symbol.name(), symbol.pos().line()));
    }

    /** The type of a top-level value or of a function's result, of this file or imported. */
    private static TypeElement memberOfOwner(JavaModel java, SourceFile ast, Symbols.Symbol symbol, Step step) {
        String owner = ast.className();
        if (symbol.imported() != null) {
            owner = symbol.imported().name().substring(0, symbol.imported().name().lastIndexOf('.'));
        }
        TypeElement type = java.resolveType(ast, owner);
        return type == null ? null : JavaModel.elementOf(java.memberType(type, step.name(), step.arguments()));
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
