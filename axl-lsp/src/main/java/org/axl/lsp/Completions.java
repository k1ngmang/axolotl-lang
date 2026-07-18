package org.axl.lsp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
    private static final List<String> BUILTIN_TYPES = List.of("Int", "Long", "Double", "Boolean", "Result");
    static final List<String> PIPE_STAGES = List.of("filter", "map", "mapResult", "peek", "sorted", "distinct", "limit",
            "skip", "forEach", "anyMatch", "allMatch", "noneMatch", "count", "first");
    private static final Set<Integer> DOTS = Set.of(AxolotlLexer.DOT, AxolotlLexer.SAFE_DOT, AxolotlLexer.COLONCOLON);
    private static final Set<Integer> QUIET = Set.of(AxolotlLexer.STRING_LITERAL, AxolotlLexer.CHAR_LITERAL,
            AxolotlLexer.LINE_COMMENT, AxolotlLexer.BLOCK_COMMENT);
    private static final Pattern PIPE_STAGE = Pattern.compile("\\|>\\s*\\w*$");
    // An import being typed, with the qualifier before the last segment as group 1.
    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(?:(?:fn|val)\\s+)?((?:\\w+\\.)*)\\w*$");
    /** The name right after `@`; a qualified one is completed after its dots like any member. */
    private static final Pattern ANNOTATION = Pattern.compile("@\\s*\\w*$");
    private static final Pattern IMPORTED_TYPE = Pattern.compile("^\\s*import\\s+([\\w.]+)\\s*$", Pattern.MULTILINE);

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
        if (inLiteralOrComment(current, line, character)) {
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
        Matcher imported = IMPORT.matcher(prefix);
        if (imported.matches()) {
            return imported(current, imported.group(1).isEmpty() ? "" : imported.group(1).substring(0, imported.group(1).length() - 1));
        }
        if (ANNOTATION.matcher(prefix).find()) {
            return annotationTypes(current);
        }
        Token dot = memberDot(current, line, character);
        if (dot != null) {
            List<Receivers.Step> receiver = Receivers.steps(current, dot,
                    t -> parsed == null ? null : workspace.typeName(parsed, pos, t.getText()));
            boolean methodReference = dot.getType() == AxolotlLexer.COLONCOLON;
            return parsed == null || receiver == null ? List.of() : members(parsed, pos, receiver, methodReference);
        }
        boolean typeOnly = Keywords.typeExpected(current, line, character);
        List<CompletionItem> items = new ArrayList<>();
        if (parsed != null) {
            Map<String, Symbols.Symbol> names = new LinkedHashMap<>();
            List<Symbols.Symbol> visible = Outline.inDeclaration(parsed, pos) ? Symbols.visibleAt(parsed.ast, pos)
                    : Symbols.topLevelSymbols(parsed.ast);
            visible.stream().filter(s -> !typeOnly || isType(s)).forEach(s -> names.put(s.name(), s));
            for (Symbols.Symbol symbol : names.values()) {
                CompletionItem item = new CompletionItem(symbol.name());
                item.setKind(kind(symbol.kind()));
                if (symbol.type() != null) {
                    item.setDetail(Hovers.typeText(symbol.type()));
                }
                items.add(item);
            }
        }
        if (typeOnly) {
            Set<String> declared = items.stream().map(CompletionItem::getLabel).collect(Collectors.toSet());
            builtinTypes(current).stream().filter(item -> !declared.contains(item.getLabel())).forEach(items::add);
            return items;
        }
        List<String> keywords = Keywords.at(current, line, character);
        // The prelude's functions are values, which only fit where an expression does.
        for (String name : keywords.contains("null") ? PRELUDE : List.<String>of()) {
            CompletionItem item = new CompletionItem(name);
            item.setKind(CompletionItemKind.Function);
            items.add(item);
        }
        for (String keyword : keywords) {
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
     * The `.`, `?.` or `::` before the member being typed, or null. The cursor is in or after the
     * member's name, which may start a line after the dot; without a name, the dot is on the
     * cursor's line.
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
                    && last.getCharPositionInLine() + last.getStopIndex() - last.getStartIndex() + 1 >= column;
            last = typed ? document.previous(last) : null;
        } else if (last != null && last.getLine() - 1 != line) {
            return null;
        }
        return last != null && DOTS.contains(last.getType()) ? last : null;
    }

    /** Inside a string, a character or a comment, where nothing is completed. */
    private static boolean inLiteralOrComment(Document document, int line, int character) {
        Pos cursor = new Pos(line + 1, document.codePointColumn(line, character) + 1);
        for (Token token : document.tokens) {
            Pos start = Document.start(token);
            if (!Symbols.before(start, cursor) || start.equals(cursor)) {
                break;
            }
            boolean inside = token.getType() == AxolotlLexer.LINE_COMMENT ? start.line() == cursor.line()
                    : !Symbols.before(Document.end(token), cursor);
            if (QUIET.contains(token.getType()) && inside) {
                return true;
            }
        }
        return false;
    }

    /** methodReference: after `::`, where a class takes its static and instance methods. */
    private List<CompletionItem> members(Document document, Pos pos, List<Receivers.Step> receiver, boolean methodReference) {
        JavaModel java = workspace.javaModel(document.module);
        Receivers.Receiver type = Receivers.typeOf(java, document.ast, pos, receiver);
        TypeElement element = JavaModel.elementOf(type.type());
        if (element == null) {
            return List.of();
        }
        String pkg = document.ast.packageName() == null ? "" : document.ast.packageName();
        List<Element> members = new ArrayList<>(java.members(element, type.statics(), pkg));
        if (methodReference) {
            if (type.statics()) {
                members.addAll(java.members(element, false, pkg));
            }
            members.removeIf(m -> m.getKind() != ElementKind.METHOD);
        }
        return items(members);
    }

    /**
     * In an import, after the qualifier typed so far: the packages and classes of a package, or the
     * nested classes and static members of a class.
     */
    private List<CompletionItem> imported(Document document, String qualifier) {
        JavaModel java = workspace.javaModel(document.module);
        String pkg = Workspace.packageName(document.text);
        TypeElement type = qualifier.isEmpty() ? null : java.type(qualifier);
        if (type != null) {
            return items(java.members(type, true, pkg));
        }
        List<CompletionItem> items = new ArrayList<>();
        for (String name : java.subpackages(qualifier)) {
            CompletionItem item = new CompletionItem(name);
            item.setKind(CompletionItemKind.Module);
            items.add(item);
        }
        items.addAll(items(new ArrayList<>(java.classesOf(qualifier, pkg))));
        return items;
    }

    /**
     * After `@`: the annotation types among the imports, in the file's package and in java.lang. The
     * imports are read from the text, since a bare `@` keeps the file from parsing.
     */
    private List<CompletionItem> annotationTypes(Document document) {
        String pkg = Workspace.packageName(document.text);
        JavaModel java = workspace.javaModel(document.module);
        List<TypeElement> types = new ArrayList<>();
        Matcher imports = IMPORTED_TYPE.matcher(document.text);
        while (imports.find()) {
            TypeElement type = java.type(imports.group(1));
            if (type != null) {
                types.add(type);
            }
        }
        types.addAll(java.classesOf(pkg, pkg));
        types.addAll(java.classesOf("java.lang", pkg));
        Set<String> seen = new HashSet<>();
        List<CompletionItem> items = new ArrayList<>();
        for (TypeElement type : types) {
            if (type.getKind() == ElementKind.ANNOTATION_TYPE && seen.add(type.getSimpleName().toString())) {
                CompletionItem item = new CompletionItem(type.getSimpleName().toString());
                item.setKind(CompletionItemKind.Interface);
                item.setDetail(type.getQualifiedName().toString());
                items.add(item);
            }
        }
        return items;
    }

    /** The types every file sees: Axolotl's own and the classes of java.lang. */
    private List<CompletionItem> builtinTypes(Document document) {
        String pkg = Workspace.packageName(document.text);
        List<String> names = new ArrayList<>(BUILTIN_TYPES);
        workspace.javaModel(document.module).classesOf("java.lang", pkg).forEach(t -> names.add(t.getSimpleName().toString()));
        return names.stream().distinct().map(name -> {
            CompletionItem item = new CompletionItem(name);
            item.setKind(CompletionItemKind.Class);
            return item;
        }).toList();
    }

    private static List<CompletionItem> items(List<Element> members) {
        List<CompletionItem> items = new ArrayList<>();
        for (Element member : members) {
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

    private static boolean isType(Symbols.Symbol symbol) {
        return symbol.kind() == Symbols.Kind.TYPE || symbol.kind() == Symbols.Kind.DECLARED_TYPE;
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
