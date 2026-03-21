package org.axl.intellij.actions;

import com.intellij.codeInsight.ExceptionUtil;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiBlockStatement;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiCaseLabelElement;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiConditionalExpression;
import com.intellij.psi.PsiDeclarationStatement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiForeachStatement;
import com.intellij.psi.PsiIfStatement;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiImportStaticStatement;
import com.intellij.psi.PsiInstanceOfExpression;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiParenthesizedExpression;
import com.intellij.psi.PsiPatternVariable;
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.PsiPrefixExpression;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiRecordComponent;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiSwitchBlock;
import com.intellij.psi.PsiSwitchExpression;
import com.intellij.psi.PsiSwitchLabeledRuleStatement;
import com.intellij.psi.PsiSwitchStatement;
import com.intellij.psi.PsiThisExpression;
import com.intellij.psi.PsiThrowStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeTestPattern;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.PsiUnaryExpression;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.PsiWhileStatement;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.Translator;

/**
 * Converts the subset of Java that maps one to one: a class with static methods and fields and
 * nested records, enums, interfaces and classes, or one record, enum, interface or class. Bodies
 * use locals, `if`, `while`, `for` over a collection, `return`, `throw`, `switch` with arrows,
 * `instanceof` and `?:`.
 */
final class JavaToAxolotl {

    /** Thrown for code outside the subset. */
    static final class Unsupported extends RuntimeException {
        Unsupported(String message) {
            super(message);
        }
    }

    private static final Map<String, String> PRIMITIVES = Map.of("int", "Int", "long", "Long", "boolean", "Boolean",
            "double", "Double", "void", "");
    private static final Map<String, String> BOXED = Map.of("java.lang.Integer", "Int?", "java.lang.Long", "Long?",
            "java.lang.Boolean", "Boolean?", "java.lang.Double", "Double?");
    private static final Set<String> KEYWORDS = Set.of("package", "import", "fn", "val", "var", "private", "return",
            "if", "else", "while", "for", "is", "when", "class", "interface", "sealed", "try", "attempt", "true", "false",
            "null");
    private static final Set<String> OPERATORS = Set.of("+", "-", "*", "/", "%", "==", "!=", "<", "<=", ">", ">=",
            "&&", "||");
    private static final Set<String> ASSIGNMENTS = Set.of("=", "+=", "-=", "*=", "/=", "%=");
    // Nullness annotations other than `Nullable` say what Axolotl code says anyway.
    private static final Set<String> NULLNESS = Set.of("Nullable", "NonNull", "NotNull", "Nonnull", "NullMarked");

    private final StringBuilder out = new StringBuilder();
    private PsiJavaFile source;
    private String packageName;
    // Pattern variables, which Axolotl writes as the tested name: `o instanceof String s` makes `s` read `o`.
    private final Map<PsiVariable, String> renamed = new HashMap<>();
    // The nesting of the statement being converted, for the arms of a `when` inside an expression.
    private int depth;
    // Imported types by simple name: the file's imports and those the converted types need.
    private final Map<String, String> importedTypes = new HashMap<>();
    private final List<String> addedImports = new ArrayList<>();
    private final Set<PsiComment> keptComments = new HashSet<>();

    static String convert(PsiJavaFile file) {
        PsiClass[] classes = file.getClasses();
        if (classes.length != 1) {
            throw new Unsupported("the file must contain exactly one class");
        }
        JavaToAxolotl converter = new JavaToAxolotl();
        String axl = converter.file(file, classes[0]);
        converter.rejectLostComments(file);
        rejectErrors(file, axl);
        return axl;
    }

    /** Comments in places Axolotl has no counterpart for, such as inside an expression. */
    private void rejectLostComments(PsiJavaFile file) {
        for (PsiComment comment : PsiTreeUtil.findChildrenOfType(file, PsiComment.class)) {
            if (!keptComments.contains(comment)) {
                throw new Unsupported("a comment cannot be kept: " + comment.getText().lines().findFirst().orElse(""));
            }
        }
    }

    /** What the conversion cannot see, such as reserved names and the order of values, the translator checks. */
    private static void rejectErrors(PsiJavaFile file, String axl) {
        String name = file.getName().replaceFirst("\\.java$", ".axl");
        String sourcePath = file.getPackageName().replace('.', '/') + "/" + name;
        for (AxlDiagnostic diagnostic : Translator.translate(sourcePath, name, axl).diagnostics()) {
            if (diagnostic.isError()) {
                throw new Unsupported(diagnostic.message());
            }
        }
    }

    static boolean isConvertible(PsiJavaFile file) {
        try {
            convert(file);
            return true;
        } catch (Unsupported e) {
            return false;
        }
    }

    private String file(PsiJavaFile file, PsiClass cls) {
        if (cls.hasTypeParameters() || !cls.isInterface() && explicitlyExtends(cls)) {
            throw new Unsupported("generic classes and subclasses have no Axolotl form");
        }
        if (file.getPackageName().isEmpty()) {
            throw new Unsupported("classes in the default package have no Axolotl form");
        }
        if (cls.getInitializers().length > 0) {
            throw new Unsupported("initializer blocks have no Axolotl form");
        }
        nullable(cls);
        source = file;
        packageName = file.getPackageName();
        // Comments of the file and of the class describe the .axl file, so they go first.
        for (PsiElement child : file.getChildren()) {
            if (child instanceof PsiComment comment) {
                keep(comment);
                out.append(comment.getText()).append("\n");
            }
        }
        for (PsiElement child = cls.getFirstChild(); child != cls.getLBrace(); child = child.getNextSibling()) {
            if (child instanceof PsiComment comment) {
                keep(comment);
                out.append(comment.getText()).append("\n");
            }
        }
        out.append("package ").append(qualifiedName(packageName)).append("\n");
        if (file.getImportList() != null) {
            for (PsiImportStatement imp : file.getImportList().getImportStatements()) {
                if (imp.isOnDemand()) {
                    throw new Unsupported("on-demand imports have no Axolotl form");
                }
                if (NULLNESS.contains(imp.getQualifiedName().substring(imp.getQualifiedName().lastIndexOf('.') + 1))) {
                    // The generated Java has its own: another `Nullable` would replace JSpecify's.
                    continue;
                }
                out.append("import ").append(qualifiedName(imp.getQualifiedName())).append("\n");
                importedTypes.put(imp.getQualifiedName().substring(imp.getQualifiedName().lastIndexOf('.') + 1),
                        imp.getQualifiedName());
            }
            for (PsiImportStaticStatement imp : file.getImportList().getImportStaticStatements()) {
                PsiClass target = imp.resolveTargetClass();
                if (imp.isOnDemand() || target == null) {
                    throw new Unsupported("on-demand static imports have no Axolotl form");
                }
                // An overloaded method does not resolve to a single element.
                String keyword = target.findMethodsByName(imp.getReferenceName(), true).length > 0 ? "fn" : "val";
                out.append("import ").append(keyword).append(" ").append(qualifiedName(target.getQualifiedName()))
                        .append(".").append(name(imp.getReferenceName())).append("\n");
            }
        }
        int importsEnd = out.length();
        if (isStaticHolder(cls)) {
            staticMembers(cls, true);
        } else {
            typeDeclaration(cls);
            staticMembers(cls, false);
        }
        out.insert(importsEnd, addedImports.stream().map(name -> "import " + qualifiedName(name) + "\n")
                .collect(Collectors.joining()));
        return out.toString();
    }

    /** A class that only holds static members and nested types, which the file's class becomes. */
    private static boolean isStaticHolder(PsiClass cls) {
        if (cls.isInterface() || cls.isEnum() || cls.isRecord() || cls.getImplementsListTypes().length > 0) {
            return false;
        }
        boolean instanceFields = Arrays.stream(cls.getFields()).anyMatch(f -> !f.hasModifierProperty(PsiModifier.STATIC));
        boolean instanceMethods = Arrays.stream(cls.getMethods())
                .anyMatch(m -> !m.hasModifierProperty(PsiModifier.STATIC) && !isPrivateConstructor(m));
        return !instanceFields && !instanceMethods;
    }

    /** A constructor that only prevents instances. */
    private static boolean isPrivateConstructor(PsiMethod method) {
        return method.isConstructor() && method.getParameterList().isEmpty() && method.getBody() != null
                && method.getBody().isEmpty();
    }

    /**
     * The static fields and methods of a class, which become top-level values and functions; with
     * nested, also its nested types. The members of a type the file is named after stay in it.
     */
    private void staticMembers(PsiClass cls, boolean nested) {
        for (PsiElement member = cls.getLBrace(); member != null; member = member.getNextSibling()) {
            switch (member) {
                case PsiField field when field.hasModifierProperty(PsiModifier.STATIC) && !(field instanceof PsiEnumConstant) ->
                        field(field, cls);
                case PsiMethod method when method.hasModifierProperty(PsiModifier.STATIC) || isPrivateConstructor(method) && nested ->
                        function(method, 0);
                case PsiClass type when nested -> typeDeclaration(type);
                case PsiComment comment when nested -> {
                    keep(comment);
                    out.append("\n").append(comment.getText()).append("\n");
                }
                default -> { }
            }
        }
    }

    private void typeDeclaration(PsiClass type) {
        if (type.hasTypeParameters() || type.getInitializers().length > 0 || !type.isInterface() && explicitlyExtends(type)) {
            throw new Unsupported("generic types, subclasses and initializer blocks have no Axolotl form");
        }
        if (type.getContainingClass() != null && !type.hasModifierProperty(PsiModifier.STATIC)) {
            throw new Unsupported("inner classes have no Axolotl form; make them static");
        }
        nullable(type);
        out.append("\n");
        leadingComments(type);
        out.append(visibility(type));
        if (type.isRecord()) {
            record(type);
        } else if (type.isEnum()) {
            enumeration(type);
        } else if (type.isInterface()) {
            interfaceDeclaration(type);
        } else {
            classDeclaration(type);
        }
    }

    /** Records and enums extend `Record` and `Enum` without saying so. */
    private static boolean explicitlyExtends(PsiClass type) {
        return type.getExtendsList() != null && type.getExtendsList().getReferencedTypes().length > 0;
    }

    /** A record without a body is a data class. */
    private void record(PsiClass type) {
        if (hasBodyMembers(type)) {
            throw new Unsupported("records with methods or fields have no Axolotl form");
        }
        out.append("data class ").append(name(type.getName())).append("(");
        PsiRecordComponent[] components = type.getRecordComponents();
        for (int i = 0; i < components.length; i++) {
            out.append(i == 0 ? "" : ", ").append("val ").append(name(components[i].getName())).append(": ")
                    .append(withNullable(type(components[i].getType()), nullable(components[i])));
        }
        out.append(")").append(supertypes(type.getImplementsListTypes())).append("\n");
    }

    private static boolean hasBodyMembers(PsiClass type) {
        return PsiTreeUtil.getChildrenOfAnyType(type, PsiMethod.class, PsiField.class, PsiClass.class).stream()
                .anyMatch(m -> !(m instanceof PsiEnumConstant));
    }

    /** An enum with constants only. */
    private void enumeration(PsiClass type) {
        if (hasBodyMembers(type) || type.getImplementsListTypes().length > 0) {
            throw new Unsupported("enums with members have no Axolotl form");
        }
        List<String> constants = new ArrayList<>();
        for (PsiField field : type.getFields()) {
            if (!(field instanceof PsiEnumConstant constant) || constant.getInitializingClass() != null
                    || constant.getArgumentList() != null && !constant.getArgumentList().isEmpty()) {
                throw new Unsupported("enum constants with arguments or bodies have no Axolotl form");
            }
            constants.add(name(constant.getName()));
        }
        out.append("enum ").append(name(type.getName())).append(" { ").append(String.join(", ", constants)).append(" }\n");
    }

    /** An interface whose methods take nothing and give a value, which its implementations provide. */
    private void interfaceDeclaration(PsiClass type) {
        boolean sealed = type.hasModifierProperty(PsiModifier.SEALED);
        for (PsiClassType permitted : type.getPermitsListTypes()) {
            PsiClass cls = permitted.resolve();
            if (cls == null || cls.getContainingFile() != source) {
                throw new Unsupported("a sealed interface is implemented in its own file in Axolotl");
            }
        }
        out.append(sealed ? "sealed " : "").append("interface ").append(name(type.getName()))
                .append(supertypes(type.getExtendsList() == null ? PsiClassType.EMPTY_ARRAY : type.getExtendsList().getReferencedTypes()))
                .append(" {\n");
        for (PsiElement member : PsiTreeUtil.getChildrenOfAnyType(type, PsiMethod.class, PsiField.class, PsiClass.class)) {
            if (!(member instanceof PsiMethod method) || method.getBody() != null || !method.getParameterList().isEmpty()
                    || method.hasTypeParameters() || PsiTypes.voidType().equals(method.getReturnType())) {
                throw new Unsupported("interfaces have only values in Axolotl: methods without parameters or a body");
            }
            leadingComments(method);
            out.append("    val ").append(name(method.getName())).append(": ")
                    .append(withNullable(type(method.getReturnType()), nullable(method))).append("\n");
        }
        out.append("}\n");
    }

    /**
     * A class whose one constructor sets each field it takes from the parameter of the same name.
     * Those fields are the parameters of the Axolotl class, the others its properties.
     */
    private void classDeclaration(PsiClass type) {
        List<PsiField> parameters = constructorFields(type);
        out.append("class ").append(name(type.getName())).append("(");
        for (int i = 0; i < parameters.size(); i++) {
            PsiField field = parameters.get(i);
            if (field.hasModifierProperty(PsiModifier.PRIVATE)) {
                throw new Unsupported("the fields a constructor sets are public in Axolotl: " + field.getName());
            }
            out.append(i == 0 ? "" : ", ").append(field.hasModifierProperty(PsiModifier.FINAL) ? "val " : "var ")
                    .append(name(field.getName())).append(": ").append(type(field, type));
        }
        out.append(")").append(supertypes(type.getImplementsListTypes())).append(" {\n");
        for (PsiField field : type.getFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) {
                if (type.getContainingClass() != null) {
                    throw new Unsupported("static members of nested classes have no Axolotl form");
                }
            } else if (!parameters.contains(field)) {
                property(field, type);
            }
        }
        for (PsiMethod method : type.getMethods()) {
            if (method.hasModifierProperty(PsiModifier.STATIC) && type.getContainingClass() != null) {
                throw new Unsupported("static members of nested classes have no Axolotl form");
            }
            if (!method.isConstructor() && !method.hasModifierProperty(PsiModifier.STATIC)) {
                function(method, 1);
            }
        }
        out.append("}\n");
    }

    /** The fields the constructor sets from its parameters, in the order of the parameters. */
    private List<PsiField> constructorFields(PsiClass type) {
        PsiMethod[] constructors = type.getConstructors();
        if (constructors.length > 1 || constructors.length == 1 && constructors[0].getBody() == null) {
            throw new Unsupported("a class has one constructor in Axolotl");
        }
        List<PsiField> fields = new ArrayList<>();
        if (constructors.length == 0) {
            return fields;
        }
        PsiMethod constructor = constructors[0];
        Map<PsiParameter, PsiField> set = new HashMap<>();
        for (PsiStatement statement : constructor.getBody().getStatements()) {
            PsiField field = fieldSetFromParameter(statement, type, constructor, set);
            if (field == null) {
                throw new Unsupported("a constructor only sets fields from its parameters in Axolotl");
            }
        }
        for (PsiParameter parameter : constructor.getParameterList().getParameters()) {
            if (!set.containsKey(parameter)) {
                throw new Unsupported("every parameter of a constructor sets a field in Axolotl");
            }
            fields.add(set.get(parameter));
        }
        PsiTreeUtil.findChildrenOfType(constructor, PsiComment.class).forEach(this::keep);
        return fields;
    }

    /** `this.name = name;`: the field, which is also recorded by its parameter; null for other statements. */
    private static PsiField fieldSetFromParameter(PsiStatement statement, PsiClass type, PsiMethod constructor,
                                                  Map<PsiParameter, PsiField> set) {
        if (!(statement instanceof PsiExpressionStatement expr) || !(expr.getExpression() instanceof PsiAssignmentExpression assign)
                || !assign.getOperationSign().getText().equals("=")
                || !(assign.getLExpression() instanceof PsiReferenceExpression target)
                || !(target.resolve() instanceof PsiField field) || field.getContainingClass() != type
                || field.hasModifierProperty(PsiModifier.STATIC) || field.getInitializer() != null
                || !(assign.getRExpression() instanceof PsiReferenceExpression value)
                || !(value.resolve() instanceof PsiParameter parameter) || parameter.getDeclarationScope() != constructor
                || !parameter.getName().equals(field.getName()) || set.containsKey(parameter)) {
            return null;
        }
        set.put(parameter, field);
        return field;
    }

    /** A field with an initializer; Java runs initializers before the constructor, so they cannot read other fields. */
    private void property(PsiField field, PsiClass type) {
        if (field.getInitializer() == null) {
            throw new Unsupported("a field the constructor does not set needs an initializer");
        }
        boolean readsFields = PsiTreeUtil.findChildrenOfType(field.getInitializer(), PsiReferenceExpression.class).stream()
                .anyMatch(ref -> ref.resolve() instanceof PsiField other && !other.hasModifierProperty(PsiModifier.STATIC));
        if (readsFields) {
            throw new Unsupported("a field initializer that reads other fields has no Axolotl form");
        }
        leadingComments(field);
        out.append("    ").append(visibility(field)).append(field.hasModifierProperty(PsiModifier.FINAL) ? "val " : "var ")
                .append(name(field.getName())).append(": ").append(type(field, type)).append(" = ")
                .append(expression(field.getInitializer())).append("\n");
    }

    private String supertypes(PsiClassType[] types) {
        if (types.length == 0) {
            return "";
        }
        return Arrays.stream(types).map(this::classType).collect(Collectors.joining(", ", " : ", ""));
    }

    private void field(PsiField field, PsiClass cls) {
        if (!field.hasModifierProperty(PsiModifier.STATIC) || !field.hasModifierProperty(PsiModifier.FINAL)
                || field.getInitializer() == null) {
            throw new Unsupported("only static final fields are converted");
        }
        out.append("\n");
        leadingComments(field);
        out.append(visibility(field)).append("val ")
                .append(name(field.getName())).append(": ").append(type(field, cls)).append(" = ")
                .append(expression(field.getInitializer())).append("\n");
    }

    /** A static method as a function, at depth 0; a method of a class, at depth 1. */
    private void function(PsiMethod method, int depth) {
        if (isPrivateConstructor(method)) {
            // A constructor that only prevents instances goes away, and with it what its comments say.
            PsiTreeUtil.findChildrenOfType(method, PsiComment.class).forEach(this::keep);
            return;
        }
        if (method.getBody() == null) {
            throw new Unsupported("abstract and native methods have no Axolotl form");
        }
        if (method.hasTypeParameters()) {
            throw new Unsupported("generic methods have no Axolotl form");
        }
        PsiClassType[] thrown = method.getThrowsList().getReferencedTypes();
        if (Arrays.stream(thrown).anyMatch(t -> !ExceptionUtil.isUncheckedException(t))) {
            throw new Unsupported("functions cannot throw checked exceptions; return a Result instead");
        }
        boolean main = method.getName().equals("main") && method.getParameterList().getParametersCount() == 1
                && method.getParameterList().getParameters()[0].getType().getCanonicalText().equals("java.lang.String[]");
        if (main && isUsed(method.getParameterList().getParameters()[0], method.getBody())) {
            throw new Unsupported("`fn main()` has no command-line arguments");
        }
        String indent = "    ".repeat(depth);
        out.append("\n");
        leadingComments(method, indent);
        out.append(indent).append(visibility(method)).append("fn ").append(name(method.getName())).append("(");
        if (!main) {
            PsiParameter[] params = method.getParameterList().getParameters();
            for (int i = 0; i < params.length; i++) {
                out.append(i == 0 ? "" : ", ").append(name(params[i].getName())).append(": ")
                        .append(withNullable(type(params[i].getType()), nullable(params[i])));
            }
        }
        out.append(")");
        boolean nullable = nullable(method) || returnsNull(method);
        String returns = type(method.getReturnType());
        if (!returns.isEmpty() && !(method.getReturnType() instanceof PsiPrimitiveType)) {
            returns = withNullable(returns, nullable);
        }
        if (!returns.isEmpty()) {
            out.append(" -> ").append(returns);
        }
        out.append(" ");
        block(method.getBody(), depth);
        out.append("\n");
    }

    /** Javadoc and the comments right before a declaration, which Java's PSI places inside it. */
    private void leadingComments(PsiMember member) {
        leadingComments(member, "");
    }

    private void leadingComments(PsiMember member, String indent) {
        for (PsiElement child : member.getChildren()) {
            if (child instanceof PsiComment comment) {
                keep(comment);
                out.append(indent).append(comment.getText()).append("\n");
            }
        }
    }

    private void block(PsiCodeBlock block, int depth) {
        out.append("{\n");
        for (PsiElement child = block.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof PsiStatement statement) {
                statement(statement, depth + 1);
                trailingComments(statement, depth + 1);
            } else if (child instanceof PsiComment comment) {
                comment(comment, depth + 1);
            }
        }
        out.append("    ".repeat(depth)).append("}");
    }

    /** A comment on its own line, or at the end of the line before it. */
    private void comment(PsiComment comment, int depth) {
        keep(comment);
        boolean trailing = !(comment.getPrevSibling() instanceof PsiWhiteSpace space) || !space.getText().contains("\n");
        if (trailing) {
            out.setLength(out.length() - 1);
            out.append(" ").append(comment.getText()).append("\n");
        } else {
            out.append("    ".repeat(depth)).append(comment.getText()).append("\n");
        }
    }

    /** A comment at the end of a statement's line, which Java's PSI may place inside the statement. */
    private void trailingComments(PsiStatement statement, int depth) {
        List<PsiComment> comments = new ArrayList<>();
        PsiElement leaf = PsiTreeUtil.getDeepestLast(statement);
        while (leaf instanceof PsiComment || leaf instanceof PsiWhiteSpace) {
            if (leaf instanceof PsiComment comment) {
                comments.addFirst(comment);
            }
            leaf = PsiTreeUtil.prevLeaf(leaf);
        }
        comments.forEach(comment -> comment(comment, depth));
    }

    private void keep(PsiComment comment) {
        keptComments.add(comment);
    }

    private void statement(PsiStatement statement, int depth) {
        String indent = "    ".repeat(depth);
        this.depth = depth;
        switch (statement) {
            case PsiDeclarationStatement declaration -> {
                for (PsiElement element : declaration.getDeclaredElements()) {
                    if (!(element instanceof PsiLocalVariable local) || local.getInitializer() == null) {
                        throw new Unsupported("locals need an initializer");
                    }
                    String keyword = local.hasModifierProperty(PsiModifier.FINAL) || !isReassigned(local, local.getParent().getParent())
                            ? "val " : "var ";
                    String type = type(local, local.getParent().getParent());
                    out.append(indent).append(keyword).append(name(local.getName())).append(": ").append(type)
                            .append(" = ").append(expression(local.getInitializer())).append("\n");
                }
            }
            case PsiReturnStatement ret -> out.append(indent).append("return")
                    .append(ret.getReturnValue() == null ? "" : " " + expression(ret.getReturnValue())).append("\n");
            case PsiExpressionStatement expr when expr.getExpression() instanceof PsiAssignmentExpression assignment ->
                    out.append(indent).append(assignment(assignment)).append("\n");
            case PsiExpressionStatement expr when expr.getExpression() instanceof PsiMethodCallExpression ->
                    out.append(indent).append(expression(expr.getExpression())).append("\n");
            case PsiIfStatement ifStatement -> {
                out.append(indent);
                ifChain(ifStatement, depth);
                out.append("\n");
            }
            case PsiWhileStatement loop -> {
                out.append(indent).append("while (").append(expression(loop.getCondition())).append(") ");
                block(body(loop.getBody()), depth);
                out.append("\n");
            }
            case PsiForeachStatement loop -> {
                PsiParameter variable = loop.getIterationParameter();
                if (isReassigned(variable, loop)) {
                    throw new Unsupported("the variable of a `for` loop is a `val` in Axolotl");
                }
                out.append(indent).append("for (").append(name(variable.getName())).append(" in ")
                        .append(expression(loop.getIteratedValue())).append(") ");
                block(body(loop.getBody()), depth);
                out.append("\n");
            }
            case PsiThrowStatement thrown -> out.append(indent).append("throw ").append(expression(thrown.getException())).append("\n");
            case PsiSwitchStatement select -> out.append(indent).append(when(select)).append("\n");
            default -> throw new Unsupported("unsupported statement: " + statement.getText());
        }
    }

    private void ifChain(PsiIfStatement ifStatement, int depth) {
        out.append("if (").append(expression(ifStatement.getCondition())).append(") ");
        block(body(ifStatement.getThenBranch()), depth);
        PsiStatement elseBranch = ifStatement.getElseBranch();
        if (elseBranch instanceof PsiIfStatement elseIf) {
            out.append(" else ");
            ifChain(elseIf, depth);
        } else if (elseBranch != null) {
            out.append(" else ");
            block(body(elseBranch), depth);
        }
    }

    private static PsiCodeBlock body(PsiStatement statement) {
        if (statement instanceof PsiBlockStatement block) {
            return block.getCodeBlock();
        }
        throw new Unsupported("branches and loop bodies must be blocks");
    }

    private static boolean isReassigned(PsiVariable variable, PsiElement scope) {
        boolean[] assigned = {false};
        scope.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitAssignmentExpression(PsiAssignmentExpression expression) {
                if (expression.getLExpression() instanceof PsiReferenceExpression ref && ref.isReferenceTo(variable)) {
                    assigned[0] = true;
                }
                super.visitAssignmentExpression(expression);
            }

            @Override
            public void visitUnaryExpression(PsiUnaryExpression expression) {
                if (expression.getOperand() instanceof PsiReferenceExpression ref && ref.isReferenceTo(variable)
                        && List.of("++", "--").contains(expression.getOperationSign().getText())) {
                    assigned[0] = true;
                }
                super.visitUnaryExpression(expression);
            }
        });
        return assigned[0];
    }

    /** `switch` with arrows as `when`; a pattern variable reads as the subject, which must be a name that does not change. */
    private String when(PsiSwitchBlock select) {
        String indent = "    ".repeat(depth);
        StringBuilder result = new StringBuilder("when (").append(expression(select.getExpression())).append(") {\n");
        for (PsiStatement rule : select.getBody().getStatements()) {
            if (!(rule instanceof PsiSwitchLabeledRuleStatement arm) || arm.getGuardExpression() != null) {
                throw new Unsupported("only `switch` with arrows and without guards has an Axolotl form");
            }
            result.append(indent).append("    ").append(conditions(arm, select.getExpression())).append(" -> ")
                    .append(armBody(arm.getBody())).append("\n");
        }
        return result.append(indent).append("}").toString();
    }

    private String conditions(PsiSwitchLabeledRuleStatement arm, PsiExpression subject) {
        if (arm.isDefaultCase()) {
            return "else";
        }
        List<String> conditions = new ArrayList<>();
        for (PsiCaseLabelElement element : arm.getCaseLabelElementList().getElements()) {
            switch (element) {
                case PsiTypeTestPattern pattern -> {
                    bind(pattern.getPatternVariable(), subject);
                    conditions.add("is " + testedType(pattern.getCheckType().getType()));
                }
                case PsiLiteralExpression literal when !isNull(literal) -> conditions.add(literal(literal));
                case PsiPrefixExpression negative when negative.getOperand() instanceof PsiLiteralExpression
                        && negative.getOperationSign().getText().equals("-") -> conditions.add(expression(negative));
                default -> throw new Unsupported("unsupported case: " + element.getText());
            }
        }
        return String.join(", ", conditions);
    }

    /** An expression, a `throw` or a block; a block that gives a value with `yield` has no Axolotl form. */
    private String armBody(PsiStatement body) {
        return switch (body) {
            case PsiExpressionStatement expr -> expression(expr.getExpression());
            case PsiThrowStatement thrown -> "throw " + expression(thrown.getException());
            case PsiBlockStatement block -> {
                int start = out.length();
                int outer = depth;
                block(block.getCodeBlock(), depth + 1);
                depth = outer;
                String text = out.substring(start);
                out.setLength(start);
                yield text;
            }
            default -> throw new Unsupported("unsupported case body: " + body.getText());
        };
    }

    /** A pattern variable reads as the name it tests, when neither of them changes. */
    private void bind(PsiPatternVariable variable, PsiExpression subject) {
        if (variable == null) {
            return;
        }
        PsiExpression tested = PsiUtil.skipParenthesizedExprDown(subject);
        if (!(tested instanceof PsiReferenceExpression ref) || ref.getQualifierExpression() != null
                || !(ref.resolve() instanceof PsiVariable name) || !(name instanceof PsiLocalVariable || name instanceof PsiParameter)
                || isReassigned(name, PsiTreeUtil.getParentOfType(name, PsiMethod.class)) || isReassigned(variable, variable.getDeclarationScope())) {
            throw new Unsupported("a pattern needs to test a local or parameter that does not change");
        }
        renamed.put(variable, name(name.getName()));
    }

    /** The type after `is`, which has no type arguments. */
    private String testedType(PsiType type) {
        if (!(type instanceof PsiClassType classType) || classType.getParameterCount() > 0) {
            throw new Unsupported("a type test needs a class without type arguments: " + type.getPresentableText());
        }
        return classType(classType);
    }

    private String instanceOf(PsiInstanceOfExpression test, boolean negated) {
        PsiType type;
        if (test.getPattern() == null) {
            type = test.getCheckType().getType();
        } else if (test.getPattern() instanceof PsiTypeTestPattern pattern) {
            bind(pattern.getPatternVariable(), test.getOperand());
            type = pattern.getCheckType().getType();
        } else {
            throw new Unsupported("record patterns have no Axolotl form");
        }
        return expression(test.getOperand()) + (negated ? " !is " : " is ") + testedType(type);
    }

    /** Whether a variable starts as or is set to `null` in a scope. */
    private static boolean holdsNull(PsiVariable variable, PsiElement scope) {
        return isNull(variable.getInitializer())
                || PsiTreeUtil.findChildrenOfType(scope, PsiAssignmentExpression.class).stream().anyMatch(assignment ->
                        assignment.getLExpression() instanceof PsiReferenceExpression ref && ref.isReferenceTo(variable)
                                && isNull(assignment.getRExpression()));
    }

    /** Whether a method returns `null` or a variable that may hold it. */
    private static boolean returnsNull(PsiMethod method) {
        return PsiTreeUtil.findChildrenOfType(method.getBody(), PsiReturnStatement.class).stream().anyMatch(ret -> {
            PsiExpression value = PsiUtil.skipParenthesizedExprDown(ret.getReturnValue());
            return isNull(value) || value instanceof PsiReferenceExpression ref && ref.resolve() instanceof PsiVariable variable
                    && holdsNull(variable, method.getContainingClass());
        });
    }

    private static boolean isNull(PsiExpression expression) {
        return PsiUtil.skipParenthesizedExprDown(expression) instanceof PsiLiteralExpression literal
                && literal.getType() == PsiTypes.nullType();
    }

    private static boolean isUsed(PsiParameter parameter, PsiElement scope) {
        return PsiTreeUtil.findChildrenOfType(scope, PsiReferenceExpression.class).stream()
                .anyMatch(ref -> ref.isReferenceTo(parameter));
    }

    /** Only as a statement: Axolotl assignments are not expressions. */
    private String assignment(PsiAssignmentExpression assignment) {
        String operator = assignment.getOperationSign().getText();
        if (!ASSIGNMENTS.contains(operator)) {
            throw new Unsupported("unsupported assignment: " + operator);
        }
        return expression(assignment.getLExpression()) + " " + operator + " " + expression(assignment.getRExpression());
    }

    /** Expressions that read the same in both languages, with `new` and `System.out.println` translated. */
    private String expression(PsiExpression expression) {
        return switch (expression) {
            case null -> "";
            case PsiLiteralExpression literal -> literal(literal);
            case PsiParenthesizedExpression paren -> "(" + expression(paren.getExpression()) + ")";
            case PsiReferenceExpression ref -> reference(ref);
            case PsiInstanceOfExpression test -> instanceOf(test, false);
            case PsiPrefixExpression not when not.getOperationSign().getText().equals("!")
                    && PsiUtil.skipParenthesizedExprDown(not.getOperand()) instanceof PsiInstanceOfExpression test -> instanceOf(test, true);
            case PsiConditionalExpression conditional -> "if (" + expression(conditional.getCondition()) + ") "
                    + expression(conditional.getThenExpression()) + " else " + expression(conditional.getElseExpression());
            case PsiSwitchExpression select -> when(select);
            case PsiMethodCallExpression call when call.getMethodExpression().getText().equals("System.out.println") ->
                    "println" + arguments(call.getArgumentList());
            case PsiMethodCallExpression call -> expression(call.getMethodExpression()) + arguments(call.getArgumentList());
            case PsiNewExpression created when created.getClassReference() != null && created.getArgumentList() != null
                    && created.getAnonymousClass() == null -> constructorCall(created);
            case PsiPolyadicExpression poly -> {
                StringBuilder sb = new StringBuilder(expression(poly.getOperands()[0]));
                for (PsiExpression operand : java.util.Arrays.copyOfRange(poly.getOperands(), 1, poly.getOperands().length)) {
                    String operator = poly.getTokenBeforeOperand(operand).getText();
                    if (!OPERATORS.contains(operator)) {
                        throw new Unsupported("unsupported operator: " + operator);
                    }
                    sb.append(" ").append(operator).append(" ").append(expression(operand));
                }
                yield sb.toString();
            }
            case PsiPrefixExpression prefix when List.of("!", "-", "+").contains(prefix.getOperationSign().getText()) ->
                    prefix.getOperationSign().getText() + expression(prefix.getOperand());
            default -> throw new Unsupported("unsupported expression: " + expression.getText());
        };
    }

    /** A name, a pattern variable as the name it tests, or a field of this class without `this`. */
    private String reference(PsiReferenceExpression ref) {
        PsiExpression qualifier = ref.getQualifierExpression();
        if (qualifier == null) {
            String pattern = renamed.get(ref.resolve() instanceof PsiVariable variable ? variable : null);
            return pattern != null ? pattern : name(ref.getReferenceName());
        }
        if (qualifier instanceof PsiThisExpression self && self.getQualifier() == null) {
            return name(ref.getReferenceName());
        }
        return expression(qualifier) + "." + name(ref.getReferenceName());
    }

    /** `Name<T>(...)` takes a simple name in Axolotl. */
    private String constructorCall(PsiNewExpression created) {
        PsiJavaCodeReferenceElement reference = created.getClassReference();
        if (reference.isQualified() && reference.getTypeParameters().length > 0) {
            throw new Unsupported("a generic class needs an import: " + reference.getText());
        }
        return reference.getText() + arguments(created.getArgumentList());
    }

    /** Numbers in the forms Axolotl reads: no binary or octal, digits on both sides of the point. */
    private static String literal(PsiLiteralExpression literal) {
        String text = literal.getText();
        String lower = text.toLowerCase(Locale.ROOT);
        Object value = literal.getValue();
        boolean floating = value instanceof Float || value instanceof Double;
        if (literal.isTextBlock() || floating && lower.startsWith("0x")) {
            throw new Unsupported("unsupported literal: " + text);
        }
        boolean binaryOrOctal = lower.startsWith("0b") || lower.length() > 1 && lower.charAt(0) == '0'
                && (Character.isDigit(lower.charAt(1)) || lower.charAt(1) == '_');
        if ((value instanceof Integer || value instanceof Long) && binaryOrOctal) {
            if (((Number) value).longValue() < 0) {
                throw new Unsupported("unsupported literal: " + text);
            }
            return value + (value instanceof Long ? "L" : "");
        }
        if (floating) {
            return text.replaceFirst("^\\.", "0.").replaceFirst("\\.(?![0-9])", ".0");
        }
        return text;
    }

    private String arguments(PsiExpressionList list) {
        return java.util.Arrays.stream(list.getExpressions()).map(this::expression)
                .collect(java.util.stream.Collectors.joining(", ", "(", ")"));
    }

    /** The type of a variable, nullable when it is annotated so or holds `null`. */
    private String type(PsiVariable variable, PsiElement scope) {
        String type = type(variable.getType());
        boolean nullable = nullable(variable) || holdsNull(variable, scope);
        return withNullable(type, nullable && !(variable.getType() instanceof PsiPrimitiveType));
    }

    private static String withNullable(String type, boolean nullable) {
        return nullable && !type.endsWith("?") ? type + "?" : type;
    }

    private static boolean nullable(PsiModifierListOwner owner) {
        return owner.getModifierList() != null && nullable(owner.getModifierList().getAnnotations());
    }

    /** Whether annotations say a value may be null; other annotations have no Axolotl form. */
    private static boolean nullable(PsiAnnotation[] annotations) {
        boolean nullable = false;
        for (PsiAnnotation annotation : annotations) {
            String name = String.valueOf(annotation.getQualifiedName());
            name = name.substring(name.lastIndexOf('.') + 1);
            if (!NULLNESS.contains(name)) {
                throw new Unsupported("annotations have no Axolotl form: @" + name);
            }
            nullable |= name.equals("Nullable");
        }
        return nullable;
    }

    private String type(PsiType type) {
        if (type == null) {
            return "";
        }
        if (type instanceof PsiPrimitiveType primitive) {
            String mapped = PRIMITIVES.get(primitive.getCanonicalText());
            if (mapped == null) {
                throw new Unsupported("unsupported primitive type: " + primitive.getCanonicalText());
            }
            return mapped;
        }
        String boxed = BOXED.get(type.getCanonicalText());
        if (boxed != null) {
            return boxed;
        }
        if (type instanceof com.intellij.psi.PsiArrayType) {
            throw new Unsupported("arrays have no Axolotl form");
        }
        if (!(type instanceof PsiClassType classType)) {
            throw new Unsupported("wildcard types have no Axolotl form");
        }
        return classType(classType);
    }

    /** A class by its simple name; one that is not visible without an import gets one. */
    private String classType(PsiClassType type) {
        PsiClass cls = type.resolve();
        if (cls == null || cls.getQualifiedName() == null) {
            throw new Unsupported("unknown type: " + type.getPresentableText());
        }
        String qualified = cls.getQualifiedName();
        String pkg = PsiUtil.getPackageName(cls);
        boolean declaredHere = cls.getContainingFile() == source;
        boolean visible = declaredHere || cls.getContainingClass() == null && ("java.lang".equals(pkg) || packageName.equals(pkg));
        String known = importedTypes.putIfAbsent(cls.getName(), qualified);
        if (known == null && !visible) {
            addedImports.add(qualified);
        } else if (known != null && !known.equals(qualified)) {
            throw new Unsupported("two types are named " + cls.getName());
        }
        List<String> arguments = new ArrayList<>();
        for (PsiType argument : type.getParameters()) {
            // A type argument keeps its class name: `Int?` there would declare nullable elements.
            String text = argument instanceof PsiClassType c ? classType(c) : type(argument);
            arguments.add(withNullable(text, nullable(argument.getAnnotations())));
        }
        return cls.getName() + (arguments.isEmpty() ? "" : "<" + String.join(", ", arguments) + ">");
    }

    private static String name(String name) {
        if (KEYWORDS.contains(name)) {
            throw new Unsupported("`" + name + "` is a keyword in Axolotl");
        }
        return name;
    }

    private static String qualifiedName(String name) {
        for (String segment : name.split("\\.")) {
            name(segment);
        }
        return name;
    }

    /**
     * Axolotl has only public and private: package-private and protected members become private
     * unless other files use them.
     */
    private static String visibility(PsiMember member) {
        if (member.hasModifierProperty(PsiModifier.PUBLIC)) {
            return "";
        }
        if (member.hasModifierProperty(PsiModifier.PRIVATE)) {
            return "private ";
        }
        var elsewhere = GlobalSearchScope.notScope(GlobalSearchScope.fileScope(member.getContainingFile()));
        return ReferencesSearch.search(member, elsewhere).findFirst() == null ? "private " : "";
    }
}
