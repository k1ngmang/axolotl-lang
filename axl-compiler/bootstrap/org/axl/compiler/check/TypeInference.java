package org.axl.compiler.check;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Import;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.ast.TypeRef;

@NullMarked public final class TypeInference {
    public final SourceFile file;
    public final Map<String, TypeRef> known;
    public final List<SourceFile> siblings;
    public final HashSet<String> inProgress;
    public TypeInference(SourceFile file, Map<String, TypeRef> known, List<SourceFile> siblings) {
        this.file = file;
        this.known = known;
        this.siblings = siblings;
        this.inProgress = new HashSet<String>();
    }
    public @Nullable TypeRef value(Decl.Value value) {
        if (value.type() != null) {
            return value.type();
        }
        if (known.containsKey(value.name())) {
            return known.get(value.name());
        }
        if (!inProgress.add(value.name())) {
            return null;
        }
        final var type = of(value.init());
        inProgress.remove(value.name());
        return type;
    }
    public @Nullable TypeRef of(Expr expr) {
        switch (expr) {
            case Expr.Literal expr$1 -> {
                return literal(expr$1);
            }
            case Expr.Paren expr$2 -> {
                return of(expr$2.inner());
            }
            case Expr.Unary expr$3 -> {
                return (expr$3.op().equals("!") ? simple("Boolean", expr$3.pos()) : of(expr$3.operand()));
            }
            case Expr.Binary expr$4 -> {
                return binary(expr$4);
            }
            case Expr.Elvis expr$5 -> {
                return elvis(expr$5);
            }
            case Expr.Is expr$6 -> {
                return simple("Boolean", expr$6.pos());
            }
            case Expr.Name expr$7 -> {
                return Nulls.or(known.get(expr$7.name()), () -> topLevelValue(expr$7.name()));
            }
            case Expr.Call expr$8 -> {
                return (expr$8.target() == null ? call(expr$8) : Nulls.or(siblingCall(expr$8), () -> member(expr$8)));
            }
            case Expr.GenericNew expr$9 -> {
                return (expr$9.typeArgs().isEmpty() ? null : new TypeRef(expr$9.pos(), expr$9.type(), expr$9.typeArgs(), false));
            }
            case Expr.NotNull expr$10 -> {
                return nonNull(of(expr$10.target()));
            }
            case Expr.Conditional expr$11 -> {
                return common(of(expr$11.then()), of(expr$11.otherwise()));
            }
            case Expr.Try expr$12 -> {
                return okType(of(expr$12.inner()));
            }
            case Expr.ClassLiteral expr$13 -> {
                return classOf(expr$13);
            }
            case Expr.Attempt expr$14 -> {
                return (throwsInstead(expr$14) ? Nulls.box(of(expr$14.body())) : resultOf(of(expr$14.body()), of(expr$14.handler()), expr$14.pos()));
            }
            default -> {
                return null;
            }
        }
    }
    private @Nullable TypeRef classOf(Expr.ClassLiteral e) {
        final var name = Expr.pathName(e.target());
        return (name == null ? null : new TypeRef(e.pos(), "Class", List.of(simple(name, e.pos())), false));
    }
    private @Nullable TypeRef okType(@Nullable TypeRef result) {
        if (result == null || !(result.name().equals("Result") || result.name().equals("axl.lang.Result")) || result.args().size() != 2) {
            return null;
        }
        return result.args().getFirst();
    }
    private @Nullable TypeRef common(@Nullable TypeRef a, @Nullable TypeRef b) {
        if (a == null || b == null || !a.name().equals(b.name()) || !a.args().isEmpty() || !b.args().isEmpty()) {
            return null;
        }
        return new TypeRef(a.pos(), a.name(), a.args(), a.nullable() || b.nullable());
    }
    private @Nullable TypeRef literal(Expr.Literal l) {
        final var text = l.text();
        if (text.startsWith("\"")) {
            return simple("String", l.pos());
        }
        if (text.equals("true") || text.equals("false")) {
            return simple("Boolean", l.pos());
        }
        if (text.equals("null") || text.startsWith("'")) {
            return null;
        }
        final var last = Character.toLowerCase(text.charAt(text.length() - 1));
        if (last == 'l') {
            return simple("Long", l.pos());
        }
        final var hex = text.startsWith("0x") || text.startsWith("0X");
        if (!hex && (text.contains(".") || text.contains("e") || text.contains("E") || last == 'd')) {
            return (last == 'f' ? null : simple("Double", l.pos()));
        }
        return (last == 'f' && !hex ? null : simple("Int", l.pos()));
    }
    private @Nullable TypeRef binary(Expr.Binary b) {
        if (comparisons.contains(b.op())) {
            return simple("Boolean", b.pos());
        }
        final var left = of(b.left());
        final var right = of(b.right());
        if (b.op().equals("+") && (isString(left) || isString(right))) {
            return simple("String", b.pos());
        }
        if (left == null || right == null || !numbers.contains(left.name()) || !numbers.contains(right.name())) {
            return null;
        }
        return (numbers.indexOf(left.name()) >= numbers.indexOf(right.name()) ? nonNull(left) : nonNull(right));
    }
    private @Nullable TypeRef elvis(Expr.Elvis e) {
        final var left = of(e.left());
        if (isThrow(e.right())) {
            return (left == null ? null : new TypeRef(left.pos(), left.name(), left.args(), false));
        }
        final var right = of(e.right());
        return (right != null && left != null && sameType(left, right) ? right : null);
    }
    private @Nullable TypeRef call(Expr.Call c) {
        final var candidates = new ArrayList<Decl.Function>();
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Function decl$1 && decl$1.name().equals(c.name()) && decl$1.params().size() == c.args().size()) {
                candidates.add(decl$1);
            }
        }
        if (candidates.size() == 1) {
            return candidates.getFirst().returnType();
        }
        if (candidates.size() > 1 || isImportedFunction(c.name())) {
            return null;
        }
        if (Character.isUpperCase(c.name().charAt(0)) && !c.name().equals("Ok") && !c.name().equals("Err")) {
            return simple(c.name(), c.pos());
        }
        return null;
    }
    private @Nullable TypeRef siblingCall(Expr.Call c) {
        final var target = c.target();
        if (!(target instanceof Expr.Name target$1) || c.safe()) {
            return null;
        }
        for (final var sibling : siblings) {
            if (sibling.className().equals(target$1.name()) && samePackage(sibling)) {
                final var candidates = new ArrayList<Decl.Function>();
                for (final var decl : sibling.decls()) {
                    if (decl instanceof Decl.Function decl$2 && !decl$2.isPrivate() && decl$2.name().equals(c.name()) && decl$2.params().size() == c.args().size()) {
                        candidates.add(decl$2);
                    }
                }
                return (candidates.size() == 1 ? candidates.getFirst().returnType() : null);
            }
        }
        return null;
    }
    private boolean samePackage(SourceFile other) {
        final var own = file.packageName();
        final var theirs = other.packageName();
        return (own == null ? Boolean.valueOf(theirs == null) : own.equals(theirs));
    }
    private @Nullable TypeRef member(Expr.Call c) {
        final var target = c.target();
        if (target == null) {
            return null;
        }
        if (target instanceof Expr.Name target$1 && Character.isUpperCase(target$1.name().charAt(0)) && !known.containsKey(target$1.name())) {
            return staticCall(target$1.name(), c);
        }
        final var receiver = of(target);
        if (receiver == null) {
            return null;
        }
        final var type = (c.args().isEmpty() ? Nulls.or(fieldOf(receiver, c.name()), () -> jdkMethod(receiver, c)) : jdkMethod(receiver, c));
        return (type == null || !c.safe() ? type : new TypeRef(type.pos(), type.name(), type.args(), true));
    }
    private @Nullable TypeRef fieldOf(TypeRef receiver, String name) {
        final var decl = typeNamed(receiver.name());
        if (decl == null) {
            return null;
        }
        final var params = typeParamsOf(decl);
        if (!params.isEmpty() && params.size() != receiver.args().size()) {
            return null;
        }
        final var field = Nulls.first(fieldsOf(decl).stream().filter(f -> f.name().equals(name)).iterator());
        final var type = Nulls.map(field, $n1 -> $n1.type());
        return (type == null ? null : Implementations.substitute(type, params, receiver.args()));
    }
    private @Nullable Decl typeNamed(String name) {
        final var simple = simpleName(name);
        for (final var decl : file.decls()) {
            if ((decl instanceof Decl.DataClass decl$1 || decl instanceof Decl.ClassDecl decl$2) && decl.name().equals(simple)) {
                return decl;
            }
        }
        for (final var sibling : siblings) {
            if (samePackage(sibling)) {
                for (final var decl : sibling.decls()) {
                    if ((decl instanceof Decl.DataClass decl$3 || decl instanceof Decl.ClassDecl decl$4) && decl.name().equals(simple)) {
                        return decl;
                    }
                }
            }
        }
        return null;
    }
    private @Nullable TypeRef jdkMethod(TypeRef receiver, Expr.Call c) {
        final var name = c.name();
        final var owner = simpleName(receiver.name());
        final var pos = c.pos();
        final var arity = c.args().size();
        if (name.equals("toString") && arity == 0) {
            return simple("String", pos);
        }
        if (name.equals("hashCode") && arity == 0) {
            return simple("Int", pos);
        }
        if (name.equals("equals") && arity == 1) {
            return simple("Boolean", pos);
        }
        if (owner.equals("String")) {
            if (STRING_STRINGS.contains(name)) {
                return simple("String", pos);
            }
            if (STRING_INTS.contains(name)) {
                return simple("Int", pos);
            }
            if (STRING_BOOLEANS.contains(name)) {
                return simple("Boolean", pos);
            }
            return (name.equals("charAt") ? simple("Character", pos) : null);
        }
        if (COLLECTIONS.contains(owner)) {
            if (name.equals("size") || name.equals("indexOf")) {
                return simple("Int", pos);
            }
            if (name.equals("isEmpty") || name.equals("contains") || name.equals("add")) {
                return simple("Boolean", pos);
            }
            if ((name.equals("subList") || name.equals("reversed")) && owner.equals("List")) {
                return nonNull(receiver);
            }
            final var element = receiver.args().size() == 1 && arity <= 1 && (name.equals("get") || name.equals("getFirst") || name.equals("getLast"));
            return (element ? receiver.args().getFirst() : null);
        }
        if (MAPS.contains(owner)) {
            if (name.equals("size")) {
                return simple("Int", pos);
            }
            if (name.equals("isEmpty") || name.equals("containsKey") || name.equals("containsValue")) {
                return simple("Boolean", pos);
            }
            if (receiver.args().size() != 2) {
                return null;
            }
            final var value = receiver.args().get(1);
            if (name.equals("keySet")) {
                return new TypeRef(pos, "Set", List.of(receiver.args().get(0)), false);
            }
            if (name.equals("values")) {
                return new TypeRef(pos, "Collection", List.of(value), false);
            }
            return (name.equals("get") && arity == 1 ? new TypeRef(pos, value.name(), value.args(), true) : null);
        }
        return null;
    }
    private @Nullable TypeRef staticCall(String owner, Expr.Call c) {
        final var name = c.name();
        final var pos = c.pos();
        final var arity = c.args().size();
        if (owner.equals("System")) {
            if (name.equals("nanoTime") || name.equals("currentTimeMillis")) {
                return simple("Long", pos);
            }
            if (name.equals("lineSeparator")) {
                return simple("String", pos);
            }
            return ((name.equals("getProperty") || name.equals("getenv")) && arity == 1 ? new TypeRef(pos, "String", new ArrayList<TypeRef>(), true) : null);
        }
        if (owner.equals("Math")) {
            if (MATH_DOUBLES.contains(name)) {
                return simple("Double", pos);
            }
            if (name.equals("round") && arity == 1) {
                return simple("Long", pos);
            }
            if (!(name.equals("max") || name.equals("min") || name.equals("abs")) || arity == 0) {
                return null;
            }
            @Nullable TypeRef widest = null;
            for (final var arg : c.args()) {
                final var type = of(arg);
                if (type == null || !numbers.contains(type.name())) {
                    return null;
                }
                widest = (widest == null || numbers.indexOf(type.name()) > numbers.indexOf(widest.name()) ? nonNull(type) : widest);
            }
            return widest;
        }
        if (owner.equals("Integer") || owner.equals("Long") || owner.equals("Double") || owner.equals("Boolean")) {
            final var own = (owner.equals("Integer") ? "Int" : owner);
            if (name.startsWith("parse") || name.equals("valueOf") || name.equals("sum") || name.equals("max") || name.equals("min")) {
                return simple(own, pos);
            }
            if (name.equals("compare") || name.equals("signum") || name.equals("bitCount")) {
                return simple("Int", pos);
            }
            return (name.equals("toString") || name.equals("toHexString") || name.equals("toBinaryString") ? simple("String", pos) : null);
        }
        if (owner.equals("Character")) {
            if (name.startsWith("is")) {
                return simple("Boolean", pos);
            }
            if (name.equals("toUpperCase") || name.equals("toLowerCase") || name.equals("valueOf")) {
                return simple("Character", pos);
            }
            return (name.equals("getNumericValue") || name.equals("compare") || name.equals("digit") ? simple("Int", pos) : null);
        }
        if (owner.equals("String")) {
            return (name.equals("valueOf") || name.equals("join") || name.equals("format") ? simple("String", pos) : null);
        }
        if (owner.equals("Objects")) {
            if (name.equals("equals") || name.equals("isNull") || name.equals("nonNull")) {
                return simple("Boolean", pos);
            }
            if (name.equals("hash") || name.equals("hashCode")) {
                return simple("Int", pos);
            }
            if (name.equals("toString")) {
                return simple("String", pos);
            }
            return (name.equals("requireNonNull") && arity >= 1 ? nonNull(of(c.args().getFirst())) : null);
        }
        if (owner.equals("List") || owner.equals("Set")) {
            return (name.equals("of") && arity > 0 ? elementsOf(owner, c.args(), pos) : null);
        }
        if (VALUE_TYPES.contains(owner)) {
            return simple((owner.equals("Paths") ? "Path" : owner), pos);
        }
        return null;
    }
    private @Nullable TypeRef elementsOf(String owner, List<Expr> args, Pos pos) {
        @Nullable TypeRef element = null;
        for (final var arg : args) {
            final var type = of(arg);
            if (type == null || type.nullable() || (element != null && !sameType(element, type))) {
                return null;
            }
            element = type;
        }
        return (element == null ? null : new TypeRef(pos, owner, List.of(element), false));
    }
    private List<Decl.TypeParam> typeParamsOf(Decl decl) {
        switch (decl) {
            case Decl.DataClass decl$1 -> {
                return decl$1.typeParams();
            }
            case Decl.ClassDecl decl$2 -> {
                return decl$2.typeParams();
            }
            default -> {
                return List.of();
            }
        }
    }
    private List<Expr.Param> fieldsOf(Decl decl) {
        switch (decl) {
            case Decl.DataClass decl$1 -> {
                return decl$1.fields();
            }
            case Decl.ClassDecl decl$2 -> {
                final var fields = new ArrayList<Expr.Param>(decl$2.parameters().stream().map(Nulls.nonNull(p -> new Expr.Param(p.pos(), p.name(), p.type(), List.of()))).toList());
                for (final var p : decl$2.properties()) {
                    fields.add(new Expr.Param(p.pos(), p.name(), p.type(), List.of()));
                }
                return fields;
            }
            default -> {
                return List.of();
            }
        }
    }
    private boolean isImportedFunction(String name) {
        return file.imports().stream().anyMatch(i -> i.kind() != Import.Kind.TYPE && i.simpleName().equals(name));
    }
    private @Nullable TypeRef topLevelValue(String name) {
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Value decl$1 && decl$1.name().equals(name)) {
                return value(decl$1);
            }
        }
        return null;
    }
    private static final List<String> numbers = List.of("Int", "Long", "Double");
    private static final Set<String> STRING_STRINGS = Set.of("toUpperCase", "toLowerCase", "trim", "strip", "stripLeading", "stripTrailing",
            "substring", "replace", "replaceAll", "replaceFirst", "repeat", "concat", "formatted", "intern", "indent");
    private static final Set<String> STRING_INTS = Set.of("length", "indexOf", "lastIndexOf", "compareTo", "compareToIgnoreCase", "codePointAt");
    private static final Set<String> STRING_BOOLEANS = Set.of("isEmpty", "isBlank", "startsWith", "endsWith", "contains", "equalsIgnoreCase",
            "matches", "contentEquals");
    private static final Set<String> COLLECTIONS = Set.of("List", "ArrayList", "LinkedList", "Set", "HashSet", "LinkedHashSet", "TreeSet", "Collection");
    private static final Set<String> MAPS = Set.of("Map", "HashMap", "LinkedHashMap", "TreeMap", "ConcurrentHashMap");
    private static final Set<String> MATH_DOUBLES = Set.of("sqrt", "pow", "floor", "ceil", "random", "log", "log10", "exp", "sin", "cos", "tan",
            "atan2", "hypot", "cbrt", "rint", "toRadians", "toDegrees");
    private static final Set<String> VALUE_TYPES = Set.of("Instant", "Duration", "LocalDate", "LocalDateTime", "LocalTime", "ZonedDateTime",
            "Path", "Paths", "UUID", "BigDecimal", "BigInteger", "URI");
    private static String simpleName(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }
    private static final List<String> comparisons = List.of("==", "!=", "<", "<=", ">", ">=", "&&", "||");
    public static void inferAll(SourceFile file, Map<String, TypeRef> known) {
        final var inference = new TypeInference(file, known, List.of());
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Value decl$1 && decl$1.type() == null) {
                final var type = inference.value(decl$1);
                if (type != null) {
                    known.put(decl$1.name(), type);
                }
            }
        }
    }
    public static @Nullable TypeRef typeOf(SourceFile file, Expr expr) {
        return new TypeInference(file, new HashMap<String, TypeRef>(), List.of()).of(expr);
    }
    public static @Nullable TypeRef typeOf(SourceFile file, List<SourceFile> siblings, Expr expr) {
        return new TypeInference(file, new HashMap<String, TypeRef>(), siblings).of(expr);
    }
    public static Map<String, TypeRef> propertyTypes(SourceFile file, List<SourceFile> siblings, Decl.@Nullable ClassDecl c) {
        final var result = new HashMap<String, TypeRef>();
        if (c == null) {
            return result;
        }
        for (final var p : c.parameters()) {
            final var type = p.type();
            if (type != null) {
                result.put(p.name(), type);
            }
        }
        final var inference = new TypeInference(file, result, siblings);
        for (final var p : c.properties()) {
            final var init = p.init();
            final var type = Nulls.or(p.type(), () -> ((init == null ? null : inference.of(init))));
            if (type != null) {
                result.put(p.name(), type);
            }
        }
        return result;
    }
    private static boolean sameType(TypeRef a, TypeRef b) {
        if (!a.name().equals(b.name()) || a.args().size() != b.args().size()) {
            return false;
        }
        var i = 0;
        while (i < a.args().size()) {
            final var left = a.args().get(i);
            final var right = b.args().get(i);
            if (!sameType(left, right) || left.nullable() != right.nullable()) {
                return false;
            }
            i += 1;
        }
        return true;
    }
    private static boolean isString(@Nullable TypeRef type) {
        return type != null && (type.name().equals("String") || type.name().equals("java.lang.String"));
    }
    private static @Nullable TypeRef nonNull(@Nullable TypeRef type) {
        return (type == null ? null : new TypeRef(type.pos(), type.name(), type.args(), false));
    }
    private static TypeRef simple(String name, Pos pos) {
        return new TypeRef(pos, name, new ArrayList<TypeRef>(), false);
    }
    private static @Nullable TypeRef resultOf(@Nullable TypeRef value, @Nullable TypeRef error, Pos pos) {
        if (value == null || error == null) {
            return null;
        }
        return new TypeRef(pos, "Result", List.of(value, error), false);
    }
    private static boolean throwsInstead(Expr.Attempt a) {
        return isThrow(a.handler());
    }
    private static boolean isThrow(Expr e) {
        return e instanceof Expr.Throw e$1 || (e instanceof Expr.Paren e$2 && isThrow(e$2.inner()));
    }
}
