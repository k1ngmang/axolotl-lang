package org.axl.compiler.gen;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.axl.compiler.GeneratedUnit;
import org.axl.compiler.JavaTypes;
import org.axl.compiler.LineMap;
import org.axl.compiler.Options;
import org.axl.compiler.ast.Decl;
import org.axl.compiler.ast.Expr;
import org.axl.compiler.ast.Expr.Binary;
import org.axl.compiler.ast.Expr.Literal;
import org.axl.compiler.ast.Expr.Name;
import org.axl.compiler.ast.Expr.Paren;
import org.axl.compiler.ast.Expr.Pipe;
import org.axl.compiler.ast.Expr.Unary;
import org.axl.compiler.ast.Import;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.check.Implementations.RequiredMethod;
import org.axl.compiler.ast.Stmt;
import org.axl.compiler.ast.Stmt.Block;
import org.axl.compiler.ast.Stmt.ExprStmt;
import org.axl.compiler.ast.Stmt.If;
import org.axl.compiler.ast.Stmt.Return;
import org.axl.compiler.ast.TypeRef;
import org.axl.compiler.check.Analysis;
import org.axl.compiler.check.Checker;
import org.axl.compiler.check.Implementations;
import org.axl.compiler.check.TypeInference;
import org.axl.compiler.check.Whens;

@NullMarked public final class Generator {
    public final SourceFile file;
    public final Analysis analysis;
    public final Options options;
    public final Map<String, String> valueTypes;
    public final Set<String> probed;
    public final Set<String> declaredFunctions;
    public Emitter em;
    public Map<String, String> smart;
    public final IdentityHashMap<Expr.Is, String> patternVariables;
    public final IdentityHashMap<Expr, @Nullable TypeRef> branchTypes;
    public int patternCount;
    public List<String> overridden;
    public List<RequiredMethod> requiredMethods;
    public @Nullable TypeRef subjectTypeOf;
    public final IdentityHashMap<Expr, Integer> startLines;
    public Decl.@Nullable ClassDecl currentClass;
    public List<Decl.Function> currentMethods;
    public String elseLabel;
    public boolean inPropertyInit;
    public int lambdaDepth;
    public List<Expr> whenValues;
    public int tempCount;
    public int nullCount;
    public int exceptionCount;
    public int copyCount;
    public int listCount;
    public Map<String, String> copies;
    public @Nullable String returnType;
    public @Nullable TypeRef resultTypeRef;
    public final HashMap<String, @Nullable TypeRef> localTypes;
    public final HashSet<String> boxedLocals;
    public int nestedValues;
    public boolean valueStage;
    public Generator(SourceFile file, Analysis analysis, Options options, Map<String, String> valueTypes, Set<String> probed) {
        this.file = file;
        this.analysis = analysis;
        this.options = options;
        this.valueTypes = valueTypes;
        this.probed = probed;
        this.declaredFunctions = declaredFunctionNames(file);
        this.em = new Emitter();
        this.smart = new HashMap<String, String>();
        this.patternVariables = new IdentityHashMap<Expr.Is, String>();
        this.branchTypes = new IdentityHashMap<Expr, @Nullable TypeRef>();
        this.patternCount = 0;
        this.overridden = List.of();
        this.requiredMethods = List.of();
        this.subjectTypeOf = null;
        this.startLines = new IdentityHashMap<Expr, Integer>();
        this.currentClass = null;
        this.currentMethods = List.of();
        this.elseLabel = "default";
        this.inPropertyInit = false;
        this.lambdaDepth = 0;
        this.whenValues = List.of();
        this.tempCount = 0;
        this.nullCount = 0;
        this.exceptionCount = 0;
        this.copyCount = 0;
        this.listCount = 0;
        this.copies = Map.of();
        this.returnType = null;
        this.resultTypeRef = null;
        this.localTypes = new HashMap<String, @Nullable TypeRef>();
        this.boxedLocals = new HashSet<String>();
        this.nestedValues = 0;
        this.valueStage = false;
    }
    public void emitFile() {
        final var packageName = Nulls.or(file.packageName(), () -> "");
        final var packagePos = Nulls.or(file.packagePos(), () -> new Pos(1, 1));
        em.line(packagePos.line(), "package " + packageName + ";");
        em.line(0, "import axl.lang.*;");
        em.line(0, "import org.jspecify.annotations.*;");
        for (final var imp : file.imports()) {
            final var keyword = (imp.kind() == Import.Kind.TYPE ? "import " : "import static ");
            em.line(imp.pos().line(), keyword + imp.name() + ";");
        }
        em.blank();
        final var fileType = typeOfFile();
        if (fileType == null) {
            em.line(0, "@NullMarked public final class " + file.className() + " {");
            em.indent();
            em.line(0, "private " + file.className() + "() {}");
        } else {
            emitFileType(fileType);
        }
        for (final var decl : file.decls()) {
            resetCounters();
            if (!decl.equals(fileType)) {
                emitDecl(decl);
            }
        }
        if (!probed.isEmpty()) {
            emitProbe();
        }
        em.dedent();
        em.line(0, "}");
    }
    private void emitDecl(Decl decl) {
        switch (decl) {
            case Decl.Value decl$1 -> {
                if (!probed.contains(decl$1.name())) {
                    emitValue(decl$1);
                }
            }
            case Decl.Function decl$2 -> {
                emitFunction(decl$2, true);
            }
            case Decl.ClassDecl decl$3 -> {
                emitClass(decl$3);
            }
            case Decl.EnumDecl decl$4 -> {
                emitEnum(decl$4);
            }
            case Decl.DataClass decl$5 -> {
                emitDataClass(decl$5);
            }
            case Decl.Interface decl$6 -> {
                emitInterface(decl$6);
            }
        }
    }
    private void resetCounters() {
        tempCount = 0;
        nullCount = 0;
        exceptionCount = 0;
        copyCount = 0;
        listCount = 0;
        patternCount = 0;
        smart = new HashMap<String, String>();
        copies = Map.of();
    }
    private String newTemp() {
        tempCount += 1;
        return "$t" + tempCount;
    }
    private String newNull() {
        nullCount += 1;
        return "$n" + nullCount;
    }
    private String newCopy() {
        copyCount += 1;
        return "$c" + copyCount;
    }
    private String newList() {
        listCount += 1;
        return "$p" + listCount;
    }
    private String patternVariable(@Nullable String narrowed) {
        return Nulls.<String>or(narrowed, () -> "") + "$" + ((narrowed == null ? "s" : "")) + newPattern();
    }
    private int newPattern() {
        patternCount += 1;
        return patternCount;
    }
    private @Nullable Decl typeOfFile() {
        for (final var d : file.decls()) {
            if (d.name().equals(file.className()) && isTypeDecl(d)) {
                return d;
            }
        }
        return null;
    }
    private void emitFileType(Decl fileType) {
        final var line = fileType.pos().line();
        final var marked = "@NullMarked " + annotations(fileType.annotations());
        if (fileType instanceof Decl.DataClass fileType$1) {
            em.line(line, marked + "public record " + fileType$1.name() + typeParamList(fileType$1.typeParams()) + "(" +
                    parameters(fileType$1.fields(), line) +
                    ")" +
                    typeList(" implements ", fileType$1.supertypes()) +
                    " {");
            em.indent();
            emitDataClassMethods(fileType$1);
            em.dedent();
        } else if (fileType instanceof Decl.Interface fileType$2) {
            em.line(line, marked + "public" + interfaceModifier(fileType$2) + " interface " + fileType$2.name() + typeParamList(fileType$2.typeParams()) +
                    typeList(" extends ", fileType$2.supertypes()) +
                    " {");
            emitRequiredValues(fileType$2);
        } else if (fileType instanceof Decl.ClassDecl fileType$3) {
            em.line(line, marked + "public final class " + fileType$3.name() + typeParamList(fileType$3.typeParams()) +
                    supertypeList(fileType$3) +
                    " {");
            emitClassMembers(fileType$3);
        } else if (fileType instanceof Decl.EnumDecl fileType$4) {
            em.line(line, marked + "public enum " + fileType$4.name() + " { " + constantList(fileType$4) + ";");
            em.indent();
            emitEnumFields(fileType$4);
            em.dedent();
        } else {
            throw new IllegalStateException();
        }
        em.indent();
    }
    private void emitClass(Decl.ClassDecl classDecl) {
        em.line(classDecl.pos().line(), annotations(classDecl.annotations()) + visibility(classDecl) + " static final class " + classDecl.name() +
                typeParamList(classDecl.typeParams()) +
                supertypeList(classDecl) +
                " {");
        emitClassMembers(classDecl);
        em.line(classDecl.pos().line(), "}");
    }
    private String supertypeList(Decl.ClassDecl classDecl) {
        final var supertypes = classDecl.supertypes();
        if (classDecl.superArgs() == null || supertypes.isEmpty()) {
            return typeList(" implements ", supertypes);
        }
        return " extends " + javaType(supertypes.getFirst()) + typeList(" implements ", supertypes.subList(1, supertypes.size()));
    }
    private void emitEnum(Decl.EnumDecl e) {
        final var line = e.pos().line();
        final var head = annotations(e.annotations()) + visibility(e) + " enum " + e.name();
        if (e.fields().isEmpty()) {
            em.line(line, head + " { " + constantList(e) + " }");
            return;
        }
        em.line(line, head + " {");
        em.indent();
        em.line(line, constantList(e) + ";");
        emitEnumFields(e);
        em.dedent();
        em.line(line, "}");
    }
    private String constantList(Decl.EnumDecl e) {
        final var texts = new ArrayList<String>();
        var i = 0;
        while (i < e.constants().size()) {
            final var arguments = e.arguments().get(i);
            final var args = arguments.stream().map(Nulls.nonNull(a -> expr(a))).toList();
            texts.add(e.constants().get(i) + ((arguments.isEmpty() ? "" : "(" + String.join(", ", args) + ")")));
            i += 1;
        }
        return String.join(", ", texts);
    }
    private void emitEnumFields(Decl.EnumDecl e) {
        if (e.fields().isEmpty()) {
            return;
        }
        final var line = e.pos().line();
        for (final var f : e.fields()) {
            em.line(f.pos().line(), annotations(f.annotations()) + "public final " + javaType(memberType(f)) + " " + f.name() + ";");
        }
        em.line(line, e.name() + "(" + parameters(e.fields(), line) + ") {");
        em.indent();
        for (final var f : e.fields()) {
            em.line(line, "this." + f.name() + " = " + f.name() + ";");
        }
        em.dedent();
        em.line(line, "}");
    }
    private void emitClassMembers(Decl.ClassDecl classDecl) {
        em.indent();
        final var outerClass = currentClass;
        final var outerMethods = currentMethods;
        currentClass = classDecl;
        currentMethods = classDecl.methods();
        final var lifting = classDecl.properties().stream().anyMatch(p -> Lifts.needed(propertyInit(p)));
        final var superArgs = classDecl.superArgs();
        final var inConstructor = !classDecl.parameters().isEmpty() || lifting || (superArgs != null && !superArgs.isEmpty());
        for (final var p : classDecl.parameters()) {
            em.line(p.pos().line(), field(p) + ";");
        }
        for (final var p : classDecl.properties()) {
            resetCounters();
            final var initial = (inConstructor ? "" : " = " + propertyCode(() -> expr(propertyInit(p))));
            em.line(p.pos().line(), field(p) + initial + ";");
        }
        if (inConstructor) {
            emitConstructor(classDecl);
        }
        final var outerOverridden = overridden;
        final var outerRequired = requiredMethods;
        overridden = Implementations.required(file, options.siblings(), classDecl.supertypes());
        requiredMethods = Implementations.requiredMethods(file, options.siblings(), classDecl.supertypes());
        for (final var method : classDecl.methods()) {
            resetCounters();
            emitFunction(method, false);
        }
        overridden = outerOverridden;
        requiredMethods = outerRequired;
        emitAccessors(classDecl);
        currentClass = outerClass;
        currentMethods = outerMethods;
        em.dedent();
    }
    private TypeRef propertyTypeOf(Decl.Property p) {
        final var type = p.type();
        if (type != null) {
            return type;
        }
        final var init = p.init();
        final @Nullable TypeRef inferred = (init == null ? null : TypeInference.propertyTypes(file, options.siblings(), currentClass).get(p.name()));
        if (inferred == null) {
            throw new IllegalStateException("property " + p.name() + " has no type");
        }
        return inferred;
    }
    private void emitConstructor(Decl.ClassDecl classDecl) {
        final var line = classDecl.pos().line();
        final var params = classDecl.parameters().stream().map(Nulls.nonNull(p -> javaType(propertyTypeOf(p)) + " " + p.name())).toList();
        em.line(line, "public " + classDecl.name() + "(" + String.join(", ", params) + ") {");
        em.indent();
        final var superArgs = classDecl.superArgs();
        if (superArgs != null && !superArgs.isEmpty()) {
            final var args = superArgs.stream().map(Nulls.nonNull(a -> expr(a))).toList();
            em.line(line, "super(" + String.join(", ", args) + ");");
        }
        for (final var p : classDecl.parameters()) {
            em.line(p.pos().line(), "this." + p.name() + " = " + p.name() + ";");
        }
        resetCounters();
        for (final var p : classDecl.properties()) {
            final var stored = nullableType(propertyTypeOf(p));
            em.line(p.pos().line(), "this." + p.name() + " = " + propertyCode(() -> valueCode(p.pos().line(), propertyInit(p), stored)) + ";");
        }
        em.dedent();
        em.line(line, "}");
    }
    private String propertyCode(Supplier<String> code) {
        inPropertyInit = true;
        final var result = code.get();
        inPropertyInit = false;
        return result;
    }
    private void emitAccessors(Decl.ClassDecl classDecl) {
        final var written = new HashSet<String>();
        for (final var member : Implementations.requiredValues(file, options.siblings(), classDecl.supertypes())) {
            final var property = propertyNamed(classDecl, member.name());
            final var ownMethod = classDecl.methods().stream().anyMatch(m -> m.name().equals(member.name()) && m.params().isEmpty());
            if (property != null && !ownMethod && written.add(member.name())) {
                em.line(property.pos().line(), "@Override public " + javaType(propertyTypeOf(property)) + " " +
                        member.name() +
                        "() { return " +
                        member.name() +
                        "; }");
            }
        }
    }
    private Decl.@Nullable Property propertyNamed(Decl.ClassDecl classDecl, String name) {
        for (final var p : classDecl.parameters()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        for (final var p : classDecl.properties()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }
    private String field(Decl.Property p) {
        final var visibility = (p.isPrivate() ? "private" : "public");
        final var modifier = (p.mutable() ? "" : " final");
        return annotations(p.annotations()) + visibility + modifier + " " + javaType(propertyTypeOf(p)) + " " + p.name();
    }
    private void emitDataClass(Decl.DataClass dataClass) {
        final var line = dataClass.pos().line();
        final var head = annotations(dataClass.annotations()) + visibility(dataClass) + " record " + dataClass.name() +
                typeParamList(dataClass.typeParams()) +
                "(" +
                parameters(dataClass.fields(), line) +
                ")" +
                typeList(" implements ", dataClass.supertypes());
        if (dataClass.methods().isEmpty()) {
            em.line(line, head + " {}");
            return;
        }
        em.line(line, head + " {");
        em.indent();
        emitDataClassMethods(dataClass);
        em.dedent();
        em.line(line, "}");
    }
    private void emitDataClassMethods(Decl.DataClass dataClass) {
        final var outerOverridden = overridden;
        final var outerRequired = requiredMethods;
        final var outerMethods = currentMethods;
        overridden = Implementations.required(file, options.siblings(), dataClass.supertypes());
        requiredMethods = Implementations.requiredMethods(file, options.siblings(), dataClass.supertypes());
        currentMethods = dataClass.methods();
        for (final var method : dataClass.methods()) {
            resetCounters();
            emitFunction(method, false);
        }
        currentMethods = outerMethods;
        overridden = outerOverridden;
        requiredMethods = outerRequired;
    }
    private void emitInterface(Decl.Interface iface) {
        final var line = iface.pos().line();
        final var head = annotations(iface.annotations()) + visibility(iface) + interfaceModifier(iface) + " interface " + iface.name() +
                typeParamList(iface.typeParams()) +
                typeList(" extends ", iface.supertypes());
        if (iface.members().isEmpty() && iface.methods().isEmpty()) {
            em.line(line, head + " {}");
            return;
        }
        em.line(line, head + " {");
        emitRequiredValues(iface);
        em.line(line, "}");
    }
    private void emitRequiredValues(Decl.Interface iface) {
        em.indent();
        for (final var member : iface.members()) {
            em.line(member.pos().line(), javaType(memberType(member)) + " " + member.name() + "();");
        }
        for (final var method : iface.methods()) {
            final var returnType = method.returnType();
            final var resultType = (returnType == null ? "void" : javaType(returnType));
            em.line(method.pos().line(), annotations(method.annotations()) + typeParams(method.typeParams()) + resultType + " " +
                    method.name() +
                    "(" +
                    parameters(method.params(), method.pos().line()) +
                    ");");
        }
        em.dedent();
    }
    private String interfaceModifier(Decl.Interface iface) {
        if (iface.isSealed()) {
            return " sealed";
        }
        final var underSealed = file.decls().stream().anyMatch(d -> d instanceof Decl.Interface d$1 && d$1.isSealed() && extendsInterface(iface, d$1.name()));
        return (underSealed ? " non-sealed" : "");
    }
    private boolean extendsInterface(Decl.Interface iface, String parentName) {
        return iface.supertypes().stream().anyMatch(t -> t.name().equals(parentName));
    }
    private void emitValue(Decl.Value value) {
        final var declared = value.type();
        final var type = (declared != null ? javaType(declared) : valueTypes.getOrDefault(value.name(), "Object"));
        final var line = value.pos().line();
        final var head = annotations(value.annotations()) + visibility(value) + " static final " + type + " " + value.name();
        em.line(line, joined(head, " = ", expr(value.init()), line, startLine(value.init())) + ";");
    }
    private void emitProbe() {
        resetCounters();
        em.line(0, "private static void " + JavaTypes.PROBE_METHOD + "() {");
        em.indent();
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Value decl$1 && probed.contains(decl$1.name())) {
                em.line(decl$1.pos().line(), "var " + decl$1.name() + " = " + expr(decl$1.init()) + ";");
            }
        }
        em.dedent();
        em.line(0, "}");
    }
    private void emitFunction(Decl.Function f, boolean isStatic) {
        final var required = (isStatic ? null : Nulls.first(requiredMethods.stream().filter(r -> r.method().name().equals(f.name()) &&
                r.method().params().size() == f.params().size())
                .iterator()));
        final var boxed = boxedParameters(f, required);
        final var params = (isStatic && isMain(f) ? "String[] args" : parameters(f.params(), f.pos().line(), boxed));
        final var declaredReturn = f.returnType();
        final var resultType = (declaredReturn == null ? "void" :
                (required != null && isTypeVariable(required, required.method().returnType()) ? boxedType(declaredReturn) :
                javaType(declaredReturn)));
        final var modifier = (isStatic ? " static " : " ");
        final var written = f.annotations().stream().anyMatch(a -> a.name().equals("Override"));
        final var override = (!isStatic && !written && overridden.contains(f.name() + "/" + f.params().size()) ? "@Override " : "");
        em.line(f.pos().line(), override + annotations(f.annotations()) + visibility(f) + modifier + typeParams(f.typeParams()) + resultType +
                " " +
                f.name() +
                "(" +
                params +
                ") {");
        returnType = (declaredReturn == null ? null : nullableType(declaredReturn));
        resultTypeRef = declaredReturn;
        localTypes.clear();
        boxedLocals.clear();
        boxedLocals.addAll(boxed);
        for (final var p : f.params()) {
            localTypes.put(p.name(), p.type());
        }
        em.indent();
        final var exprBody = f.exprBody();
        final var body = f.body();
        if (exprBody != null) {
            final var line = exprBody.pos().line();
            final Stmt stmt = (declaredReturn == null ? new ExprStmt(exprBody.pos(), exprBody) : new Return(exprBody.pos(), exprBody));
            statement(stmt);
            em.dedent();
            em.line(line, "}");
        } else if (body != null) {
            emitStatements(body.statements());
            em.dedent();
            em.line(body.end().line(), "}");
        }
    }
    private Set<String> boxedParameters(Decl.Function f, @Nullable RequiredMethod required) {
        final var result = new HashSet<String>();
        if (required == null) {
            return result;
        }
        var i = 0;
        while (i < f.params().size()) {
            if (isTypeVariable(required, required.method().params().get(i).type())) {
                result.add(f.params().get(i).name());
            }
            i += 1;
        }
        return result;
    }
    private boolean isTypeVariable(RequiredMethod required, @Nullable TypeRef type) {
        return type != null && type.args().isEmpty() && required.typeParams().contains(type.name());
    }
    private void emitStatements(List<Stmt> list) {
        for (final var stmt : list) {
            statement(stmt);
        }
    }
    private void statement(Stmt stmt) {
        switch (stmt) {
            case Stmt.Local stmt$1 -> {
                localStatement(stmt$1);
            }
            case Stmt.Assign stmt$2 -> {
                assignStatement(stmt$2);
            }
            case Stmt.Return stmt$3 -> {
                returnStatement(stmt$3);
            }
            case Stmt.Jump stmt$4 -> {
                em.line(stmt$4.pos().line(), (stmt$4.isBreak() ? "break;" : "continue;"));
            }
            case Stmt.Use stmt$5 -> {
                useStatement(stmt$5);
            }
            case Stmt.If stmt$6 -> {
                ifStatement(stmt$6);
            }
            case Stmt.While stmt$7 -> {
                emitWhile(stmt$7);
            }
            case Stmt.For stmt$8 -> {
                emitFor(stmt$8);
            }
            case Stmt.ExprStmt stmt$9 -> {
                exprStatement(stmt$9);
            }
            case Stmt.Block stmt$10 -> {
                em.line(stmt$10.pos().line(), "{");
                em.indent();
                emitStatements(stmt$10.statements());
                em.dedent();
                em.line(stmt$10.end().line(), "}");
            }
        }
    }
    private void localStatement(Stmt.Local local) {
        final var line = local.pos().line();
        final var declared = local.type();
        final var init = widened(local.init(), declared);
        final var modifiers = (local.mutable() ? "" : "final ");
        final var whole = withoutParens(init);
        final var branchType = (declared == null && whole instanceof Expr.Conditional whole$1 ? typedBranch(whole$1) : null);
        final var typeCode = (declared != null ? javaType(declared) : (branchType != null ? javaType(branchType) : "var"));
        if (whole instanceof Expr.When whole$2) {
            emitWhen(line, whole$2, modifiers + typeCode + " " + local.name() + " = ", WhenForm.VALUE);
            localTypes.put(local.name(), declared);
            return;
        }
        final var localType = Nulls.or(declared, () -> Nulls.or(branchType, () -> typeOf(init)));
        final var stored = (declared == null ? null : nullableType(declared));
        withCopiesOf(line, Captures.capturedIn(init, analysis, inlinesElvis(init)), () -> {
            final var code = (init instanceof Expr.Try init$3 ? unwrapTry(line, init$3) : valueCode(line, init, stored));
            em.line(line, modifiers + typeCode + " " + local.name() + " = " + code + ";");
        });
        localTypes.put(local.name(), localType);
        final var unwrapped = whole instanceof Expr.Try whole$4 || whole instanceof Expr.Elvis whole$5;
        if (declared == null && unwrapped) {
            boxedLocals.add(local.name());
        } else {
            boxedLocals.remove(local.name());
        }
    }
    private @Nullable TypeRef typedBranch(Expr.Conditional c) {
        final var thenType = typeOf(c.then());
        final var elseType = typeOf(c.otherwise());
        if (thenType == null && elseType != null && !elseType.args().isEmpty() && withoutParens(c.then()) instanceof Expr.Call) {
            return elseType;
        }
        if (elseType == null && thenType != null && !thenType.args().isEmpty() && withoutParens(c.otherwise()) instanceof Expr.Call) {
            return thenType;
        }
        return null;
    }
    private void assignStatement(Stmt.Assign assign) {
        final var line = assign.pos().line();
        final var assigned = assign.value();
        withCopiesOf(line, Captures.capturedIn(assigned, analysis, inlinesElvis(assigned)), () -> {
            final var valueLifts = Lifts.needed(assigned);
            final var target = assignTarget(line, assign.target(), valueLifts);
            final var readsOld = valueLifts && !assign.op().equals("=") && !isLocal(assign.target());
            final @Nullable String old = (readsOld ? newTemp() : null);
            if (old != null) {
                em.line(line, "final var " + old + " = " + target + ";");
            }
            final var code = valueCode(line, assigned, null);
            if (old != null) {
                em.line(line, target + " = " + old + ";");
            }
            em.line(line, target + " " + assign.op() + " " + code + ";");
        });
    }
    private void returnStatement(Stmt.Return ret) {
        final var line = ret.pos().line();
        final var value = ret.value();
        if (value == null) {
            em.line(line, "return;");
            return;
        }
        final var whole = withoutParens(value);
        if (whole instanceof Expr.When whole$1) {
            emitWhen(line, whole$1, "", WhenForm.RESULT);
            return;
        }
        withCopiesOf(line, Captures.capturedIn(value, analysis, inlinesElvis(value)), () ->
                em.line(line, "return " + valueCode(line, widened(value, resultTypeRef), returnType) + ";"));
    }
    private void ifStatement(Stmt.If ifStmt) {
        final var split = shortCircuitWithLift(ifStmt.condition());
        if (split != null && leavesOnEither(ifStmt, split)) {
            exitingOr(ifStmt, split);
            return;
        }
        if (split != null && splitsCondition(ifStmt)) {
            shortCircuitIf(ifStmt, split);
            return;
        }
        emitIf(ifStmt, "");
        em.line(blockEnd(ifStmt), "}");
        smart = union(smart, narrowedAfter(ifStmt));
    }
    private void exprStatement(Stmt.ExprStmt exprStmt) {
        final var line = exprStmt.pos().line();
        final var e = withoutParens(exprStmt.expr());
        final var condition = runsWhen(e);
        if (e instanceof Expr.When e$1) {
            emitWhen(line, e$1, "", WhenForm.STATEMENT);
        } else if (e instanceof Expr.Throw e$2) {
            withCopies(line, e$2.value(), () -> em.line(line, "throw " + lifted(line, e$2.value()).code() + ";"));
        } else if (condition != null) {
            final var action = actionOf(e);
            final var thenBlock = new Block(action.pos(), List.of(new ExprStmt(action.pos(), action)), action.pos());
            statement(new If(exprStmt.pos(), condition, thenBlock, null));
        } else if (e instanceof Expr.Try e$3) {
            withCopies(line, e$3.inner(), () -> unwrapTry(line, e$3));
        } else if (e instanceof Expr.Attempt e$4) {
            withCopies(line, e$4, () -> attemptStatement(line, e$4));
        } else if (Chains.hasSafeStep(e)) {
            final var chain = Chains.flatten(e);
            withCopiesOf(line, Captures.inStatementChain(chain, analysis), () -> safeChainStatement(line, chain));
        } else if (Lifts.needed(e)) {
            withCopies(line, e, () -> liftedStatement(line, e, lifted(line, e)));
        } else {
            withCopies(line, e, () -> em.line(line, expr(e) + ";"));
        }
    }
    private void emitIf(Stmt.If ifStmt, String prefix) {
        final var line = ifStmt.pos().line();
        final var condition = ifStmt.condition();
        final var captured = Captures.capturedIn(condition, analysis);
        final var prelude = !captured.isEmpty() || Lifts.needed(condition);
        if (!prefix.isEmpty() && prelude) {
            em.line(line, prefix + "{");
            em.indent();
            statement(ifStmt);
            em.dedent();
            return;
        }
        final var plain = prefix.isEmpty() && captured.isEmpty() && ifStmt.elseBranch() == null;
        final var nested = (plain ? andWithLiftedRight(condition) : null);
        if (nested != null) {
            em.line(line, "if (" + lifted(line, nested.left()).code() + ") {");
            em.indent();
            final var inner = new If(ifStmt.pos(), nested.right(), ifStmt.then(), null);
            narrowed(whenTrue(nested.left()), () -> {
                emitIf(inner, "");
                em.line(blockEnd(inner), "}");
            });
            em.dedent();
            return;
        }
        withCopies(line, condition, () -> em.line(line, prefix + "if (" + lifted(line, condition).code() + ") {"));
        narrowed(whenTrue(condition), () -> emitBlock(ifStmt.then()));
        final var negated = whenFalse(condition);
        final var otherwise = (negated.isEmpty() ? otherCase(condition) : negated);
        final var elseBranch = ifStmt.elseBranch();
        if (elseBranch instanceof Stmt.If elseBranch$1) {
            narrowed(otherwise, () -> emitIf(elseBranch$1, "} else "));
        } else if (elseBranch instanceof Stmt.Block elseBranch$2) {
            em.line(elseBranch$2.pos().line(), "} else {");
            narrowed(otherwise, () -> emitBlock(elseBranch$2));
        }
    }
    private boolean splitsCondition(Stmt.If ifStmt) {
        final var condition = ifStmt.condition();
        final var simpleAnd = ifStmt.elseBranch() == null && condition instanceof Expr.Binary condition$1 && condition$1.op().equals("&&");
        if (simpleAnd || !Captures.capturedIn(condition, analysis).isEmpty()) {
            return false;
        }
        return shortCircuitWithLift(condition) != null;
    }
    private void shortCircuitIf(Stmt.If ifStmt, Expr.Binary b) {
        final var line = ifStmt.pos().line();
        final var and = b.op().equals("&&");
        final var otherwise = asBlock(ifStmt.elseBranch());
        final Stmt.@Nullable Block inside = (and ? Nulls.box(ifStmt.then()) : otherwise);
        final Stmt.@Nullable Block after = (and ? otherwise : Nulls.box(ifStmt.then()));
        em.line(line, "do {");
        em.indent();
        final var left = lifted(line, b.left()).code();
        em.line(line, "if (" + ((and ? left : negated(left))) + ") {");
        em.indent();
        final var right = narrowedValue(rightNarrowing(b), () -> lifted(line, b.right()).code());
        em.line(line, "if (" + ((and ? right : negated(right))) + ") {");
        if (inside != null) {
            final var holds = (and ? whenTrue(b) : whenFalse(b));
            narrowed(holds, () -> emitBlock(inside));
        }
        final var closing = (inside == null ? line : inside.end().line());
        if (inside == null || !exits(inside)) {
            em.indent();
            em.line(closing, "break;");
            em.dedent();
        }
        em.line(closing, "}");
        em.dedent();
        em.line(line, "}");
        if (after != null) {
            emitStatements(after.statements());
        }
        em.dedent();
        em.line(blockEnd(ifStmt), "} while (false);");
    }
    private boolean leavesOnEither(Stmt.If ifStmt, Expr.Binary b) {
        final var simple = b.op().equals("||") && ifStmt.elseBranch() == null;
        return simple && exits(ifStmt.then()) && Captures.capturedIn(b, analysis).isEmpty();
    }
    private void exitingOr(Stmt.If ifStmt, Expr.Binary b) {
        final var line = ifStmt.pos().line();
        em.line(line, "if (" + lifted(line, b.left()).code() + ") {");
        narrowed(whenTrue(b.left()), () -> emitBlock(ifStmt.then()));
        em.line(ifStmt.then().end().line(), "}");
        smart = union(smart, whenFalse(b.left()));
        em.line(line, "if (" + lifted(line, b.right()).code() + ") {");
        narrowed(whenTrue(b.right()), () -> emitBlock(ifStmt.then()));
        em.line(ifStmt.then().end().line(), "}");
        smart = union(smart, whenFalse(b.right()));
    }
    private Map<String, String> otherCase(Expr condition) {
        final var test = withoutParens(condition);
        if (!(test instanceof Expr.Is test$1) || test$1.negated() || !(test$1.target() instanceof Expr.Name)) {
            return Map.of();
        }
        final var target = test$1.target();
        final var accessor = resultAccessor(test$1.type());
        if (!(target instanceof Expr.Name target$2) || accessor == null || !neverChanges(target$2)) {
            return Map.of();
        }
        final var remaining = (accessor.equals("value") ? "error" : "value");
        return Map.of(target$2.name(), "!" + remaining + ":" + bareName(target$2.name()));
    }
    private Map<String, String> narrowedAfter(Stmt.If ifStmt) {
        final var thenExits = exits(ifStmt.then());
        final var elseBranch = ifStmt.elseBranch();
        final var elseExits = elseBranch != null && exits(elseBranch);
        if (thenExits && !elseExits) {
            final var other = otherCase(ifStmt.condition());
            return (other.isEmpty() ? whenFalse(ifStmt.condition()) : other);
        }
        if (elseExits && !thenExits) {
            return whenTrue(ifStmt.condition());
        }
        return Map.of();
    }
    private void emitWhile(Stmt.While loop) {
        final var line = loop.pos().line();
        final var condition = loop.condition();
        final var plain = Captures.capturedIn(condition, analysis).isEmpty();
        if (plain && !Lifts.needed(condition)) {
            em.line(line, "while (" + expr(condition) + ") {");
            narrowed(whenTrue(condition), () -> emitBlock(loop.body()));
        } else {
            em.line(line, "while (true) {");
            em.indent();
            final var split = (plain ? shortCircuitWithLift(condition) : null);
            if (split != null) {
                whileChecks(line, split);
            } else {
                withCopies(line, condition, () -> em.line(line, "if (" + negated(lifted(line, condition).code()) + ") break;"));
            }
            narrowed(whenTrue(condition), () -> emitStatements(loop.body().statements()));
            em.dedent();
        }
        em.line(loop.body().end().line(), "}");
    }
    private void whileChecks(int line, Expr.Binary b) {
        final var left = lifted(line, b.left()).code();
        final var and = b.op().equals("&&");
        em.line(line, "if (" + negated(left) + ") " + ((and ? "break;" : "{")));
        if (!and) {
            em.indent();
        }
        final var right = narrowedValue(rightNarrowing(b), () -> lifted(line, b.right()).code());
        em.line(line, "if (" + negated(right) + ") break;");
        if (!and) {
            em.dedent();
            em.line(line, "}");
        }
    }
    private void emitWhen(int line, Expr.When w, String head, WhenForm form) {
        final var subject = new ArrayList<String>();
        final var bindingName = w.bindingName();
        final var subjectType = typeOf(w.subject());
        final var byIndex = switchesByIndex(w, subjectType);
        withCopies(line, w.subject(), () -> {
            if (bindingName != null || byIndex) {
                final var name = Nulls.or(bindingName, () -> "") + "$e" + newPattern();
                em.line(line, "final var " + name + " = " + lifted(line, w.subject()).code() + ";");
                subject.add(name);
            } else {
                subject.add(Nulls.or(unnarrowed(w.subject()), () -> lifted(line, w.subject()).code()));
            }
        });
        final var subjectCode = subject.getFirst();
        final var narrowedName = Nulls.or(bindingName, () -> narrowableName(withoutParens(w.subject())));
        final var whole = nameBinding(bindingName, subjectCode);
        final var mayBeNull = subjectType == null || subjectType.nullable();
        final var selector = (byIndex ? armIndex(subjectCode, w, mayBeNull) : (boxesSubject(w, subjectType) ? "Nulls.box(" + subjectCode + ")" : subjectCode));
        em.line(line, head + "switch (" + selector + ") {");
        em.indent();
        final var outerValues = whenValues;
        final var outerElse = elseLabel;
        whenValues = (form == WhenForm.VALUE ? armValues(w) : List.of());
        elseLabel = elseLabelOf(w, subjectType, byIndex);
        final var outerSubject = subjectTypeOf;
        subjectTypeOf = subjectType;
        var i = 0;
        while (i < w.arms().size()) {
            final var arm = w.arms().get(i);
            final var index = (byIndex ? i : -1);
            narrowed(whole, () -> emitArm(arm, narrowedName, form, index));
            i += 1;
        }
        whenValues = outerValues;
        elseLabel = outerElse;
        subjectTypeOf = outerSubject;
        if (form == WhenForm.RESULT && needsUnreachableDefault(w)) {
            em.line(w.end().line(), "default -> throw new IllegalStateException();");
        } else if (form == WhenForm.STATEMENT && !byIndex && hasNullArm(w) && needsUnreachableDefault(w)) {
            em.line(w.end().line(), "default -> {}");
        }
        em.dedent();
        em.line(w.end().line(), (form == WhenForm.VALUE ? "};" : "}"));
    }
    private @Nullable String unnarrowed(Expr subject) {
        final var inner = withoutParens(subject);
        return (inner instanceof Expr.Name inner$1 && smart.containsKey(inner$1.name()) ? inner$1.name() : null);
    }
    private @Nullable String narrowableName(Expr subject) {
        if (subject instanceof Expr.Name subject$1 && neverChanges(subject$1)) {
            return subject$1.name();
        }
        return null;
    }
    private boolean switchesByIndex(Expr.When w, @Nullable TypeRef subjectType) {
        if (subjectType != null && Set.of("Long", "Double", "Float", "Boolean").contains(subjectType.name())) {
            return true;
        }
        for (final var arm : w.arms()) {
            for (final var condition : arm.conditions()) {
                final var constant = condition.constant();
                if (constant != null && notSwitchable(constant.text())) {
                    return true;
                }
            }
        }
        return false;
    }
    private String armIndex(String subject, Expr.When w, boolean mayBeNull) {
        final var sb = new StringBuilder();
        final var nullArm = nullArmIndex(w);
        if (mayBeNull || nullArm >= 0) {
            sb.append("java.util.Objects.isNull(").append(subject).append(") ? ").append(nullArm).append(" : ");
        }
        var i = 0;
        while (i < w.arms().size()) {
            final var constants = w.arms().get(i).conditions().stream().map(Nulls.nonNull(c -> constantOf(c).text())).filter(t -> !"null".equals(t)).toList();
            if (!constants.isEmpty()) {
                final var tests = constants.stream().map(Nulls.nonNull(t -> subject + " == " + literal(t))).toList();
                sb.append(String.join(" || ", tests)).append(" ? ").append(i).append(" : ");
            }
            i += 1;
        }
        return sb.append("-1").toString();
    }
    private int nullArmIndex(Expr.When w) {
        var i = 0;
        while (i < w.arms().size()) {
            if (w.arms().get(i).conditions().stream().anyMatch(c -> "null".equals(Nulls.typed(Nulls.map(c.constant(), $n1 -> $n1.text())).value()))) {
                return i;
            }
            i += 1;
        }
        return -1;
    }
    private String elseLabelOf(Expr.When w, @Nullable TypeRef subjectType, boolean byIndex) {
        if (byIndex || (subjectType != null && !subjectType.nullable()) || hasNullArm(w)) {
            return "default";
        }
        if (subjectType == null) {
            return "case null, default";
        }
        final var covered = w.arms().stream().anyMatch(a -> a.conditions().stream().anyMatch(c -> testsType(c, subjectType)));
        return (covered ? "case null" : "case null, default");
    }
    private boolean boxesSubject(Expr.When w, @Nullable TypeRef subjectType) {
        return subjectType == null && !testsObjects(w) && !hasNullArm(w) && !switchesByIndex(w, subjectType);
    }
    private boolean hasNullArm(Expr.When w) {
        return nullArmIndex(w) >= 0;
    }
    private void emitArm(Expr.Arm arm, @Nullable String narrowedName, WhenForm form, int index) {
        final var conditions = arm.conditions();
        if (conditions.isEmpty()) {
            emitBranch(arm, elseLabel, Map.of(), form);
            return;
        }
        if (index >= 0) {
            emitBranch(arm, "case " + index, Map.of(), form);
            return;
        }
        final var testsTypes = conditions.stream().anyMatch(c -> c.type() != null);
        if (!testsTypes) {
            final var constants = conditions.stream().map(Nulls.nonNull(c -> literal(constantOf(c).text()))).toList();
            emitBranch(arm, "case " + String.join(", ", constants), Map.of(), form);
            return;
        }
        for (final var condition : conditions) {
            if (condition.type() == null) {
                emitBranch(arm, "case null", Map.of(), form);
            } else {
                final var type = conditionType(condition);
                final var bare = (narrowedName == null ? "" : bareName(narrowedName));
                final var taken = recordPattern(type, subjectTypeOf, narrowedName, bare);
                if (taken != null) {
                    emitBranch(arm, "case " + taken.label(), nameBinding(narrowedName, taken.marker()), form);
                } else {
                    final var variable = patternVariable(narrowedName);
                    final var narrowing = nameBinding(narrowedName, variable);
                    emitBranch(arm, "case " + testedType(type, subjectTypeOf) + " " + variable, narrowing, form);
                }
            }
        }
    }
    private void emitBranch(Expr.Arm arm, String label, Map<String, String> narrowing, WhenForm form) {
        final var line = arm.pos().line();
        final var single = singleExpression(arm);
        if (form == WhenForm.VALUE && single != null) {
            if (single instanceof Expr.Throw single$1 && !Lifts.needed(single$1.value())) {
                em.line(line, label + " -> throw " + narrowedValue(narrowing, () -> expr(single$1.value())) + ";");
                return;
            }
            if (!Lifts.needed(single) && Captures.capturedIn(single, analysis).isEmpty()) {
                final var code = narrowedValue(narrowing, () -> expr(single));
                em.line(line, label + " -> " + boxedBranch(code, single, whenValues) + ";");
                return;
            }
        }
        em.line(line, label + " -> {");
        em.indent();
        narrowed(narrowing, () -> emitArmBody(arm, form));
        em.dedent();
        em.line((arm.body().isEmpty() ? line : arm.body().getLast().pos().line()), "}");
    }
    private void emitArmBody(Expr.Arm arm, WhenForm form) {
        final var body = (form == WhenForm.RESULT ? Whens.returning(arm) : arm.body());
        var i = 0;
        while (i < body.size()) {
            final var stmt = body.get(i);
            final var last = i == body.size() - 1;
            if (form == WhenForm.VALUE && last && stmt instanceof Stmt.ExprStmt stmt$1 && !(stmt$1.expr() instanceof Expr.Throw)) {
                final var valueLine = stmt$1.pos().line();
                withCopies(valueLine, stmt$1.expr(), () -> {
                    final var code = lifted(valueLine, stmt$1.expr()).code();
                    em.line(valueLine, "yield " + boxedBranch(code, stmt$1.expr(), whenValues) + ";");
                });
            } else {
                statement(stmt);
            }
            i += 1;
        }
    }
    private void emitFor(Stmt.For loop) {
        final var line = loop.pos().line();
        withCopies(line, loop.iterable(), () -> {
            final var name = (loop.name().equals("_") ? "$unused" + newPattern() : loop.name());
            em.line(line, "for (final var " + name + " : " + lifted(line, loop.iterable()).code() + ") {");
        });
        emitBlock(loop.body());
        em.line(loop.body().end().line(), "}");
    }
    private void emitBlock(Stmt.Block block) {
        final var outer = smart;
        em.indent();
        emitStatements(block.statements());
        em.dedent();
        smart = outer;
    }
    private void liftedStatement(int line, Expr rest, Lifted left) {
        var code = left.code();
        while (code.startsWith("(") && code.endsWith(")") && closingParen(code) == code.length() - 1) {
            code = code.substring(1, code.length() - 1);
        }
        if (code.matches("[\\w$]+")) {
            return;
        }
        final var e = withoutParens(rest);
        final var call = (e instanceof Expr.Call e$1 || e instanceof Expr.GenericNew e$2 || e instanceof Expr.Try e$3 || e instanceof Expr.Pipe e$4) && code.endsWith(")");
        em.line(line, (call ? code + ";" : "final var " + newTemp() + " = " + code + ";"));
    }
    private String valueCode(int line, Expr e, @Nullable String stored) {
        if (e instanceof Expr.Elvis e$1 && Lifts.needed(e$1.right())) {
            return elvisChain(line, e$1, stored).code();
        }
        if (Lifts.needed(e)) {
            return lifted(line, e).code();
        }
        if (!inlinesElvis(e) || !(e instanceof Expr.Elvis e$2)) {
            return expr(e);
        }
        final var temp = newTemp();
        em.line(line, "final var " + temp + " = " + expr(e$2.left()) + ";");
        return "java.util.Objects.nonNull(" + temp + ") ? " + temp + " : " + expr(e$2.right());
    }
    private boolean widens(Expr number, @Nullable TypeRef left) {
        final var type = TypeInference.typeOf(file, number);
        if (left == null) {
            return type != null && type.name().equals("Int");
        }
        return type != null && WIDENING.contains(type.name()) && WIDENING.indexOf(type.name()) <= WIDENING.indexOf(left.name());
    }
    private boolean inlinesElvis(Expr e) {
        if (!options.inlineElvis()) {
            return false;
        }
        return e instanceof Expr.Elvis e$1 && !Lifts.needed(e$1.right()) && !Lifts.needed(e$1) && !mayMixNumbers(e$1);
    }
    private boolean mayMixNumbers(Expr.Elvis elvis) {
        final var left = typeOf(elvis.left());
        final var right = typeOf(elvis.right());
        if (left != null && right != null && left.name().equals(right.name())) {
            return false;
        }
        final var leftOther = left != null && !NUMBERS.contains(left.name());
        final var rightOther = right != null && !NUMBERS.contains(right.name());
        return !(leftOther || rightOther);
    }
    private @Nullable TypeRef typeOf(Expr e) {
        if (branchTypes.containsKey(e)) {
            return branchTypes.get(e);
        }
        final var inner = withoutParens(e);
        if (inner instanceof Expr.Name inner$1 && localTypes.containsKey(inner$1.name())) {
            return localTypes.get(inner$1.name());
        }
        if (inner instanceof Expr.Elvis inner$2) {
            final var left = typeOf(inner$2.left());
            final var right = typeOf(inner$2.right());
            return (left == null || right == null || !left.name().equals(right.name()) ? null :
                    new TypeRef(left.pos(), left.name(), left.args(), right.nullable()));
        }
        if (inner instanceof Expr.NotNull inner$3) {
            final var target = typeOf(inner$3.target());
            return (target == null ? null : new TypeRef(target.pos(), target.name(), target.args(), false));
        }
        if (inner instanceof Expr.Conditional inner$4) {
            return commonBranchType(typeOf(inner$4.then()), typeOf(inner$4.otherwise()), inner$4);
        }
        final var fromMap = mapGet(inner);
        if (fromMap != null) {
            return fromMap;
        }
        return infer(inner);
    }
    private @Nullable TypeRef infer(Expr e) {
        return new TypeInference(file, knownTypes(), options.siblings()).of(e);
    }
    private Map<String, TypeRef> knownTypes() {
        final var result = new HashMap<String, TypeRef>();
        for (final var local : localTypes.entrySet()) {
            final var type = local.getValue();
            if (type != null) {
                result.put(local.getKey(), type);
            }
        }
        for (final var entry : smart.entrySet()) {
            for (final var test : patternVariables.entrySet()) {
                final var target = test.getKey().target();
                if (test.getValue().equals(entry.getValue()) && target instanceof Expr.Name target$1 && target$1.name().equals(entry.getKey())) {
                    result.put(entry.getKey(), test.getKey().type());
                }
            }
        }
        return result;
    }
    private @Nullable TypeRef mapGet(Expr e) {
        if (!(e instanceof Expr.Call e$1) || !e$1.name().equals("get") || e$1.args().size() != 1 || e$1.safe()) {
            return null;
        }
        final var target = e$1.target();
        if (!(target instanceof Expr.Name target$2)) {
            return null;
        }
        final var type = localTypes.get(target$2.name());
        if (type == null || type.nullable() || type.args().size() != 2 || !MAP_TYPES.contains(type.name())) {
            return null;
        }
        final var value = type.args().get(1);
        return new TypeRef(value.pos(), value.name(), value.args(), true);
    }
    private @Nullable TypeRef commonBranchType(@Nullable TypeRef a, @Nullable TypeRef b, Expr.Conditional c) {
        final var same = a != null && b != null && a.name().equals(b.name()) && a.args().isEmpty() && b.args().isEmpty();
        return (a != null && same ? new TypeRef(a.pos(), a.name(), a.args(), a.nullable() || java.util.Objects.requireNonNull(b).nullable()) :
                infer(c));
    }
    private String elvisCode(Expr.Elvis elvis, String left) {
        final var right = elvis.right();
        final var leftType = typeOf(elvis.left());
        final var isNumber = Lifts.isNumber(right);
        final var number = isNumber && widens(right, leftType) && (!Chains.hasSafeStep(elvis.left()) || left.matches("\\$t\\d+"));
        final @Nullable String narrow = (isNumber ? NARROW_NUMBERS.get(nameOf(leftType)) : null);
        final var fallback = (number ? expr(right) : lambdaFallback(right, narrow));
        final var witness = (number || narrow != null ? "" : elvisWitness(leftType, right));
        return joined("Nulls." + witness + "or(" + left, ", ", fallback, startLine(elvis.left()), startLine(right)) + ")";
    }
    private String leftAssociatedCode(Expr.Elvis e, boolean inLambda) {
        final var chain = leftAssociated(e);
        nestedValues += 1;
        final var left = exprIn(chain.left(), inLambda);
        nestedValues -= 1;
        return elvisCode(chain, left);
    }
    private Expr.Elvis leftAssociated(Expr.Elvis e) {
        final var parts = new ArrayList<Expr>();
        parts.add(e.left());
        elvisParts(e.right(), parts);
        final var first = typeOf(e.left());
        if (parts.size() < 3 || typeOf(parts.getLast()) != null || first == null || !first.args().isEmpty()) {
            return e;
        }
        var i = 1;
        while (i < parts.size() - 1) {
            final var type = typeOf(parts.get(i));
            if (type == null || !type.nullable() || !type.name().equals(first.name()) || !type.args().isEmpty()) {
                return e;
            }
            i += 1;
        }
        var chain = new Expr.Elvis(e.pos(), parts.get(0), parts.get(1));
        var k = 2;
        while (k < parts.size()) {
            chain = new Expr.Elvis(e.pos(), chain, parts.get(k));
            k += 1;
        }
        return chain;
    }
    private void elvisParts(Expr e, List<Expr> out) {
        final var inner = withoutParens(e);
        if (inner instanceof Expr.Elvis inner$1) {
            out.add(inner$1.left());
            elvisParts(inner$1.right(), out);
        } else {
            out.add(e);
        }
    }
    private String elvisWitness(@Nullable TypeRef leftType, Expr right) {
        final var rightType = typeOf(right);
        if (rightType == null && leftType != null && !leftType.args().isEmpty() && !leftType.name().equals("*")) {
            return "<" + boxedType(new TypeRef(leftType.pos(), leftType.name(), leftType.args(), false)) + ">";
        }
        if (rightType == null || !rightType.args().isEmpty() || leftType == null || !rightType.name().equals(leftType.name())) {
            return "";
        }
        if (rightType.nullable()) {
            return (nestedValues > 0 ? nullableWitness(rightType) : "");
        }
        return "<" + boxedType(rightType) + ">";
    }
    private String nullableWitness(@Nullable TypeRef type) {
        if (type == null || !type.nullable() || !type.args().isEmpty() || type.name().contains(".")) {
            return "";
        }
        return "<@Nullable " + boxedType(new TypeRef(type.pos(), type.name(), type.args(), false)) + ">";
    }
    private String lambdaFallback(Expr right, @Nullable String narrow) {
        if (narrow != null) {
            return "() -> " + converted(expr(right), narrow);
        }
        final var inner = withoutParens(right);
        if (inner instanceof Expr.NotNull inner$1) {
            return "() -> Nulls.sure(" + exprIn(inner$1.target(), true) + ")";
        }
        if (inner instanceof Expr.Throw inner$2) {
            return "() -> { throw " + exprIn(inner$2.value(), true) + "; }";
        }
        return "() -> " + exprIn(right, true);
    }
    private void withCopies(int line, Expr e, Runnable emit) {
        withCopiesOf(line, Captures.capturedIn(e, analysis), emit);
    }
    private void withCopiesOf(int line, List<String> captured, Runnable emit) {
        if (captured.isEmpty()) {
            emit.run();
            return;
        }
        final var saved = copies;
        final var mine = new HashMap<String, String>(saved);
        copies = mine;
        for (final var name : captured) {
            if (!saved.containsKey(name)) {
                final var copy = newCopy();
                em.line(line, "final var " + copy + " = " + name + ";");
                mine.put(name, copy);
            }
        }
        emit.run();
        copies = saved;
    }
    private String unwrapTry(int statementLine, Expr.Try t) {
        final var line = Math.max(statementLine, t.pos().line());
        final var inner = lifted(line, t.inner()).code();
        final var temp = newTemp();
        em.line(line, "final var " + temp + " = " + inner + ";");
        em.line(line, "if (" + temp + ".isErr()) return Result.propagate(" + temp + ");");
        return "Result.unwrap(" + temp + ")";
    }
    private void safeChainStatement(int line, Chains.Chain chain) {
        final var steps = chain.steps();
        final var first = indexOfFirstSafe(steps);
        var current = liftedPrefix(line, chain, first);
        var opened = 0;
        var i = first;
        while (i < steps.size()) {
            final var step = steps.get(i);
            if (step.safe()) {
                final var temp = newNull();
                final var value = (opened > 0 ? "Nulls.typed(" + current + ").value()" : current);
                em.line(line, "final var " + temp + " = " + value + ";");
                em.line(line, "if (" + temp + " != null) {");
                em.indent();
                opened += 1;
                current = temp;
            }
            current += stepText(step, false);
            i += 1;
        }
        em.line(line, current + ";");
        var j = 0;
        while (j < opened) {
            em.dedent();
            em.line(line, "}");
            j += 1;
        }
    }
    private String expr(Expr e) {
        return exprIn(e, false);
    }
    private String exprIn(Expr e, boolean inLambda) {
        switch (e) {
            case Expr.Literal e$1 -> {
                return literal(e$1.text());
            }
            case Expr.Name e$2 -> {
                return nameCode(e$2, inLambda);
            }
            case Expr.Member e$3 -> {
                return chainCode(deconstructed(Chains.flatten(e$3)), inLambda);
            }
            case Expr.Call e$4 -> {
                return callExpr(e$4, inLambda);
            }
            case Expr.GenericNew e$5 -> {
                return genericNew(e$5, argsCode(e$5.args(), inLambda, e$5.pos().line()));
            }
            case Expr.MethodRef e$6 -> {
                return exprIn(e$6.target(), inLambda) + "::" + e$6.name();
            }
            case Expr.ClassLiteral e$7 -> {
                return classLiteral(e$7);
            }
            case Expr.NotNull e$8 -> {
                return "java.util.Objects.requireNonNull(" + exprIn(e$8.target(), inLambda) + ")";
            }
            case Expr.Unary e$9 -> {
                return unary(e$9.op(), exprIn(e$9.operand(), inLambda));
            }
            case Expr.Binary e$10 -> {
                return binaryCode(e$10, inLambda);
            }
            case Expr.Is e$11 -> {
                return typeCheck(e$11, inLambda);
            }
            case Expr.Conditional e$12 -> {
                return conditionalCode(e$12, inLambda);
            }
            case Expr.Throw e$13 -> {
                throw new IllegalStateException("throw at " + e$13.pos() + " cannot be an expression");
            }
            case Expr.Elvis e$14 -> {
                return leftAssociatedCode(e$14, inLambda);
            }
            case Expr.Paren e$15 -> {
                return parenCode(e$15, inLambda);
            }
            case Expr.Attempt e$16 -> {
                return attemptCode(e$16);
            }
            case Expr.Lambda e$17 -> {
                return lambdaCode(e$17);
            }
            case Expr.Pipe e$18 -> {
                return pipeCode(exprIn(e$18.source(), inLambda), e$18.source(), e$18.stages(), a -> exprIn(a, inLambda));
            }
            case Expr.Try e$19 -> {
                throw new IllegalStateException("try at " + e$19.pos() + " should have been lifted");
            }
            case Expr.When e$20 -> {
                throw new IllegalStateException("when at " + e$20.pos() + " should have been lifted");
            }
        }
    }
    private String nameCode(Expr.Name n, boolean inLambda) {
        final var known = smart.get(n.name());
        if (known != null) {
            return (known.startsWith("@") || known.startsWith("!") ? known.substring(known.lastIndexOf(":") + 1) : known);
        }
        if (inLambda && analysis.mutableRefs().contains(n)) {
            return copies.getOrDefault(n.name(), n.name());
        }
        final var c = currentClass;
        if (c != null && lambdaDepth > 0 && inPropertyInit && analysis.fieldNames().contains(n)) {
            return c.name() + ".this." + n.name();
        }
        return n.name();
    }
    private String callExpr(Expr.Call c, boolean inLambda) {
        final var target = c.target();
        if (target != null && isPath(target) && isTypeName(c.name())) {
            return "new " + exprIn(target, inLambda) + "." + c.name() + "(" + argsCode(c.args(), inLambda, c.pos().line()) + ")";
        }
        if (target != null) {
            return chainCode(deconstructed(Chains.flatten(c)), inLambda);
        }
        return callCode(c, argsCode(widenedArgs(c), inLambda, c.pos().line()));
    }
    private List<Expr> widenedArgs(Expr.Call c) {
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Function decl$1 && decl$1.name().equals(c.name()) && decl$1.params().size() == c.args().size()) {
                final var params = decl$1.params();
                final var args = new ArrayList<Expr>();
                var i = 0;
                while (i < params.size()) {
                    args.add(widened(c.args().get(i), params.get(i).type()));
                    i += 1;
                }
                return args;
            }
        }
        return c.args();
    }
    private String binaryCode(Expr.Binary b, boolean inLambda) {
        final var left = compared(b, b.left(), exprIn(b.left(), inLambda));
        final var right = compared(b, b.right(), narrowedValue(rightNarrowing(b), () -> exprIn(b.right(), inLambda)));
        return comparison(b, left, right);
    }
    private String comparison(Expr.Binary b, String left, String right) {
        if (boxedOnBothSides(b)) {
            final var same = joined("Nulls.same(" + left, ", ", right, startLine(b.left()), startLine(b.right())) + ")";
            return (b.op().equals("==") ? same : "!" + same);
        }
        return joined(left, " " + b.op() + " ", right, startLine(b.left()), startLine(b.right()));
    }
    private boolean boxedOnBothSides(Expr.Binary b) {
        if (!b.op().equals("==") && !b.op().equals("!=")) {
            return false;
        }
        return (isUnwrapped(b.left()) || isUnwrapped(b.right()) || bothCharacters(b)) && primitiveOf(b.left()) == null &&
                primitiveOf(b.right()) == null &&
                !isNullLiteral(b.left()) &&
                !isNullLiteral(b.right());
    }
    private boolean bothCharacters(Expr.Binary b) {
        final var left = typeOf(b.left());
        final var right = typeOf(b.right());
        return left != null && right != null && left.name().equals("Character") && right.name().equals("Character");
    }
    private boolean isUnwrapped(Expr e) {
        final var inner = withoutParens(e);
        return inner instanceof Expr.Try inner$1 || inner instanceof Expr.Elvis inner$2 || inner instanceof Expr.NotNull inner$3 ||
                (inner instanceof Expr.Name inner$4 && boxedLocals.contains(inner$4.name()));
    }
    private @Nullable String primitiveOf(Expr e) {
        final var inner = withoutParens(e);
        if (inner instanceof Expr.Literal inner$1) {
            return "literal";
        }
        final var type = typeOf(e);
        return (type == null || type.nullable() ? null : PRIMITIVE_NAMES.get(type.name()));
    }
    private String compared(Expr.Binary b, Expr operand, String code) {
        if ((!b.op().equals("==") && !b.op().equals("!=")) || !isUnwrapped(operand)) {
            return code;
        }
        final var type = typeOf(operand);
        final var primitive = (type == null || type.nullable() ? null : PRIMITIVE_NAMES.get(type.name()));
        return (primitive == null ? code : "((" + primitive + ") " + code + ")");
    }
    private String withNullTests(String code, Expr condition) {
        final var tests = new ArrayList<String>();
        nullTests(condition, tests);
        return (tests.isEmpty() || !Lifts.needed(condition) ? code : code + " && " + String.join(" && ", tests));
    }
    private void nullTests(Expr e, List<String> out) {
        final var inner = withoutParens(e);
        if (!(inner instanceof Expr.Binary inner$1)) {
            return;
        }
        if (inner$1.op().equals("&&")) {
            nullTests(inner$1.left(), out);
            nullTests(inner$1.right(), out);
        } else if (inner$1.op().equals("!=") && inner$1.left() instanceof Expr.Name && isNullLiteral(inner$1.right())) {
            out.add(expr(inner$1));
        }
    }
    private String conditionalCode(Expr.Conditional c, boolean inLambda) {
        return conditional(exprIn(c.condition(), inLambda), c, inLambda);
    }
    private String conditional(String condition, Expr.Conditional c, boolean inLambda) {
        final var branches = List.of(c.then(), c.otherwise());
        nestedValues += 1;
        final var thenCode = narrowedValue(whenTrue(c.condition()), () -> remembered(c.then(), inLambda));
        final var negated = whenFalse(c.condition());
        final var otherwise = narrowedValue((negated.isEmpty() ? otherCase(c.condition()) : negated),
                () -> remembered(c.otherwise(), inLambda));
        nestedValues -= 1;
        final var thenLine = startLine(c.then());
        final var head = joined("(" + condition, " ? ", boxedBranch(thenCode, c.then(), branches), c.pos().line(), thenLine);
        return joined(head, " : ", boxedBranch(otherwise, c.otherwise(), branches), thenLine, startLine(c.otherwise())) + ")";
    }
    private String remembered(Expr branch, boolean inLambda) {
        branchTypes.put(branch, typeOf(branch));
        return exprIn(branch, inLambda);
    }
    private String boxedBranch(String code, Expr branch, List<Expr> branches) {
        final var type = typeOf(branch);
        final var noneNull = branches.stream().noneMatch(b -> mayBeNull(b));
        if (noneNull) {
            return code;
        }
        if (type == null) {
            final var wider = widestTypedNumber(branches);
            if (wider != null && mayBeNull(branch)) {
                return "Nulls.to" + wider + "(" + code + ")";
            }
            final var nullable = branches.stream().anyMatch(b -> holdsNull(b));
            final var inner = withoutParens(branch);
            final var plain = inner instanceof Expr.Literal inner$1 || inner instanceof Expr.Lambda inner$2 || inner instanceof Expr.MethodRef inner$3;
            return (nullable && !plain ? "Nulls.box(" + code + ")" : code);
        }
        final var widest = widestNumber(type.name(), branches);
        if (primitiveType(branch) != null) {
            return BOXED_NAMES.getOrDefault(widest, "Object") + ".valueOf(" + code + ")";
        }
        if (!WIDENING.contains(type.name()) || widest.equals(type.name())) {
            return code;
        }
        return "Nulls.to" + widest + "(" + code + ")";
    }
    private String typedConditional(Expr.Conditional c, String code) {
        return ((givesValue(c) ? "Nulls.present(" : "Nulls.inferred(")) + code + ").value()";
    }
    private boolean branchBecomesCall(Expr.Conditional c) {
        return List.of(c.then(), c.otherwise()).stream().anyMatch(b -> becomesCallSomewhere(withoutParens(b)));
    }
    private boolean becomesCallSomewhere(Expr e) {
        return becomesCall(e) || (e instanceof Expr.Conditional e$1 && branchBecomesCall(e$1));
    }
    private boolean fallbackGivesValue(Expr e) {
        final var inner = withoutParens(e);
        if (inner instanceof Expr.Elvis inner$1) {
            return fallbackGivesValue(inner$1.right());
        }
        if (inner instanceof Expr.Conditional inner$2) {
            return fallbackGivesValue(inner$2.then()) && fallbackGivesValue(inner$2.otherwise());
        }
        return !isNullLiteral(inner) && !Chains.hasSafeStep(inner) && !typedNullable(inner);
    }
    private boolean givesValue(Expr e) {
        final var inner = withoutParens(e);
        if (inner instanceof Expr.Elvis inner$1) {
            return givesValue(inner$1.right());
        }
        if (inner instanceof Expr.Conditional inner$2) {
            return givesValue(inner$2.then()) && givesValue(inner$2.otherwise());
        }
        return !mayBeNull(inner);
    }
    private @Nullable String widestTypedNumber(List<Expr> branches) {
        @Nullable String widest = null;
        for (final var b : branches) {
            final var name = Nulls.map(typeOf(b), $n1 -> $n1.name());
            if ("Double".equals(name) || ("Long".equals(name) && widest == null)) {
                widest = name;
            }
        }
        return widest;
    }
    private boolean holdsNull(Expr e) {
        final var inner = withoutParens(e);
        if (inner instanceof Expr.Elvis inner$1) {
            return holdsNull(inner$1.right());
        }
        if (inner instanceof Expr.Conditional inner$2) {
            return holdsNull(inner$2.then()) || holdsNull(inner$2.otherwise());
        }
        return typedNullable(inner) || Chains.hasSafeStep(inner);
    }
    private boolean typedNullable(Expr e) {
        final var type = typeOf(e);
        return type != null && type.nullable();
    }
    private @Nullable String primitiveType(Expr e) {
        final var type = typeOf(e);
        if (type == null || type.nullable() || !BOXED_NAMES.containsKey(type.name())) {
            return null;
        }
        return type.name();
    }
    private boolean mayBeNull(Expr e) {
        final var type = typeOf(e);
        if (type != null) {
            return type.nullable();
        }
        final var inner = withoutParens(e);
        return !(inner instanceof Expr.Binary inner$1) && !(inner instanceof Expr.Unary inner$2) && !(inner instanceof Expr.Literal inner$3) && !(inner instanceof Expr.Is inner$4);
    }
    private String widestNumber(String type, List<Expr> branches) {
        var widest = type;
        for (final var b : branches) {
            final var other = typeOf(b);
            if (other != null && WIDENING.contains(type) && WIDENING.indexOf(other.name()) > WIDENING.indexOf(widest)) {
                widest = other.name();
            }
        }
        return widest;
    }
    private String parenCode(Expr.Paren p, boolean inLambda) {
        if (becomesCall(p.inner())) {
            return exprIn(p.inner(), inLambda);
        }
        return "(" + exprIn(p.inner(), inLambda) + ")";
    }
    private String attemptCode(Expr.Attempt a) {
        final var errorName = a.errorName();
        final var error = (errorName != null ? errorName : "$ex" + nextException());
        final var handler = withoutParens(a.handler());
        if (handler instanceof Expr.Throw handler$1) {
            return "Result.orThrow(() -> " + exprIn(a.body(), true) + ", " + error + " -> " + exprIn(handler$1.value(), true) + ")";
        }
        return "Result.attempt(() -> " + exprIn(a.body(), true) + ", " + error + " -> " + exprIn(a.handler(), true) + ")";
    }
    private void useStatement(Stmt.Use stmt) {
        final var line = stmt.pos().line();
        final var declared = stmt.type();
        final var typeCode = (declared == null ? "var" : javaType(declared));
        withCopies(line, stmt.resource(), () ->
                em.line(line, "try (" + typeCode + " " + stmt.name() + " = " + lifted(line, stmt.resource()).code() + ") {"));
        emitBlock(stmt.body());
        final var handler = stmt.handler();
        if (handler == null) {
            em.line(stmt.body().end().line(), "}");
            return;
        }
        final var errorName = stmt.errorName();
        final var error = (errorName != null ? errorName : "$ex" + nextException());
        em.line(stmt.body().end().line(), "} catch (Exception " + error + ") {");
        em.indent();
        final var inner = withoutParens(handler);
        if (inner instanceof Expr.Throw inner$1) {
            em.line(startLine(inner$1), "throw " + exprIn(inner$1.value(), true) + ";");
        } else {
            em.line(startLine(handler), exprIn(handler, true) + ";");
        }
        em.dedent();
        em.line(stmt.body().end().line(), "}");
    }
    private void attemptStatement(int line, Expr.Attempt a) {
        final var errorName = a.errorName();
        final var error = (errorName != null ? errorName : "$ex" + nextException());
        final var handler = withoutParens(a.handler());
        em.line(line, "try {");
        em.indent();
        em.line(line, exprIn(a.body(), true) + ";");
        em.dedent();
        em.line(line, "} catch (Exception " + error + ") {");
        em.indent();
        if (handler instanceof Expr.Throw handler$1) {
            em.line(startLine(handler$1), "throw " + exprIn(handler$1.value(), true) + ";");
        } else {
            em.line(startLine(handler), exprIn(handler, true) + ";");
        }
        em.dedent();
        em.line(line, "}");
    }
    private int nextException() {
        exceptionCount += 1;
        return exceptionCount;
    }
    private String typeCheck(Expr.Is check, boolean inLambda) {
        final var type = testedType(check.type(), typeOf(check.target()));
        final var target = check.target();
        if (!(target instanceof Expr.Name target$1) || !neverChanges(target$1)) {
            final var plainTest = exprIn(target, inLambda) + " instanceof " + type;
            return (check.negated() ? "!(" + plainTest + ")" : plainTest);
        }
        final var taken = recordPattern(check.type(), typeOf(target$1), target$1.name(), bareName(target$1.name()));
        if (taken != null) {
            patternVariables.put(check, taken.marker());
            final var test = exprIn(target$1, inLambda) + " instanceof " + taken.label();
            return (check.negated() ? "!(" + test + ")" : test);
        }
        final var variable = patternVariable(target$1.name());
        patternVariables.put(check, variable);
        final var test = exprIn(target$1, inLambda) + " instanceof " + type + " " + variable;
        return (check.negated() ? "!(" + test + ")" : test);
    }
    private @Nullable String resultAccessor(TypeRef type) {
        final var name = type.name();
        if (name.equals("Ok") || name.equals("axl.lang.Ok")) {
            return "value";
        }
        if (name.equals("Err") || name.equals("axl.lang.Err")) {
            return "error";
        }
        return null;
    }
    private @Nullable Pattern recordPattern(TypeRef tested, @Nullable TypeRef subject, @Nullable String name, String bare) {
        final var accessor = resultAccessor(tested);
        final List<String> fields = (accessor != null ? List.of(accessor) : genericRecordFields(tested, subject));
        if (fields.isEmpty()) {
            return null;
        }
        final var variables = fields.stream().map(Nulls.nonNull(f -> patternVariable(name))).toList();
        final var pairs = new ArrayList<String>();
        var i = 0;
        while (i < fields.size()) {
            pairs.add(fields.get(i) + "=" + variables.get(i));
            i += 1;
        }
        final var declared = variables.stream().map(Nulls.nonNull(v -> "var " + v)).toList();
        return new Pattern(tested.name() + "(" + String.join(", ", declared) + ")", "@" + String.join(",", pairs) + ":" + bare);
    }
    private List<String> genericRecordFields(TypeRef tested, @Nullable TypeRef subject) {
        if (!tested.args().isEmpty() || (subject != null && argumentsOf(tested, subject) != null)) {
            return List.of();
        }
        final var decl = declaredType(tested.name());
        if (decl instanceof Decl.DataClass decl$1 && !decl$1.typeParams().isEmpty()) {
            return decl$1.fields().stream().map(Nulls.nonNull(f -> f.name())).toList();
        }
        return List.of();
    }
    private @Nullable Decl declaredType(String name) {
        for (final var decl : file.decls()) {
            if (decl.name().equals(name) && isTypeDecl(decl)) {
                return decl;
            }
        }
        @Nullable Decl found = null;
        for (final var sibling : options.siblings()) {
            for (final var decl : sibling.decls()) {
                if (decl.name().equals(name) && isTypeDecl(decl) && samePackage(sibling)) {
                    if (found != null) {
                        return null;
                    }
                    found = decl;
                }
            }
        }
        return found;
    }
    private boolean samePackage(SourceFile other) {
        final var own = file.packageName();
        final var theirs = other.packageName();
        return (own == null ? Boolean.valueOf(theirs == null) : own.equals(theirs));
    }
    private String bareName(String name) {
        final var known = smart.get(name);
        if (known == null) {
            return name;
        }
        return (known.startsWith("@") || known.startsWith("!") ? known.substring(known.lastIndexOf(":") + 1) : known);
    }
    private Chains.Chain deconstructed(Chains.Chain chain) {
        final var base = chain.base();
        final var steps = chain.steps();
        if (!(base instanceof Expr.Name base$1) || steps.isEmpty()) {
            return chain;
        }
        final var known = smart.get(base$1.name());
        if (known == null || !(known.startsWith("@") || known.startsWith("!"))) {
            return chain;
        }
        final var first = steps.getFirst();
        final var args = first.args();
        if (args == null || !args.isEmpty() || first.safe()) {
            return chain;
        }
        if (known.startsWith("!")) {
            final var accessor = known.substring(1, known.indexOf(":"));
            if (!first.name().equals(accessor)) {
                return chain;
            }
            final var read = (accessor.equals("value") ? "Result.unwrap(" : "Result.unwrapError(");
            return new Chains.Chain(new Expr.Name(base$1.pos(), read + known.substring(known.lastIndexOf(":") + 1) + ")"), steps.subList(1, steps.size()));
        }
        final var variable = patternVariableOf(known, first.name());
        if (variable == null) {
            return chain;
        }
        return new Chains.Chain(new Expr.Name(base$1.pos(), variable), steps.subList(1, steps.size()));
    }
    private @Nullable String patternVariableOf(String marker, String accessor) {
        final var pairs = marker.substring(1, marker.lastIndexOf(":"));
        var start = 0;
        while (start < pairs.length()) {
            final var comma = pairs.indexOf(",", start);
            final var end = (comma < 0 ? pairs.length() : comma);
            final var separator = pairs.indexOf("=", start);
            if (pairs.substring(start, separator).equals(accessor)) {
                return pairs.substring(separator + 1, end);
            }
            start = end + 1;
        }
        return null;
    }
    private String liftedTypeCheck(int line, Expr.Is check) {
        final var test = lifted(line, check.target()).code() + " instanceof " + testedType(check.type(), typeOf(check.target()));
        return (check.negated() ? "!(" + test + ")" : test);
    }
    private String testedType(TypeRef tested, @Nullable TypeRef subject) {
        final var name = tested.name();
        final var ownArguments = name.equals("Ok") || name.equals("Err") || name.equals("axl.lang.Ok") || name.equals("axl.lang.Err");
        if (ownArguments && subject != null && subject.args().size() == 2 && isResultType(subject)) {
            return name + "<" + boxedType(subject.args().get(0)) + ", " + boxedType(subject.args().get(1)) + ">";
        }
        if (subject != null && !subject.args().isEmpty() && tested.args().isEmpty()) {
            final var arguments = argumentsOf(tested, subject);
            if (arguments != null) {
                return name + "<" + String.join(", ", arguments) + ">";
            }
        }
        return boxedType(tested);
    }
    private @Nullable List<String> argumentsOf(TypeRef tested, TypeRef subject) {
        final var decl = declaredType(tested.name());
        if (decl == null) {
            return null;
        }
        final var typeParams = typeParamsOf(decl);
        final var parent = Nulls.first(supertypesOf(decl).stream().filter(t -> t.name().equals(subject.name()) && t.args().size() == subject.args().size()).iterator());
        if (typeParams.isEmpty() || parent == null) {
            return null;
        }
        final var arguments = new ArrayList<String>();
        for (final var param : typeParams) {
            @Nullable String found = null;
            var i = 0;
            while (i < parent.args().size()) {
                if (parent.args().get(i).name().equals(param.name())) {
                    found = boxedType(subject.args().get(i));
                }
                i += 1;
            }
            if (found == null) {
                return null;
            }
            arguments.add(found);
        }
        return arguments;
    }
    private Map<String, String> whenTrue(Expr condition) {
        if (condition instanceof Expr.Is condition$1) {
            return (condition$1.negated() ? Map.of() : checkedName(condition$1));
        }
        if (condition instanceof Expr.Paren condition$2) {
            return whenTrue(condition$2.inner());
        }
        if (condition instanceof Expr.Unary condition$3 && condition$3.op().equals("!")) {
            return whenFalse(condition$3.operand());
        }
        if (condition instanceof Expr.Binary condition$4 && condition$4.op().equals("&&")) {
            return union(whenTrue(condition$4.left()), whenTrue(condition$4.right()));
        }
        return Map.of();
    }
    private Map<String, String> whenFalse(Expr condition) {
        if (condition instanceof Expr.Is condition$1) {
            return (condition$1.negated() ? checkedName(condition$1) : Map.of());
        }
        if (condition instanceof Expr.Paren condition$2) {
            return whenFalse(condition$2.inner());
        }
        if (condition instanceof Expr.Unary condition$3 && condition$3.op().equals("!")) {
            return whenTrue(condition$3.operand());
        }
        if (condition instanceof Expr.Binary condition$4 && condition$4.op().equals("||")) {
            return union(whenFalse(condition$4.left()), whenFalse(condition$4.right()));
        }
        return Map.of();
    }
    private Map<String, String> checkedName(Expr.Is check) {
        final var variable = patternVariables.get(check);
        final var target = check.target();
        if (variable != null && target instanceof Expr.Name target$1) {
            return Map.of(target$1.name(), variable);
        }
        return Map.of();
    }
    private Map<String, String> rightNarrowing(Expr.Binary b) {
        if (b.op().equals("&&")) {
            return whenTrue(b.left());
        }
        if (b.op().equals("||")) {
            return whenFalse(b.left());
        }
        return Map.of();
    }
    private String narrowedValue(Map<String, String> names, Supplier<String> body) {
        final var outer = smart;
        smart = union(outer, names);
        final var result = body.get();
        smart = outer;
        return result;
    }
    private void narrowed(Map<String, String> names, Runnable body) {
        final var outer = smart;
        smart = union(outer, names);
        body.run();
        smart = outer;
    }
    private String genericNew(Expr.GenericNew g, String args) {
        final var typeArgs = g.typeArgs().stream().map(Nulls.nonNull(t -> boxedType(t))).toList();
        return "new " + g.type() + "<" + String.join(", ", typeArgs) + ">(" + args + ")";
    }
    private String callCode(Expr.Call c, String args) {
        if (c.name().equals("println") && !declaredFunctions.contains("println")) {
            return "System.out.println(" + args + ")";
        }
        if (c.name().equals("print") && !declaredFunctions.contains("print")) {
            return "System.out.print(" + args + ")";
        }
        if (c.name().equals("readLine") && c.args().isEmpty() && !declaredFunctions.contains("readLine")) {
            return "Console.readLine()";
        }
        if (c.name().equals("Ok") || c.name().equals("Err")) {
            return "new " + c.name() + "<>(" + args + ")";
        }
        if (Character.isUpperCase(c.name().charAt(0)) && !declaredFunctions.contains(c.name())) {
            return "new " + c.name() + "(" + args + ")";
        }
        if (callsHiddenFunction(c)) {
            return file.className() + "." + c.name() + "(" + args + ")";
        }
        return c.name() + "(" + args + ")";
    }
    private boolean callsHiddenFunction(Expr.Call c) {
        final var owner = currentClass;
        if (currentMethods.isEmpty() || (owner != null && owner.name().equals(file.className()))) {
            return false;
        }
        final var arity = c.args().size();
        final var methods = currentMethods.stream().filter(m -> m.name().equals(c.name())).toList();
        final var method = methods.stream().anyMatch(m -> m.params().size() == arity);
        final var function = file.decls().stream().anyMatch(d -> d instanceof Decl.Function d$1 && d$1.name().equals(c.name()) && d$1.params().size() == arity);
        return !methods.isEmpty() && !method && function;
    }
    private String chainCode(Chains.Chain chain, boolean inLambda) {
        final var steps = chain.steps();
        final var created = constructorStep(chain);
        if (created < 0) {
            return chainFrom(exprIn(chain.base(), inLambda), steps, 0, inLambda, startLine(chain.base()));
        }
        final var path = new StringBuilder(exprIn(chain.base(), inLambda));
        var i = 0;
        while (i < created) {
            path.append(".").append(steps.get(i).name());
            i += 1;
        }
        final var step = steps.get(created);
        final var args = argsCode(Nulls.<List<Expr>>or(step.args(), () -> List.of()), inLambda, step.line());
        final var prefix = "new " + path + "." + step.name() + "(" + args + ")";
        return chainFrom(prefix, steps, created + 1, inLambda, startLine(chain.base()));
    }
    private int constructorStep(Chains.Chain chain) {
        if (!isStaticName(chain.base())) {
            return -1;
        }
        final var steps = chain.steps();
        var i = 0;
        while (i < steps.size() && steps.get(i).args() == null && !steps.get(i).safe()) {
            i += 1;
        }
        if (i < steps.size() && steps.get(i).args() != null && !steps.get(i).safe() && isTypeName(steps.get(i).name())) {
            return i;
        }
        return -1;
    }
    private String chainFrom(String prefix, List<Chains.Step> steps, int index, boolean inLambda, int baseLine) {
        if (index == steps.size()) {
            return prefix;
        }
        final var step = steps.get(index);
        if (step.safe()) {
            final var param = newNull();
            final var rest = chainFrom(param + stepOnLine(step, true, steps, index, baseLine), steps, index + 1, true, baseLine);
            return "Nulls.map(" + prefix + ", " + param + " -> " + rest + ")";
        }
        return chainFrom(prefix + stepOnLine(step, inLambda, steps, index, baseLine), steps, index + 1, inLambda, baseLine);
    }
    private String stepOnLine(Chains.Step step, boolean inLambda, List<Chains.Step> steps, int index, int baseLine) {
        final var text = stepText(step, inLambda);
        final var previous = (index == 0 ? baseLine : steps.get(index - 1).line());
        return (step.line() > previous ? Emitter.continued(step.line(), text) : text);
    }
    private String stepText(Chains.Step step, boolean inLambda) {
        final var args = step.args();
        if (args == null) {
            return "." + step.name();
        }
        return "." + step.name() + "(" + argsCode(args, inLambda, step.line()) + ")";
    }
    private String argsCode(List<Expr> args, boolean inLambda, int line) {
        final var codes = args.stream().map(Nulls.nonNull(a -> argument(a, inLambda))).toList();
        return joinedArgs(args, codes, line);
    }
    private String argument(Expr arg, boolean inLambda) {
        if (arg instanceof Expr.Paren arg$1) {
            return "(" + argument(arg$1.inner(), inLambda) + ")";
        }
        if (arg instanceof Expr.NotNull arg$2) {
            final var check = (withoutParens(arg$2.target()) instanceof Expr.Name ? "java.util.Objects.requireNonNull(" : "Nulls.sure(");
            return check + argument(arg$2.target(), inLambda) + ")";
        }
        if (arg instanceof Expr.Elvis arg$3 && hasSafeElvis(arg$3) && fallbackGivesValue(arg$3.right())) {
            return "Nulls.present(" + exprIn(arg$3, inLambda) + ").value()";
        }
        if (arg instanceof Expr.Conditional arg$4 && branchBecomesCall(arg$4)) {
            return typedConditional(arg$4, exprIn(arg$4, inLambda));
        }
        if (Chains.hasSafeStep(arg) || hasSafeElvis(arg)) {
            return "Nulls.typed(" + exprIn(arg, inLambda) + ").value()";
        }
        return exprIn(arg, inLambda);
    }
    private String lambdaCode(Expr.Lambda l) {
        lambdaDepth += 1;
        final var code = lambdaCodeIn(l);
        lambdaDepth -= 1;
        return code;
    }
    private String lambdaCodeIn(Expr.Lambda l) {
        final var params = lambdaParams(l);
        final var body = l.body();
        final var outerCopies = copies;
        copies = Map.of();
        final var single = lambdaExpression(body);
        if (single != null && !Checker.isAttemptStatement(single, valueStage)) {
            final var bodyLine = startLine(single);
            final var code = expr(single);
            copies = outerCopies;
            return (bodyLine > l.pos().line() ? params + " ->" + Emitter.continued(bodyLine, code) : params + " -> " + code);
        }
        final var outer = em;
        final var outerReturnType = returnType;
        final var outerSmart = smart;
        returnType = null;
        em = new Emitter();
        em.indent();
        emitStatements(body);
        final var block = em.embedded();
        em = outer;
        smart = outerSmart;
        copies = outerCopies;
        returnType = outerReturnType;
        return params + " -> {" + block + Emitter.continuation(l.end().line(), "}");
    }
    private String lambdaParams(Expr.Lambda l) {
        if (analysis.implicitIt().contains(l)) {
            return "it";
        }
        final var params = l.params();
        if (params.size() == 1 && params.getFirst().type() == null) {
            return params.getFirst().name();
        }
        final var plain = params.stream().allMatch(p -> isPlainParam(p));
        final var names = (plain ? params.stream().map(Nulls.nonNull(p -> p.name())).toList() : params.stream().map(Nulls.nonNull(p -> typedParam(p))).toList());
        return "(" + String.join(", ", names) + ")";
    }
    private boolean isPlainParam(Expr.Param p) {
        final var type = p.type();
        return type == null || javaType(type).equals(primitive(type));
    }
    private String typedParam(Expr.Param p) {
        final var type = p.type();
        if (type == null) {
            return p.name();
        }
        return boxedType(type) + " " + p.name();
    }
    private Lifted lifted(int line, Expr e) {
        if (!Lifts.needed(e)) {
            return new Lifted(expr(e), isPure(e));
        }
        switch (e) {
            case Expr.Try e$1 -> {
                return new Lifted(unwrapTry(line, e$1), true);
            }
            case Expr.When e$2 -> {
                return liftedWhen(line, e$2);
            }
            case Expr.Binary e$3 -> {
                return liftedBinary(line, e$3);
            }
            case Expr.Elvis e$4 -> {
                return liftedElvis(line, e$4);
            }
            case Expr.Unary e$5 -> {
                return new Lifted(unary(e$5.op(), lifted(line, e$5.operand()).code()), false);
            }
            case Expr.Paren e$6 -> {
                return liftedParen(line, e$6);
            }
            case Expr.Call e$7 -> {
                return liftedCall(line, e$7);
            }
            case Expr.Member e$8 -> {
                return chainLifted(line, deconstructed(Chains.flatten(e$8)));
            }
            case Expr.GenericNew e$9 -> {
                return new Lifted(genericNew(e$9, codes(operands(line, e$9.args()))), false);
            }
            case Expr.MethodRef e$10 -> {
                return new Lifted(lifted(line, e$10.target()).code() + "::" + e$10.name(), false);
            }
            case Expr.ClassLiteral e$11 -> {
                return new Lifted(classLiteral(e$11), false);
            }
            case Expr.NotNull e$12 -> {
                return new Lifted("java.util.Objects.requireNonNull(" + lifted(line, e$12.target()).code() + ")", false);
            }
            case Expr.Pipe e$13 -> {
                return pipeLifted(line, e$13);
            }
            case Expr.Conditional e$14 -> {
                return new Lifted(conditional(withNullTests(lifted(line, e$14.condition()).code(), e$14.condition()), e$14, false), false);
            }
            case Expr.Is e$15 -> {
                return new Lifted(liftedTypeCheck(line, e$15), false);
            }
            default -> {
                throw new IllegalStateException("nothing to lift in " + e);
            }
        }
    }
    private Lifted liftedWhen(int line, Expr.When w) {
        final var temp = newTemp();
        emitWhen(line, w, "final var " + temp + " = ", WhenForm.VALUE);
        return new Lifted(temp, true);
    }
    private Lifted liftedBinary(int line, Expr.Binary b) {
        if (shortCircuitWithLift(b) != null) {
            return shortCircuit(line, b);
        }
        final var ops = operands(line, List.of(b.left(), b.right()));
        final var left = compared(b, b.left(), ops.get(0).code());
        return new Lifted(comparison(b, left, compared(b, b.right(), ops.get(1).code())), false);
    }
    private Lifted liftedElvis(int line, Expr.Elvis el) {
        if (Lifts.needed(el.right())) {
            return elvisChain(line, el, null);
        }
        if (Lifts.numberAfterSafeCall(el) && !Lifts.needed(el.left())) {
            final var temp = newTemp();
            em.line(line, "final var " + temp + " = " + expr(el.left()) + ";");
            return new Lifted(elvisCode(el, temp), false);
        }
        return new Lifted(elvisCode(el, lifted(line, el.left()).code()), false);
    }
    private Lifted liftedParen(int line, Expr.Paren p) {
        final var inner = lifted(line, p.inner());
        final var code = (becomesCall(p.inner()) ? inner.code() : "(" + inner.code() + ")");
        return new Lifted(code, inner.pure());
    }
    private Lifted liftedCall(int line, Expr.Call c) {
        if (c.target() == null) {
            return new Lifted(callCode(c, codes(operands(line, c.args()))), false);
        }
        return chainLifted(line, deconstructed(Chains.flatten(c)));
    }
    private boolean isLocal(Expr target) {
        return target instanceof Expr.Name target$1 && analysis.mutableRefs().contains(target$1);
    }
    private boolean neverChanges(Expr.Name name) {
        return analysis.stableNames().contains(name) && !analysis.mutableRefs().contains(name);
    }
    private String assignTarget(int line, Expr target, boolean valueLifts) {
        if (!(target instanceof Expr.Member target$1) || (!valueLifts && !Lifts.needed(target$1))) {
            return expr(target);
        }
        final var receiver = lifted(line, target$1.target());
        var code = receiver.code();
        if (valueLifts && !receiver.pure()) {
            code = newTemp();
            em.line(line, "final var " + code + " = " + receiver.code() + ";");
        }
        return code + "." + target$1.name();
    }
    private List<Lifted> operands(int line, List<Expr> exprs) {
        var last = -1;
        var i = 0;
        while (i < exprs.size()) {
            if (Lifts.hoisted(exprs.get(i))) {
                last = i;
            }
            i += 1;
        }
        final var result = new ArrayList<Lifted>();
        var k = 0;
        while (k < exprs.size()) {
            final var e = exprs.get(k);
            final var at = Math.max(line, startLine(e));
            var operand = (k < last && takesTypeFromTarget(e) ? withArgumentsFirst(at, e) : lifted(at, e));
            if ((k < last && !operand.pure()) || Chains.hasSafeStep(e)) {
                final var temp = newTemp();
                em.line(at, "final var " + temp + " = " + operand.code() + ";");
                operand = new Lifted(temp, true);
            } else {
                final var conditional = withoutParens(e);
                if (conditional instanceof Expr.Conditional conditional$1 && branchBecomesCall(conditional$1)) {
                    operand = new Lifted(typedConditional(conditional$1, operand.code()), operand.pure());
                }
            }
            result.add(operand);
            k += 1;
        }
        return result;
    }
    private boolean takesTypeFromTarget(Expr e) {
        if (e instanceof Expr.Call e$1) {
            final var target = e$1.target();
            if (target == null) {
                return e$1.name().equals("Ok") || e$1.name().equals("Err");
            }
            return target instanceof Expr.Name target$2 && analysis.staticNames().contains(target$2) && TARGET_TYPED_FACTORIES.contains(e$1.name());
        }
        if (e instanceof Expr.GenericNew e$3) {
            return e$3.typeArgs().isEmpty();
        }
        return e instanceof Expr.MethodRef e$4;
    }
    private Lifted withArgumentsFirst(int line, Expr e) {
        if (e instanceof Expr.MethodRef e$1) {
            return methodRefFirst(line, e$1);
        }
        final var codes = new ArrayList<String>();
        for (final var arg : argumentsOf(e)) {
            var operand = (takesTypeFromTarget(arg) ? withArgumentsFirst(line, arg) : lifted(line, arg));
            if (!operand.pure()) {
                final var temp = newTemp();
                em.line(line, "final var " + temp + " = " + operand.code() + ";");
                operand = new Lifted(temp, true);
            }
            codes.add(operand.code());
        }
        return new Lifted(inlineCall(e, String.join(", ", codes)), true);
    }
    private Lifted methodRefFirst(int line, Expr.MethodRef r) {
        final var target = lifted(line, r.target());
        if (target.pure() || isPath(r.target())) {
            return new Lifted(target.code() + "::" + r.name(), true);
        }
        final var temp = newTemp();
        em.line(line, "final var " + temp + " = " + target.code() + ";");
        return new Lifted(temp + "::" + r.name(), true);
    }
    private String inlineCall(Expr e, String argsText) {
        if (e instanceof Expr.Call e$1) {
            final var target = e$1.target();
            if (target == null) {
                return callCode(e$1, argsText);
            }
            return expr(target) + "." + e$1.name() + "(" + argsText + ")";
        }
        if (e instanceof Expr.GenericNew e$2) {
            return genericNew(e$2, argsText);
        }
        throw new IllegalStateException();
    }
    private String codes(List<Lifted> list) {
        final var texts = list.stream().map(Nulls.nonNull(l -> l.code())).toList();
        return String.join(", ", texts);
    }
    private String classLiteral(Expr.ClassLiteral e) {
        final var name = Nulls.or(Expr.pathName(e.target()), () -> "Object");
        return boxedType(new TypeRef(e.pos(), name, List.of(), false)) + ".class";
    }
    private boolean isPure(Expr e) {
        switch (e) {
            case Expr.Literal e$1 -> {
                return true;
            }
            case Expr.Name e$2 -> {
                return analysis.stableNames().contains(e$2) || analysis.staticNames().contains(e$2);
            }
            case Expr.Lambda e$3 -> {
                return true;
            }
            case Expr.MethodRef e$4 -> {
                return isPure(e$4.target()) || isPath(e$4.target());
            }
            case Expr.ClassLiteral e$5 -> {
                return true;
            }
            case Expr.Paren e$6 -> {
                return isPure(e$6.inner());
            }
            case Expr.Call e$7 -> {
                return isPureResult(e$7);
            }
            default -> {
                return false;
            }
        }
    }
    private boolean isPureResult(Expr.Call c) {
        if (c.target() == null && (c.name().equals("Ok") || c.name().equals("Err"))) {
            return c.args().stream().allMatch(a -> isPure(a));
        }
        return false;
    }
    private boolean isPath(Expr e) {
        switch (e) {
            case Expr.Name e$1 -> {
                return analysis.staticNames().contains(e$1);
            }
            case Expr.Member e$2 -> {
                return !e$2.safe() && isPath(e$2.target());
            }
            default -> {
                return false;
            }
        }
    }
    private Lifted shortCircuit(int line, Expr.Binary b) {
        final var left = lifted(line, b.left()).code();
        final var temp = newTemp();
        final var and = b.op().equals("&&");
        em.line(line, "boolean " + temp + " = " + ((and ? "false" : "true")) + ";");
        em.line(line, "if (" + ((and ? left : negated(left))) + ") {");
        em.indent();
        em.line(line, temp + " = " + narrowedValue(rightNarrowing(b), () -> lifted(line, b.right()).code()) + ";");
        em.dedent();
        em.line(line, "}");
        return new Lifted(temp, true);
    }
    private Lifted elvisChain(int line, Expr.Elvis elvis, @Nullable String stored) {
        final var left = lifted(line, elvis.left()).code();
        final var right = elvis.right();
        if (stored == null && right instanceof Expr.Try right$1 && !Lifts.needed(right$1.inner())) {
            return elvisTry(line, left, right$1);
        }
        final var temp = newTemp();
        em.line(line, ((stored == null ? "var" : stored)) + " " + temp + " = " + left + ";");
        em.line(line, "if (java.util.Objects.isNull(" + temp + ")) {");
        em.indent();
        final var value = lifted(line, right).code();
        if (stored == null) {
            final var fallback = newTemp();
            em.line(line, "final var " + fallback + " = " + value + ";");
            em.line(line, temp + " = " + fallback + ";");
        } else {
            em.line(line, temp + " = " + value + ";");
        }
        em.dedent();
        em.line(line, "}");
        return new Lifted(temp, true);
    }
    private Lifted elvisTry(int line, String left, Expr.Try t) {
        final var value = newTemp();
        em.line(line, "final var " + value + " = " + left + ";");
        final var result = newTemp();
        em.line(line, "final var " + result + " = java.util.Objects.isNull(" + value + ") ? " + expr(t.inner()) + " : null;");
        em.line(line, "if (" + result + " != null && " + result + ".isErr()) return Result.propagate(" + result + ");");
        return new Lifted("(java.util.Objects.nonNull(" + value + ") ? " + value +
                " : Result." +
                nullableWitness(typeOf(t)) +
                "unwrap(java.util.Objects.requireNonNull(" +
                result +
                ")))",
                false);
    }
    private Lifted chainLifted(int line, Chains.Chain chain) {
        final var first = indexOfFirstSafe(chain.steps());
        final var prefix = liftedPrefix(line, chain, first);
        return new Lifted(chainFrom(prefix, chain.steps(), first, false, startLine(chain.base())), false);
    }
    private String liftedPrefix(int line, Chains.Chain chain, int firstSafe) {
        final var steps = chain.steps();
        final var base = lifted(line, chain.base());
        var current = base.code();
        var fixed = base.pure();
        var typeOrPackage = isStaticName(chain.base());
        var segment = (typeOrPackage ? baseName(chain) : "");
        var i = 0;
        while (i < firstSafe) {
            final var step = steps.get(i);
            final var stepArgs = step.args();
            if (stepArgs == null) {
                typeOrPackage = typeOrPackage && (Character.isLowerCase(segment.charAt(0)) || isTypeName(step.name()));
                fixed = typeOrPackage;
                segment = step.name();
                current += "." + step.name();
            } else {
                final var hoisted = stepArgs.stream().anyMatch(a -> Lifts.hoisted(a));
                if (hoisted && !fixed) {
                    final var temp = newTemp();
                    em.line(line, "final var " + temp + " = " + current + ";");
                    current = temp;
                }
                final var argsText = (hoisted ? codes(operands(line, stepArgs)) : plainArgs(stepArgs));
                current += "." + step.name() + "(" + argsText + ")";
                fixed = false;
                typeOrPackage = false;
            }
            i += 1;
        }
        return current;
    }
    private String plainArgs(List<Expr> args) {
        final var texts = args.stream().map(Nulls.nonNull(a -> expr(a))).toList();
        return String.join(", ", texts);
    }
    private boolean isStaticName(Expr e) {
        return e instanceof Expr.Name e$1 && analysis.staticNames().contains(e$1);
    }
    private Lifted pipeLifted(int line, Expr.Pipe pipe) {
        final var stages = pipe.stages();
        var mapResult = 0;
        while (mapResult < stages.size() && !stages.get(mapResult).name().equals("mapResult")) {
            mapResult += 1;
        }
        final var eager = new ArrayList<Expr>();
        eager.add(pipe.source());
        var i = 0;
        while (i < mapResult) {
            eager.addAll(stages.get(i).args());
            i += 1;
        }
        final var ops = operands(line, eager);
        final var argCodes = new IdentityHashMap<Expr, String>();
        var j = 1;
        while (j < eager.size()) {
            argCodes.put(eager.get(j), ops.get(j).code());
            j += 1;
        }
        final Function<Expr, String> argOf = a -> argCodes.getOrDefault(a, "");
        if (mapResult == stages.size()) {
            return new Lifted(pipeCode(ops.getFirst().code(), pipe.source(), stages, argOf), false);
        }
        final var items = (mapResult == 0 ? parenthesized(ops.getFirst().code(), pipe.source()) :
                pipeCode(ops.getFirst().code(), pipe.source(), stages.subList(0, mapResult), argOf));
        final var list = mapResultLoop(line, stages.get(mapResult), items);
        final var rest = stages.subList(mapResult + 1, stages.size());
        if (rest.isEmpty()) {
            return new Lifted(list, true);
        }
        return pipeLifted(line, new Pipe(pipe.pos(), new Name(pipe.pos(), list), rest));
    }
    private String mapResultLoop(int line, Expr.Stage stage, String items) {
        final var lambda = stageLambda(stage);
        final var param = (lambda.params().isEmpty() ? "it" : lambda.params().getFirst().name());
        final var body = firstExpression(lambda.body());
        final var list = newList();
        final var element = boxedType(mapResultType(stage));
        em.line(line, "final java.util.List<" + element + "> " + list + " = new java.util.ArrayList<>();");
        em.line(line, "for (final var " + param + " : " + items + ") {");
        em.indent();
        final var temp = newTemp();
        final var bodyLine = lambda.pos().line();
        final var inner = withoutParens(body);
        final var typeCode = (inner instanceof Expr.Conditional inner$1 || inner instanceof Expr.When inner$2 ? stageResultType(element) : "var");
        em.line(bodyLine, "final " + typeCode + " " + temp + " = " + expr(body) + ";");
        if (!isOkCall(body)) {
            em.line(bodyLine, "if (" + temp + ".isErr()) return Result.propagate(" + temp + ");");
        }
        em.line(bodyLine, list + ".add(Result.<" + element + ">unwrap(" + temp + "));");
        em.dedent();
        em.line(line, "}");
        return list;
    }
    private Expr.Lambda stageLambda(Expr.Stage stage) {
        final var lambda = stage.lambda();
        if (lambda == null) {
            throw new IllegalStateException("mapResult without a lambda");
        }
        return lambda;
    }
    private String stageResultType(String element) {
        final var result = resultTypeRef;
        if (result == null || !isResultType(result) || result.args().size() != 2) {
            return "var";
        }
        return "Result<" + element + ", " + boxedType(result.args().get(1)) + ">";
    }
    private TypeRef mapResultType(Expr.Stage stage) {
        final var type = analysis.mapResultTypes().get(stage);
        if (type == null) {
            throw new IllegalStateException("mapResult without an element type");
        }
        return type;
    }
    private String pipeCode(String source, Expr sourceExpr, List<Expr.Stage> stages, Function<Expr, String> arg) {
        final var sb = new StringBuilder(parenthesized(source, sourceExpr)).append(".stream()");
        var terminal = false;
        var previousLine = startLine(sourceExpr);
        var index = 0;
        for (final var stage : stages) {
            final var stageBody = stage.lambda();
            final var outerStage = valueStage;
            valueStage = !stage.name().equals("forEach") && !stage.name().equals("peek");
            final var lambdaText = (stageBody == null ? "" : lambdaCode(stageBody));
            valueStage = outerStage;
            if (stage.pos().line() > previousLine) {
                sb.append(Emitter.continued(stage.pos().line(), ""));
                previousLine = stage.pos().line();
            }
            final var next = (index + 1 < stages.size() ? stages.get(index + 1) : null);
            appendStage(sb, stage, lambdaText, arg, next != null && next.name().equals("filter") && dropsNulls(next));
            index += 1;
            if (Checker.TERMINAL_STAGES.contains(stage.name())) {
                terminal = true;
            }
        }
        return (terminal ? sb.toString() : sb.append(".toList()").toString());
    }
    private void appendStage(StringBuilder sb, Expr.Stage stage, String lambdaText, Function<Expr, String> arg,
            boolean nullsDropped) {
        final var name = stage.name();
        switch (name) {
            case "limit", "skip" -> {
                sb.append('.').append(name).append('(').append(arg.apply(stage.args().getFirst())).append(')');
            }
            case "distinct" -> {
                sb.append(".distinct()");
            }
            case "count" -> {
                sb.append(".count()");
            }
            case "first" -> {
                sb.insert(0, "Nulls.first(").append(".iterator())");
            }
            case "map" -> {
                final var element = analysis.mapResultTypes().get(stage);
                final var witness = (element == null ? "" : "<" + boxedType(element) + ">");
                final var wrap = (nullsDropped ? "Nulls.nullable(" : "Nulls.nonNull(");
                sb.append(".").append(witness).append("map(").append(wrap).append(lambdaText).append("))");
            }
            case "sorted" -> {
                final var order = (!lambdaText.isEmpty() ? lambdaText :
                        (!stage.args().isEmpty() ? arg.apply(stage.args().getFirst()) : "java.util.Comparator.naturalOrder()"));
                sb.append(".sorted(").append(order).append(')');
            }
            case "filter" -> {
                sb.append(".filter(").append(lambdaText).append(')');
                if (dropsNulls(stage)) {
                    sb.insert(0, "Nulls.nonNulls(").append(')');
                }
                final var kept = keptType(stage);
                if (kept != null) {
                    sb.append(".<").append(kept).append(">map(").append(kept).append(".class::cast)");
                }
            }
            case null, default -> {
                sb.append('.').append(name).append('(').append(lambdaText).append(')');
            }
        }
    }
    private @Nullable String keptType(Expr.Stage stage) {
        final var lambda = stage.lambda();
        if (lambda == null || lambda.params().size() > 1 || (lambda.params().isEmpty() && !analysis.implicitIt().contains(lambda))) {
            return null;
        }
        final var param = (lambda.params().isEmpty() ? "it" : lambda.params().getFirst().name());
        final var body = lambdaExpression(lambda.body());
        if (!(body instanceof Expr.Is body$1) || body$1.negated() || !isName(body$1.target(), param)) {
            return null;
        }
        final var tested = body$1.type();
        final var name = tested.name();
        final var hasArguments = name.equals("Ok") || name.equals("Err") || name.equals("axl.lang.Ok") || name.equals("axl.lang.Err");
        return (hasArguments ? null : boxedType(tested));
    }
    private boolean dropsNulls(Expr.Stage stage) {
        final var lambda = stage.lambda();
        if (lambda == null || lambda.params().size() > 1 || (lambda.params().isEmpty() && !analysis.implicitIt().contains(lambda))) {
            return false;
        }
        final var param = (lambda.params().isEmpty() ? "it" : lambda.params().getFirst().name());
        final var body = lambdaExpression(lambda.body());
        if (!(body instanceof Expr.Binary body$1) || !body$1.op().equals("!=")) {
            return false;
        }
        return (isName(body$1.left(), param) && isNullLiteral(body$1.right())) || (isNullLiteral(body$1.left()) && isName(body$1.right(), param));
    }
    private String joinedArgs(List<Expr> args, List<String> codes, int line) {
        final var sb = new StringBuilder();
        var previous = line;
        var i = 0;
        while (i < codes.size()) {
            final var argLine = startLine(args.get(i));
            if (argLine > previous) {
                sb.append((i > 0 ? "," : "")).append(Emitter.continued(argLine, codes.get(i)));
            } else {
                sb.append((i > 0 ? ", " : "")).append(codes.get(i));
            }
            previous = argLine;
            i += 1;
        }
        return sb.toString();
    }
    private int startLine(Expr e) {
        final var known = startLines.get(e);
        if (known != null) {
            return known;
        }
        final var line = firstLine(e);
        startLines.put(e, line);
        return line;
    }
    private int firstLine(Expr e) {
        switch (e) {
            case Expr.Member e$1 -> {
                return startLine(e$1.target());
            }
            case Expr.Call e$2 -> {
                return callStartLine(e$2);
            }
            case Expr.Binary e$3 -> {
                return startLine(e$3.left());
            }
            case Expr.Elvis e$4 -> {
                return startLine(e$4.left());
            }
            case Expr.Pipe e$5 -> {
                return startLine(e$5.source());
            }
            case Expr.MethodRef e$6 -> {
                return startLine(e$6.target());
            }
            case Expr.NotNull e$7 -> {
                return startLine(e$7.target());
            }
            default -> {
                return e.pos().line();
            }
        }
    }
    private int callStartLine(Expr.Call c) {
        final var target = c.target();
        if (target != null) {
            return startLine(target);
        }
        return c.pos().line();
    }
    private static final Map<String, String> PRIMITIVE_NAMES = Map.of("Int", "int", "Long", "long", "Boolean", "boolean",
            "Double", "double");
    private static final Map<String, String> BOXED_NAMES = Map.of("Int", "Integer", "Long", "Long", "Boolean", "Boolean",
            "Double", "Double");
    private static final Set<String> NUMBERS = Set.of("Int", "Long", "Double", "Float", "Short", "Byte", "Integer", "Character");
    private static final Map<String, String> NARROW_NUMBERS = Map.of("Short", "short", "Byte", "byte", "Character", "char",
            "Float", "float");
    private static final List<String> WIDENING = List.of("Int", "Long", "Double");
    private static final Set<String> TARGET_TYPED_FACTORIES = Set.of("of", "copyOf", "ofEntries", "entry", "empty",
            "emptyList", "emptySet", "emptyMap", "naturalOrder", "reverseOrder", "nullsFirst", "nullsLast", "identity");
    public enum WhenForm { STATEMENT, RESULT, VALUE }
    public record Lifted(String code, boolean pure) {}
    public static GeneratedUnit generate(String sourcePath, SourceFile file, Analysis analysis, Options options,
            Map<String, String> valueTypes) {
        final var generator = new Generator(file, analysis, options, valueTypes, Set.of());
        generator.emitFile();
        return new GeneratedUnit(sourcePath, Nulls.or(file.packageName(), () -> ""), file.className(), generator.em.text(),
                lineMap(sourcePath, generator.em));
    }
    public static GeneratedUnit probe(SourceFile file, Analysis analysis, Options options, Map<String, String> valueTypes,
            Set<String> probed) {
        final var generator = new Generator(file, analysis, options, valueTypes, probed);
        generator.emitFile();
        final var name = file.className() + ".axl";
        return new GeneratedUnit(name, Nulls.or(file.packageName(), () -> ""), file.className(), generator.em.text(),
                lineMap(name, generator.em));
    }
    private static LineMap lineMap(String sourcePath, Emitter em) {
        return new LineMap(sourcePath, em.lineNumbers().stream().mapToInt(Integer::intValue).toArray());
    }
    public static String javaType(TypeRef type) {
        return typeText(type, false);
    }
    private static String boxedType(TypeRef type) {
        return typeText(type, true);
    }
    private static String typeText(TypeRef type, boolean generic) {
        if (type.name().equals(TypeRef.ANY)) {
            return "?";
        }
        final var primitive = PRIMITIVE_NAMES.get(type.name());
        var name = type.name();
        if (primitive != null) {
            name = (generic || type.nullable() ? BOXED_NAMES.getOrDefault(type.name(), primitive) : primitive);
        }
        if (!type.args().isEmpty()) {
            final var args = type.args().stream().map(Nulls.nonNull(t -> typeText(t, true))).toList();
            name += "<" + String.join(", ", args) + ">";
        }
        if (!type.nullable()) {
            return name;
        }
        final var dot = type.name().lastIndexOf(".");
        return (dot < 0 ? "@Nullable " + name : name.substring(0, dot + 1) + "@Nullable " + name.substring(dot + 1));
    }
    private static String nullableType(TypeRef type) {
        return boxedType(new TypeRef(type.pos(), type.name(), type.args(), true));
    }
    private static @Nullable String primitive(TypeRef type) {
        final var name = PRIMITIVE_NAMES.get(type.name());
        if (name == null || type.nullable()) {
            return null;
        }
        return name;
    }
    private static Set<String> declaredFunctionNames(SourceFile file) {
        final var names = new LinkedHashSet<String>();
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Function decl$1) {
                names.add(decl$1.name());
            }
        }
        for (final var imp : file.imports()) {
            if (imp.kind() == Import.Kind.FN) {
                names.add(imp.simpleName());
            }
        }
        return names;
    }
    private static Expr propertyInit(Decl.Property p) {
        final var init = p.init();
        if (init == null) {
            throw new IllegalStateException("property " + p.name() + " has no initializer");
        }
        return init;
    }
    private static TypeRef memberType(Expr.Param member) {
        final var type = member.type();
        if (type == null) {
            throw new IllegalStateException("member " + member.name() + " has no type");
        }
        return type;
    }
    private static TypeRef conditionType(Expr.Cond condition) {
        final var type = condition.type();
        if (type == null) {
            throw new IllegalStateException("condition without a type");
        }
        return type;
    }
    private static Expr.Literal constantOf(Expr.Cond condition) {
        final var constant = condition.constant();
        if (constant == null) {
            throw new IllegalStateException("condition without a constant");
        }
        return constant;
    }
    private static @Nullable Expr runsWhen(Expr statement) {
        if (statement instanceof Expr.Binary statement$1 && statement$1.op().equals("&&")) {
            return statement$1.left();
        }
        if (statement instanceof Expr.Binary statement$2 && statement$2.op().equals("||")) {
            return new Unary(statement$2.pos(), "!", new Paren(statement$2.pos(), statement$2.left()));
        }
        if (statement instanceof Expr.Elvis statement$3) {
            return new Binary(statement$3.pos(), "==", statement$3.left(), new Literal(statement$3.pos(), "null"));
        }
        return null;
    }
    private static Expr actionOf(Expr statement) {
        if (statement instanceof Expr.Binary statement$1) {
            return statement$1.right();
        }
        if (statement instanceof Expr.Elvis statement$2) {
            return statement$2.right();
        }
        throw new IllegalStateException();
    }
    private static Expr withoutParens(Expr e) {
        return (e instanceof Expr.Paren e$1 ? withoutParens(e$1.inner()) : e);
    }
    private static String annotations(List<Decl.Annotation> list) {
        final var sb = new StringBuilder();
        for (final var a : list) {
            sb.append("@").append(a.name());
            if (!a.args().isEmpty()) {
                sb.append("(").append(String.join(", ", a.args().stream().map(Nulls.nonNull(arg -> annotationArg(arg))).toList())).append(")");
            }
            sb.append(" ");
        }
        return sb.toString();
    }
    private static String annotationArg(Decl.AnnotationArg arg) {
        final var value = arg.value();
        final var code = (value != null ? annotationValue(value) : "{" + String.join(", ", arg.elements().stream().map(Nulls.nonNull(e -> annotationArg(e))).toList()) + "}");
        final var name = arg.name();
        return (name == null ? code : name + " = " + code);
    }
    private static String annotationValue(Expr e) {
        switch (e) {
            case Expr.Literal e$1 -> {
                return literal(e$1.text());
            }
            case Expr.Name e$2 -> {
                return e$2.name();
            }
            case Expr.Member e$3 -> {
                return annotationValue(e$3.target()) + "." + e$3.name();
            }
            case Expr.ClassLiteral e$4 -> {
                return boxedType(new TypeRef(e$4.pos(), Nulls.or(Expr.pathName(e$4.target()), () -> "Object"), List.of(), false)) + ".class";
            }
            case Expr.Unary e$5 -> {
                return e$5.op() + annotationValue(e$5.operand());
            }
            case Expr.Binary e$6 -> {
                return annotationValue(e$6.left()) + " " + e$6.op() + " " + annotationValue(e$6.right());
            }
            case Expr.Paren e$7 -> {
                return "(" + annotationValue(e$7.inner()) + ")";
            }
            default -> {
                throw new IllegalStateException("not a constant in an annotation at " + e.pos());
            }
        }
    }
    private static String parameters(List<Expr.Param> params, int declarationLine) {
        return parameters(params, declarationLine, Set.of());
    }
    private static String parameters(List<Expr.Param> params, int declarationLine, Set<String> boxed) {
        final var sb = new StringBuilder();
        var previous = declarationLine;
        var i = 0;
        while (i < params.size()) {
            final var p = params.get(i);
            final var type = (boxed.contains(p.name()) ? boxedType(memberType(p)) : javaType(memberType(p)));
            final var code = annotations(p.annotations()) + type + " " + p.name();
            if (p.pos().line() > previous) {
                sb.append((i > 0 ? "," : "")).append(Emitter.continued(p.pos().line(), code));
                previous = p.pos().line();
            } else {
                sb.append((i > 0 ? ", " : "")).append(code);
            }
            i += 1;
        }
        return sb.toString();
    }
    private static String typeParams(List<Decl.TypeParam> params) {
        final var list = typeParamList(params);
        return (list.isEmpty() ? "" : list + " ");
    }
    private static String typeParamList(List<Decl.TypeParam> params) {
        if (params.isEmpty()) {
            return "";
        }
        final var texts = params.stream().map(Nulls.nonNull(p -> p.name() + boundText(p.bound()))).toList();
        return "<" + String.join(", ", texts) + ">";
    }
    private static String boundText(@Nullable TypeRef bound) {
        if (bound == null) {
            return " extends @Nullable Object";
        }
        if (bound.name().equals("Object") && !bound.nullable()) {
            return "";
        }
        return " extends " + javaType(bound);
    }
    private static List<Decl.TypeParam> typeParamsOf(Decl decl) {
        switch (decl) {
            case Decl.DataClass decl$1 -> {
                return decl$1.typeParams();
            }
            case Decl.ClassDecl decl$2 -> {
                return decl$2.typeParams();
            }
            case Decl.Interface decl$3 -> {
                return decl$3.typeParams();
            }
            case Decl.Function decl$4 -> {
                return decl$4.typeParams();
            }
            default -> {
                return List.of();
            }
        }
    }
    private static List<TypeRef> supertypesOf(Decl decl) {
        switch (decl) {
            case Decl.DataClass decl$1 -> {
                return decl$1.supertypes();
            }
            case Decl.ClassDecl decl$2 -> {
                return decl$2.supertypes();
            }
            case Decl.Interface decl$3 -> {
                return decl$3.supertypes();
            }
            default -> {
                return List.of();
            }
        }
    }
    private static String typeList(String keyword, List<TypeRef> types) {
        if (types.isEmpty()) {
            return "";
        }
        final var names = types.stream().map(Nulls.nonNull(t -> javaType(t))).toList();
        return keyword + String.join(", ", names);
    }
    private static boolean isMain(Decl.Function f) {
        return f.name().equals("main") && f.params().isEmpty() && f.returnType() == null && !f.isPrivate();
    }
    private static String visibility(Decl decl) {
        return (decl.isPrivate() ? "private" : "public");
    }
    private static Map<String, String> nameBinding(@Nullable String name, String variable) {
        if (name == null) {
            return Map.of();
        }
        return Map.of(name, variable);
    }
    private static Expr.@Nullable Binary shortCircuitWithLift(Expr condition) {
        if (condition instanceof Expr.Binary condition$1 && (condition$1.op().equals("&&") || condition$1.op().equals("||")) && Lifts.needed(condition$1.right())) {
            return condition$1;
        }
        return null;
    }
    private static Expr.@Nullable Binary andWithLiftedRight(Expr condition) {
        if (condition instanceof Expr.Binary condition$1 && condition$1.op().equals("&&") && Lifts.needed(condition$1.right())) {
            return condition$1;
        }
        return null;
    }
    private static Stmt.@Nullable Block asBlock(@Nullable Stmt elseBranch) {
        if (elseBranch instanceof Stmt.Block elseBranch$1) {
            return elseBranch$1;
        }
        if (elseBranch instanceof Stmt.If elseBranch$2) {
            return new Block(elseBranch$2.pos(), List.of(elseBranch$2), new Pos(blockEnd(elseBranch$2), 1));
        }
        return null;
    }
    private static boolean exits(Stmt stmt) {
        switch (stmt) {
            case Stmt.Return stmt$1 -> {
                return true;
            }
            case Stmt.Jump stmt$2 -> {
                return true;
            }
            case Stmt.Use stmt$3 -> {
                return false;
            }
            case Stmt.ExprStmt stmt$4 -> {
                return stmt$4.expr() instanceof Expr.Throw || whenLeaves(stmt$4.expr());
            }
            case Stmt.Block stmt$5 -> {
                return !stmt$5.statements().isEmpty() && exits(stmt$5.statements().getLast());
            }
            case Stmt.If stmt$6 -> {
                return exitsIf(stmt$6);
            }
            case Stmt.While stmt$7 -> {
                return loopsForever(stmt$7);
            }
            default -> {
                return false;
            }
        }
    }
    private static boolean whenLeaves(Expr e) {
        if (!(e instanceof Expr.When e$1)) {
            return false;
        }
        final var hasElse = e$1.arms().stream().anyMatch(a -> a.conditions().isEmpty());
        final var allLeave = e$1.arms().stream().allMatch(a -> !a.body().isEmpty() && exits(a.body().getLast()));
        return hasElse && allLeave;
    }
    private static boolean exitsIf(Stmt.If ifStmt) {
        final var elseBranch = ifStmt.elseBranch();
        return elseBranch != null && exits(ifStmt.then()) && exits(elseBranch);
    }
    private static boolean loopsForever(Stmt.While loop) {
        final var condition = loop.condition();
        return condition instanceof Expr.Literal condition$1 && condition$1.text().equals("true");
    }
    private static int blockEnd(Stmt.If ifStmt) {
        final var elseBranch = ifStmt.elseBranch();
        if (elseBranch instanceof Stmt.If elseBranch$1) {
            return blockEnd(elseBranch$1);
        }
        if (elseBranch instanceof Stmt.Block elseBranch$2) {
            return elseBranch$2.end().line();
        }
        return ifStmt.then().end().line();
    }
    private static @Nullable Expr singleExpression(Expr.Arm arm) {
        if (arm.block()) {
            return null;
        }
        final var first = arm.body().getFirst();
        if (first instanceof Stmt.ExprStmt first$1) {
            return first$1.expr();
        }
        return null;
    }
    private static List<Expr> argumentsOf(Expr e) {
        if (e instanceof Expr.Call e$1) {
            return e$1.args();
        }
        if (e instanceof Expr.GenericNew e$2) {
            return e$2.args();
        }
        throw new IllegalStateException();
    }
    private static Expr firstExpression(List<Stmt> body) {
        final var first = body.getFirst();
        if (first instanceof Stmt.ExprStmt first$1) {
            return first$1.expr();
        }
        throw new IllegalStateException();
    }
    private static boolean testsObjects(Expr.When w) {
        for (final var arm : w.arms()) {
            for (final var c : arm.conditions()) {
                final var constant = c.constant();
                if (c.type() != null) {
                    return true;
                }
                if (constant != null && (constant.text().startsWith("\"") || Character.isLetter(constant.text().charAt(0)))) {
                    return !constant.text().equals("true") && !constant.text().equals("false");
                }
            }
        }
        return false;
    }
    private static boolean isTypeDecl(Decl d) {
        return d instanceof Decl.DataClass d$1 || d instanceof Decl.Interface d$2 || d instanceof Decl.ClassDecl d$3 || d instanceof Decl.EnumDecl d$4;
    }
    private static boolean isResultType(TypeRef type) {
        return type.name().equals("Result") || type.name().equals("axl.lang.Result");
    }
    private static boolean needsUnreachableDefault(Expr.When w) {
        final var hasElse = w.arms().stream().anyMatch(a -> a.conditions().isEmpty());
        final var testsTypes = w.arms().stream().anyMatch(a -> a.conditions().stream().anyMatch(c -> c.type() != null));
        return !hasElse && !testsTypes;
    }
    private static boolean notSwitchable(String text) {
        if (text.equals("true") || text.equals("false")) {
            return true;
        }
        final var number = text.startsWith("-") || Character.isDigit(text.charAt(0));
        if (!number) {
            return false;
        }
        final var lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("0x")) {
            return lower.endsWith("l");
        }
        return lower.endsWith("l") || lower.endsWith("f") || lower.endsWith("d") || lower.contains(".") || lower.contains("e");
    }
    private static boolean testsType(Expr.Cond c, TypeRef subjectType) {
        final var type = c.type();
        return type != null && type.name().equals(subjectType.name());
    }
    private static List<Expr> armValues(Expr.When w) {
        final var values = new ArrayList<Expr>();
        for (final var arm : w.arms()) {
            final var body = arm.body();
            final var last = (body.isEmpty() ? null : body.getLast());
            if (last instanceof Stmt.ExprStmt last$1 && !(last$1.expr() instanceof Expr.Throw)) {
                values.add(last$1.expr());
            }
        }
        return values;
    }
    private static @Nullable Expr lambdaExpression(List<Stmt> body) {
        if (body.size() != 1) {
            return null;
        }
        final var first = body.getFirst();
        if (first instanceof Stmt.ExprStmt first$1 && !(first$1.expr() instanceof Expr.Throw)) {
            return first$1.expr();
        }
        return null;
    }
    private static boolean isOkCall(Expr e) {
        final var inner = withoutParens(e);
        return inner instanceof Expr.Call inner$1 && inner$1.target() == null && inner$1.name().equals("Ok");
    }
    private static boolean isName(Expr e, String name) {
        return e instanceof Expr.Name e$1 && e$1.name().equals(name);
    }
    private static boolean isNullLiteral(Expr e) {
        return e instanceof Expr.Literal e$1 && e$1.text().equals("null");
    }
    private static boolean hasSafeElvis(Expr e) {
        return e instanceof Expr.Elvis e$1 && Chains.hasSafeStep(e$1.left());
    }
    private static boolean becomesCall(Expr e) {
        return e instanceof Expr.Elvis e$1 || e instanceof Expr.Attempt e$2 || Chains.hasSafeStep(e);
    }
    private static Map<String, String> union(Map<String, String> a, Map<String, String> b) {
        final var result = new HashMap<String, String>(a);
        result.putAll(b);
        return result;
    }
    private static int indexOfFirstSafe(List<Chains.Step> steps) {
        var index = 0;
        while (index < steps.size() && !steps.get(index).safe()) {
            index += 1;
        }
        return index;
    }
    private static String baseName(Chains.Chain chain) {
        final var base = chain.base();
        if (base instanceof Expr.Name base$1) {
            return base$1.name();
        }
        return "";
    }
    private static boolean isTypeName(String name) {
        return Character.isUpperCase(name.charAt(0)) && !name.equals(name.toUpperCase(Locale.ROOT));
    }
    private static int closingParen(String code) {
        var depth = 0;
        var i = 0;
        while (i < code.length()) {
            final var c = code.charAt(i);
            if (c == '(') {
                depth += 1;
            } else if (c == ')') {
                depth -= 1;
                if (depth == 0) {
                    return i;
                }
            }
            i += 1;
        }
        return -1;
    }
    private static String converted(String number, String target) {
        if (target.equals("float") && number.matches("-?[\\d.]+")) {
            return number + "F";
        }
        return "(" + target + ") " + number;
    }
    private static String nameOf(@Nullable TypeRef type) {
        return (type == null ? "" : type.name());
    }
    private static String joined(String head, String separator, String tail, int headLine, int tailLine) {
        if (tailLine > headLine) {
            return head + separator.stripTrailing() + Emitter.continued(tailLine, tail);
        }
        return head + separator + tail;
    }
    private static String parenthesized(String code, Expr source) {
        final var simple = source instanceof Expr.Name source$1 || source instanceof Expr.Call source$2 || source instanceof Expr.Member source$3 || source instanceof Expr.Paren source$4 ||
                source instanceof Expr.GenericNew source$5 ||
                source instanceof Expr.Literal source$6;
        return (simple ? code : "(" + code + ")");
    }
    private static Expr widened(Expr e, @Nullable TypeRef target) {
        final var name = (target == null ? "" : target.name());
        if (!name.equals("Long") && !name.equals("Double")) {
            return e;
        }
        if (e instanceof Expr.Unary e$1 && e$1.op().equals("-")) {
            return new Expr.Unary(e$1.pos(), e$1.op(), widened(e$1.operand(), target));
        }
        if (!(e instanceof Expr.Literal e$2) || !e$2.text().matches("[0-9][0-9_]*")) {
            return e;
        }
        return new Expr.Literal(e$2.pos(), e$2.text() + ((name.equals("Long") ? "L" : ".0")));
    }
    private static String literal(String text) {
        if (!text.contains("\\u") || !(text.startsWith("\"") || text.startsWith("'"))) {
            return text;
        }
        final var sb = new StringBuilder();
        var i = 0;
        while (i < text.length()) {
            final var c = text.charAt(i);
            if (c == '\\' && i + 5 < text.length() && text.charAt(i + 1) == 'u') {
                var end = i + 2;
                while (end < text.length() && text.charAt(end) == 'u') {
                    end += 1;
                }
                sb.append(escaped(Integer.parseInt(text.substring(end, end + 4), 16)));
                i = end + 3;
            } else if (c == '\\') {
                sb.append(c).append(text.charAt(i + 1));
                i += 1;
            } else {
                sb.append(c);
            }
            i += 1;
        }
        return sb.toString();
    }
    private static String escaped(int code) {
        switch (code) {
            case 34 -> {
                return "\\\"";
            }
            case 39 -> {
                return "\\'";
            }
            case 92 -> {
                return "\\\\";
            }
            case 10 -> {
                return "\\n";
            }
            case 13 -> {
                return "\\r";
            }
            case 9 -> {
                return "\\t";
            }
            case 8 -> {
                return "\\b";
            }
            case 12 -> {
                return "\\f";
            }
            default -> {
                return escapedOther(code);
            }
        }
    }
    private static String escapedOther(int code) {
        if (code < 32) {
            return String.format("\\%03o", code);
        }
        if (code > 126) {
            return String.format("\\u%04x", code);
        }
        return String.valueOf(Character.toChars(code));
    }
    private static String negated(String condition) {
        return (condition.matches("[\\w$]+") ? "!" + condition : "!(" + condition + ")");
    }
    private static String unary(String op, String operand) {
        final var merges = !operand.isEmpty() && "+-".indexOf(operand.charAt(0)) >= 0 && !op.equals("!");
        return (merges ? op + " " + operand : op + operand);
    }
    private static final Set<String> MAP_TYPES = Set.of("Map", "HashMap", "TreeMap", "LinkedHashMap", "ConcurrentHashMap",
            "java.util.Map", "java.util.HashMap", "java.util.TreeMap", "java.util.LinkedHashMap", "java.util.concurrent.ConcurrentHashMap");
    public record Pattern(String label, String marker) {}
}
