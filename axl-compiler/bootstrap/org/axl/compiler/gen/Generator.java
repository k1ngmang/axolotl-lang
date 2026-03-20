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
    public final LinkedHashSet<String> declaredFunctions;
    public Emitter em;
    public Map<String, String> smart;
    public final IdentityHashMap<Expr.Is, String> patternVariables;
    public int patternCount;
    public List<String> overridden;
    public String elseLabel;
    public List<Expr> whenValues;
    public int tempCount;
    public int nullCount;
    public int exceptionCount;
    public int copyCount;
    public int listCount;
    public Map<String, String> copies;
    public @Nullable String returnType;
    public final HashMap<String, @Nullable TypeRef> localTypes;
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
        this.patternCount = 0;
        this.overridden = List.of();
        this.elseLabel = "default";
        this.whenValues = List.of();
        this.tempCount = 0;
        this.nullCount = 0;
        this.exceptionCount = 0;
        this.copyCount = 0;
        this.listCount = 0;
        this.copies = Map.of();
        this.returnType = null;
        this.localTypes = new HashMap<String, @Nullable TypeRef>();
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
            if (decl != fileType) {
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
            case Decl.Value $s1 -> {
                if (!probed.contains($s1.name())) {
                    emitValue($s1);
                }
            }
            case Decl.Function $s2 -> {
                emitFunction($s2, true);
            }
            case Decl.ClassDecl $s3 -> {
                emitClass($s3);
            }
            case Decl.EnumDecl $s4 -> {
                em.line($s4.pos().line(), visibility($s4) + " enum " + $s4.name() + " { " +
                        String.join(", ", $s4.constants()) +
                        " }");
            }
            case Decl.DataClass $s5 -> {
                emitDataClass($s5);
            }
            case Decl.Interface $s6 -> {
                emitInterface($s6);
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
    private int newPattern() {
        patternCount += 1;
        return patternCount;
    }
    private @Nullable Decl typeOfFile() {
        for (final var d : file.decls()) {
            if (d.name().equals(file.className()) && (d instanceof Decl.DataClass $s1 || d instanceof Decl.Interface $s2 || d instanceof Decl.ClassDecl $s3)) {
                return d;
            }
        }
        return null;
    }
    private void emitFileType(Decl fileType) {
        final var line = fileType.pos().line();
        if (fileType instanceof Decl.DataClass $s1) {
            em.line(line, "@NullMarked public record " + $s1.name() + "(" + parameters($s1.fields(), line) + ")" +
                    typeList(" implements ", $s1.supertypes()) +
                    " {");
        } else if (fileType instanceof Decl.Interface $s2) {
            em.line(line, "@NullMarked public" + interfaceModifier($s2) + " interface " + $s2.name() +
                    typeList(" extends ", $s2.supertypes()) +
                    " {");
            emitRequiredValues($s2);
        } else if (fileType instanceof Decl.ClassDecl $s3) {
            em.line(line, "@NullMarked public final class " + $s3.name() +
                    typeList(" implements ", $s3.supertypes()) +
                    " {");
            emitClassMembers($s3);
        } else {
            throw new IllegalStateException();
        }
        em.indent();
    }
    private void emitClass(Decl.ClassDecl classDecl) {
        em.line(classDecl.pos().line(), visibility(classDecl) + " static final class " + classDecl.name() +
                typeList(" implements ", classDecl.supertypes()) +
                " {");
        emitClassMembers(classDecl);
        em.line(classDecl.pos().line(), "}");
    }
    private void emitClassMembers(Decl.ClassDecl classDecl) {
        em.indent();
        final var inConstructor = !classDecl.parameters().isEmpty();
        for (final var p : classDecl.parameters()) {
            em.line(p.pos().line(), field(p) + ";");
        }
        for (final var p : classDecl.properties()) {
            resetCounters();
            final var initial = (inConstructor ? "" : " = " + expr(propertyInit(p)));
            em.line(p.pos().line(), field(p) + initial + ";");
        }
        if (inConstructor) {
            emitConstructor(classDecl);
        }
        final var outerOverridden = overridden;
        overridden = Implementations.requiredValues(file, classDecl.supertypes()).stream().map(Nulls.nonNull(m -> m.name())).toList();
        for (final var method : classDecl.methods()) {
            resetCounters();
            emitFunction(method, false);
        }
        overridden = outerOverridden;
        emitAccessors(classDecl);
        em.dedent();
    }
    private void emitConstructor(Decl.ClassDecl classDecl) {
        final var line = classDecl.pos().line();
        final var params = classDecl.parameters().stream().map(Nulls.nonNull(p -> javaType(propertyType(p)) + " " + p.name())).toList();
        em.line(line, "public " + classDecl.name() + "(" + String.join(", ", params) + ") {");
        em.indent();
        for (final var p : classDecl.parameters()) {
            em.line(p.pos().line(), "this." + p.name() + " = " + p.name() + ";");
        }
        for (final var p : classDecl.properties()) {
            resetCounters();
            em.line(p.pos().line(), "this." + p.name() + " = " + expr(propertyInit(p)) + ";");
        }
        em.dedent();
        em.line(line, "}");
    }
    private void emitAccessors(Decl.ClassDecl classDecl) {
        final var written = new HashSet<String>();
        for (final var member : Implementations.requiredValues(file, classDecl.supertypes())) {
            final var property = propertyNamed(classDecl, member.name());
            final var ownMethod = classDecl.methods().stream().anyMatch(m -> m.name().equals(member.name()) && m.params().isEmpty());
            if (property != null && !ownMethod && written.add(member.name())) {
                em.line(property.pos().line(), "@Override public " + javaType(propertyType(property)) + " " +
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
        return visibility + modifier + " " + javaType(propertyType(p)) + " " + p.name();
    }
    private void emitDataClass(Decl.DataClass dataClass) {
        final var line = dataClass.pos().line();
        em.line(line, visibility(dataClass) + " record " + dataClass.name() + "(" + parameters(dataClass.fields(), line) + ")" +
                typeList(" implements ", dataClass.supertypes()) +
                " {}");
    }
    private void emitInterface(Decl.Interface iface) {
        final var line = iface.pos().line();
        final var head = visibility(iface) + interfaceModifier(iface) + " interface " + iface.name() +
                typeList(" extends ", iface.supertypes());
        if (iface.members().isEmpty()) {
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
        em.dedent();
    }
    private String interfaceModifier(Decl.Interface iface) {
        if (iface.isSealed()) {
            return " sealed";
        }
        final var underSealed = file.decls().stream().anyMatch(d -> d instanceof Decl.Interface $s1 && $s1.isSealed() && extendsInterface(iface, $s1.name()));
        return (underSealed ? " non-sealed" : "");
    }
    private boolean extendsInterface(Decl.Interface iface, String parentName) {
        return iface.supertypes().stream().anyMatch(t -> t.name().equals(parentName));
    }
    private void emitValue(Decl.Value value) {
        final var declared = value.type();
        final var type = (declared != null ? javaType(declared) : valueTypes.getOrDefault(value.name(), "Object"));
        final var line = value.pos().line();
        final var head = visibility(value) + " static final " + type + " " + value.name();
        em.line(line, joined(head, " = ", expr(value.init()), line, startLine(value.init())) + ";");
    }
    private void emitProbe() {
        resetCounters();
        em.line(0, "private static void " + JavaTypes.PROBE_METHOD + "() {");
        em.indent();
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Value $s1 && probed.contains($s1.name())) {
                em.line($s1.pos().line(), "var " + $s1.name() + " = " + expr($s1.init()) + ";");
            }
        }
        em.dedent();
        em.line(0, "}");
    }
    private void emitFunction(Decl.Function f, boolean isStatic) {
        final var params = (isStatic && isMain(f) ? "String[] args" : parameters(f.params(), f.pos().line()));
        final var declaredReturn = f.returnType();
        final var resultType = (declaredReturn == null ? "void" : javaType(declaredReturn));
        final var modifier = (isStatic ? " static " : " ");
        final var annotation = (!isStatic && f.params().isEmpty() && overridden.contains(f.name()) ? "@Override " : "");
        em.line(f.pos().line(), annotation + visibility(f) + modifier + resultType + " " + f.name() + "(" + params + ") {");
        returnType = (declaredReturn == null ? null : nullableType(declaredReturn));
        localTypes.clear();
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
    private void emitStatements(List<Stmt> list) {
        for (final var stmt : list) {
            statement(stmt);
        }
    }
    private void statement(Stmt stmt) {
        switch (stmt) {
            case Stmt.Local $s1 -> {
                localStatement($s1);
            }
            case Stmt.Assign $s2 -> {
                assignStatement($s2);
            }
            case Stmt.Return $s3 -> {
                returnStatement($s3);
            }
            case Stmt.If $s4 -> {
                ifStatement($s4);
            }
            case Stmt.While $s5 -> {
                emitWhile($s5);
            }
            case Stmt.For $s6 -> {
                emitFor($s6);
            }
            case Stmt.ExprStmt $s7 -> {
                exprStatement($s7);
            }
            case Stmt.Block $s8 -> {
                em.line($s8.pos().line(), "{");
                em.indent();
                emitStatements($s8.statements());
                em.dedent();
                em.line($s8.end().line(), "}");
            }
        }
    }
    private void localStatement(Stmt.Local local) {
        final var line = local.pos().line();
        final var init = local.init();
        final var declared = local.type();
        final var modifiers = (local.mutable() ? "" : "final ");
        final var typeCode = (declared == null ? "var" : javaType(declared));
        final var whole = withoutParens(init);
        if (whole instanceof Expr.When $s1) {
            emitWhen(line, $s1, modifiers + typeCode + " " + local.name() + " = ", WhenForm.VALUE);
            localTypes.put(local.name(), declared);
            return;
        }
        final var localType = Nulls.or(declared, () -> typeOf(init));
        final var stored = (declared == null ? null : nullableType(declared));
        withCopiesOf(line, Captures.capturedIn(init, analysis, inlinesElvis(init)), () -> {
            final var code = (init instanceof Expr.Try $s2 ? unwrapTry(line, $s2) : valueCode(line, init, stored));
            em.line(line, modifiers + typeCode + " " + local.name() + " = " + code + ";");
        });
        localTypes.put(local.name(), localType);
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
        if (whole instanceof Expr.When $s1) {
            emitWhen(line, $s1, "", WhenForm.RESULT);
            return;
        }
        withCopiesOf(line, Captures.capturedIn(value, analysis, inlinesElvis(value)), () ->
                em.line(line, "return " + valueCode(line, value, returnType) + ";"));
    }
    private void ifStatement(Stmt.If ifStmt) {
        final var split = shortCircuitWithLift(ifStmt.condition());
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
        if (e instanceof Expr.When $s1) {
            emitWhen(line, $s1, "", WhenForm.STATEMENT);
        } else if (e instanceof Expr.Throw $s2) {
            withCopies(line, $s2.value(), () -> em.line(line, "throw " + lifted(line, $s2.value()).code() + ";"));
        } else if (condition != null) {
            final var action = actionOf(e);
            final var thenBlock = new Block(action.pos(), List.of(new ExprStmt(action.pos(), action)), action.pos());
            statement(new If(exprStmt.pos(), condition, thenBlock, null));
        } else if (e instanceof Expr.Try $s3) {
            withCopies(line, $s3.inner(), () -> unwrapTry(line, $s3));
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
            emitIf(inner, "");
            em.line(blockEnd(inner), "}");
            em.dedent();
            return;
        }
        withCopies(line, condition, () -> em.line(line, prefix + "if (" + lifted(line, condition).code() + ") {"));
        narrowed(whenTrue(condition), () -> emitBlock(ifStmt.then()));
        final var otherwise = whenFalse(condition);
        final var elseBranch = ifStmt.elseBranch();
        if (elseBranch instanceof Stmt.If $s1) {
            narrowed(otherwise, () -> emitIf($s1, "} else "));
        } else if (elseBranch instanceof Stmt.Block $s2) {
            em.line($s2.pos().line(), "} else {");
            narrowed(otherwise, () -> emitBlock($s2));
        }
    }
    private boolean splitsCondition(Stmt.If ifStmt) {
        final var condition = ifStmt.condition();
        final var simpleAnd = ifStmt.elseBranch() == null && condition instanceof Expr.Binary $s1 && $s1.op().equals("&&");
        if (simpleAnd || !Captures.capturedIn(condition, analysis).isEmpty()) {
            return false;
        }
        return shortCircuitWithLift(condition) != null;
    }
    private void shortCircuitIf(Stmt.If ifStmt, Expr.Binary b) {
        final var line = ifStmt.pos().line();
        final var and = b.op().equals("&&");
        final var otherwise = asBlock(ifStmt.elseBranch());
        final Stmt.@Nullable Block inside = (and ? ifStmt.then() : otherwise);
        final Stmt.@Nullable Block after = (and ? otherwise : ifStmt.then());
        em.line(line, "do {");
        em.indent();
        final var left = lifted(line, b.left()).code();
        em.line(line, "if (" + ((and ? left : negated(left))) + ") {");
        em.indent();
        final var right = lifted(line, b.right()).code();
        em.line(line, "if (" + ((and ? right : negated(right))) + ") {");
        if (inside != null) {
            emitBlock(inside);
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
    private Map<String, String> narrowedAfter(Stmt.If ifStmt) {
        final var thenExits = exits(ifStmt.then());
        final var elseBranch = ifStmt.elseBranch();
        final var elseExits = elseBranch != null && exits(elseBranch);
        if (thenExits && !elseExits) {
            return whenFalse(ifStmt.condition());
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
        em.line(line, "if (" + negated(lifted(line, b.right()).code()) + ") break;");
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
                final var name = "$e" + newPattern();
                em.line(line, "final var " + name + " = " + lifted(line, w.subject()).code() + ";");
                subject.add(name);
            } else {
                subject.add(lifted(line, w.subject()).code());
            }
        });
        final var subjectCode = subject.getFirst();
        final var narrowedName = Nulls.or(bindingName, () -> narrowableName(withoutParens(w.subject())));
        final var whole = nameBinding(bindingName, subjectCode);
        final var mayBeNull = subjectType != null && subjectType.nullable();
        final var selector = (byIndex ? armIndex(subjectCode, w, mayBeNull) : subjectCode);
        em.line(line, head + "switch (" + selector + ") {");
        em.indent();
        final var outerValues = whenValues;
        final var outerElse = elseLabel;
        whenValues = (form == WhenForm.VALUE ? armValues(w) : List.of());
        elseLabel = elseLabelOf(w, subjectType, byIndex);
        var i = 0;
        while (i < w.arms().size()) {
            final var arm = w.arms().get(i);
            final var index = (byIndex ? i : -1);
            narrowed(whole, () -> emitArm(arm, narrowedName, form, index));
            i += 1;
        }
        whenValues = outerValues;
        elseLabel = outerElse;
        if (form == WhenForm.RESULT && needsUnreachableDefault(w)) {
            em.line(w.end().line(), "default -> throw new IllegalStateException();");
        }
        em.dedent();
        em.line(w.end().line(), (form == WhenForm.VALUE ? "};" : "}"));
    }
    private @Nullable String narrowableName(Expr subject) {
        if (subject instanceof Expr.Name $s1 && neverChanges($s1)) {
            return $s1.name();
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
        if (mayBeNull) {
            sb.append(subject).append(" == null ? -1 : ");
        }
        var i = 0;
        while (i < w.arms().size()) {
            final var conditions = w.arms().get(i).conditions();
            if (!conditions.isEmpty()) {
                final var tests = conditions.stream().map(Nulls.nonNull(c -> subject + " == " + literal(constantOf(c).text()))).toList();
                sb.append(String.join(" || ", tests)).append(" ? ").append(i).append(" : ");
            }
            i += 1;
        }
        return sb.append("-1").toString();
    }
    private String elseLabelOf(Expr.When w, @Nullable TypeRef subjectType, boolean byIndex) {
        if (byIndex || subjectType != null && !subjectType.nullable()) {
            return "default";
        }
        if (subjectType == null && !testsObjects(w)) {
            return "default";
        }
        if (subjectType == null) {
            return "case null, default";
        }
        final var covered = w.arms().stream().anyMatch(a -> a.conditions().stream().anyMatch(c -> testsType(c, subjectType)));
        return (covered ? "case null" : "case null, default");
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
                final var variable = "$s" + newPattern();
                final var narrowing = nameBinding(narrowedName, variable);
                emitBranch(arm, "case " + javaType(conditionType(condition)) + " " + variable, narrowing, form);
            }
        }
    }
    private void emitBranch(Expr.Arm arm, String label, Map<String, String> narrowing, WhenForm form) {
        final var line = arm.pos().line();
        final var single = singleExpression(arm);
        if (form == WhenForm.VALUE && single != null) {
            if (single instanceof Expr.Throw $s1 && !Lifts.needed($s1.value())) {
                em.line(line, label + " -> throw " + narrowedValue(narrowing, () -> expr($s1.value())) + ";");
                return;
            }
            if (!Lifts.needed(single)) {
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
            if (form == WhenForm.VALUE && last && stmt instanceof Stmt.ExprStmt $s1 && !($s1.expr() instanceof Expr.Throw)) {
                final var code = lifted($s1.pos().line(), $s1.expr()).code();
                em.line($s1.pos().line(), "yield " + boxedBranch(code, $s1.expr(), whenValues) + ";");
            } else {
                statement(stmt);
            }
            i += 1;
        }
    }
    private void emitFor(Stmt.For loop) {
        final var line = loop.pos().line();
        withCopies(line, loop.iterable(), () ->
                em.line(line, "for (final var " + loop.name() + " : " + lifted(line, loop.iterable()).code() + ") {"));
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
        final var call = (e instanceof Expr.Call $s1 || e instanceof Expr.GenericNew $s2 || e instanceof Expr.Try $s3 || e instanceof Expr.Pipe $s4) && code.endsWith(")");
        em.line(line, (call ? code + ";" : "final var " + newTemp() + " = " + code + ";"));
    }
    private String valueCode(int line, Expr e, @Nullable String stored) {
        if (e instanceof Expr.Elvis $s1 && Lifts.needed($s1.right())) {
            return elvisChain(line, $s1, stored).code();
        }
        if (Lifts.needed(e)) {
            return lifted(line, e).code();
        }
        if (!inlinesElvis(e) || !(e instanceof Expr.Elvis $s2)) {
            return expr(e);
        }
        final var temp = newTemp();
        em.line(line, "final var " + temp + " = " + expr($s2.left()) + ";");
        return "java.util.Objects.nonNull(" + temp + ") ? " + temp + " : " + expr($s2.right());
    }
    private boolean widens(Expr number, @Nullable TypeRef left) {
        if (left == null) {
            return true;
        }
        final var type = TypeInference.typeOf(file, number);
        return type != null && WIDENING.contains(type.name()) && WIDENING.indexOf(type.name()) <= WIDENING.indexOf(left.name());
    }
    private boolean inlinesElvis(Expr e) {
        if (!options.inlineElvis()) {
            return false;
        }
        return e instanceof Expr.Elvis $s1 && !Lifts.needed($s1.right()) && !Lifts.needed($s1) && !mayMixNumbers($s1);
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
        final var inner = withoutParens(e);
        if (inner instanceof Expr.Name $s1 && localTypes.containsKey($s1.name())) {
            return localTypes.get($s1.name());
        }
        return TypeInference.typeOf(file, inner);
    }
    private String elvisCode(Expr.Elvis elvis, String left) {
        final var right = elvis.right();
        final var leftType = typeOf(elvis.left());
        final var isNumber = Lifts.isNumber(right);
        final var number = isNumber && widens(right, leftType) && (!Chains.hasSafeStep(elvis.left()) || left.matches("\\$t\\d+"));
        final @Nullable String narrow = (isNumber ? NARROW_NUMBERS.get(nameOf(leftType)) : null);
        final var fallback = (number ? expr(right) : lambdaFallback(right, narrow));
        return joined("Nulls.or(" + left, ", ", fallback, startLine(elvis.left()), startLine(right)) + ")";
    }
    private String lambdaFallback(Expr right, @Nullable String narrow) {
        if (narrow != null) {
            return "() -> " + converted(expr(right), narrow);
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
            case Expr.Literal $s1 -> {
                return literal($s1.text());
            }
            case Expr.Name $s2 -> {
                return nameCode($s2, inLambda);
            }
            case Expr.Member $s3 -> {
                return chainCode(Chains.flatten($s3), inLambda);
            }
            case Expr.Call $s4 -> {
                return callExpr($s4, inLambda);
            }
            case Expr.GenericNew $s5 -> {
                return genericNew($s5, argsCode($s5.args(), inLambda, $s5.pos().line()));
            }
            case Expr.MethodRef $s6 -> {
                return exprIn($s6.target(), inLambda) + "::" + $s6.name();
            }
            case Expr.NotNull $s7 -> {
                return "java.util.Objects.requireNonNull(" + exprIn($s7.target(), inLambda) + ")";
            }
            case Expr.Unary $s8 -> {
                return unary($s8.op(), exprIn($s8.operand(), inLambda));
            }
            case Expr.Binary $s9 -> {
                return binaryCode($s9, inLambda);
            }
            case Expr.Is $s10 -> {
                return typeCheck($s10, inLambda);
            }
            case Expr.Conditional $s11 -> {
                return conditionalCode($s11, inLambda);
            }
            case Expr.Throw $s12 -> {
                throw new IllegalStateException("throw at " + $s12.pos() + " cannot be an expression");
            }
            case Expr.Elvis $s13 -> {
                return elvisCode($s13, exprIn($s13.left(), inLambda));
            }
            case Expr.Paren $s14 -> {
                return parenCode($s14, inLambda);
            }
            case Expr.Attempt $s15 -> {
                return attemptCode($s15);
            }
            case Expr.Lambda $s16 -> {
                return lambdaCode($s16);
            }
            case Expr.Pipe $s17 -> {
                return pipeCode(exprIn($s17.source(), inLambda), $s17.source(), $s17.stages(), a -> exprIn(a, inLambda));
            }
            case Expr.Try $s18 -> {
                throw new IllegalStateException("try at " + $s18.pos() + " should have been lifted");
            }
            case Expr.When $s19 -> {
                throw new IllegalStateException("when at " + $s19.pos() + " should have been lifted");
            }
        }
    }
    private String nameCode(Expr.Name n, boolean inLambda) {
        final var known = smart.get(n.name());
        if (known != null) {
            return known;
        }
        if (inLambda && analysis.mutableRefs().contains(n)) {
            return copies.getOrDefault(n.name(), n.name());
        }
        return n.name();
    }
    private String callExpr(Expr.Call c, boolean inLambda) {
        final var target = c.target();
        final var path = (target == null ? null : packagePath(target));
        if (path != null && isTypeName(c.name())) {
            return "new " + path + "." + c.name() + "(" + argsCode(c.args(), inLambda, c.pos().line()) + ")";
        }
        if (target != null) {
            return chainCode(Chains.flatten(c), inLambda);
        }
        return callCode(c, argsCode(c.args(), inLambda, c.pos().line()));
    }
    private String binaryCode(Expr.Binary b, boolean inLambda) {
        final var left = exprIn(b.left(), inLambda);
        final var right = narrowedValue(rightNarrowing(b), () -> exprIn(b.right(), inLambda));
        return joined(left, " " + b.op() + " ", right, startLine(b.left()), startLine(b.right()));
    }
    private String conditionalCode(Expr.Conditional c, boolean inLambda) {
        return conditional(exprIn(c.condition(), inLambda), c, inLambda);
    }
    private String conditional(String condition, Expr.Conditional c, boolean inLambda) {
        final var branches = List.of(c.then(), c.otherwise());
        final var thenCode = narrowedValue(whenTrue(c.condition()), () -> exprIn(c.then(), inLambda));
        final var otherwise = narrowedValue(whenFalse(c.condition()), () -> exprIn(c.otherwise(), inLambda));
        return "(" + condition + " ? " + boxedBranch(thenCode, c.then(), branches) + " : " +
                boxedBranch(otherwise, c.otherwise(), branches) +
                ")";
    }
    private String boxedBranch(String code, Expr branch, List<Expr> branches) {
        final var type = primitiveType(branch);
        final var noneNull = branches.stream().noneMatch(b -> mayBeNull(b));
        if (type == null || noneNull) {
            return code;
        }
        return boxClass(type, branches) + ".valueOf(" + code + ")";
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
        return !(inner instanceof Expr.Binary $s1) && !(inner instanceof Expr.Unary $s2) && !(inner instanceof Expr.Literal $s3) && !(inner instanceof Expr.Is $s4);
    }
    private String boxClass(String type, List<Expr> branches) {
        var widest = type;
        for (final var b : branches) {
            final var other = typeOf(b);
            if (other != null && WIDENING.contains(type) && WIDENING.indexOf(other.name()) > WIDENING.indexOf(widest)) {
                widest = other.name();
            }
        }
        return BOXED_NAMES.getOrDefault(widest, "Object");
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
        return "Result.attempt(() -> " + exprIn(a.body(), true) + ", " + error + " -> " + exprIn(a.handler(), true) + ")";
    }
    private int nextException() {
        exceptionCount += 1;
        return exceptionCount;
    }
    private String typeCheck(Expr.Is check, boolean inLambda) {
        final var type = javaType(check.type());
        final var target = check.target();
        if (!(target instanceof Expr.Name $s1) || !neverChanges($s1)) {
            final var plainTest = exprIn(target, inLambda) + " instanceof " + type;
            return (check.negated() ? "!(" + plainTest + ")" : plainTest);
        }
        final var variable = "$s" + newPattern();
        patternVariables.put(check, variable);
        final var test = exprIn($s1, inLambda) + " instanceof " + type + " " + variable;
        return (check.negated() ? "!(" + test + ")" : test);
    }
    private String liftedTypeCheck(int line, Expr.Is check) {
        final var test = lifted(line, check.target()).code() + " instanceof " + boxedType(check.type());
        return (check.negated() ? "!(" + test + ")" : test);
    }
    private Map<String, String> whenTrue(Expr condition) {
        if (condition instanceof Expr.Is $s1) {
            return ($s1.negated() ? Map.of() : checkedName($s1));
        }
        if (condition instanceof Expr.Paren $s2) {
            return whenTrue($s2.inner());
        }
        if (condition instanceof Expr.Unary $s3 && $s3.op().equals("!")) {
            return whenFalse($s3.operand());
        }
        if (condition instanceof Expr.Binary $s4 && $s4.op().equals("&&")) {
            return union(whenTrue($s4.left()), whenTrue($s4.right()));
        }
        return Map.of();
    }
    private Map<String, String> whenFalse(Expr condition) {
        if (condition instanceof Expr.Is $s1) {
            return ($s1.negated() ? checkedName($s1) : Map.of());
        }
        if (condition instanceof Expr.Paren $s2) {
            return whenFalse($s2.inner());
        }
        if (condition instanceof Expr.Unary $s3 && $s3.op().equals("!")) {
            return whenTrue($s3.operand());
        }
        if (condition instanceof Expr.Binary $s4 && $s4.op().equals("||")) {
            return union(whenFalse($s4.left()), whenFalse($s4.right()));
        }
        return Map.of();
    }
    private Map<String, String> checkedName(Expr.Is check) {
        final var variable = patternVariables.get(check);
        final var target = check.target();
        if (variable != null && target instanceof Expr.Name $s1) {
            return Map.of($s1.name(), variable);
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
        if (c.name().equals("Ok") || c.name().equals("Err")) {
            return "new " + c.name() + "<>(" + args + ")";
        }
        if (Character.isUpperCase(c.name().charAt(0)) && !declaredFunctions.contains(c.name())) {
            return "new " + c.name() + "(" + args + ")";
        }
        return c.name() + "(" + args + ")";
    }
    private String chainCode(Chains.Chain chain, boolean inLambda) {
        return chainFrom(exprIn(chain.base(), inLambda), chain.steps(), 0, inLambda, startLine(chain.base()));
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
        final var previous = (index == 0 ? Integer.valueOf(baseLine) : steps.get(index - 1).line());
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
        if (arg instanceof Expr.Paren $s1) {
            return "(" + argument($s1.inner(), inLambda) + ")";
        }
        if (arg instanceof Expr.NotNull $s2) {
            return "java.util.Objects.requireNonNull(" + argument($s2.target(), inLambda) + ")";
        }
        if (Chains.hasSafeStep(arg) || hasSafeElvis(arg)) {
            return "Nulls.typed(" + exprIn(arg, inLambda) + ").value()";
        }
        return exprIn(arg, inLambda);
    }
    private String lambdaCode(Expr.Lambda l) {
        final var params = lambdaParams(l);
        final var body = l.body();
        final var outerCopies = copies;
        copies = Map.of();
        final var single = lambdaExpression(body);
        if (single != null) {
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
            case Expr.Try $s1 -> {
                return new Lifted(unwrapTry(line, $s1), true);
            }
            case Expr.When $s2 -> {
                return liftedWhen(line, $s2);
            }
            case Expr.Binary $s3 -> {
                return liftedBinary(line, $s3);
            }
            case Expr.Elvis $s4 -> {
                return liftedElvis(line, $s4);
            }
            case Expr.Unary $s5 -> {
                return new Lifted(unary($s5.op(), lifted(line, $s5.operand()).code()), false);
            }
            case Expr.Paren $s6 -> {
                return liftedParen(line, $s6);
            }
            case Expr.Call $s7 -> {
                return liftedCall(line, $s7);
            }
            case Expr.Member $s8 -> {
                return chainLifted(line, Chains.flatten($s8));
            }
            case Expr.GenericNew $s9 -> {
                return new Lifted(genericNew($s9, codes(operands(line, $s9.args()))), false);
            }
            case Expr.MethodRef $s10 -> {
                return new Lifted(lifted(line, $s10.target()).code() + "::" + $s10.name(), false);
            }
            case Expr.NotNull $s11 -> {
                return new Lifted("java.util.Objects.requireNonNull(" + lifted(line, $s11.target()).code() + ")", false);
            }
            case Expr.Pipe $s12 -> {
                return pipeLifted(line, $s12);
            }
            case Expr.Conditional $s13 -> {
                return new Lifted(conditional(lifted(line, $s13.condition()).code(), $s13, false), false);
            }
            case Expr.Is $s14 -> {
                return new Lifted(liftedTypeCheck(line, $s14), false);
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
        final var code = joined(ops.get(0).code(), " " + b.op() + " ", ops.get(1).code(), startLine(b.left()), startLine(b.right()));
        return new Lifted(code, false);
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
        return chainLifted(line, Chains.flatten(c));
    }
    private boolean isLocal(Expr target) {
        return target instanceof Expr.Name $s1 && analysis.mutableRefs().contains($s1);
    }
    private boolean neverChanges(Expr.Name name) {
        return analysis.stableNames().contains(name) && !analysis.mutableRefs().contains(name);
    }
    private String assignTarget(int line, Expr target, boolean valueLifts) {
        if (!(target instanceof Expr.Member $s1) || (!valueLifts && !Lifts.needed($s1))) {
            return expr(target);
        }
        final var receiver = lifted(line, $s1.target());
        var code = receiver.code();
        if (valueLifts && !receiver.pure()) {
            code = newTemp();
            em.line(line, "final var " + code + " = " + receiver.code() + ";");
        }
        return code + "." + $s1.name();
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
            }
            result.add(operand);
            k += 1;
        }
        return result;
    }
    private boolean takesTypeFromTarget(Expr e) {
        if (e instanceof Expr.Call $s1) {
            final var target = $s1.target();
            if (target == null) {
                return $s1.name().equals("Ok") || $s1.name().equals("Err");
            }
            return target instanceof Expr.Name $s2 && analysis.staticNames().contains($s2) && TARGET_TYPED_FACTORIES.contains($s1.name());
        }
        if (e instanceof Expr.GenericNew $s3) {
            return $s3.typeArgs().isEmpty();
        }
        return e instanceof Expr.MethodRef $s4;
    }
    private Lifted withArgumentsFirst(int line, Expr e) {
        if (e instanceof Expr.MethodRef $s1) {
            return methodRefFirst(line, $s1);
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
        if (e instanceof Expr.Call $s1) {
            final var target = $s1.target();
            if (target == null) {
                return callCode($s1, argsText);
            }
            return expr(target) + "." + $s1.name() + "(" + argsText + ")";
        }
        if (e instanceof Expr.GenericNew $s2) {
            return genericNew($s2, argsText);
        }
        throw new IllegalStateException();
    }
    private String codes(List<Lifted> list) {
        final var texts = list.stream().map(Nulls.nonNull(l -> l.code())).toList();
        return String.join(", ", texts);
    }
    private boolean isPure(Expr e) {
        switch (e) {
            case Expr.Literal $s1 -> {
                return true;
            }
            case Expr.Name $s2 -> {
                return analysis.stableNames().contains($s2) || analysis.staticNames().contains($s2);
            }
            case Expr.Lambda $s3 -> {
                return true;
            }
            case Expr.MethodRef $s4 -> {
                return isPure($s4.target()) || isPath($s4.target());
            }
            case Expr.Paren $s5 -> {
                return isPure($s5.inner());
            }
            case Expr.Call $s6 -> {
                return isPureResult($s6);
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
            case Expr.Name $s1 -> {
                return analysis.staticNames().contains($s1);
            }
            case Expr.Member $s2 -> {
                return !$s2.safe() && isPath($s2.target());
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
        if (stored == null && right instanceof Expr.Try $s1 && !Lifts.needed($s1.inner())) {
            return elvisTry(line, left, $s1);
        }
        final var temp = newTemp();
        em.line(line, ((stored == null ? "var" : stored)) + " " + temp + " = " + left + ";");
        em.line(line, "if (java.util.Objects.isNull(" + temp + ")) {");
        em.indent();
        em.line(line, temp + " = " + lifted(line, right).code() + ";");
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
        return new Lifted("java.util.Objects.nonNull(" + value + ") ? " + value +
                " : Result.unwrap(java.util.Objects.requireNonNull(" +
                result +
                "))",
                false);
    }
    private Lifted chainLifted(int line, Chains.Chain chain) {
        final var first = indexOfFirstSafe(chain.steps());
        final var prefix = liftedPrefix(line, chain, first);
        return new Lifted(chainFrom(prefix, chain.steps(), first, false, startLine(chain.base())), false);
    }
    private @Nullable String packagePath(Expr target) {
        if (target instanceof Expr.Name $s1 && analysis.staticNames().contains($s1) && Character.isLowerCase($s1.name().charAt(0))) {
            return $s1.name();
        }
        if (target instanceof Expr.Member $s2 && !$s2.safe() && Character.isLowerCase($s2.name().charAt(0))) {
            final var path = packagePath($s2.target());
            return (path == null ? null : path + "." + $s2.name());
        }
        return null;
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
        return e instanceof Expr.Name $s1 && analysis.staticNames().contains($s1);
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
        final var items = (mapResult == 0 ? parenthesized(ops.getFirst().code(), pipe.source()) : pipeCode(ops.getFirst().code(), pipe.source(), stages.subList(0, mapResult), argOf));
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
        em.line(bodyLine, "final var " + temp + " = " + expr(body) + ";");
        em.line(bodyLine, "if (" + temp + ".isErr()) return Result.propagate(" + temp + ");");
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
        for (final var stage : stages) {
            final var stageBody = stage.lambda();
            final var lambdaText = (stageBody == null ? "" : lambdaCode(stageBody));
            if (stage.pos().line() > previousLine) {
                sb.append(Emitter.continued(stage.pos().line(), ""));
                previousLine = stage.pos().line();
            }
            appendStage(sb, stage, lambdaText, arg);
            if (Checker.TERMINAL_STAGES.contains(stage.name())) {
                terminal = true;
            }
        }
        return (terminal ? sb.toString() : sb.append(".toList()").toString());
    }
    private void appendStage(StringBuilder sb, Expr.Stage stage, String lambdaText, Function<Expr, String> arg) {
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
                sb.append(".map(Nulls.nonNull(").append(lambdaText).append("))");
            }
            case null, default -> {
                sb.append('.').append(name).append('(').append(lambdaText).append(')');
            }
        }
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
    private static LinkedHashSet<String> declaredFunctionNames(SourceFile file) {
        final var names = new LinkedHashSet<String>();
        for (final var decl : file.decls()) {
            if (decl instanceof Decl.Function $s1) {
                names.add($s1.name());
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
    private static TypeRef propertyType(Decl.Property p) {
        final var type = p.type();
        if (type == null) {
            throw new IllegalStateException("property " + p.name() + " has no type");
        }
        return type;
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
        if (statement instanceof Expr.Binary $s1 && $s1.op().equals("&&")) {
            return $s1.left();
        }
        if (statement instanceof Expr.Binary $s2 && $s2.op().equals("||")) {
            return new Unary($s2.pos(), "!", new Paren($s2.pos(), $s2.left()));
        }
        if (statement instanceof Expr.Elvis $s3) {
            return new Binary($s3.pos(), "==", $s3.left(), new Literal($s3.pos(), "null"));
        }
        return null;
    }
    private static Expr actionOf(Expr statement) {
        if (statement instanceof Expr.Binary $s1) {
            return $s1.right();
        }
        if (statement instanceof Expr.Elvis $s2) {
            return $s2.right();
        }
        throw new IllegalStateException();
    }
    private static Expr withoutParens(Expr e) {
        return (e instanceof Expr.Paren $s1 ? withoutParens($s1.inner()) : e);
    }
    private static String parameters(List<Expr.Param> params, int declarationLine) {
        final var sb = new StringBuilder();
        var previous = declarationLine;
        var i = 0;
        while (i < params.size()) {
            final var p = params.get(i);
            final var code = javaType(memberType(p)) + " " + p.name();
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
        if (condition instanceof Expr.Binary $s1 && ($s1.op().equals("&&") || $s1.op().equals("||")) && Lifts.needed($s1.right())) {
            return $s1;
        }
        return null;
    }
    private static Expr.@Nullable Binary andWithLiftedRight(Expr condition) {
        if (condition instanceof Expr.Binary $s1 && $s1.op().equals("&&") && Lifts.needed($s1.right())) {
            return $s1;
        }
        return null;
    }
    private static Stmt.@Nullable Block asBlock(@Nullable Stmt elseBranch) {
        if (elseBranch instanceof Stmt.Block $s1) {
            return $s1;
        }
        if (elseBranch instanceof Stmt.If $s2) {
            return new Block($s2.pos(), List.of($s2), new Pos(blockEnd($s2), 1));
        }
        return null;
    }
    private static boolean exits(Stmt stmt) {
        switch (stmt) {
            case Stmt.Return $s1 -> {
                return true;
            }
            case Stmt.ExprStmt $s2 -> {
                return $s2.expr() instanceof Expr.Throw;
            }
            case Stmt.Block $s3 -> {
                return !$s3.statements().isEmpty() && exits($s3.statements().getLast());
            }
            case Stmt.If $s4 -> {
                return exitsIf($s4);
            }
            case Stmt.While $s5 -> {
                return loopsForever($s5);
            }
            default -> {
                return false;
            }
        }
    }
    private static boolean exitsIf(Stmt.If ifStmt) {
        final var elseBranch = ifStmt.elseBranch();
        return elseBranch != null && exits(ifStmt.then()) && exits(elseBranch);
    }
    private static boolean loopsForever(Stmt.While loop) {
        final var condition = loop.condition();
        return condition instanceof Expr.Literal $s1 && $s1.text().equals("true");
    }
    private static int blockEnd(Stmt.If ifStmt) {
        final var elseBranch = ifStmt.elseBranch();
        if (elseBranch instanceof Stmt.If $s1) {
            return blockEnd($s1);
        }
        if (elseBranch instanceof Stmt.Block $s2) {
            return $s2.end().line();
        }
        return ifStmt.then().end().line();
    }
    private static @Nullable Expr singleExpression(Expr.Arm arm) {
        if (arm.block()) {
            return null;
        }
        final var first = arm.body().getFirst();
        if (first instanceof Stmt.ExprStmt $s1) {
            return $s1.expr();
        }
        return null;
    }
    private static List<Expr> argumentsOf(Expr e) {
        if (e instanceof Expr.Call $s1) {
            return $s1.args();
        }
        if (e instanceof Expr.GenericNew $s2) {
            return $s2.args();
        }
        throw new IllegalStateException();
    }
    private static Expr firstExpression(List<Stmt> body) {
        final var first = body.getFirst();
        if (first instanceof Stmt.ExprStmt $s1) {
            return $s1.expr();
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
            if (last instanceof Stmt.ExprStmt $s1 && !($s1.expr() instanceof Expr.Throw)) {
                values.add($s1.expr());
            }
        }
        return values;
    }
    private static @Nullable Expr lambdaExpression(List<Stmt> body) {
        if (body.size() != 1) {
            return null;
        }
        final var first = body.getFirst();
        if (first instanceof Stmt.ExprStmt $s1 && !($s1.expr() instanceof Expr.Throw)) {
            return $s1.expr();
        }
        return null;
    }
    private static boolean hasSafeElvis(Expr e) {
        return e instanceof Expr.Elvis $s1 && Chains.hasSafeStep($s1.left());
    }
    private static boolean becomesCall(Expr e) {
        return e instanceof Expr.Elvis $s1 || e instanceof Expr.Attempt $s2 || Chains.hasSafeStep(e);
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
        if (base instanceof Expr.Name $s1) {
            return $s1.name();
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
    private static String joinedArgs(List<Expr> args, List<String> codes, int line) {
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
    private static String joined(String head, String separator, String tail, int headLine, int tailLine) {
        if (tailLine > headLine) {
            return head + separator.stripTrailing() + Emitter.continued(tailLine, tail);
        }
        return head + separator + tail;
    }
    private static int startLine(Expr e) {
        switch (e) {
            case Expr.Member $s1 -> {
                return startLine($s1.target());
            }
            case Expr.Call $s2 -> {
                return callStartLine($s2);
            }
            case Expr.Binary $s3 -> {
                return startLine($s3.left());
            }
            case Expr.Elvis $s4 -> {
                return startLine($s4.left());
            }
            case Expr.Pipe $s5 -> {
                return startLine($s5.source());
            }
            case Expr.MethodRef $s6 -> {
                return startLine($s6.target());
            }
            case Expr.NotNull $s7 -> {
                return startLine($s7.target());
            }
            default -> {
                return e.pos().line();
            }
        }
    }
    private static int callStartLine(Expr.Call c) {
        final var target = c.target();
        if (target != null) {
            return startLine(target);
        }
        return c.pos().line();
    }
    private static String parenthesized(String code, Expr source) {
        final var simple = source instanceof Expr.Name $s1 || source instanceof Expr.Call $s2 || source instanceof Expr.Member $s3 || source instanceof Expr.Paren $s4 ||
                source instanceof Expr.GenericNew $s5 ||
                source instanceof Expr.Literal $s6;
        return (simple ? code : "(" + code + ")");
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
}
