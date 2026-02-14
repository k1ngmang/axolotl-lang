package org.axl.intellij.actions;

import com.intellij.codeInsight.ExceptionUtil;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiBlockStatement;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDeclarationStatement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiIfStatement;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiImportStaticStatement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiParenthesizedExpression;
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.PsiPrefixExpression;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.PsiWhileStatement;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.Translator;

/**
 * Converts the subset of Java that maps one to one: a class with only static methods and fields,
 * whose bodies use locals, `if`, `while`, `return` and expression statements.
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
            "if", "else", "while", "try", "attempt", "true", "false", "null");
    private static final Set<String> OPERATORS = Set.of("+", "-", "*", "/", "%", "==", "!=", "<", "<=", ">", ">=",
            "&&", "||");
    private static final Set<String> ASSIGNMENTS = Set.of("=", "+=", "-=", "*=", "/=", "%=");

    private final StringBuilder out = new StringBuilder();
    private String packageName;
    // Imported types by simple name: the file's imports and those the converted types need.
    private final Map<String, String> importedTypes = new HashMap<>();
    private final List<String> addedImports = new ArrayList<>();

    static String convert(PsiJavaFile file) {
        PsiClass[] classes = file.getClasses();
        if (classes.length != 1) {
            throw new Unsupported("the file must contain exactly one class");
        }
        String axl = new JavaToAxolotl().file(file, classes[0]);
        rejectErrors(file, axl);
        return axl;
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
        if (cls.isInterface() || cls.isEnum() || cls.isRecord() || cls.getInnerClasses().length > 0
                || cls.getExtendsListTypes().length > 0 || cls.getImplementsListTypes().length > 0
                || cls.hasTypeParameters()) {
            throw new Unsupported("only plain classes with static members are converted");
        }
        if (file.getPackageName().isEmpty()) {
            throw new Unsupported("classes in the default package have no Axolotl form");
        }
        if (cls.getInitializers().length > 0) {
            throw new Unsupported("initializer blocks have no Axolotl form");
        }
        packageName = file.getPackageName();
        out.append("package ").append(qualifiedName(packageName)).append("\n");
        if (file.getImportList() != null) {
            for (PsiImportStatement imp : file.getImportList().getImportStatements()) {
                if (imp.isOnDemand()) {
                    throw new Unsupported("on-demand imports have no Axolotl form");
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
        for (var field : cls.getFields()) {
            if (!field.hasModifierProperty(PsiModifier.STATIC) || !field.hasModifierProperty(PsiModifier.FINAL)
                    || field.getInitializer() == null) {
                throw new Unsupported("only static final fields are converted");
            }
            out.append("\n").append(visibility(field.hasModifierProperty(PsiModifier.PRIVATE))).append("val ")
                    .append(name(field.getName())).append(": ").append(type(field, cls)).append(" = ")
                    .append(expression(field.getInitializer())).append("\n");
        }
        for (PsiMethod method : cls.getMethods()) {
            if (method.isConstructor() && method.getParameterList().isEmpty() && method.getBody() != null
                    && method.getBody().isEmpty()) {
                continue;
            }
            if (!method.hasModifierProperty(PsiModifier.STATIC) || method.getBody() == null) {
                throw new Unsupported("only static methods are converted");
            }
            if (method.hasTypeParameters()) {
                throw new Unsupported("generic methods have no Axolotl form");
            }
            PsiClassType[] thrown = method.getThrowsList().getReferencedTypes();
            if (Arrays.stream(thrown).anyMatch(t -> !ExceptionUtil.isUncheckedException(t))) {
                throw new Unsupported("functions cannot throw checked exceptions; return a Result instead");
            }
            function(method);
        }
        out.insert(importsEnd, addedImports.stream().map(name -> "import " + qualifiedName(name) + "\n")
                .collect(Collectors.joining()));
        return out.toString();
    }

    private void function(PsiMethod method) {
        boolean main = method.getName().equals("main") && method.getParameterList().getParametersCount() == 1
                && method.getParameterList().getParameters()[0].getType().getCanonicalText().equals("java.lang.String[]");
        if (main && isUsed(method.getParameterList().getParameters()[0], method.getBody())) {
            throw new Unsupported("`fn main()` has no command-line arguments");
        }
        out.append("\n").append(visibility(method.hasModifierProperty(PsiModifier.PRIVATE))).append("fn ")
                .append(name(method.getName())).append("(");
        if (!main) {
            PsiParameter[] params = method.getParameterList().getParameters();
            for (int i = 0; i < params.length; i++) {
                out.append(i == 0 ? "" : ", ").append(name(params[i].getName())).append(": ")
                        .append(type(params[i].getType()));
            }
        }
        out.append(")");
        String returns = type(method.getReturnType());
        if (!returns.isEmpty() && !returns.endsWith("?") && !(method.getReturnType() instanceof PsiPrimitiveType)
                && returnsNull(method)) {
            returns += "?";
        }
        if (!returns.isEmpty()) {
            out.append(" -> ").append(returns);
        }
        out.append(" ");
        block(method.getBody(), 0);
        out.append("\n");
    }

    private void block(PsiCodeBlock block, int depth) {
        out.append("{\n");
        for (PsiStatement statement : block.getStatements()) {
            statement(statement, depth + 1);
        }
        out.append("    ".repeat(depth)).append("}");
    }

    private void statement(PsiStatement statement, int depth) {
        String indent = "    ".repeat(depth);
        switch (statement) {
            case PsiDeclarationStatement declaration -> {
                for (PsiElement element : declaration.getDeclaredElements()) {
                    if (!(element instanceof PsiLocalVariable local) || local.getInitializer() == null) {
                        throw new Unsupported("locals need an initializer");
                    }
                    String keyword = local.hasModifierProperty(PsiModifier.FINAL) || !isReassigned(local) ? "val " : "var ";
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

    private static boolean isReassigned(PsiLocalVariable local) {
        boolean[] assigned = {false};
        PsiElement scope = local.getParent().getParent();
        scope.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitAssignmentExpression(PsiAssignmentExpression expression) {
                if (expression.getLExpression() instanceof PsiReferenceExpression ref && ref.isReferenceTo(local)) {
                    assigned[0] = true;
                }
                super.visitAssignmentExpression(expression);
            }
        });
        return assigned[0];
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
    private static String assignment(PsiAssignmentExpression assignment) {
        String operator = assignment.getOperationSign().getText();
        if (!ASSIGNMENTS.contains(operator)) {
            throw new Unsupported("unsupported assignment: " + operator);
        }
        return expression(assignment.getLExpression()) + " " + operator + " " + expression(assignment.getRExpression());
    }

    /** Expressions that read the same in both languages, with `new` and `System.out.println` translated. */
    private static String expression(PsiExpression expression) {
        return switch (expression) {
            case null -> "";
            case PsiLiteralExpression literal -> literal(literal);
            case PsiParenthesizedExpression paren -> "(" + expression(paren.getExpression()) + ")";
            case PsiReferenceExpression ref -> ref.getQualifierExpression() == null ? name(ref.getReferenceName())
                    : expression(ref.getQualifierExpression()) + "." + name(ref.getReferenceName());
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

    /** `Name<T>(...)` takes a simple name in Axolotl. */
    private static String constructorCall(PsiNewExpression created) {
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

    private static String arguments(PsiExpressionList list) {
        return java.util.Arrays.stream(list.getExpressions()).map(JavaToAxolotl::expression)
                .collect(java.util.stream.Collectors.joining(", ", "(", ")"));
    }

    /** The type of a variable, nullable when it holds `null`. */
    private String type(PsiVariable variable, PsiElement scope) {
        String type = type(variable.getType());
        boolean nullable = !type.endsWith("?") && !(variable.getType() instanceof PsiPrimitiveType) && holdsNull(variable, scope);
        return nullable ? type + "?" : type;
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
        boolean visible = cls.getContainingClass() == null && ("java.lang".equals(pkg) || packageName.equals(pkg));
        String known = importedTypes.putIfAbsent(cls.getName(), qualified);
        if (known == null && !visible) {
            addedImports.add(qualified);
        } else if (known != null && !known.equals(qualified)) {
            throw new Unsupported("two types are named " + cls.getName());
        }
        List<String> arguments = new ArrayList<>();
        for (PsiType argument : type.getParameters()) {
            // A type argument keeps its class name: `Int?` there would declare nullable elements.
            arguments.add(argument instanceof PsiClassType c ? classType(c) : type(argument));
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

    private static String visibility(boolean isPrivate) {
        return isPrivate ? "private " : "";
    }
}
